(ns bbh.acp
  "bbh as an Agent Client Protocol agent over stdio, for Emacs agent-shell."
  (:require [babashka.fs :as fs]
            [bbh.models :as models]
            [bbh.repl :as repl]
            [bbh.session :as session]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private out-lock (Object.))

(defn- send! [msg]
  (locking out-lock
    (println (json/generate-string (assoc msg :jsonrpc "2.0")))
    (flush)))

(defn- update! [sid update]
  (send! {:method "session/update" :params {:sessionId sid :update update}}))

(defn- text-block [s]
  [{:type "content" :content {:type "text" :text s}}])

(defn- title
  "The code's first line, which agent-shell shows on the collapsed tool block."
  [code]
  (let [[line & more] (str/split-lines (str/trim code))
        line (str/trim line)]
    (cond (> (count line) 72) (str (subs line 0 71) "…")
          more (str line " …")
          :else line)))

(defn- clojure-block [code] (str "```clojure\n" code "\n```"))

(defn- event->update [{:keys [type] :as ev}]
  (case type
    :text {:sessionUpdate "agent_message_chunk" :content {:type "text" :text (:text ev)}}
    :thought {:sessionUpdate "agent_thought_chunk" :content {:type "text" :text (:text ev)}}
    :tool-call {:sessionUpdate "tool_call" :toolCallId (:id ev) :title (title (:code ev)) :kind "execute"
                :status "in_progress" :rawInput {:code (:code ev)}
                :content (text-block (clojure-block (:code ev)))}
    :tool-result {:sessionUpdate "tool_call_update" :toolCallId (:id ev) :status (name (:status ev))
                  :content (text-block (str (clojure-block (:code ev)) "\n\n```\n" (:text ev) "\n```"))}
    ;; _meta.bbh.request is one model request's tokens and whether its prefix
    ;; came from the prompt cache, which the Doom config shows.
    :usage (cond-> {:sessionUpdate "usage_update" :cost {:amount (:spent ev) :currency "USD"}}
             (:used ev) (assoc :used (:used ev))
             (:size ev) (assoc :size (:size ev))
             (:request ev) (assoc :_meta {:bbh {:request (:request ev)}}))))

(defn- turn-usage
  "A turn's tokens in the PromptResponse usage form."
  [{:keys [input cached written output thinking requests]}]
  (when requests
    {:inputTokens input :outputTokens output :cachedReadTokens cached :cachedWriteTokens written
     :thoughtTokens thinking :totalTokens (+ input output)}))

(defn- data-dir []
  (fs/path (or (System/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh" "conversations"))

(def ^:private sessions (atom {}))

(defn- config-options
  "The model selector in the configOptions form that newer ACP clients read
  instead of the models field."
  [s]
  (let [current (:model @s)]
    [{:id "model" :name "Model" :category "model" :type "select" :currentValue current
      :options (for [m (distinct (cons current models/available))] {:value m :name m})}]))

(defn- commands-update [sid s]
  (update! sid {:sessionUpdate "available_commands_update"
                :availableCommands (session/all-commands s)}))

(defn- new-session [{:keys [cwd]}]
  (let [sid (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS"))
        s (session/new-session {:dir (str (fs/path (data-dir) sid))
                                :init (or (System/getenv "BBH_INIT") (str (fs/expand-home "~/.config/bbh/init.clj")))
                                :cwd (or cwd (str (fs/cwd)))
                                :model (models/default-model)
                                :make-engine models/engine
                                :emit #(when-not (and (= :text (:type %)) (empty? (:text %)))
                                         (update! sid (event->update %)))})
        problem (session/start-repl! s)]
    (swap! sessions assoc sid s)
    [sid problem]))

(defn- rpc-error! [id e]
  (when (some? id)
    (send! {:id id :error {:code (or (:rpc-code (ex-data e)) -32000)
                           :message (or (ex-message e) (str e))}})))

(defn- prompt-text [blocks]
  (when-not (and (sequential? blocks) (seq blocks)
                (every? #(and (= "text" (:type %)) (string? (:text %))) blocks))
    (throw (ex-info "bbh accepts non-empty arrays of text prompt blocks only" {:rpc-code -32602})))
  (str/join "\n\n" (map :text blocks)))

(defn- handle [{:keys [id method params]}]
  (let [s (some->> (:sessionId params) (get @sessions))]
    (when (and (contains? #{"session/prompt" "session/set_model" "session/set_config_option" "session/cancel"} method)
               (nil? s))
      (throw (ex-info "Unknown session" {:rpc-code -32602})))
    (when (and (contains? #{"session/prompt" "session/set_model" "session/set_config_option"} method) (:busy @s))
      (throw (ex-info "This session is busy; cancel or wait for the current turn" {:rpc-code -32000})))
    (case method
      "initialize"
      (send! {:id id :result {:protocolVersion 1
                              :agentInfo {:name "bbh" :title "bbh" :version "0.1"}
                              :agentCapabilities {:loadSession false
                                                  :promptCapabilities {:image false :audio false :embeddedContext false}}
                              :authMethods []}})

      "session/new"
      (let [[sid problem] (new-session params)
            current (:model @(get @sessions sid))]
        ;; The current model is listed even when it is not one of bbh's suggestions,
        ;; because clients such as Harbor only select models from this list.
        (send! {:id id :result {:sessionId sid
                                :models {:currentModelId current
                                         :availableModels (for [m (distinct (cons current models/available))] {:modelId m :name m})}
                                :configOptions (config-options (get @sessions sid))}})
        (commands-update sid (get @sessions sid))
        (when problem (update! sid (event->update {:type :text :text (str "[bbh] " problem)}))))

      "session/set_model"
      (do (session/set-model! s (:modelId params))
          (send! {:id id :result {}}))

      "session/set_config_option"
      (do (when-not (= "model" (:configId params))
            (throw (ex-info (str "bbh has no config option " (:configId params)) {:rpc-code -32602})))
          (session/set-model! s (:value params))
          (send! {:id id :result {:configOptions (config-options s)}}))

      "session/prompt"
      (let [text (prompt-text (:prompt params))]
        (swap! s assoc :busy true)
        (future
          (let [response (try
                           (let [stop ({:end-turn "end_turn" :cancelled "cancelled"} (session/prompt! s text))]
                             {:id id :result (cond-> {:stopReason stop}
                                               (turn-usage (:turn-usage @s)) (assoc :usage (turn-usage (:turn-usage @s))))})
                           (catch Exception e
                             {:id id :error {:code -32000 :message (or (ex-message e) (str e))}}))]
            (swap! s assoc :busy false)
            (send! response))))

      "session/cancel"
      (some-> s session/interrupt!)

      (when id
        (send! {:id id :error {:code -32601 :message (str "bbh does not implement " method)}})))))

(defn -main [& _]
  (try
    (doseq [line (line-seq (io/reader System/in))
            :when (not (str/blank? line))]
      (let [request (try (json/parse-string line true)
                         (catch Exception _ ::invalid))]
        (if (or (= ::invalid request) (not (map? request)) (not (string? (:method request))))
          (send! {:id nil :error {:code (if (= ::invalid request) -32700 -32600)
                                  :message "Invalid JSON-RPC request"}})
          (try (handle request)
               (catch Exception e (rpc-error! (:id request) e))))))
    (finally
      (doseq [s (vals @sessions)] (session/close! s))
      (shutdown-agents))))
