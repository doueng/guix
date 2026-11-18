(ns rexmux.verify
  "make rexmux-verify: the live checks, each in its own process so one
  failure does not hide the rest."
  (:require [babashka.process :as p]))

(def checks [["sidecar on a real Rex server" ["bb" "test:live"]]])

(defn -main [& _]
  (let [results (doall (for [[what argv] checks]
                         (do (println "==" what)
                             [what (:exit @(p/process argv {:inherit true :continue true}))])))]
    (doseq [[what exit] results] (println (format "%-4s %s" (if (zero? exit) "ok" "FAIL") what)))
    (System/exit (if (every? (comp zero? second) results) 0 1))))
