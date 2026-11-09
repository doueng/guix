(ns bbh.super.sessions
  "The bbh sessions Workers run in, and reaping them when their Worker ends.
  A session is `bbh.main acp --id <conversation>`, which Emacs starts as its
  own process group: the ACP server, its nREPL, claude and the MCP server. A
  finished Worker's session idles at about 300 MB until killed, and about 22 of
  them held 7.6 GB when the laptop froze (T108). Killing the group ends all of
  it; the transcript stays on disk and in its Emacs buffer."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.super.model :as model]
            [bbh.super.process :as process]
            [clojure.string :as str]))

;; ---- reading the machine ---------------------------------------------------

(defn- proc-bytes [path]
  ;; slurp reads procfs files as empty, since they report size 0.
  (try (String. (fs/read-all-bytes path)) (catch Exception _ nil)))

(defn conversation-of
  "The conversation an argv runs as a bbh ACP session (`... bbh.main acp --id
  ID`), or nil when it is no session."
  [argv]
  (let [v (vec argv)
        main (.indexOf v "bbh.main")]
    (when (and (>= main 0) (= "acp" (get v (inc main))))
      (second (drop-while #(not= "--id" %) (subvec v main))))))

(defn- pgid [pid]
  (when-let [s (proc-bytes (str "/proc/" pid "/stat"))]
    (nth (str/split (subs s (+ 2 (str/last-index-of s ")"))) #"\s+") 2 nil)))

(defn- group
  "The pids now in the process group LEADER led."
  [leader]
  (for [d (fs/list-dir "/proc")
        :let [pid (parse-long (str (fs/file-name d)))]
        :when (and pid (= (str leader) (pgid pid)))]
    pid))

(defn live
  "The bbh ACP sessions running on this machine as [{:pid :start :conversation}]."
  []
  (vec (for [d (fs/list-dir "/proc")
             :let [pid (parse-long (str (fs/file-name d)))]
             :when pid
             :let [c (some-> (proc-bytes (str d "/cmdline")) (str/split #"\u0000") conversation-of)]
             :when c
             :let [id (process/identity-of pid)]
             :when id]
         (assoc id :conversation c))))

(defn machine
  "MemAvailable, MemTotal and swap used in MB, from /proc/meminfo."
  []
  (let [kb (into {} (for [[_ k v] (re-seq #"(?m)^(\w+):\s+(\d+) kB" (or (proc-bytes "/proc/meminfo") ""))]
                      [k (parse-long v)]))
        mb #(some-> (get kb %) (quot 1024))]
    {:mem-available-mb (mb "MemAvailable") :mem-total-mb (mb "MemTotal")
     :swap-used-mb (when (and (kb "SwapTotal") (kb "SwapFree")) (quot (- (kb "SwapTotal") (kb "SwapFree")) 1024))}))

;; ---- killing -----------------------------------------------------------------

(defn- signal! [sig target]
  (zero? (:exit (p/shell {:continue true :out :discard :err :discard} "kill" (str "-" sig) "--" (str target)))))

(defn kill!
  "End session S, {:pid :start}, with its whole process group: TERM, then KILL
  after GRACE-MS. Only when its pid is still the process that started at
  :start and leads its own group, so a reused pid is never signalled. Returns
  true when the session is gone."
  [{:keys [pid] :as s} & {:keys [grace-ms] :or {grace-ms 3000}}]
  (if-not (and (process/matches? s) (= (str pid) (pgid pid)))
    ;; Its leader is gone, as when Emacs shut the client down: kill whatever
    ;; still runs in the group it led.
    (if (process/matches? s)
      false
      (do (doseq [m (group pid)] (signal! "KILL" m)) true))
    (do (signal! "TERM" (str "-" pid))
        (let [end (+ (System/currentTimeMillis) grace-ms)]
          (while (and (process/matches? s) (< (System/currentTimeMillis) end)) (Thread/sleep 100)))
        ;; What is left of the group, the leader or children that ignored TERM.
        (signal! "KILL" (str "-" pid))
        (Thread/sleep 100)
        (not (process/matches? s)))))

;; ---- deciding ----------------------------------------------------------------

(defn owners
  "Each conversation a Worker of STATE ran in, to that Worker."
  [state]
  (into {} (keep #(when (:conversation %) [(:conversation %) %])) (vals (:workers state))))

(defn holds-session?
  "Whether Worker W may have a live session: it is active. A done, failed,
  cancelled or queued Worker has none."
  [w]
  (boolean (model/active-states (:state w))))

(defn stale
  "The SESSIONS whose conversation is a Worker's of STATE that may no longer
  have one. A session no Worker owns, such as the Router or one you opened
  yourself, is left alone."
  [state sessions]
  (let [owner (owners state)]
    (filterv #(some-> (owner (:conversation %)) holds-session? not) sessions)))

(def default-grace-ms
  "How long a stale session that is in a turn may finish it, as a Worker that
  calls super-done! and then writes its report, before it is killed anyway."
  600000)

(defn plan
  "Which of SESSIONS to kill now. A session is reaped when it is stale for
  STATE and is its conversation's announced session in AGENTS (conversation to
  agent.json's {:pid :busy}), so a controller only ever kills sessions of its
  own conversations directory. One in a turn waits until GRACE-MS after it was
  first seen stale. SINCE maps conversation to when it was first seen stale.
  Returns {:kill [session] :stale [session] :since since'}."
  [state sessions agents since now grace-ms]
  (let [mine (filterv #(= (:pid %) (:pid (agents (:conversation %)))) (stale state sessions))
        since (into {} (map (fn [{c :conversation}] [c (get since c now)])) mine)]
    {:stale mine
     :since since
     :kill (filterv (fn [{c :conversation}]
                      (or (not (:busy (agents c))) (>= (- now (since c)) grace-ms)))
                    mine)}))

(defn report
  "Live sessions against active Workers, for the health log and bbh super list."
  [state sessions agents stale reaped]
  {:sessions (count sessions)
   :worker-sessions (count (filter #(and ((owners state) (:conversation %)) (= (:pid %) (:pid (agents (:conversation %)))))
                                   sessions))
   :active-workers (count (filter holds-session? (vals (:workers state))))
   :stale (vec (sort (map :conversation stale)))
   :reaped (vec (sort reaped))})
