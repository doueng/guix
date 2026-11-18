(ns rexmux.agents-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [rexmux.agents :as a]
            [rexmux.props :refer [trials]]))

(deftest a-real-rex-event-becomes-a-blocked-agent
  ;; Captured from rex events --all --json: a pane printed
  ;; OSC 7501 ; state=blocked:kind=question:app=pi:msg=Which file?
  (let [evs (map #(json/parse-string % true) (remove str/blank? (str/split-lines (slurp "test/fixtures/events-blocked.ndjson"))))
        s (reduce a/report a/empty-state (keep a/from-event evs))
        [row] (a/attention s)]
    (is (= ["blocked" "question" "pi" "Which file?"] ((juxt :state :kind :app :msg) row)))))

(def blocks ["b1" "b2" "b3" "b4"])

(def gen-step
  (gen/one-of
   [(gen/let [b (gen/elements blocks) st (gen/elements (conj a/states "clear" "bogus")) id (gen/elements ["" "x"])]
      [:report {:session "s" :block b :id id :record {:state st}}])
    (gen/fmap (fn [b] [:focus b]) (gen/elements blocks))
    (gen/fmap (fn [b] [:close b]) (gen/elements blocks))
    (gen/fmap (fn [b] [:next-blocked b]) (gen/elements (conj blocks nil)))]))

(defn- apply-step [{:keys [state focus]} [kind x]]
  (case kind
    :report {:state (a/report state x) :focus focus}
    :focus {:state (a/focused state x) :focus x}
    :close {:state (a/block-closed state x) :focus focus}
    :next-blocked (let [n (a/next-blocked state x)] {:state (cond-> state n (a/focused (:block n))) :focus (or (:block n) focus)})))

(defn- blocked [state] (set (keep (fn [[b e]] (when (= "blocked" (:state (a/status e))) b)) (:blocks state))))

(deftest blocked-agents-stay-in-attention-and-are-reachable
  (is (:pass? (tc/quick-check
               (trials 400)
               (prop/for-all [steps (gen/vector gen-step 0 40)]
                 (let [{:keys [state focus]} (reduce apply-step {:state a/empty-state :focus nil} steps)
                       bl (blocked state)
                       att (a/attention state)
                       ;; press next-blocked as many times as there are blocked agents
                       visited (loop [cur focus n (count bl) seen #{}]
                                 (if (zero? n) seen
                                     (let [nb (a/next-blocked state cur)] (recur (:block nb) (dec n) (conj seen (:block nb))))))]
                   (and (every? (set (map :block att)) bl)                 ; never hidden by focus
                        (= bl (disj visited nil))                          ; cycling visits each blocked one
                        (= (sort-by a/rank (map :state att)) (map :state att)) ; most urgent first
                        (every? #(not= "idle" (:state %)) att))))))))

(deftest focusing-done-marks-it-seen-but-blocked-stays
  (let [s (-> a/empty-state
              (a/report {:block "b1" :record {:state "done"}})
              (a/report {:block "b2" :record {:state "blocked"}}))]
    (is (= ["b2" "b1"] (map :block (a/attention s))))
    (is (= ["b2"] (map :block (a/attention (-> s (a/focused "b1") (a/focused "b2"))))))))

(deftest unknown-state-is-ignored-and-clear-removes
  (let [s (a/report a/empty-state {:block "b1" :record {:state "working"}})]
    (is (= s (a/report s {:block "b1" :record {:state "sleeping"}})))
    (is (= a/empty-state (a/report s {:block "b1" :record {:state "clear"}})))))
