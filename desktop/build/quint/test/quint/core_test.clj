(ns quint.core-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [quint.core :as quint]))

(def counter (str (fs/path (fs/parent *file*) "fixtures" "counter.qnt")))

(deftest tla-value-reads-what-tlc-prints
  (is (= {:k [:Busy 1] :xs #{1 2} :t [2 false] :l [1 2]}
         (quint/tla-value "[k |-> [Busy |-> 1], xs |-> {1, 2}, t |-> <<2, FALSE>>, l |-> <<1, 2>>]")))
  (is (= {:Idle 0 :Busy 1} (quint/tla-value "([Idle |-> [tag |-> \"UNIT\"]] :> 0 @@ [Busy |-> [tag |-> \"UNIT\"]] :> 1)")))
  (is (= #{[:Closed 0]} (quint/tla-value "{<<[Closed |-> [tag |-> \"UNIT\"]], 0>>}")))
  (is (= "a \"b\"" (quint/tla-value "\"a \\\"b\\\"\"")))
  (is (= {} (quint/tla-value "[]"))))

(deftest unparen-box-gives-tlc-its-action-form
  (is (= "p == [][(x' >= x)]_x" (quint/unparen-box "p == []([(x' >= x)]_x)")))
  (is (= "p == [][f(a) /\\ (b)]_<<x, y>> /\\ q" (quint/unparen-box "p == []([f(a) /\\ (b)]_<<x, y>>) /\\ q"))))

(deftest typecheck-reports-errors-with-places
  (let [bad (str (fs/create-temp-file {:suffix ".qnt"}))]
    (spit bad "module bad {\n  var x: int\n  action init = x' = y\n  action step = x' = x\n}\n")
    (let [{:keys [ok? errors]} (quint/typecheck bad)]
      (is (not ok?))
      (is (= 3 (:line (first errors))))
      (is (re-find #"'y' not found" (:message (first errors)))))
    (fs/delete bad))
  (is (= {:ok? true :errors []} (quint/typecheck counter))))

(deftest test-names-failures-with-their-trace
  (let [{:keys [ok? passed failed traces]} (quint/test counter {:main "counter3"})]
    (is (not ok?))
    (is (= ["incTest"] passed))
    (is (= ["wrongTest"] failed))
    (is (= {:n 1 :mode [:Busy 1] :seen #{0 1}} (last (traces "wrongTest"))))))

(deftest run-returns-the-violation-as-data
  (let [{:keys [ok? status trace]} (quint/run counter {:main "counter3" :invariant "tiny" :seed "0x1"})]
    (is (not ok?))
    (is (= :violation status))
    (is (= 2 (:n (last trace)))))
  (is (= :ok (:status (quint/run counter {:main "counter3" :invariant "small" :seed "0x1" :max-samples 50})))))

(deftest model-check-reports-each-rule
  (let [rs (into {} (map (juxt :rule identity))
                 (quint/model-check counter "counter3" {:invariants ["small" "tiny"]
                                                         :properties ["neverDown" "grows" "reachesMax" "leavesZero"]}))]
    (testing "what holds, over every state"
      (is (= #{"small" "grows" "leavesZero"} (set (keep (fn [[r v]] (when (:ok? v) r)) rs))))
      (is (= 10 (:states (rs "small")))))
    (testing "a broken invariant, with its shortest trace"
      (is (= [{:n 0 :mode :Idle :seen #{0}} {:n 1 :mode [:Busy 1] :seen #{0 1}} {:n 2 :mode [:Busy 2] :seen #{0 1 2}}]
             (:states (:trace (rs "tiny"))))))
    (testing "a broken action property"
      (is (= 0 (:n (last (:states (:trace (rs "neverDown")))))))
      (is (= :property (:kind (rs "neverDown")))))
    (testing "a broken liveness rule, found though TLC does not name it, with its lasso"
      (is (false? (:ok? (rs "reachesMax"))))
      (is (some? (:loop (:trace (rs "reachesMax"))))))))

(deftest repl-steps-through-actions
  (let [r (quint/repl counter "counter3")]
    (try
      (is (:ok? (quint/repl-eval r "init")))
      (is (re-find #"n: 0 => 1" (:out (quint/repl-eval r "inc"))))
      (is (= {:ok? true :out "1"} (quint/repl-eval r "n")))
      (is (false? (:ok? (quint/repl-eval r "n +"))))
      (is (= "2" (:out (quint/repl-eval r "n + 1"))) "a session survives an error")
      (finally (quint/repl-stop r)))))
