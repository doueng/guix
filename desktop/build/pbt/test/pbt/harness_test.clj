(ns pbt.harness-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [pbt.cover :as cover]
            [pbt.harness :as h]
            [pbt.mbt :as mbt]
            [pbt.fixture :as fx]
            [rexg.model]))

(deftest instrumented-code-computes-the-same-and-counts-its-branches
  (let [cases [[fx/classify [-3 0 1 2 5 12 13]] [fx/sum-to [0 10 100]] [fx/flags [{} {:a 1 :b 2} {:d 1 :e 3} {:e -1}]]
               [fx/shape [[1 2] :x]] [(fx/twice inc) [1]]]
        plain (mapv (fn [[f xs]] (mapv f xs)) cases)
        sites (cover/instrument! 'pbt.fixture)]
    (try
      (cover/reset-counts!)
      (is (= plain (mapv (fn [[f xs]] (mapv f xs)) [[fx/classify [-3 0 1 2 5 12 13]] [fx/sum-to [0 10 100]]
                                                     [fx/flags [{} {:a 1 :b 2} {:d 1 :e 3} {:e -1}]] [fx/shape [[1 2] :x]]
                                                     [(fx/twice inc) [1]]])))
      (let [all (cover/snapshot {'pbt.fixture :all})
            some (cover/snapshot {'pbt.fixture #{'classify}})]
        (is (= sites (:total all)))
        (is (= (:total all) (:hit all)) "every arm ran")
        (is (< (:total some) (:total all)))
        (cover/reset-counts!)
        (fx/classify 0)
        (is (< (:hit (cover/snapshot {'pbt.fixture #{'classify}})) (:total some)) "one input reaches fewer arms"))
      (finally (cover/restore! ['pbt.fixture])))))

(def ^:private seven-suite
  "A suite whose property fails on any vector holding 7."
  {:id :seven})

(defn seven-check [xs] (if (some #{7} xs) [[:has-seven]] []))
(defn seven-observe [xs] {:steps (map (fn [x] {:state (mod x 3) :action x}) xs) :labels (when (some #{7} xs) #{:seven})})
(def seven-gen (gen/vector (gen/choose 0 9) 0 12))

(deftest a-failure-is-shrunk-keeping-its-signature
  (let [m (h/measure (assoc seven-suite :gen `seven-gen :check `seven-check :observe `seven-observe :cover {}
                            :budget {:seeds [1] :trials 100 :max-size 50}))
        [f] (:failures m)]
    (is (= [:has-seven] (:signature f)))
    (is (= [7] (:input f)) "shrunk to the smallest vector that fails the same way")
    (is (pos? (get-in m [:behaviour :transitions])))))

(deftest the-verdict-wants-more-without-less-or-slower
  (let [m (fn [cov tr ms & [fails]] {:coverage {:hit cov} :behaviour {:transitions tr} :ms ms :failures (mapv #(hash-map :signature %) fails)})]
    (is (= :accepted (:verdict (h/judge (m 10 10 100) (m 11 10 100)))))
    (is (= :accepted (:verdict (h/judge (m 10 10 100) (m 10 12 120)))))
    (is (= :rejected (:verdict (h/judge (m 10 10 100) (m 10 10 100)))) "nothing rose")
    (is (= :rejected (:verdict (h/judge (m 10 10 100) (m 12 9 100)))) "transitions fell")
    (is (= :rejected (:verdict (h/judge (m 10 10 100) (m 12 12 400)))) "too slow")
    (is (= :accepted (:verdict (h/judge (m 10 10 100) (m 10 10 100 [[:boom]])))) "a new failure")))

(deftest the-guard-refuses-files-outside-the-generators
  (let [s (h/suite :rexg-connection)]
    (is (empty? (h/guard-problems s ["desktop/build/ghostty-rex/test/rexg/gens/connection.clj"])))
    (is (= [[:outside-allowlist "desktop/build/ghostty-rex/test/rexg/connection_test.clj"]]
           (vec (filter #(= :outside-allowlist (first %))
                        (h/guard-problems s ["desktop/build/ghostty-rex/test/rexg/gens/connection.clj"
                                             "desktop/build/ghostty-rex/test/rexg/connection_test.clj"])))))))

(deftest quint-traces-replay-on-the-real-code
  (let [spec (fn [id] (update (:mbt (h/suite id)) :file #(str h/repo "/" %)))]
    (testing "the connect module agrees with rexg.model"
      (is (empty? (:divergences (mbt/replay (spec :rexg-connect-mbt) 8 :seed 2)))))
    (testing "a connect-failed that does nothing diverges"
      (with-redefs [rexg.model/connect-failed (fn [m _ _ _] [m []])]
        (is (seq (:divergences (mbt/replay (spec :rexg-connect-mbt) 8 :seed 2))))))
    (testing "the workers module agrees with bbh.super.model but for the known window"
      (let [{:keys [known]} (spec :bbh-workers-mbt)]
        (is (every? known (keys (:signatures (mbt/replay (spec :bbh-workers-mbt) 8 :seed 2)))))))))
