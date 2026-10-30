(ns bbh.claude
  "Claude Code as the agent loop. It runs on the user's Claude subscription and
  calls the session's clojure tool through bbh.mcp, which forwards each call
  back here over a loopback socket."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.session :as session]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net InetAddress ServerSocket]
           [java.util.concurrent LinkedBlockingQueue]))

(def ^:private tool-name "mcp__bbh__clojure")

(defn- serve-bridge
  "Answer bbh.mcp's describe and call requests for session S."
  [s token]
  (let [server (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))]
    (future
      (loop []
        (when-let [sock (try (.accept server) (catch Exception _ nil))]
          (future
            (with-open [sock sock]
              (let [in (io/reader sock)
                    out (io/writer sock)
                    {:keys [op id code] :as req} (json/parse-string (.readLine in) true)
                    reply (cond (not= token (:token req)) {:error "bad token"}
                                (= op "describe") {:description (get-in @s [:manifest :tool-description])}
                                (= op "call") {:text (session/run-code! s id code)}
                                :else {:error (str "unknown op " op)})]
                (.write out (str (json/generate-string reply) "\n"))
                (.flush out))))
          (recur))))
    {:server server :token token}))

(defn- send-line! [^java.io.Writer w msg]
  (locking w
    (.write w (str (json/generate-string msg) "\n"))
    (.flush w)))

(defn- session-file [s] (str (fs/path (:dir @s) "claude-session")))

(defn- seen-file
  "How many transcript messages the Claude Code session has seen. A resumed
  session that has seen fewer gets the rest as history, such as the turns
  another model took after a /model switch."
  [s]
  (str (fs/path (:dir @s) "claude-seen")))

(defn- seen [s]
  (let [f (seen-file s)]
    (when (fs/exists? f) (parse-long (str/trim (slurp f))))))

(defn- history
  "The transcript so far as text, for a Claude Code session that never saw it."
  [messages]
  (str/join "\n\n"
            (for [{:keys [role content tool_calls]} messages]
              (str role ": " content
                   (str/join (for [c tool_calls] (str "\n[clojure] " (get-in c [:function :arguments]))))))))

