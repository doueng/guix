(ns ^:dev/always cookie-autoaccept.prehide-test
  (:require [cljs.test :refer [deftest is]])
  (:require-macros [cookie-autoaccept.data :as data]))

(def selectors (vec (data/prehide-selectors)))

(deftest the-prehide-list-covers-the-major-cmp-roots-and-no-page-wide-element
  (doseq [s ["#onetrust-banner-sdk" "#CybotCookiebotDialog" "#didomi-host" "#qc-cmp2-container" "#usercentrics-root"
             "div[id^='sp_message_container_']" "#truste-consent-track"]]
    (is (some #{s} selectors) s))
  (doseq [s ["html" "body" "main" "*" "div" "#app" "#root"]]
    (is (not (some #{s} selectors)) s)))

(deftest the-prehide-list-is-built-from-the-vendored-rules-without-duplicates
  (is (> (count selectors) 600))
  (is (= (count selectors) (count (set selectors))))
  (is (every? #(and (string? %) (= % (.trim %)) (not= % "")) selectors)))
