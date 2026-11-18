(ns rexmux.test-runner
  "Runs every rexmux.*-test namespace except the live one. PROPS_SCALE
  multiplies property trials (default 1)."
  (:require [clojure.test :as t]))

(def namespaces '[rexmux.proto-test rexmux.schema-test rexmux.registry-test rexmux.agents-test rexmux.sidecar-test])

(defn -main [& _]
  (apply require namespaces)
  (let [{:keys [fail error]} (apply t/run-tests namespaces)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
