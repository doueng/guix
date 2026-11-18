(ns rexmux.props
  "Trial counts for property tests: PROPS_SCALE times a default.")

(defn trials [n] (* n (or (some-> (System/getenv "PROPS_SCALE") parse-long) 1)))
