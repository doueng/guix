(ns bbh.super.process
  "Linux process identity and detached-job observation. Never signal by pid alone."
  (:require [babashka.fs :as fs] [babashka.process :as p] [bbh.conversation :as conversation] [clojure.string :as str]))

(defn identity-of [pid]
  (when (and (integer? pid) (pos? pid))
    (try
      (let [s (:out (p/shell {:out :string :err :string :continue true} "cat" (str "/proc/" pid "/stat")))
            fields (str/split (subs s (+ 2 (str/last-index-of s ")"))) #"\s+")]
        (when-not (#{"Z" "X"} (first fields))
          {:pid pid :start (nth fields 19)}))
      (catch Exception _ nil))))

(defn matches? [{:keys [pid start]}]
  (and start (= {:pid pid :start start} (identity-of pid))))

(defn read-text [path]
  (try (str/trim (slurp (str path))) (catch java.io.IOException _ nil)))

(defn boot
  "This boot of the machine: its boot id and when it booted, in epoch ms."
  []
  {:id (read-text "/proc/sys/kernel/random/boot_id")
   ;; slurp reads /proc/stat as empty, since procfs reports its size as 0.
   :at-ms (some->> (try (String. (fs/read-all-bytes "/proc/stat")) (catch java.io.IOException _ nil))
                   (re-find #"(?m)^btime (\d+)") second parse-long (* 1000))})

(defn- before-boot?
  "Whether the job at BASE started before BOOT, so no process of it can be alive:
  its .boot names another boot, or, for a job from before .boot was written, its
  .cmd, written just before it was launched, is older than the boot."
  [base {:keys [id at-ms]}]
  (if-let [b (read-text (str base ".boot"))]
    (boolean (and id (not= b id)))
    (boolean (and at-ms (< (fs/file-time->millis (fs/last-modified-time (str base ".cmd"))) at-ms)))))

(defn jobs
  "The detached jobs under ROOT with their state: :done with an exit, :running
  when its process is the one that started it, :rebooted when it started before
  the machine's last boot (BOOT, default now's), so it died with that boot,
  :unknown without a start time, else :lost."
  ([root] (jobs root (boot)))
  ([root boot]
   (mapv (fn [f]
           (let [base (str/replace (str f) #"\.cmd$" "")
                 pid (some-> (read-text (str base ".pid")) parse-long)
                 start (read-text (str base ".start"))
                 exit (some-> (read-text (str base ".exit")) parse-long)
                 identity {:pid pid :start start}]
             (merge identity {:id (str/replace (fs/file-name f) #"\.cmd$" "")
                              :base base :exit exit
                              :state (cond (some? exit) :done
                                           (before-boot? base boot) :rebooted
                                           (matches? identity) :running
                                           (nil? start) :unknown
                                           :else :lost)})))
         (fs/glob (conversation/file root :jobs) "*.cmd"))))

(defn- stat-field
  "Field N after the command of /proc/PID/stat: 0 state, 2 pgrp, 3 session."
  [pid n]
  (try (let [s (String. (fs/read-all-bytes (str "/proc/" pid "/stat")))]
         (nth (str/split (subs s (+ 2 (str/last-index-of s ")"))) #"\s+") n nil))
       (catch Exception _ nil)))

(defn session-members
  "The live pids in the session SID leads, whatever process group each is in.
  Zombies are left out: they are dead already and only wait for their parent."
  [sid]
  (vec (for [d (fs/list-dir "/proc")
             :let [pid (parse-long (str (fs/file-name d)))]
             :when (and pid (= (str sid) (stat-field pid 3))
                        (not (#{"Z" "X"} (stat-field pid 0))))]
         pid)))

(defn kill-session!
  "SIGKILL every process left in the session SID led, in passes, so a process
  forked meanwhile goes too. Linux reuses no pid that still names a session,
  so this is safe after the leader is gone. Returns whether the session is
  empty."
  [sid]
  (loop [n 5]
    (let [ms (session-members sid)]
      (cond (empty? ms) true
            (zero? n) false
            :else (do (doseq [m ms]
                        (p/shell {:out :discard :err :discard :continue true} "kill" "-KILL" "--" (str m)))
                      (Thread/sleep 50)
                      (recur (dec n)))))))

(defn stop-job!
  "Kill the verified detached job: its process group and every other process
  still in the session it leads. A job runs under setsid, so a browser or
  server it launched in a group of its own, as Chrome under a test script,
  still shares its session (T179). Return false on missing identity."
  [{:keys [pid] :as job}]
  (if (and (matches? job)
           (= (str pid) (str/trim (:out (p/shell {:out :string :err :string :continue true} "ps" "-o" "pgid=" "-p" (str pid))))))
    (let [r (p/shell {:out :string :err :string :continue true} "kill" "-KILL" "--" (str "-" pid))]
      (kill-session! pid)
      (zero? (:exit r)))
    false))
