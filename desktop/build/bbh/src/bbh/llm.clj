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
      (:usage chunk) (assoc :usage (:usage chunk)))))

(defn- finish [msg]
  (let [calls (->> (:tool_calls msg) (sort-by key) (mapv val))]
    {:message (cond-> {:role "assistant" :content (:content msg)}
                (seq calls) (assoc :tool_calls calls))
     :usage (:usage msg)}))

(defn accumulate
  "Fold streamed chunks into {:message :usage}."
  [chunks]
  (finish (reduce step {:content ""} chunks)))

(defn- chunks [body]
  (->> (line-seq (io/reader body))
       (keep #(when (str/starts-with? % "data: ") (subs % 6)))
       (take-while #(not= % "[DONE]"))
       (map #(json/parse-string % true))))

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
  [body cancelled on-retry]
  (loop [delays retry-delays-ms]
    (let [resp (try (http/post "https://openrouter.ai/api/v1/chat/completions"
                               {:headers {"Authorization" (str "Bearer " (openrouter-key))
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
  "A sample function for MODEL on OpenRouter. Calls ON-OPEN with a function
  that aborts the request, and ON-RETRY with a note before each retry."
  [model]
  (fn [{:keys [messages tools]} {:keys [on-text on-open on-retry]}]
    (let [cancelled (atom false)
          _ (on-open #(reset! cancelled true))
          resp (post! (json/generate-string (cond-> {:model model :messages messages
                                                     :stream true :usage {:include true}}
                                              (seq tools) (assoc :tools tools)))
                      cancelled (or on-retry (fn [_])))
          acc (volatile! {:content ""})]
      (if-not resp
        {:message {:role "assistant" :content ""} :usage nil :interrupted true}
        (with-open [body (:body resp)]
          (on-open #(do (reset! cancelled true) (.close ^java.io.InputStream body)))
          (try
            (doseq [c (chunks body)]
              (when-let [e (:error c)]
                (throw (ex-info (str "OpenRouter: " (:message e)) c)))
              (some-> (get-in c [:choices 0 :delta :content]) on-text)
              (vswap! acc step c))
            (finish @acc)
            (catch java.io.IOException e
              (if @cancelled
                (-> (finish @acc) (update :message dissoc :tool_calls) (assoc :interrupted true))
                (throw e)))))))))
