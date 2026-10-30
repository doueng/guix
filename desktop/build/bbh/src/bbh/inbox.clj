(ns bbh.inbox
  "The bbh side of the Plannotator Inbox. Agents post through its MCP route, and
  a poller long-polls its bridge for what the person wrote. The bridge is an
  internal Plannotator contract, so `bb test:inbox` proves it against the
  version modules/engstrand/packages/plannotator.scm pins.

  A front end owns delivery. The poller hands it each command once and is told
  `ack`, `retry` or `busy`. Delivery is at least once, because an ack follows
  the turn that held the wake."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private host "bbh")
(def ^:private poll-ms 25000)

(defn connection
  "The running Inbox's {:port :token}, read fresh because a restart changes them.
  modules/engstrand/features/plannotator.scm pins the Inbox to this directory."
  []
  (let [dir (or (System/getenv "PLANNOTATOR_DATA_DIR") (fs/expand-home "~/.plannotator"))
        f (fs/path dir "inbox" "inbox.json")]
    (when (fs/exists? f)
      (json/parse-string (slurp (str f)) true))))

(defn- post [{:keys [port token]} path body timeout-ms]
  (http/post (str "http://127.0.0.1:" port path)
             {:throw false :timeout timeout-ms
              :headers {"Authorization" (str "Bearer " token)
                        "Content-Type" "application/json"
                        "Accept" "application/json, text/event-stream"
                        "MCP-Protocol-Version" "2025-06-18"}
              :body (json/generate-string body)}))

(defn- mcp-reply
  "The JSON-RPC message in BODY, which is either plain JSON or one SSE event."
  [body]
  (json/parse-string (or (second (re-find #"(?m)^data: (.*)$" body)) body) true))

(defn tool!
  "Call the Inbox tool TOOL as inbox id SESSION working in PROJECT. Returns its
  structured result, else its text."
  [{:keys [session project]} tool args]
  (let [conn (or (connection) (throw (ex-info "The Plannotator Inbox is not running" {})))
        {:keys [status body]} (post conn "/mcp"
                                    {:jsonrpc "2.0" :id 1 :method "tools/call"
                                     :params {:name tool
                                              :arguments (merge {:project_path project} args
                                                                {:agent_session session
                                                                 :agent_host host
                                                                 :agent_name host})}}
                                    110000)
        {:keys [result error]} (when (= 200 status) (mcp-reply body))
        text (get-in result [:content 0 :text])]
    (cond (not= 200 status) (throw (ex-info (str "The Inbox answered " status) {:body body}))
          error (throw (ex-info (:message error) error))
          (:isError result) (throw (ex-info (str text) result))
          :else (or (:structuredContent result) text))))

(defn wake-text
  "The prompt that hands the agent COMMAND."
  [{:keys [id subject body thread_id]}]
  (str "Plannotator Inbox: " subject " (" id ")\n\n"
       body "\n\n"
       "[thread " thread_id " · the person reads the Inbox, not this chat, so send your answer there with "
       "(inbox \"send_message\" {:reply_to \"" id "\" :body \"...\"})]"))

(defn- bridge! [{:keys [session]} path body timeout-ms]
  (when-let [conn (connection)]
    (post conn (str "/api/inbox/bridge/" path) (assoc body :session session :host host) timeout-ms)))

(defn- report!
  "Best effort. An ack that is lost repeats the delivery later, which is allowed."
  [p body]
  (try (bridge! p "event" body 5000)
       (catch Exception e
         (binding [*out* *err*] (println "bbh inbox:" (ex-message e))))))

(defn- poll!
  "One long poll. Returns the commands, or nil when the Inbox did not answer."
  [{:keys [project state started wait-ms] :as p}]
  (let [{:keys [busy idle-since]} @state
        r (try (bridge! p "poll" {:waitMs wait-ms :project_path project :started_at started
                                  :busy busy :idle_since idle-since}
                        (+ wait-ms 5000))
               (catch Exception _ nil))]
    (when (= 200 (:status r))
      (:commands (json/parse-string (:body r) true)))))

(defn- poll-loop [{:keys [state deliver retry-ms] :as p}]
  (while (not (:stopped @state))
    (let [commands (poll! p)
          fresh (remove (comp (:seen @state) :id) commands)]
      (when-not (:stopped @state)
        (doseq [c fresh]
          (swap! state update :seen conj (:id c))
          (deliver {:id (:id c) :text (wake-text c)}))
        ;; The Inbox answers at once while a delivered command waits for its ack.
        (when (or (nil? commands) (and (seq commands) (empty? fresh)))
          (Thread/sleep (long retry-ms)))))))

(defn start!
  "Poll for inbox id SESSION on a daemon thread, calling DELIVER with {:id :text}
  for each new command. Returns the poller."
  [{:keys [session project deliver wait-ms retry-ms]
    :or {wait-ms poll-ms retry-ms 2000}}]
  (let [p {:session session :project project :deliver deliver :wait-ms wait-ms :retry-ms retry-ms
           :started (str (java.time.Instant/now))
           :state (atom {:seen #{} :busy false :idle-since (System/currentTimeMillis)})}]
    (doto (Thread. #(poll-loop p)) (.setDaemon true) (.start))
    p))

(defn stop! [{:keys [state]}]
  (swap! state assoc :stopped true))

(defn ack! [p id]
  (report! p {:type "delivered" :id id}))

(defn retry!
  "Have the next poll deliver ID again."
  [{:keys [state]} id]
  (swap! state update :seen disj id))

(defn busy! [{:keys [state] :as p} busy]
  (let [now (System/currentTimeMillis)
        {:keys [idle-since]} (swap! state #(cond-> (assoc % :busy busy) (not busy) (assoc :idle-since now)))]
    (report! p {:type "state" :busy busy :idle_since idle-since})))

(defn -poll
  "Serve SESSION to a front end on stdio. Deliveries go out as JSON lines. Input
  lines are `ack ID`, `retry ID`, `busy true` and `busy false`, and EOF ends it."
  [session project]
  (let [out (Object.)
        p (start! {:session session :project project
                   :deliver (fn [delivery]
                              (locking out
                                (println (json/generate-string delivery))
                                (flush)))})]
    (doseq [line (line-seq (io/reader System/in))]
      (let [[cmd arg] (str/split line #" " 2)]
        (case cmd
          "ack" (ack! p arg)
          "retry" (retry! p arg)
          "busy" (busy! p (= "true" arg))
          nil)))))
