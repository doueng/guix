(ns bbh.main
  "Command line entry: the terminal front end, or bbh acp for agent-shell."
  (:require [babashka.fs :as fs]
            [bbh.acp :as acp]
            [bbh.inbox :as inbox]
            [bbh.models :as models]
            [bbh.session :as session]
            [bbh.transcript :as transcript]
            [clojure.string :as str])
  (:import [org.jline.reader EndOfFileException LineReader LineReaderBuilder UserInterruptException]
           [org.jline.terminal Terminal$Signal TerminalBuilder]))

(defn- data-dir []
  (fs/path (or (System/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh"))

(defn- conversation-dir [resume]
  (let [root (fs/path (data-dir) "conversations")
        saved (when (fs/exists? root)
                (->> (fs/list-dir root) (filter #(fs/exists? (fs/path % "messages.jsonl"))) (map str) sort))]
    (cond (nil? resume) (str (fs/path root (transcript/new-id)))
          (= "last" resume) (transcript/continuation (or (last saved) (throw (ex-info "No conversation to resume" {}))))
          (some #{(str (fs/path root resume))} saved) (transcript/continuation (str (fs/path root resume)))
          :else (throw (ex-info (str "No conversation " resume " under " root) {})))))

(defn- dim [s] (str "\u001b[2m" s "\u001b[0m"))

(defn- cache-note [{:keys [cache ratio]}]
  (case cache
    :cold "cache cold"
    :miss (format "cache MISS, %.0f%% cached" (* 100 ratio))
    :hit (format "%.0f%% cached" (* 100 ratio))))

(defn- print-event [{:keys [type] :as ev}]
  (case type
    :text (do (print (:text ev)) (flush))
    :thought (do (print (dim (:text ev))) (flush))
    :tool-call (println (str "\n" (dim (str "[clojure]\n" (:code ev)))))
    :tool-result (println (dim (str/join "\n" (take 15 (str/split-lines (:text ev))))))
    :usage (when-let [r (:request ev)]
             (println (str "\n" (dim (format "[context %s%s, %s, $%.4f]" (or (:used ev) "?")
                                               (if (:size ev) (str "/" (:size ev)) "")
                                               (cache-note r) (double (:spent ev)))))))))

(defn- parse-args [args]
  (loop [[flag value & more] args opts {}]
    (case flag
      nil opts
      "--model" (recur more (assoc opts :model value))
      "--resume" (recur more (assoc opts :resume value))
      (throw (ex-info (str "Usage: bbh [acp] [--model PROVIDER/MODEL] [--resume ID|last]; unknown " flag) {})))))

(defn- inbox-connect!
  "Keep the poller on S's inbox id, which /resume changes. Wakes of the old id
  stay pending in the Inbox until that conversation is resumed."
  [state s]
  (let [id (transcript/inbox-id (:dir @s))]
    (when (not= id (:id @state))
      (some-> (:poller @state) inbox/stop!)
      (swap! state assoc :id id :pending [] :failed []
             :poller (inbox/start! {:session id :project (:cwd @s)
                                    :deliver #(swap! state update :pending conj %)})))))

(defn- run-prompt!
  "Run TEXT, which holds DELIVERY when it came from the Inbox. A completed turn
  acks it and has failed wakes sent again. A failed one leaves it unacked."
  [state s text delivery]
  (let [{:keys [poller]} @state]
    (inbox/busy! poller true)
    (try
      (session/prompt! s text)
      (when delivery (inbox/ack! poller (:id delivery)))
      (run! #(inbox/retry! poller (:id %)) (:failed @state))
      (swap! state assoc :failed [])
      (catch Exception e
        (println (str "\n" (ex-message e)))
        (when delivery (swap! state update :failed conj delivery)))
      (finally (inbox/busy! poller false))))
  (println)
  (inbox-connect! state s))

(defn- nudge-reader
  "End an empty readLine when a wake is waiting, because a blocked read cannot
  see it. A draft is left alone, so the wake runs after that prompt instead."
  [state ^LineReader reader]
  (doto (Thread. (fn []
                   (loop []
                     (Thread/sleep 1000)
                     (when (and (seq (:pending @state)) (.isReading reader)
                                (str/blank? (str (.getBuffer reader))))
                       (.raise (.getTerminal reader) Terminal$Signal/INT))
                     (recur))))
    (.setDaemon true)
    (.start)))

(defn- terminal [args]
  (let [{:keys [model resume]} (parse-args args)
        dir (conversation-dir resume)
        s (session/new-session {:dir dir
                                :init (or (System/getenv "BBH_INIT") (str (fs/expand-home "~/.config/bbh/init.clj")))
                                :model (or model (models/default-model))
                                :make-engine models/engine
                                :emit print-event
                                :notes (when resume ["This conversation resumed in a new REPL, so earlier live definitions are gone."])})
        _ (some-> (session/start-repl! s) println)
        reader (-> (LineReaderBuilder/builder)
                   (.terminal (-> (TerminalBuilder/builder) (.system true) (.build)))
                   (.variable LineReader/HISTORY_FILE (fs/path (data-dir) "history"))
                   (.build))
        inbox-state (atom {})]
    (inbox-connect! inbox-state s)
    (nudge-reader inbox-state reader)
    (sun.misc.Signal/handle (sun.misc.Signal. "INT")
                            (reify sun.misc.SignalHandler (handle [_ _] (session/interrupt! s))))
    (println (dim (str "bbh " (fs/file-name dir) " " (:model @s) " nREPL " (:port (:repl @s)) "  /help lists commands")))
    (try
      (loop []
        (if-let [delivery (first (:pending @inbox-state))]
          (do (swap! inbox-state update :pending subvec 1)
              (println (dim (first (str/split-lines (:text delivery)))))
              (run-prompt! inbox-state s (:text delivery) delivery)
              (recur))
          (let [line (try (str/trim (.readLine reader "> "))
                          (catch UserInterruptException _ "")
                          (catch EndOfFileException _ nil))]
            (cond (or (nil? line) (= "/quit" line)) nil
                  (str/blank? line) (recur)
                  :else (do (run-prompt! inbox-state s line nil)
                            (recur))))))
      (finally
        (inbox/stop! (:poller @inbox-state))
        (session/close! s)
        (shutdown-agents)))))

(defn -main [& args]
  (case (first args)
    "acp" (acp/-main (some-> (:resume (parse-args (rest args))) conversation-dir))
    "inbox-poll" (let [[_ id project] args]
                   (inbox/-poll (transcript/inbox-id (str (fs/path (data-dir) "conversations" id))) project))
    (terminal args)))
