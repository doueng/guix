(ns rexg.test-runner
  (:require [clojure.test :as t]
            [rexg.connect-mbt-test]
            [rexg.connection-test]
            [rexg.hints-test]
            [rexg.keys-test]
            [rexg.model-test]
            [rexg.names-test]
            [rexg.resize-mbt-test]
            [rexg.rex-test]
            [rexg.screen-test]
            [rexg.stream-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'rexg.keys-test 'rexg.model-test 'rexg.hints-test 'rexg.screen-test 'rexg.connection-test 'rexg.connect-mbt-test 'rexg.names-test 'rexg.rex-test 'rexg.stream-test 'rexg.resize-mbt-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
