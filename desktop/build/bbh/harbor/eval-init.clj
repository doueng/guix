
;; Benchmark overlay, appended to the production init by bbh_agent.py.
;; Skills and poteto-mode stay off as in eval/bbh/compare.clj, and BBH_EFFORT
;; sets the reasoning effort so a run can match a leaderboard row.
(ns bbh.env)
(defn skills [] [])
(reset! poteto? false)
(def ^:private production-manifest manifest)
(defn manifest []
  (cond-> (production-manifest)
    (System/getenv "BBH_EFFORT") (assoc :effort (System/getenv "BBH_EFFORT"))))
(ns user)
