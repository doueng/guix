(ns bbh.codex
  "The ChatGPT subscription as an engine, over the Codex Responses API. The
  login is Pi's openai-codex OAuth entry, refreshed by Pi."
  (:require [bbh.auth :as auth]
            [bbh.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private url "https://chatgpt.com/backend-api/codex/responses")

(defn- login
  "The subscription's access token and account id, from Pi's auth.json."
  []
  (let [{:keys [access accountId expires]} (auth/pi-entry "openai-codex")]
    (when-not (and access (> (or expires 0) (System/currentTimeMillis)))
      (throw (ex-info "Log in to OpenAI Codex in Pi; its token is missing or expired." {})))
    {:token access :account accountId}))

(def ^:private text-part {"user" "input_text" "assistant" "output_text"})

(defn- item
  "One transcript message as Responses API input items: a text message, one
  function_call per tool call, or a function_call_output for a tool result."
  [{:keys [role content tool_calls tool_call_id]}]
  (if-let [part (text-part role)]
    (cond-> (if (seq content)
              [{:type "message" :role role :content [{:type part :text content}]}]
              [])
      (seq tool_calls) (into (for [{:keys [id function]} tool_calls]
                               {:type "function_call" :call_id id
                                :name (:name function)
                                :arguments (or (:arguments function) "{}")})))
    (when (= "tool" role)
      [{:type "function_call_output" :call_id tool_call_id :output (str content)}])))

(defn request
  "The Responses API request for MODEL. bbh's chat-completions request becomes
  INSTRUCTIONS from the system message plus flat INPUT items, and its function
  tools lose the nested :function map."
  [model {:keys [messages tools effort max-tokens session-id]}]
  (cond-> {:model model
           :instructions (str/join "\n\n" (keep #(when (= "system" (:role %)) (:content %)) messages))
           :input (vec (mapcat item messages))
           :stream true
           :store false}
    (seq tools) (assoc :tools (vec (for [{:keys [function]} tools]
                                     (assoc function :type "function"))))
    effort (assoc :reasoning {:effort effort})
    max-tokens (assoc :max_output_tokens max-tokens)
    session-id (assoc :prompt_cache_key session-id)))

(defn- usage-record [{:keys [input_tokens output_tokens input_tokens_details output_tokens_details]}]
  (when input_tokens
    {:input input_tokens
     :cached (or (:cached_tokens input_tokens_details) 0)
     :written (or (:cache_write_tokens input_tokens_details) 0)
     :output (or output_tokens 0)
     :thinking (or (:reasoning_tokens output_tokens_details) 0)
     :cost nil}))

(defn- step [acc {:keys [type] :as e}]
  (case type
    "response.output_text.delta" (update acc :content str (:delta e))
    "response.output_item.done"
    (let [{:keys [call_id name arguments] :as it} (:item e)]
      (if (= "function_call" (:type it))
        (update acc :calls conj {:id call_id :type "function"
                                 :function {:name name :arguments arguments}})
        acc))
    "response.completed" (assoc acc :usage (get-in e [:response :usage])
                                 :incomplete (get-in e [:response :incomplete_details :reason]))
    acc))

(defn- finish
  "The sample contract's reply from the folded events: {:message :usage}, plus
  :truncated when the output limit ended the reply."
  [{:keys [content calls usage incomplete]}]
  (cond-> {:message (cond-> {:role "assistant" :content content}
                      (seq calls) (assoc :tool_calls (vec calls)))
           :usage (usage-record usage)}
    (= "max_output_tokens" incomplete) (assoc :truncated true)))

(defn accumulate
  "Fold streamed events into the sample contract's reply."
  [events]
  (finish (reduce step {:content "" :calls []} events)))

(defn sample
  "A sample function for MODEL on the ChatGPT subscription. Takes the same
  request and callbacks as bbh.llm/openrouter."
  [model]
  (fn [req {:keys [on-text on-thought on-open on-retry]}]
    (let [{:keys [token account]} (login)
          cancelled (atom false)
          _ (on-open #(reset! cancelled true))
          resp (llm/stream! "Codex" url
                            {"Authorization" (str "Bearer " token)
                             "chatgpt-account-id" account
                             "Content-Type" "application/json"
                             "Accept" "text/event-stream"
                             "originator" "codex_cli_rs"
                             "User-Agent" "codex_cli_rs"}
                            (json/generate-string (request model req))
                            cancelled (or on-retry (fn [_])))
          acc (volatile! {:content "" :calls []})]
      (if-not resp
        {:message {:role "assistant" :content ""} :usage nil :interrupted true}
        (with-open [body (:body resp)]
          (on-open #(do (reset! cancelled true) (.close ^java.io.InputStream body)))
          (try
            (doseq [{:keys [type delta] :as e} (llm/chunks body)]
              (when-let [err (or (:error e) (get-in e [:response :error]))]
                (throw (ex-info (str "Codex: " (or (:message err) err)) e)))
              (cond
                (and (= "response.output_text.delta" type) (seq delta)) (on-text delta)
                (and (= "response.reasoning_summary_text.delta" type) (seq delta) on-thought) (on-thought delta))
              (vswap! acc step e))
            (finish @acc)
            (catch java.io.IOException e
              (if @cancelled
                (-> (finish @acc) (update :message dissoc :tool_calls) (assoc :interrupted true))
                (throw e)))))))))
