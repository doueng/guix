(ns bbh.llm
  "Streaming OpenAI-compatible chat completions."
  (:require [babashka.http-client :as http]
            [bbh.auth :as auth]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- openrouter-key []
  (or (auth/api-key "OPENROUTER_API_KEY" "openrouter")
      (throw (ex-info "Set OPENROUTER_API_KEY or log in to OpenRouter in Pi" {}))))

(defn- merge-detail
  "Fold one streamed reasoning detail into the one with its index: text fields
  concatenate and the rest keep their latest non-nil value."
  [acc {:keys [index] :as d}]
  (update acc (or index 0)
          (fn [prev]
            (reduce-kv (fn [m k v]
                         (cond (nil? v) m
                               (and (#{:text :summary :data} k) (string? v)) (update m k str v)
                               :else (assoc m k v)))
                       (or prev {}) d))))

(defn- step [msg chunk]
  (let [delta (get-in chunk [:choices 0 :delta])]
    (cond-> (reduce (fn [m {:keys [index id function]}]
                      (update-in m [:tool_calls index]
                                 (fn [call]
                                   (cond-> (or call {:id nil :type "function" :function {:name "" :arguments ""}})
                                     id (assoc :id id)
                                     (:name function) (update-in [:function :name] str (:name function))
                                     (:arguments function) (update-in [:function :arguments] str (:arguments function))))))
                    (update msg :content str (:content delta))
                    (:tool_calls delta))
      (seq (:reasoning_details delta)) (update :reasoning_details #(reduce merge-detail (or % (sorted-map)) (:reasoning_details delta)))
      (:usage chunk) (assoc :usage (:usage chunk))
      (get-in chunk [:choices 0 :finish_reason]) (assoc :finish_reason (get-in chunk [:choices 0 :finish_reason]))
      (get-in chunk [:choices 0 :native_finish_reason]) (assoc :native_finish_reason (get-in chunk [:choices 0 :native_finish_reason])))))

(def ^:private output-limit-reasons
  "Stop reasons meaning the output limit cut the reply off. OpenRouter can report
  a cut-off tool call as tool_calls, with the provider's own reason, such as
  DeepSeek's length or Anthropic's max_tokens, in native_finish_reason."
  #{"length" "max_tokens"})

(defn usage-record
  "OpenRouter's usage object as bbh's per-request usage record."
  [{:keys [prompt_tokens completion_tokens cost prompt_tokens_details completion_tokens_details]}]
  (when prompt_tokens
    {:input prompt_tokens
     :cached (or (:cached_tokens prompt_tokens_details) 0)
     :written (or (:cache_write_tokens prompt_tokens_details) 0)
     :output (or completion_tokens 0)
     :thinking (or (:reasoning_tokens completion_tokens_details) 0)
     :cost cost}))

(defn- finish [msg]
  (let [calls (->> (:tool_calls msg) (sort-by key) (mapv val))
        details (vec (vals (:reasoning_details msg)))]
    (cond-> {:message (cond-> {:role "assistant" :content (:content msg)}
                        (seq details) (assoc :reasoning_details details)
                        (seq calls) (assoc :tool_calls calls))
             :usage (usage-record (:usage msg))}
      (some output-limit-reasons [(:finish_reason msg) (:native_finish_reason msg)]) (assoc :truncated true))))

(defn accumulate
  "Fold streamed chunks into {:message :usage :truncated}, where :truncated means
  the reply stopped at the output limit."
  [chunks]
  (finish (reduce step {:content ""} chunks)))

(defn- chunks [body]
  (->> (line-seq (io/reader body))
       (keep #(when (str/starts-with? % "data: ") (subs % 6)))
       (take-while #(not= % "[DONE]"))
       (map #(json/parse-string % true))))

(def ^:private effort-rank ["none" "minimal" "low" "medium" "high" "xhigh" "max"])

(defn pick-effort
  "EFFORT when SUPPORTED allows it, else the nearest supported level, ties going
  up. Nil SUPPORTED means the model takes any level."
  [effort supported]
  (if (or (nil? supported) (some #{effort} supported))
    effort
    (let [rank #(.indexOf ^java.util.List effort-rank %)
          want (rank effort)]
      (->> supported
           (filter #(>= (rank %) 0))
           (sort-by (fn [e] [(abs (- (rank e) want)) (- (rank e))]))
           first))))

(defn- fetch-models []
  (let [{:keys [status body]} (http/get "https://openrouter.ai/api/v1/models" {:throw false :timeout 20000})]
    (when (= 200 status)
      (into {} (for [m (:data (json/parse-string body true))] [(:id m) m])))))

(def ^:private models (delay (try (fetch-models) (catch Exception _ nil))))

(defn model-info
  "MODEL's context window, output limit and effort levels from OpenRouter's
  model list, fetched once per process. Nil when the list is unavailable."
  [model]
  (when-let [m (get @models model)]
    {:size (:context_length m)
     :max-output (get-in m [:top_provider :max_completion_tokens])
     :efforts (when-let [r (:reasoning m)] (or (:supported_efforts r) :any))}))

(defn request-body
  "The chat completion request for MODEL. Anthropic models get OpenRouter's
  automatic cache breakpoint, which advances with the conversation."
  [model info {:keys [messages tools effort max-tokens session-id]}]
  (let [efforts (:efforts info)
        effort (when (and effort efforts) (pick-effort effort (when (sequential? efforts) efforts)))
        max-tokens (or max-tokens (some-> (:max-output info) (min 128000)))]
    (cond-> {:model model :messages messages :stream true :usage {:include true}}
      (seq tools) (assoc :tools tools)
      effort (assoc :reasoning {:effort effort})
      max-tokens (assoc :max_tokens max-tokens)
      session-id (assoc :session_id session-id)
      (str/starts-with? model "anthropic/") (assoc :cache_control {:type "ephemeral"}))))

(def retry-delays-ms
  "Waits before each retry of a rate limit, server error or failed connection."
  [1000 2000 4000 8000 16000])

(defn- retry-wait-ms [resp delay]
  (or (some-> (get-in resp [:headers "retry-after"]) parse-long (* 1000) (min 60000)) delay))

(defn- overflow? [status text]
  (and (= 400 status)
       (boolean (re-find #"(?i)context length|context window|maximum context|too many tokens|prompt is too long" text))))

(defn- post!
  "POST BODY, retrying while CANCELLED is false. Returns a 200 response or throws,
  with :overflow in the ex-data when the request exceeded the model's context."
  [key body cancelled on-retry]
  (loop [delays retry-delays-ms]
    (let [resp (try (http/post "https://openrouter.ai/api/v1/chat/completions"
                               {:headers {"Authorization" (str "Bearer " key)
                                          "Content-Type" "application/json"}
                                :body body :as :stream :throw false})
                    (catch java.io.IOException e {:exception e}))
          status (:status resp)]
      (cond
        (= 200 status) resp
        (and (seq delays) (or (:exception resp) (= 429 status) (<= 500 status 599)))
        (let [wait (retry-wait-ms resp (first delays))
              end (+ (System/currentTimeMillis) wait)]
          (some-> ^java.io.InputStream (:body resp) .close)
          (on-retry (str "OpenRouter " (or status (ex-message (:exception resp)))
                         ", retrying in " (quot (+ wait 999) 1000) " s."))
          (while (and (not @cancelled) (< (System/currentTimeMillis) end)) (Thread/sleep 50))
          (when-not @cancelled (recur (rest delays))))
        (:exception resp) (throw (:exception resp))
        :else (let [text (with-open [b ^java.io.InputStream (:body resp)] (slurp b))]
                (throw (ex-info (str "OpenRouter " status ": " text)
                                {:status status :overflow (overflow? status text)})))))))

(defn openrouter
  "A sample function for MODEL on OpenRouter. The request takes :messages,
  :tools, :effort, :max-tokens and :session-id. Calls ON-OPEN with a function
  that aborts the request, ON-RETRY with a note before each retry, and
  ON-THOUGHT with reasoning text as it streams. The usage record carries the
  model's context window as :size."
  [model]
  (fn [req {:keys [on-text on-thought on-open on-retry]}]
    (let [cancelled (atom false)
          _ (on-open #(reset! cancelled true))
          info (model-info model)
          resp (post! (openrouter-key) (json/generate-string (request-body model info req))
                      cancelled (or on-retry (fn [_])))
          acc (volatile! {:content ""})
          with-size #(cond-> % (and (:usage %) (:size info)) (assoc-in [:usage :size] (:size info)))]
      (if-not resp
        {:message {:role "assistant" :content ""} :usage nil :interrupted true}
        (with-open [body (:body resp)]
          (on-open #(do (reset! cancelled true) (.close ^java.io.InputStream body)))
          (try
            (doseq [c (chunks body)]
              (when-let [e (:error c)]
                (throw (ex-info (str "OpenRouter: " (:message e)) c)))
              (some-> (get-in c [:choices 0 :delta :content]) on-text)
              (when-let [t (not-empty (get-in c [:choices 0 :delta :reasoning]))]
                (when on-thought (on-thought t)))
              (vswap! acc step c))
            (with-size (finish @acc))
            (catch java.io.IOException e
              (if @cancelled
                (-> (finish @acc) with-size (update :message dissoc :tool_calls) (assoc :interrupted true))
                (throw e)))))))))
