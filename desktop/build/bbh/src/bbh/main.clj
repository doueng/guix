(ns bbh.main
  "Command line entry: the terminal front end, or bbh acp for agent-shell."
  (:require [babashka.fs :as fs]
            [bbh.acp :as acp]
            [bbh.models :as models]
            [bbh.session :as session]
            [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [org.jline.reader EndOfFileException LineReader LineReaderBuilder UserInterruptException]
           [org.jline.terminal TerminalBuilder]))

(defn- data-dir []
  (fs/path (or (System/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh"))

(defn- conversation-dir [resume]
  (let [root (fs/path (data-dir) "conversations")
        saved (when (fs/exists? root)
                (->> (fs/list-dir root) (filter #(fs/exists? (fs/path % "messages.jsonl"))) (map str) sort))]
    (str (cond (nil? resume) (fs/path root (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS")))
               (= "last" resume) (or (last saved) (throw (ex-info "No conversation to resume" {})))
               (some #{(str (fs/path root resume))} saved) (fs/path root resume)
               :else (throw (ex-info (str "No conversation " resume " under " root) {}))))))

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
                   (.build))]
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
                :else (do (try (session/prompt! s line)
                               (catch Exception e (println (str "\n" (ex-message e)))))
                          (println)
                          (recur)))))
      (finally
        (session/close! s)
        (shutdown-agents)))))

(defn -main [& args]
  (if (= "acp" (first args))
    (acp/-main (some-> (:resume (parse-args (rest args))) conversation-dir))
    (terminal args)))
