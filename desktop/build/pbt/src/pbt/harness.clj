(ns pbt.harness
  "Runs a property suite under a fixed budget and measures what its generator
  explores. A suite (suites.edn) names a generator var, a property (CHECK:
  input -> problems, empty when it holds) and an observer (OBSERVE: input ->
  {:steps [{:state :action}] :labels #{}}); the last two are human-owned.

  Inputs are a pure function of the generator and the budget: trial i of seed
  s draws from (random/make-random (+ (* s 1000003) i)) at size (mod i
  max-size), so a baseline and a candidate generator meet the same seeds. A
  failing input is shrunk greedily through its rose tree, keeping its
  signature, and saved as a regression case that is replayed first."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            [pbt.cover :as cover]))

(def root
  "desktop/build/pbt"
  (str (fs/parent (fs/absolutize (or (System/getProperty "babashka.config") "desktop/build/pbt/bb.edn")))))

(def repo (str (fs/parent (fs/parent (fs/parent root)))))

(defn suites [] (edn/read-string (slurp (str root "/suites.edn"))))

(defn suite [id]
  (let [id (keyword (str/replace (str id) #"^:" ""))]
    (or (some-> (get (suites) id) (assoc :id id))
        (throw (ex-info (str "no suite " id "; suites: " (str/join " " (map name (keys (suites))))) {:id id})))))

(defn- var-of [sym] @(requiring-resolve sym))

;; Running

(defn signature
  "What tells one failure from another: the kinds of its problems."
  [problems]
  (vec (sort (distinct (map #(if (sequential? %) (first %) %) problems)))))

(defn- problems-of [check x]
  (try (vec (check x))
       (catch Throwable e [[:exception (.getName (class e)) (ex-message e)]])))

(defn- shrink
  "The smallest value in ROSE's tree that still fails CHECK with signature
  SIG, and the steps taken: depth first, the first failing child each time."
  [check sig rose]
  (loop [r rose steps 0]
    (if-let [c (and (< steps 2000)
                    (first (filter #(= sig (signature (problems-of check (rose/root %)))) (rose/children r))))]
      (recur c (inc steps))
      [(rose/root r) steps])))

(defn- draws
  "[seed trial rose] for every trial of BUDGET, lazily."
  [g {:keys [seeds trials max-size] :or {max-size 200}}]
  (for [s seeds i (range trials)]
    [s i (gen/call-gen g (random/make-random (+ (* s 1000003) i)) (mod i max-size))]))

(defn regressions-file [{:keys [id]}] (str root "/regressions/" (name id) ".edn"))

(defn regressions [s]
  (let [f (regressions-file s)] (if (fs/exists? f) (edn/read-string (slurp f)) [])))

(defn- explore
  "Run CHECK over every draw of G under BUDGET, collecting up to MAX-FAILURES
  distinct failures, each shrunk. Returns {:ms :trials :failures}."
  [g check budget & {:keys [max-failures] :or {max-failures 5}}]
  (let [t0 (System/nanoTime)
        {:keys [trials failures]}
        (reduce (fn [acc [s i r]]
                  (let [ps (problems-of check (rose/root r))]
                    (cond-> (update acc :trials inc)
                      (and (seq ps) (not (get-in acc [:sigs (signature ps)])))
                      (-> (assoc-in [:sigs (signature ps)] true)
                          (update :failures conj
                                  (let [sig (signature ps)
                                        [small steps] (shrink check sig r)]
                                    {:seed s :trial i :signature sig :input small :shrink-steps steps
                                     :problems (problems-of check small)})))
                      true (as-> a (if (>= (count (:failures a)) max-failures) (reduced a) a)))))
                {:trials 0 :failures [] :sigs {}}
                (draws g budget))]
    {:ms (quot (- (System/nanoTime) t0) 1000000) :trials trials :failures failures}))

(defn- behaviour
  "Distinct abstract states, actions, transitions [s a s'], action 3-grams
  and whole action histories over OBSERVATIONS, and how often each label came
  up."
  [observations]
  (let [steps (map :steps observations)
        trans (for [ss steps [x y] (partition 2 1 ss)] [(:state x) (:action y) (:state y)])
        acts (map #(map :action %) steps)]
    {:states (count (distinct (mapcat #(map :state %) steps)))
     :actions (count (distinct (mapcat identity acts)))
     :transitions (count (distinct trans))
     :ngrams (count (distinct (mapcat #(partition 3 1 %) acts)))
     :histories (count (distinct acts))
     :labels (into (sorted-map) (frequencies (mapcat :labels observations)))}))

(defn- observe-all
  "Coverage of the suite's :cover namespaces and the behaviour OBSERVE sees,
  over the same draws, with the code under test instrumented for this pass
  only."
  [{:keys [cover]} g check observe budget]
  (let [nss (keys cover)]
    (doseq [n nss] (cover/instrument! n))
    (try
      (cover/reset-counts!)
      (let [obs (doall (for [[_ _ r] (draws g budget)
                             :let [x (rose/root r)]]
                         (do (problems-of check x)
                             (try (observe x) (catch Throwable _ {:steps [] :labels [:observe-threw]})))))]
        {:coverage (dissoc (cover/snapshot cover) :counts)
         :uncovered (let [c (:counts (cover/snapshot cover))]
                      (->> @cover/sites (filter (fn [[id s]] (and (cover/selected? cover s) (not (c id)))))
                           (map (comp #(select-keys % [:ns :in :line :kind]) val))
                           (sort-by (juxt (comp str :ns) :line)) vec))
         :behaviour (behaviour obs)})
      (finally (cover/restore! nss)))))

(defn measure
  "One measurement of suite S with its generator as now loaded: regression
  replays, then the budget's draws (timed, uninstrumented), then coverage and
  behaviour over the same draws."
  [s]
  (let [{:keys [gen check observe budget]} s
        g (var-of gen) chk (var-of check) obs (var-of observe)
        replays (vec (for [r (regressions s)]
                       {:signature (:signature r) :still-fails? (boolean (seq (problems-of chk (:input r))))}))
        run (explore g chk budget)
        ;; The faster of two timings, so one slow run does not decide a verdict.
        run (update run :ms min (:ms (explore g chk budget)))
        seen (observe-all s g chk obs budget)]
    (merge {:budget budget :replays replays} run seen)))

;; Generators, properties and the ledger

(defn sha [text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (subs (format "%064x" (BigInteger. 1 (.digest md (.getBytes ^String text "UTF-8")))) 0 16)))

(defn- abs-path [f] (str (fs/path repo f)))

(defn property-sha
  "One hash over suite S's human-owned files."
  [s]
  (sha (str/join "\n" (map #(str % "\n" (slurp (abs-path %))) (sort (:properties s))))))

(defn generator-source [s] (slurp (abs-path (first (:generators s)))))

(defn ledger-file [{:keys [id]}] (str root "/ledger/" (name id) ".edn"))

(defn ledger [s] (let [f (ledger-file s)] (if (fs/exists? f) (edn/read-string (slurp f)) [])))

(defn accepted [s] (last (filter #(#{:accepted :baseline} (:verdict %)) (ledger s))))

(defn- snapshot-file [s gsha] (str root "/ledger/" (name (:id s)) "/" gsha ".clj"))

(defn- pretty [x] (with-out-str (pp/pprint x)))

(defn- record! [s round]
  (let [rounds (ledger s)
        round (assoc round :round (count rounds))]
    (fs/create-dirs (fs/parent (ledger-file s)))
    (spit (ledger-file s) (pretty (conj rounds round)))
    round))

(defn- keep-generator! [s src]
  (let [f (snapshot-file s (sha src))]
    (fs/create-dirs (fs/parent f))
    (spit f src)
    (sha src)))

(defn- save-failures!
  "Add FAILURES whose signature is not saved yet to S's regression cases."
  [s failures]
  (let [old (regressions s)
        known (set (map :signature old))
        new (vec (for [f failures :when (not (known (:signature f)))]
                   (assoc (select-keys f [:seed :trial :signature :input :problems]) :found (str (java.time.LocalDate/now)))))]
    (when (seq new)
      (fs/create-dirs (fs/parent (regressions-file s)))
      (spit (regressions-file s) (pretty (into old new))))
    new))

(defn- load-generator! [s src]
  (let [g (:gen s)]
    (load-string src)
    (when-not (resolve g) (throw (ex-info (str "generator source defines no " g) {:gen g})))))

(defn summary
  "The numbers a round is judged on."
  [m]
  {:coverage (get-in m [:coverage :hit]) :coverage-total (get-in m [:coverage :total])
   :transitions (get-in m [:behaviour :transitions]) :states (get-in m [:behaviour :states])
   :histories (get-in m [:behaviour :histories]) :ngrams (get-in m [:behaviour :ngrams])
   :labels (vec (sort (keys (get-in m [:behaviour :labels]))))
   :ms (:ms m) :trials (:trials m) :failures (mapv :signature (:failures m))})

;; The guard

(defn changed-files
  "Files the working copy changed since revision BASE, relative to the repo."
  [base]
  (let [{:keys [out exit err]} (p/sh {:dir repo} "jj" "diff" "--name-only" "--from" base "--to" "@")]
    (when-not (zero? exit) (throw (ex-info (str "jj diff failed: " err) {})))
    (vec (remove str/blank? (str/split-lines out)))))

(defn allowlist
  "What an agent may change for suite S: its generator file, its ledger and
  its regression cases."
  [s]
  (let [rel #(str (fs/relativize repo %))]
    (into (set (:generators s)) [(rel (ledger-file s)) (rel (regressions-file s))])))

(defn guard-problems
  "Why an agent's change for suite S is refused: a changed file outside the
  allowlist, or property files that differ from the last accepted round."
  [s changed]
  (let [allowed (allowlist s)
        snap (str (fs/relativize repo (str root "/ledger/" (name (:id s)))))
        base (accepted s)]
    (concat
     (for [f changed :when (not (or (allowed f) (str/starts-with? f (str snap "/"))))]
       [:outside-allowlist f])
     (when (and base (not= (:property-sha base) (property-sha s)))
       [[:properties-changed (:property-sha base) (property-sha s)]]))))

;; Acceptance

(def slack
  "How much slower a candidate may run: 25% plus 20 ms of noise."
  {:factor 1.25 :ms 20})

(defn judge
  "The verdict on candidate measurement CAND against baseline BASE: accepted
  when coverage hits or transitions rise, or a new failure signature appears;
  and none of coverage, transitions and action 3-grams falls, no label the
  baseline reached is lost, and the time stays within slack."
  [base cand]
  (let [b (summary base) c (summary cand)
        worse (filter #(< (c %) (b %)) [:coverage :transitions :ngrams])
        better (filter #(> (c %) (b %)) [:coverage :transitions])
        lost (remove (set (:labels c)) (:labels b))
        new-sigs (remove (set (:failures b)) (:failures c))
        slow? (> (:ms c) (+ (* (:factor slack) (:ms b)) (:ms slack)))]
    (cond (seq worse) {:verdict :rejected :reason (str "worse " (str/join ", " (map name worse)))}
          (seq lost) {:verdict :rejected :reason (str "lost labels " (str/join ", " (map name lost)))}
          slow? {:verdict :rejected :reason (format "slower: %d ms against %d ms" (:ms c) (:ms b))}
          (seq new-sigs) {:verdict :accepted :reason (str "new failure " (pr-str (vec new-sigs)))}
          (seq better) {:verdict :accepted :reason (str "more " (str/join ", " (map name better)))}
          :else {:verdict :rejected :reason "no primary metric rose"})))

(defn baseline!
  "Record the current generator and properties of suite S as the baseline,
  measured. A human does this, at the start and after changing properties."
  [s & {:keys [note by] :or {by :human}}]
  (let [src (generator-source s)
        _ (load-generator! s src)
        m (measure s)]
    (save-failures! s (:failures m))
    (record! s {:verdict :baseline :by by :at (str (java.time.Instant/now)) :note note
                :generator-sha (keep-generator! s src) :property-sha (property-sha s)
                :metrics (summary m)})))

(defn try!
  "Judge the working copy's generator of suite S against the last accepted
  one, on the same code, properties, seeds and trials, and record the
  verdict. BASE is the revision the agent started from."
  [s & {:keys [base note tokens minutes] :or {base "@-"}}]
  (let [prev (or (accepted s) (throw (ex-info (str "no baseline for " (name (:id s)) "; a human runs bb baseline first") {})))
        guard (vec (guard-problems s (changed-files base)))
        src (generator-source s)
        round {:by :agent :at (str (java.time.Instant/now)) :note note :property-sha (property-sha s)
               :generator-sha (sha src) :cost (cond-> {} tokens (assoc :tokens tokens) minutes (assoc :minutes minutes))}]
    (if (seq guard)
      (record! s (assoc round :verdict :rejected :reason (str "guard: " (pr-str guard))))
      (let [_ (load-generator! s (slurp (snapshot-file s (:generator-sha prev))))
            b (measure s)
            _ (load-generator! s src)
            c (measure s)
            {:keys [verdict] :as v} (judge b c)
            saved (save-failures! s (:failures c))]
        (when (= :accepted verdict) (keep-generator! s src))
        (record! s (merge round v {:metrics (summary c) :baseline-metrics (summary b)
                                   :new-regressions (mapv :signature saved)}))))))

;; Reports

(defn report
  "Text for measurement M of suite S."
  [s m]
  (let [{:keys [coverage behaviour failures replays uncovered]} m]
    (str/join
     "\n"
     (concat
      [(format "%s  %d trials in %d ms (seeds %s)" (name (:id s)) (:trials m) (:ms m) (pr-str (get-in m [:budget :seeds])))
       (format "coverage  %d of %d sites (%.1f%%)  %s" (:hit coverage) (:total coverage) (* 100 (:fraction coverage))
               (str/join "  " (for [[n {:keys [hit total]}] (:by-ns coverage)] (str n " " hit "/" total))))
       (format "behaviour states %d  actions %d  transitions %d  3-grams %d  histories %d"
               (:states behaviour) (:actions behaviour) (:transitions behaviour) (:ngrams behaviour) (:histories behaviour))
       (str "labels    " (str/join "  " (for [[k n] (:labels behaviour)] (str (name k) " " n))))]
      (for [r replays] (str "replay    " (pr-str (:signature r)) (if (:still-fails? r) " still fails" " passes now")))
      (for [f failures]
        (str "FAILURE   " (pr-str (:signature f)) " seed " (:seed f) " trial " (:trial f) ", shrunk in " (:shrink-steps f) " steps\n"
             "  input:    " (pr-str (:input f)) "\n  problems: " (subs (pr-str (:problems f)) 0 (min 400 (count (pr-str (:problems f)))))))
      [(if (> (count uncovered) 25) (str "never reached (first 25 of " (count uncovered) "):") (str "never reached (" (count uncovered) "):"))]
      (for [{:keys [ns in line kind]} (take 25 uncovered)] (format "  %s/%s line %s %s" ns in line (name kind)))))))

(defn ledger-table [s]
  (str/join "\n"
            (cons "round  by     verdict   coverage  transitions  states  histories  ms     reason"
                  (for [{:keys [round by verdict metrics reason]} (ledger s)]
                    (format "%-6d %-6s %-9s %-9s %-12s %-7s %-10s %-6s %s" round (name by) (name verdict)
                            (:coverage metrics) (:transitions metrics) (:states metrics) (:histories metrics) (:ms metrics) (or reason ""))))))

(defn- arg-map [args]
  (into {} (for [[k v] (partition 2 args)] [(keyword (str/replace k #"^--" "")) v])))

(defn -main [cmd & [id & args]]
  (let [opts (arg-map args)
        s (when id (suite id))
        num #(some-> % parse-long)]
    (case cmd
      "list" (doseq [[k v] (sort-by key (suites))] (println (format "%-18s %s" (name k) (:doc v))))
      "measure" (let [m (do (load-generator! s (generator-source s)) (measure s))]
                 (println (report s m))
                 (when (= "true" (:save opts)) (println "saved" (count (save-failures! s (:failures m))) "new regression cases"))
                 (System/exit (if (seq (:failures m)) 1 0)))
      "baseline" (println (pretty (baseline! s :note (:note opts) :by (keyword (or (:by opts) "human")))))
      "try" (let [r (try! s :base (or (:base opts) "@-") :note (:note opts)
                          :tokens (num (:tokens opts)) :minutes (num (:minutes opts)))]
              (println (pretty (dissoc r :baseline-metrics)))
              (System/exit (if (= :accepted (:verdict r)) 0 3)))
      "ledger" (println (ledger-table s))
      "mbt" (let [{:keys [known] :as spec} (:mbt s)
                  r ((requiring-resolve 'pbt.mbt/replay) (update spec :file #(str repo "/" %)) (or (num (:traces opts)) 30)
                     :seed (or (num (:seed opts)) 1) :max-steps (or (num (:steps opts)) 30))
                  new (remove (comp (set (keys known)) key) (:signatures r))]
              (println (format "%s: %d Quint traces, %d steps replayed on the real code" (name (:id s)) (:traces r) (:steps r)))
              (println "actions  " (str/join "  " (for [[a n] (:actions r)] (str a " " n))))
              (doseq [[sig n] (:signatures r)]
                (println (if (known sig) "known    " "DIVERGES ") (pr-str sig) "in" n "traces"))
              (doseq [d (take 1 (filter #(not (known ((requiring-resolve 'pbt.mbt/divergence-signature) %))) (:divergences r)))]
                (println (pretty d)))
              (System/exit (if (seq new) 1 0)))
      "replay" (let [chk (var-of (:check s))
                     rs (regressions s)]
                 (doseq [r rs] (println (pr-str (:signature r)) (if (seq (problems-of chk (:input r))) "still fails" "passes")))
                 (System/exit (if (some #(seq (problems-of chk (:input %))) rs) 1 0))))))
