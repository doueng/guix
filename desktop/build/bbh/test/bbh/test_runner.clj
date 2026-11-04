(ns bbh.test-runner
  "Run the bbh tests in parallel worker processes. Most tests wait on child REPLs, jobs and sockets rather than the
  CPU, so the suite takes about as long as its slowest tests. Each worker runs one test var at a time, as the
  serial suite did, and takes the next from this process over stdin. The slowest tests of the last run go first."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

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
        run-worker (fn []
                     (let [proc (p/process {:in :pipe :out :pipe :err :inherit
                                            ;; No test reaches the Emacs daemon of the person running the suite.
                                            :extra-env {marker-var marker "BBH_EMACS_SERVER" (str "bbh-test-" marker)}}
                                           "bb" "-e" "(require 'bbh.test-runner) (bbh.test-runner/worker)")
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
        rs (cond-> @results (seq leaked) (conj {:var 'leaked-controllers :ms 0 :counters {:test 1 :error 1}}))
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
