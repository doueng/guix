(ns bbh.limits-property-test
  "bbh.limits/classify over generated failure texts. A usage-limit message
  is a limit whose window and reset come back as written: the same wall
  clock in the zone it names, else the session's, at its next occurrence;
  an epoch or a relative wait exactly. A transient failure is never a limit,
  and no text throws or yields a reset outside the window bbh believes."
  (:require [bbh.gens.limits :as gens]
            [bbh.limits :as limits]
            [bbh.props :as props]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import (java.time Instant ZoneId ZonedDateTime)))

(defn- wall-clock [ms zone]
  (str (.toLocalDateTime (ZonedDateTime/ofInstant (Instant/ofEpochMilli ms) (ZoneId/of zone)))))

(defn- limit-problem
  "What classify gets wrong about generated limit message C, or nil."
  [{:keys [text now zone real-zone window want want-ms]}]
  (let [{:keys [kind until] w :window} (limits/classify text :now now :zone (ZoneId/of zone))]
    (cond (not= :limit kind) [:not-a-limit kind]
          (not= window w) [:window w]
          (nil? until) [:no-reset]
          want-ms (when (not= want-ms until) [:reset-ms until])
          (not= want (wall-clock until (or real-zone zone))) [:reset (wall-clock until (or real-zone zone))]
          (<= until now) [:reset-not-ahead until])))

(deftest property-a-limit-message-names-its-window-and-reset
  (props/check "limit messages" 300 (prop/for-all [c gens/gen-limit] (nil? (limit-problem c)))
               :explain limit-problem))

(deftest property-a-transient-failure-is-retried-not-paused
  (props/check "transient failures" 50
               (prop/for-all [t gens/gen-transient] (= :transient (:kind (limits/classify t))))))

(deftest property-other-failures-are-no-limit-and-nothing-throws
  (props/check "other failures" 200
               (prop/for-all [t (gen/one-of [gens/gen-other gen/string])
                              now (gen/large-integer* {:min 1767225600000 :max 1893456000000})]
                 (let [{:keys [kind until]} (limits/classify t :now now)]
                   (and (not= :limit kind)
                        (or (nil? until) (< (- now 300000) until (+ now limits/max-pause-ms))))))))

(deftest the-messages-the-person-sees
  ;; 12:00 UTC on Wednesday 25 November 2026.
  (let [now (.toEpochMilli (Instant/parse "2026-11-25T12:00:00Z"))
        at (fn [text] (some-> (:until (limits/classify text :now now :zone (ZoneId/of "UTC"))) Instant/ofEpochMilli str))]
    (is (= "2026-11-25T15:20:00Z" (at "Claude Code: You've hit your 5-hour limit · resets 3:20pm (Europe/London)")))
    (is (= "2026-11-28T14:00:00Z" (at "You've hit your weekly limit · resets Nov 28, 9am (America/New_York)")))
    (is (= "2026-11-30T09:00:00Z" (at "You've hit your Opus limit · resets Monday 9am (Europe/London)")))
    (is (= "2026-11-26T11:00:00Z" (at "You've hit your limit · resets 11am (Europe/London)")) "a past time today is tomorrow's")
    (is (nil? (at "You've hit your limit")) "no reset: the controller probes")
    (is (= "5-hour" (:window (limits/classify "You've hit your 5-hour limit · resets 3:20pm (Europe/London)"))))
    (is (= (+ now 3600000)
           (:until (limits/classify "You've hit your limit" :now now
                                    :windows {:five_hour {:utilization 1.0 :resetsAt (quot (+ now 3600000) 1000)}
                                              :seven_day {:utilization 0.4 :resetsAt (quot (+ now 86400000) 1000)}})))
        "without a reset in the text, the used-up rate-limit window's")))
