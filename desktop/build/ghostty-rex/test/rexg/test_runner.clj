(ns rexg.test-runner
  (:require [clojure.test :as t]
            [rexg.hints-test]
            [rexg.keys-test]
            [rexg.model-test]
            [rexg.screen-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'rexg.keys-test 'rexg.model-test 'rexg.hints-test 'rexg.screen-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
