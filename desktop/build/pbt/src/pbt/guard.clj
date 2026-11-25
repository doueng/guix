(ns pbt.guard
  "What make lint checks of the property suites: generators and properties
  live apart, and the ledger shows no agent round that changed a property."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edamame.core :as e]
            [pbt.harness :as h]))

(defn- requires
  "The namespaces FILE's ns form requires."
  [file]
  (let [[_ _ & clauses] (first (e/parse-string-all (slurp file) {:all true :auto-resolve name :readers (fn [_] identity)}))]
    (set (for [c clauses :when (and (seq? c) (= :require (first c)))
               spec (rest c)]
           (if (sequential? spec) (first spec) spec)))))

(def ^:private test-nss '#{clojure.test clojure.test.check clojure.test.check.properties})

(defn separation-problems
  "A generator file that requires a test or property namespace or defines a
  test, and a property file that requires test.check's generators: an agent
  that may edit the first must not be able to change what the second
  asserts."
  [suites]
  (concat
   (for [[id s] suites f (:generators s)
         :let [path (str (fs/path h/repo f))]
         problem (if-not (fs/exists? path)
                   [(str "missing generator file " f)]
                   (concat (for [n (requires path) :when (test-nss n)] (str f " requires " n))
                           (when (re-find #"\((deftest|defspec|prop/for-all|is) " (slurp path))
                             [(str f " asserts something; properties live in " (str/join ", " (:properties s)))])))]
     (str (name id) ": " problem))
   (for [[id s] suites f (:properties s)
         :when (str/ends-with? f ".clj")
         :let [path (str (fs/path h/repo f))]
         problem (if-not (fs/exists? path)
                   [(str "missing property file " f)]
                   (when ((requires path) 'clojure.test.check.generators)
                     [(str f " requires clojure.test.check.generators; generators live in " (str/join ", " (:generators s)))]))]
     (str (name id) ": " problem))))

(defn ledger-problems
  "Rounds an agent recorded with properties other than the accepted
  round's before it, and accepted generators whose source was not kept."
  [suites]
  (for [[id s] suites
        :let [rounds (h/ledger (assoc s :id id))
              kept? (set (filter #(#{:accepted :baseline} (:verdict %)) rounds))
              before (reductions (fn [prev r] (if (kept? r) r prev)) nil rounds)]
        [prev r] (map vector before rounds)
        problem (concat
                 (when (and prev (= :agent (:by r)) (kept? r) (not= (:property-sha prev) (:property-sha r)))
                   ["an agent's accepted round changed the properties"])
                 (when (and (kept? r) (not (fs/exists? (str h/root "/ledger/" (name id) "/" (:generator-sha r) ".clj"))))
                   [(str "accepted generator " (:generator-sha r) " was not kept")]))]
    (str (name id) " ledger round " (:round r) ": " problem)))

(defn -main [& _]
  (let [suites (h/suites)
        problems (concat (separation-problems suites) (ledger-problems suites))]
    (doseq [p problems] (println p))
    (System/exit (if (seq problems) 1 0))))
