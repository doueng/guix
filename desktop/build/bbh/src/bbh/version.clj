(ns bbh.version
  "Which bbh code a long-lived process runs, and following the checkout: the
  controller and the website record the source version they booted with and
  exit once it changed while they are idle, so Shepherd's respawn starts them
  on the new code. `bbh super list` and the Inbox header show a process that
  still runs older code."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def root
  "The bbh directory this code was loaded from: the one holding bb.edn and src."
  (str (-> *file* fs/absolutize fs/parent fs/parent fs/parent)))

(defn fingerprint
  "The version of the bbh source under ROOT: sha256 over each file's path and
  bytes, for bb.edn and everything under src, in path order. Its first 12 hex
  digits."
  ([] (fingerprint root))
  ([root]
   (let [md (MessageDigest/getInstance "SHA-256")
         files (sort-by str (cons (fs/path root "bb.edn")
                                  (filter fs/regular-file? (fs/glob (fs/path root "src") "**"))))]
     (doseq [f files :when (fs/exists? f)]
       (.update md (.getBytes (str (fs/relativize root f) "\n") "UTF-8"))
       (.update md (fs/read-all-bytes f)))
     (subs (apply str (map #(format "%02x" %) (.digest md))) 0 12))))

(defn- versions-dir [data-dir] (fs/path data-dir "versions"))

(defn record!
  "Write that process NAME, this one, runs VERSION, to <data-dir>/versions/<name>.edn."
  [data-dir name version]
  (fs/create-dirs (versions-dir data-dir))
  (spit (str (fs/path (versions-dir data-dir) (str name ".edn")))
        (pr-str {:name name :version version :pid (.pid (java.lang.ProcessHandle/current)) :started-at (System/currentTimeMillis)})))

(defn running
  "The recorded versions of the processes still alive, by name."
  [data-dir]
  (into (sorted-map)
        (for [f (when (fs/directory? (versions-dir data-dir)) (fs/glob (versions-dir data-dir) "*.edn"))
              :let [r (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
              :when (and r (some-> (java.lang.ProcessHandle/of (long (:pid r))) (.orElse nil) .isAlive))]
          [(:name r) r])))

(defn stale
  "The names of running processes whose version is not SOURCE's, the current one."
  ([data-dir] (stale data-dir (fingerprint)))
  ([data-dir source]
   (vec (for [[n r] (running data-dir) :when (not= source (:version r))] n))))

(defn status-line
  "One line for `bbh super list`: the source version and each process's, stale ones marked."
  ([data-dir] (status-line data-dir (fingerprint)))
  ([data-dir source]
   (str "code " source
        (apply str (for [[n r] (running data-dir)]
                     (str ", " n " " (:version r) (when (not= source (:version r)) " (stale: restarts when idle)")))))))

(defn loads?
  "Whether the bbh code under ROOT loads in a fresh bb, so a restart onto it
  would come up."
  [root]
  (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                    "bb" "--config" (str (fs/path root "bb.edn")) "-e" "(require 'bbh.main)")]
    (or (zero? exit)
        (do (binding [*out* *err*]
              (println "bbh: the new code does not load, so this process keeps running:"
                       (or (not-empty (str/join " " (filter #(re-find #"^(Message|Location):" %) (str/split-lines err))))
                           (str/trim err))))
            false))))

(defn follow!
  "Start a daemon thread that calls EXIT! with a reason once this process
  should end: when GONE? says its state went away, or when the source under
  ROOT has a version other than BOOT that held for two looks INTERVAL-MS
  apart, the process is IDLE?, and the new code LOADS?. A version that does
  not load is tried once and then waited out. Returns the thread."
  [{:keys [root boot interval-ms idle? exit! loads? gone?]
    :or {root root interval-ms 10000 loads? loads? gone? (constantly false)}}]
  (let [boot (or boot (fingerprint root))]
    (doto (Thread.
           (fn []
             (loop [seen nil tried {}]
               (Thread/sleep (long interval-ms))
               (let [v (try (fingerprint root) (catch Exception _ boot))]
                 (cond (gone?) (exit! "its state directory is gone")
                       (= v boot) (recur nil tried)
                       (not= v seen) (recur v tried)
                       (not (idle?)) (recur v tried)
                       :else (let [ok (if (contains? tried v) (tried v) (loads? root))]
                               (if ok
                                 (exit! (str "the source changed from " boot " to " v))
                                 (recur v (assoc tried v ok)))))))))
      (.setDaemon true)
      (.start))))
