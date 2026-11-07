(ns bbh.test-runner
  "Run the bbh tests in parallel worker processes. Most tests wait on child REPLs, jobs and sockets rather than the
  CPU, so the suite takes about as long as its slowest tests. Each worker runs one test var at a time, as the
  serial suite did, and takes the next from this process over stdin. The slowest tests of the last run go first."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [bbh.hermetic :as hermetic]))

(def ^:private result-prefix "\u0000bbh-test-result ")

(defn- test-namespaces []
  (->> (fs/glob "test/bbh" "*_test.clj")
       (map #(-> (fs/file-name %) (str/replace #"\.clj$" "") (str/replace "_" "-")))
       sort
       (mapv #(symbol (str "bbh." %)))))

(defn- run-var
  "Run test var SYM with its namespace's fixtures. Returns its counters, time and report text."
  [sym]
  (require (symbol (namespace sym)))
  (let [out (java.io.StringWriter.)
        start (System/currentTimeMillis)
        counters (binding [t/*report-counters* (ref t/*initial-report-counters*)
                           t/*test-out* out
                           *out* out]
                   (try (t/test-vars [(resolve sym)])
                        (catch Throwable e
                          (t/do-report {:type :error :message "uncaught" :expected nil :actual e})))
                   @t/*report-counters*)]
    {:var sym :ms (- (System/currentTimeMillis) start) :counters counters :out (str out)}))

(defn worker
  "Read test var names from stdin, one per line, and print one result line for each."
  []
  (doseq [line (line-seq (io/reader *in*))]
    (let [r (run-var (symbol line))]
      (locking *out* (println (str result-prefix (pr-str r))) (flush)))))

(defn- times-file [] (str (fs/path (fs/temp-dir) "bbh-test-times.edn")))

(defn- last-times []
  (try (edn/read-string (slurp (times-file))) (catch Exception _ {})))

(defn- all-vars []
  (let [nss (test-namespaces)]
    (apply require nss)
    (for [n nss, v (sort-by (comp :line meta) (vals (ns-interns n))) :when (:test (meta v))]
      (symbol v))))

(def ^:private marker-var
  "Set on the test workers and inherited by everything they start, so what
  outlives the suite can be found. Not BBH_-prefixed: hermetic drops those."
  "BB_TEST_RUN")

(defn- read-proc [pid file]
  (try (str/replace (slurp (str "/proc/" pid "/" file)) "\u0000" " ") (catch Exception _ "")))

(defn leaked-controllers
  "The controllers, `bbh super serve` or `bbh.super/start!` in a bb, still
  running from the test run MARKER, as {:pid :cmd}."
  [marker]
  (for [d (fs/list-dir "/proc")
        :let [pid (str (fs/file-name d))]
        :when (re-matches #"\d+" pid)
        :when (str/includes? (read-proc pid "environ") (str marker-var "=" marker " "))
        :let [cmd (str/trim (read-proc pid "cmdline"))]
        :when (re-find #"super serve|bbh\.super.*start!" cmd)]
    {:pid (parse-long pid) :cmd cmd}))

(defn- real-dirs
  "The person's bbh dirs, as this process, outside the hermetic env, sees them."
  []
  (let [home (System/getProperty "user.home")
        xdg (fn [k fallback] (or (System/getenv k) (str (fs/path home fallback))))]
    (mapv #(str (fs/path % "bbh"))
          [(xdg "XDG_STATE_HOME" ".local/state") (xdg "XDG_DATA_HOME" ".local/share")
           (xdg "XDG_CONFIG_HOME" ".config")])))

(defn- entries
  "Paths up to two levels under the real bbh dirs, such as workflows/<run> and conversations/<id>."
  []
  (set (for [d (real-dirs) :when (fs/exists? d), p (fs/glob d "{*,*/*}" {:hidden true})] (str p))))

(defn- mentions? [path s]
  (some (fn [f] (and (fs/regular-file? f) (< (fs/size f) 4000000)
                     (try (str/includes? (slurp (str f)) s) (catch Exception _ false))))
        (if (fs/directory? path) (fs/glob path "**" {:hidden true}) [path])))

(defn leaks
  "Entries the suite added to the person's bbh dirs. The person's own sessions
  may write there meanwhile, so an entry counts only if it names ROOT, the
  suite's temp home, which holds every test's temp dirs."
  [before root]
  (sort (filter #(mentions? % root) (remove before (entries)))))

(defn emacs-suite
  "Run test:emacs under the hermetic env, CMD with EXTRA env, and fail on writes to the real bbh dirs."
  [extra cmd]
  (let [root (hermetic/root)
        tmp (str (fs/create-dirs (fs/path root "tmp")))
        before (entries)
        {:keys [exit]} (apply p/shell {:continue true :env (merge (hermetic/env) {"TMPDIR" tmp} extra)} cmd)
        written (leaks before root)]
    (doseq [w written] (println (str "ERROR: a test wrote to the real bbh dirs: " w)))
    (when (empty? written) (fs/delete-tree root))
    (System/exit (if (and (zero? exit) (empty? written)) 0 1))))

(defn -main
  "Run every test var on BBH_TEST_JOBS workers (default the core count), print failures and the slowest tests,
  and exit nonzero on any failure or error."
  [& _]
  (let [start (System/currentTimeMillis)
        marker (str (random-uuid))
        times (last-times)
        queue (java.util.concurrent.LinkedBlockingQueue. (sort-by #(- (get times % 0)) (all-vars)))
        jobs (or (some-> (System/getenv "BBH_TEST_JOBS") parse-long)
                 (.availableProcessors (Runtime/getRuntime)))
        results (atom [])
        root (str (fs/create-temp-dir {:prefix "bbh-test-home"}))
        tmp (str (fs/create-dirs (fs/path root "tmp")))
        before (entries)
        sqlite (str (fs/path (System/getProperty "user.home") ".guix-home/profile/lib/libsqlite3.so.0"))
        worker-env (merge {marker-var marker
                           ;; No test reaches the Emacs daemon of the person running the suite.
                           "BBH_EMACS_SERVER" (str "bbh-test-" marker)
                           hermetic/root-var root
                           "TMPDIR" tmp}
                          (hermetic/dirs root)
                          ;; sqlite looks under user.home, which now names root.
                          (when (and (not (System/getenv "BBH_SQLITE_LIB")) (fs/exists? sqlite))
                            {"BBH_SQLITE_LIB" sqlite}))
        run-worker (fn []
                     (let [proc (p/process {:in :pipe :out :pipe :err :inherit :extra-env worker-env}
                                           ;; Temp dirs and fs/expand-home land under root too.
                                           "bb" (str "-Djava.io.tmpdir=" tmp) (str "-Duser.home=" root) "-e" "(require 'bbh.test-runner) (bbh.test-runner/worker)")
                           in (io/writer (:in proc))
                           send! #(if-let [v (.poll queue)]
                                    (do (.write in (str v "\n")) (.flush in) v)
                                    (do (.close in) nil))]
                       (loop [current (send!) lines (line-seq (io/reader (:out proc)))]
                         (cond
                           (nil? current) (doseq [l lines] (println l))
                           (empty? lines)
                           (let [msg (str "\nERROR: the worker died while running " current "\n")]
                             (locking results (print msg) (flush))
                             (swap! results conj {:var current :ms 0 :out msg :counters {:test 1 :error 1}}))
                           (str/starts-with? (first lines) result-prefix)
                           (let [r (edn/read-string (subs (first lines) (count result-prefix)))]
                             (when (seq (:out r)) (locking results (print (:out r)) (flush)))
                             (swap! results conj r)
                             (recur (send!) (rest lines)))
                           :else (do (println (first lines)) (recur current (rest lines)))))
                       @proc))
        _ (run! deref (doall (repeatedly jobs #(future (run-worker)))))
        leaked (doall (leaked-controllers marker))
        _ (doseq [{:keys [pid cmd]} leaked]
            (println (str "\nERROR: a test left controller " pid " running: " cmd))
            (p/shell {:continue true :err :discard} "kill" "-KILL" (str pid)))
        written (leaks before root)
        _ (doseq [w written] (println (str "\nERROR: a test wrote to the real bbh dirs: " w)))
        _ (when (empty? written) (fs/delete-tree root))
        rs (cond-> @results
             (seq leaked) (conj {:var 'leaked-controllers :ms 0 :counters {:test 1 :error 1}})
             (seq written) (conj {:var 'real-dirs-written :ms 0 :counters {:test 1 :error 1}}))
        total (apply merge-with + (map :counters rs))
        bad (+ (:fail total 0) (:error total 0))]
    (spit (times-file) (pr-str (into times (map (juxt :var :ms)) rs)))
    (println "\nSlowest tests (ms):")
    (doseq [{:keys [var ms]} (take 10 (sort-by (comp - :ms) rs))] (println (format "%7d  %s" ms var)))
    (println (format "\nRan %d tests containing %d assertions on %d workers in %.1f s."
                     (:test total 0) (+ (:pass total 0) (:fail total 0) (:error total 0)) jobs
                     (/ (- (System/currentTimeMillis) start) 1000.0)))
    (println (str (:fail total 0) " failures, " (:error total 0) " errors."))
    (System/exit (if (and (zero? bad) (= (count rs) (count (all-vars)))) 0 1))))
