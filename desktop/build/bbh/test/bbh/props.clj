(ns bbh.props
  "The trial budget and failure report every property test shares. `bb test`
  runs each property with its default trials, a few seconds in all; `bb
  test:props` sets BBH_PROPS=full and runs them PROPS_SCALE times as many
  (default 20). BBH_PROP_SEED replays one seed. A failure names the seed,
  the shrunk input and the command that replays it."
  (:require [clojure.test :refer [is]]
            [clojure.test.check :as tc]))

(defn scale
  "How many times its default trials a property runs."
  []
  (if (= "full" (System/getenv "BBH_PROPS"))
    (or (some-> (System/getenv "PROPS_SCALE") parse-long) 20)
    1))

(defn report
  "The failure message for quick-check RESULT of property NAME."
  [name {:keys [seed shrunk fail num-tests]}]
  (str name " failed with seed " seed " after " num-tests " trials.\n"
       "Smallest input: " (pr-str (:smallest shrunk fail)) "\n"
       "Replay: BBH_PROP_SEED=" seed " bb test   (or bb test:props)"))

(defn check
  "Run PROPERTY for TRIALS times (scale) trials, or FULL trials when given and
  BBH_PROPS=full, for a property too slow to scale, and assert it passed. On
  BBH_PROP_SEED it runs that seed. EXPLAIN, given the smallest failing
  arguments, says what broke. Returns the quick-check result."
  [name trials property & {:keys [explain max-size full]}]
  (let [seed (some-> (System/getenv "BBH_PROP_SEED") parse-long)
        result (apply tc/quick-check (if (and full (> (scale) 1)) full (* trials (scale))) property
                      (concat (when seed [:seed seed]) (when max-size [:max-size max-size])))]
    (is (:pass? result)
        (str (report name result)
             (when (and explain (not (:pass? result)))
               (str "\nWhy: " (pr-str (apply explain (get-in result [:shrunk :smallest])))))))
    result))
