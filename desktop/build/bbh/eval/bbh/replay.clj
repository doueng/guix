(ns bbh.replay
  "Full replay batches: each recorded Worker conversation runs again, contained, under an arm's init. An item's
  state is the fold of its entries in OUT/journal.edn, so an interrupted batch resumes. See eval/README.md."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.grade :as grade]
            [bbh.sandbox :as sandbox]
            [bbh.transcript :as transcript]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def transitions
  "Each state and the states it may move to. :failed and :timed-out go back to :prepared only on a retry."
  {nil #{:selected}
   :selected #{:prepared}
   :prepared #{:running}
   :running #{:finished :failed :timed-out}
   :finished #{:graded}
   :failed #{:prepared}
   :timed-out #{:prepared}
   :graded #{}})

(defn- journal-file [out] (str (fs/path out "journal.edn")))

(defn entries [out]
  (let [f (journal-file out)]
    (if (fs/exists? f) (mapv edn/read-string (remove str/blank? (str/split-lines (slurp f)))) [])))

(defn states
  "Each item's current {:state :data}, where :data merges every entry's data in order."
  [out]
  (reduce (fn [m {:keys [item to data]}] (-> m (assoc-in [item :state] to) (update-in [item :data] merge data)))
          {} (entries out)))

(defn transition!
  "Move ITEM to state TO with DATA, appending to the journal. Throws on a move the table does not allow."
  [out item to data]
  (let [from (get-in (states out) [item :state])]
    (when-not (contains? (transitions from) to)
      (throw (ex-info (str "replay: " item " cannot go from " (pr-str from) " to " (pr-str to)) {:item item :from from :to to})))
    (fs/create-dirs out)
    (spit (journal-file out) (str (pr-str {:at (str (java.time.Instant/now)) :item item :from from :to to :data data}) "\n") :append true)
    to))

(defn base-revision
  "The commit a super workspace started from: the parent of its first working-copy commit, at the jj operation
  that created it."
  [repo workspace]
  (let [ops (:out (p/shell {:dir repo :out :string :err :string} "jj" "op" "log" "--ignore-working-copy" "--no-graph"
                           "-T" "id.short() ++ \" \" ++ description ++ \"\\n\""))
        op (some #(when (str/ends-with? % (str "create initial working-copy commit in workspace " workspace)) (first (str/split % #" ")))
                 (str/split-lines ops))]
    (when op
      (str/trim (:out (p/shell {:dir repo :out :string :err :string} "jj" "--ignore-working-copy" "--at-op" op "log" "--no-graph"
                               "-r" (str workspace "@-") "-T" "commit_id"))))))

(defn recorded-run
  "The wf run id the brief names, and that run's first journal form: {:run :wf {:workflow :task :check :repro :bench}}."
  [prompt state-dir]
  (when-let [run (second (re-find #"wf run is (\S+?) \(" prompt))]
    (let [f (fs/file state-dir "bbh" "workflows" run "journal.edn")]
      {:run run
       :wf (when (fs/exists? f)
             (let [j (edn/read-string (first (str/split-lines (slurp f))))]
               (-> (select-keys j [:task :check :repro :bench]) (assoc :workflow (some-> (:workflow j) name)))))})))

(defn- state-dir [] (or (System/getenv "XDG_STATE_HOME") (str (System/getProperty "user.home") "/.local/state")))

(defn prepare
  "An item's data from recorded conversation DIR under ARM: the brief, its cwd, workspace, base revision and wf run."
  [dir arm init repo]
  (let [prompt (:content (first (filter #(= "user" (:role %)) (transcript/load-messages dir))))
        cwd (some-> (fs/file dir "agent.json") str (#(when (fs/exists? %) (second (re-find #"\"project\":\"([^\"]+)\"" (slurp %))))))
        workspace (some-> cwd fs/file-name str)
        {:keys [run wf]} (recorded-run (str prompt) (state-dir))]
    {:trace (str dir) :arm (name arm) :init init :cwd cwd :repo repo :workspace workspace
     :base (when (and cwd (not (fs/exists? cwd))) (base-revision repo workspace))
     :prompt prompt :old-run run :wf wf}))

(defn- run-item
  "Run one prepared item in a fresh sandbox under OUT/scratch. Returns [state data]."
  [out item {:keys [init cwd prompt repo workspace base wf old-run]} {:keys [model timeout-ms]}]
  (let [dir (fs/path out "scratch" (str/replace item "/" "-"))
        _ (when (fs/exists? dir) (p/shell "chmod" "-R" "u+rwx" (str dir)) (fs/delete-tree dir))
        scratch (str (fs/create-dirs dir))
        r (sandbox/run! scratch {:init init :cwd cwd :model model :prompt prompt :old-run old-run
                                 :prepare {:repo repo :workspace workspace :base base :wf wf}}
                        :timeout-ms timeout-ms)]
    [(cond (:timed-out r) :timed-out
           (= :end-turn (get-in r [:result :end])) :finished
           :else :failed)
     (merge (select-keys r [:out :blocked :super :changed :exit]) {:result (:result r) :scratch scratch}
            (when-not (:result r) {:err (subs (str (:err r)) 0 (min 2000 (count (str (:err r)))))}))]))

(defn- run-locked! [out dirs arms retry repo model run-item grade opts]
  (doseq [[item {:keys [state]}] (states out) :when (= :running state)]
    (transition! out item :failed {:reason :lost}))
  (doseq [dir dirs [arm init] arms
          :let [item (str (fs/file-name dir) "/" (name arm))]]
    (loop []
      (let [{:keys [state data]} (get (states out) item)]
        (case state
          nil (do (transition! out item :selected {}) (recur))
          :selected (do (transition! out item :prepared (prepare dir arm init repo)) (recur))
          (:failed :timed-out) (when retry (transition! out item :prepared {:reason nil}) (recur))
          :prepared (do (transition! out item :running {:started (str (java.time.Instant/now))})
                        (let [[to d] (run-item out item data (assoc opts :model model))]
                          (transition! out item to d)
                          (recur)))
          :finished (transition! out item :graded (grade data opts))
          nil))))
  (states out))

(defn run-batch!
  "Replay each conversation in DIRS under each arm in ARMS ({name init}), one item at a time, resuming OUT's journal.
  RUN-ITEM is injectable for tests. Returns the final states."
  [out dirs arms {:keys [retry repo model run-item grade] :or {repo "/home/engstrand/guix" model "claude/claude-opus-5-5"
                                                                    run-item run-item grade grade/grade}
                  :as opts}]
  (fs/create-dirs out)
  (with-open [ch (java.nio.channels.FileChannel/open (fs/path out "lock")
                   (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE java.nio.file.StandardOpenOption/WRITE]))]
    (when-not (try (.tryLock ch) (catch IllegalStateException _ nil))
      (throw (ex-info (str "replay: another batch holds " out) {:out out})))
    (run-locked! out dirs arms retry repo model run-item grade opts)))

(defn -main [out & args]
  (let [{:keys [arms dirs retry]}
        (loop [[a v & more :as as] args o {:arms {} :dirs []}]
          (cond (empty? as) o
                (= "--arm" a) (let [[k init] (str/split v #"=" 2)] (recur more (assoc-in o [:arms k] (str (fs/absolutize init)))))
                (= "--retry" a) (recur (rest as) (assoc o :retry true))
                :else (recur (rest as) (update o :dirs conj a))))]
    (let [md (grade/report (run-batch! out dirs arms {:retry retry}))]
      (spit (str (fs/path out "report.md")) md)
      (println md))))
