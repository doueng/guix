(ns bbh.main
  "Command line entry: the terminal front end, or bbh acp for agent-shell."
  (:require [babashka.fs :as fs]
            [bbh.acp :as acp]
            [bbh.inbox :as inbox]
            [bbh.models :as models]
            [bbh.session :as session]
            [bbh.super.client :as super]
            [bbh.super.pane :as pane]
            [bbh.super.voice :as voice]
            [bbh.transcript :as transcript]
            [bbh.web :as web]
            [clojure.string :as str])
  (:import [org.jline.reader EndOfFileException LineReader LineReaderBuilder UserInterruptException]
           [org.jline.terminal TerminalBuilder]))

(defn- conversation-dir
  "The conversation to run: RESUME names a saved one, ID a new one under that
  name (for a controller that must know the mailbox first), else a fresh one."
  [resume id]
  (let [root (fs/path (super/data-dir) "conversations")
        saved (when (fs/exists? root)
                (->> (fs/list-dir root) (filter #(fs/exists? (fs/path % "messages.jsonl"))) (map str) sort))]
    (cond (and (nil? resume) id) (if (fs/exists? (fs/path root id "messages.jsonl"))
                                   (transcript/continuation (str (fs/path root id)))
                                   (str (fs/path root id)))
          (nil? resume) (str (fs/path root (transcript/new-id)))
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
                                               (cache-note r) (double (:spent ev)))))))
    :model nil))

(defn- parse-args [args]
  (loop [[flag value & more] args opts {}]
    (case flag
      nil opts
      "--model" (recur more (assoc opts :model value))
      "--resume" (recur more (assoc opts :resume value))
      "--id" (recur more (assoc opts :id value))
      (throw (ex-info (str "Usage: bbh [acp] [--model PROVIDER/MODEL] [--resume ID|last] [--id ID]; unknown " flag) {})))))

(defn- terminal [args]
  (let [{:keys [model resume id]} (parse-args args)
        dir (conversation-dir resume id)
        s (session/new-session {:dir dir
                                :init (or (System/getenv "BBH_INIT") (str (fs/expand-home "~/.config/bbh/init.clj")))
                                :model (or model (models/default-model))
                                :make-engine models/engine
                                :sandbox? (= "router" (System/getenv "BBH_ROLE"))
                                :emit print-event
                                :notes (when resume ["This conversation resumed in a new REPL, so earlier live definitions are gone."])})
        _ (some-> (session/start-repl! s) println)
        reader (-> (LineReaderBuilder/builder)
                   (.terminal (-> (TerminalBuilder/builder) (.system true) (.build)))
                   (.variable LineReader/HISTORY_FILE (fs/path (super/data-dir) "history"))
                   (.build))
        turn (Object.)
        prompt! (fn [text & opts]
                  (locking turn
                    (try (apply session/prompt! s text opts)
                         (catch Exception e (println (str "\n" (ex-message e)))))
                    (println)))
        ;; The mailbox is how the website and bbh super reach this session. A
        ;; message waits for the turn in progress, then runs as a prompt of its own.
        poller (promise)
        _ (deliver poller (inbox/start! {:root (inbox/root-dir dir) :project (str (fs/cwd))
                                         :deliver (session/mailbox-handler
                                                   s {:prompt! prompt!
                                                      :show! #(.printAbove reader (str (dim "[mailbox] ") %))
                                                      :ack! #(inbox/ack! @poller %)})}))]
    (sun.misc.Signal/handle (sun.misc.Signal. "INT")
                            (reify sun.misc.SignalHandler (handle [_ _] (session/interrupt! s))))
    (println (dim (str "bbh " (fs/file-name dir) " " (:model @s) " nREPL " (:port (:repl @s)) "  /help lists commands")))
    (try
      (loop []
        (let [line (try (str/trim (.readLine reader "> "))
                        (catch UserInterruptException _ "")
                        (catch EndOfFileException _ nil))]
          (cond (or (nil? line) (= "/quit" line)) nil
                (str/blank? line) (recur)
                :else (do (prompt! line) (recur)))))
      (finally
        (inbox/stop! @poller)
        (session/close! s)
        (shutdown-agents)))))

(defn -main [& args]
  (case (first args)
    "acp" (let [{:keys [resume id]} (parse-args (rest args))]
            (acp/-main (when (or resume id) (conversation-dir resume id))))
    "inbox-poll" (let [[_ id project] args]
                   (inbox/-poll (str (fs/path (super/data-dir) "conversations" id)) project))
    "web" (web/-main (super/data-dir))
    "super" (apply super/-main (rest args))
    "say" (apply voice/-main (rest args))
    "inbox" (apply pane/-main (rest args))
    (terminal args)))
