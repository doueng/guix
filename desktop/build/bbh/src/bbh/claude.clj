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

(defn- history
  "The transcript so far as text, for a Claude Code session that never saw it."
  [messages]
  (str/join "\n\n"
            (for [{:keys [role content tool_calls]} messages]
              (str role ": " content
                   (str/join (for [c tool_calls] (str "\n[clojure] " (get-in c [:function :arguments]))))))))

(defn- start-claude! [s system]
  (let [{:keys [dir cwd engine bridge]} @s
        resume (let [f (session-file s)] (when (fs/exists? f) (str/trim (slurp f))))
        mcp {:mcpServers {:bbh {:command "bb"
                                :args ["--config" (System/getProperty "babashka.config") "-m" "bbh.mcp"]
                                :env {"BBH_BRIDGE_PORT" (str (.getLocalPort ^ServerSocket (:server bridge)))
                                      "BBH_BRIDGE_TOKEN" (:token bridge)}}}}
        proc (apply p/process
                    {:dir cwd :err :append :err-file (fs/file dir "claude.log")
                     :extra-env {"DISABLE_AUTOUPDATER" "1"}}
                    (or (System/getenv "BBH_CLAUDE") "claude")
                    "-p" "--input-format" "stream-json" "--output-format" "stream-json"
                    "--verbose" "--include-partial-messages"
                    "--tools" "" "--strict-mcp-config" "--mcp-config" (json/generate-string mcp)
                    "--allowedTools" tool-name "--setting-sources" ""
                    "--system-prompt" system "--model" (:model engine)
                    (when resume ["--resume" resume]))
        queue (LinkedBlockingQueue.)]
    (future
      (try
        (doseq [line (line-seq (io/reader (:out proc)))]
          (.put queue (try (json/parse-string line true) (catch Exception _ {:type "unparsed" :line line}))))
        (catch Exception _ nil)
        (finally (.put queue {:type ::exit}))))
    (swap! s assoc :claude {:proc proc :queue queue :in (io/writer (:in proc)) :system system :fresh (nil? resume)})))

(defmethod session/stop-engine! :claude [s]
  (when-let [proc (get-in @s [:claude :proc])]
    (p/destroy-tree proc))
  (when-let [server (get-in @s [:bridge :server])]
    (.close ^ServerSocket server))
  (swap! s dissoc :claude :bridge))

(defn- ensure-claude! [s system]
  (let [{:keys [proc] :as c} (:claude @s)]
    (when (and c (or (not (p/alive? proc)) (not= system (:system c))))
      (session/stop-engine! s)))
  (when-not (:bridge @s)
    (swap! s assoc :bridge (serve-bridge s (str (random-uuid)))))
  (when-not (:claude @s)
    (start-claude! s system)))

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

(defmethod session/turn! :claude [s]
  (let [system (session/system-prompt! s)
        prior (pop (:messages @s))
        _ (ensure-claude! s system)
        {:keys [in queue fresh]} (:claude @s)
        text (:content (peek (:messages @s)))
        text (if (and fresh (some #(= "assistant" (:role %)) prior))
               (str "[bbh] Earlier in this conversation, before you joined it:\n\n" (history prior) "\n\n" text)
               text)]
    (swap! s assoc-in [:claude :fresh] false)
    (swap! s assoc :cancel #(send-line! in {:type "control_request" :request_id (str (random-uuid))
                                            :request {:subtype "interrupt"}}))
    (send-line! in {:type "user" :message {:role "user" :content text}})
    (try
      (loop [used nil texts 0]
        (let [{:keys [type event message] :as ev} (.take ^LinkedBlockingQueue queue)]
          (case type
            "stream_event"
            (let [{etype :type :keys [delta content_block]} event]
              (cond (and (= "content_block_delta" etype) (= "text_delta" (:type delta)) (seq (:text delta)))
                    (do (session/emit! s {:type :text :text (:text delta)}) (recur used texts))
                    (and (= "content_block_start" etype) (= "text" (:type content_block)))
                    (do (when (pos? texts) (session/emit! s {:type :text :text "\n\n"})) (recur used (inc texts)))
                    (= "message_start" etype)
                    (let [{:keys [input_tokens cache_read_input_tokens cache_creation_input_tokens]} (:usage (:message event))]
                      (recur (+ (or input_tokens 0) (or cache_read_input_tokens 0) (or cache_creation_input_tokens 0)) texts))
                    :else (recur used texts)))
            "assistant" (do (record-assistant! s (:content message)) (recur used texts))
            "user" (do (record-tool-results! s (:content message)) (recur used texts))
            "system" (do (when (and (= "init" (:subtype ev)) (:session_id ev))
                           (spit (session-file s) (:session_id ev)))
                         (recur used texts))
            "rate_limit_event" (do (swap! s assoc :limits (get-in ev [:rate_limit_info :unifiedWindows]))
                                   (recur used texts))
            "result" (do
                       (session/add-usage! s {:used used :cost (:total_cost_usd ev)})
                       (when (and (:is_error ev) (not (:interrupted @s)))
                         (throw (ex-info (str "Claude Code: "
                                              (or (not-empty (str/join "\n" (:errors ev)))
                                                  (:result ev) (:subtype ev)))
                                         {:subtype (:subtype ev)}))))
            ::exit (do (session/stop-engine! s)
                       (throw (ex-info (str "Claude Code exited; see " (fs/path (:dir @s) "claude.log")) {})))
            (recur used texts))))
      (finally (swap! s assoc :cancel nil)))))