(defn- start-claude! [s system effort]
  (let [{:keys [dir cwd engine bridge]} @s
        resume (let [f (session-file s)] (when (fs/exists? f) (str/trim (slurp f))))
        mcp {:mcpServers {:bbh {:command "bb"
                                :args ["--config" (System/getProperty "babashka.config") "-m" "bbh.mcp"]
                                :env {"BBH_BRIDGE_PORT" (str (.getLocalPort ^ServerSocket (:server bridge)))
                                      "BBH_BRIDGE_TOKEN" (:token bridge)}}}}
        proc (apply p/process
                    {:dir cwd :err :append :err-file (fs/file dir "claude.log")
                     :extra-env {"DISABLE_AUTOUPDATER" "1" "DISABLE_AUTO_COMPACT" "1"}}
                    (or (System/getenv "BBH_CLAUDE") "claude")
                    "-p" "--input-format" "stream-json" "--output-format" "stream-json"
                    "--verbose" "--include-partial-messages"
                    "--tools" "" "--strict-mcp-config" "--mcp-config" (json/generate-string mcp)
                    "--allowedTools" tool-name "--setting-sources" ""
                    "--system-prompt" system "--model" (:model engine)
                    (concat (when effort ["--effort" effort])
                            (when resume ["--resume" resume])))
        queue (LinkedBlockingQueue.)]
    (future
      (let [unparsed (atom [])]
        (try
          (doseq [line (line-seq (io/reader (:out proc)))]
            (.put queue (try (json/parse-string line true)
                             (catch Exception _
                               (swap! unparsed #(vec (take-last 20 (conj % line))))
                               {:type "unparsed" :line line}))))
          (catch Exception _ nil)
          (finally (.put queue {:type ::exit :unparsed @unparsed})))))
    (swap! s assoc :claude {:proc proc :queue queue :in (io/writer (:in proc)) :system system :effort effort
                            :fresh (nil? resume) :cost 0.0})))

(defmethod session/stop-engine! :claude [s]
  (when-let [proc (get-in @s [:claude :proc])]
    (p/destroy-tree proc))
  (when-let [server (get-in @s [:bridge :server])]
    (.close ^ServerSocket server))
  (swap! s dissoc :claude :bridge))

(defn- ensure-claude!
  "A running Claude Code for SYSTEM and EFFORT. Changing either restarts it on
  the same session, which also starts its prompt cache over."
  [s system effort]
  (let [{:keys [proc] :as c} (:claude @s)]
    (when (and c (or (not (p/alive? proc)) (not= [system effort] [(:system c) (:effort c)])))
      (session/stop-engine! s)))
  (when-not (:bridge @s)
    (swap! s assoc :bridge (serve-bridge s (str (random-uuid)))))
  (when-not (:claude @s)
    (start-claude! s system effort)))

(defn- request-usage
  "A Messages API usage object as bbh's usage record."
  [{:keys [input_tokens cache_read_input_tokens cache_creation_input_tokens output_tokens output_tokens_details]}]
  {:input (+ (or input_tokens 0) (or cache_read_input_tokens 0) (or cache_creation_input_tokens 0))
   :cached (or cache_read_input_tokens 0)
   :written (or cache_creation_input_tokens 0)
   :output (or output_tokens 0)
   :thinking (or (:thinking_tokens output_tokens_details) 0)})

(defn- record-assistant! [s content]
  (let [text (str/join (keep #(when (= "text" (:type %)) (:text %)) content))
        calls (for [{:keys [type id name input]} content :when (= "tool_use" type)]
                {:id id :type "function"
                 :function {:name (if (= tool-name name) "clojure" name) :arguments (json/generate-string input)}})]
    (when (or (seq text) (seq calls))
      (session/record! s (cond-> {:role "assistant" :content text} (seq calls) (assoc :tool_calls (vec calls)))))))

(defn- record-tool-results! [s content]
  (doseq [{:keys [type tool_use_id] :as block} content :when (= "tool_result" type)]
    (session/record! s {:role "tool" :tool_call_id tool_use_id
                        :content (let [c (:content block)]
                                   (if (string? c) c (str/join (keep :text c))))})))

(defn- exited!
  "Record why Claude Code's process ended in claude.log, stop the engine, and return the error for the user.
  Claude Code often says nothing on stderr, so its exit code and stray stdout are the only trace."
  [s unparsed]
  (let [log (fs/file (:dir @s) "claude.log")
        code (:exit (deref (get-in @s [:claude :proc]) 5000 nil))
        stderr (when (fs/exists? log) (->> (fs/read-all-lines log) (remove str/blank?)
                                             (remove #(str/starts-with? % "claude exited with code ")) last))]
    (spit log (str (str/join (map #(str % "\n") unparsed))
                   "claude exited with code " (if (some? code) code "unknown") " at " (java.time.Instant/now) "\n")
          :append true)
    (session/stop-engine! s)
    (ex-info (str "Claude Code exited with code " (if (some? code) code "unknown")
                  (when-let [why (or stderr (last unparsed))] (str ": " why))
                  "; see " log)
             {:exit code})))

(defn- calls-answered?
  "Whether every tool call of the latest assistant message has its result."
  [messages]
  (let [results (take-while #(= "tool" (:role %)) (rseq messages))]
    (= (count results) (count (:tool_calls (nth (rseq messages) (count results) nil))))))

(defmethod session/turn! :claude [s]
  (let [system (session/system-prompt! s)
        prior (pop (:messages @s))
        _ (ensure-claude! s system (get-in @s [:manifest :effort]))
        {:keys [in queue fresh]} (:claude @s)
        missed (when-not fresh
                 (subvec prior (min (count prior) (or (seen s) (count prior)))))
        text (:content (peek (:messages @s)))
        text (cond (and fresh (some #(= "assistant" (:role %)) prior))
                   (str "[bbh] Earlier in this conversation, before you joined it:\n\n"
                        (history prior) "\n\n" text)
                   (seq missed)
                   (str "[bbh] Messages in this conversation since you last took part:\n\n"
                        (history missed) "\n\n" text)
                   :else text)]
    (swap! s assoc-in [:claude :fresh] false)
    (swap! s assoc :cancel #(send-line! in {:type "control_request" :request_id (str (random-uuid))
                                            :request {:subtype "interrupt"}}))
    (send-line! in {:type "user" :message {:role "user" :content text}})
    (try
      ;; A request starts when its prompt or tool results go out, not at its
      ;; first stream event, so the run includes the time to first token.
      (loop [texts 0 request-start (System/currentTimeMillis) calls 0]
        (let [{:keys [type event message] :as ev} (.take ^LinkedBlockingQueue queue)]
          (case type
            "stream_event"
            (let [{etype :type :keys [delta content_block]} event]
              (cond (and (= "content_block_delta" etype) (= "text_delta" (:type delta)) (seq (:text delta)))
                    (do (session/emit! s {:type :text :text (:text delta)}) (recur texts request-start calls))
                    (and (= "content_block_delta" etype) (= "thinking_delta" (:type delta)) (seq (:thinking delta)))
                    (do (session/emit! s {:type :thought :text (:thinking delta)}) (recur texts request-start calls))
                    (and (= "content_block_start" etype) (= "text" (:type content_block)))
                    (do (when (pos? texts) (session/emit! s {:type :text :text "\n\n"}))
                        (recur (inc texts) request-start calls))
                    (and (= "content_block_start" etype) (= "tool_use" (:type content_block)))
                    (recur texts request-start (inc calls))
                    ;; One per model request, with that request's final token counts.
                    (and (= "message_delta" etype) (:usage event))
                    (let [usage (request-usage (:usage event))]
                      (session/add-usage! s usage)
                      (session/trace-run! s "llm" request-start
                                          (cond-> {:usage usage :tool-calls calls}
                                            (:interrupted @s) (assoc :status "interrupted")
                                            (= "max_tokens" (:stop_reason delta)) (assoc :truncated true)))
                      (recur texts request-start 0))
                    :else (recur texts request-start calls)))
            "assistant" (do (record-assistant! s (:content message)) (recur texts request-start calls))
            "user" (do (record-tool-results! s (:content message))
                       ;; Claude Code owns the loop and has no hook between requests, so
                       ;; it is replaced once it holds no unanswered call.
                       (if (and (session/rollover-due? s) (calls-answered? (:messages @s)))
                         (do (session/continue-mid-turn! s) (session/turn! s))
                         ;; An interrupt also arrives as a user event, with no tool result.
                         (recur texts
                                (if (some #(= "tool_result" (:type %)) (:content message))
                                  (System/currentTimeMillis)
                                  request-start)
                                calls)))
            "system" (do (when (and (= "init" (:subtype ev)) (:session_id ev))
                           (spit (session-file s) (:session_id ev)))
                         (recur texts request-start calls))
            "rate_limit_event" (do (swap! s assoc :limits (get-in ev [:rate_limit_info :unifiedWindows]))
                                   (recur texts request-start calls))
            "result" (let [total (or (:total_cost_usd ev) 0)
                           ;; Claude Code's total covers its whole process, not this turn.
                           cost (- total (get-in @s [:claude :cost] 0))]
                       (swap! s assoc-in [:claude :cost] total)
                       (session/add-usage! s {:cost (max 0 cost)
                                              :size (some :contextWindow (vals (:modelUsage ev)))})
                       (when (and (:is_error ev) (not (:interrupted @s)))
                         (let [why (or (not-empty (str/join "\n" (:errors ev))) (:result ev) (:subtype ev))]
                           (if (re-find #"(?i)prompt is too long" (str why))
                             (do (session/continue-mid-turn! s) (session/turn! s))
                             (throw (ex-info (str "Claude Code: " why) {:subtype (:subtype ev)}))))))
            ::exit (throw (exited! s (:unparsed ev)))
            (recur texts request-start calls))))
      (finally
        (swap! s assoc :cancel nil)
        (spit (seen-file s) (str (count (:messages @s))))))))
