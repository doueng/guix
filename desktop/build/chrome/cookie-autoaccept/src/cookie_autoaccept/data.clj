(ns cookie-autoaccept.data
  "Compile-time data, emitted as JavaScript array and object literals so the content scripts that use it need no
  cljs.core data structures. Namespaces that call these macros are marked ^:dev/always, since a change to the
  files read here does not invalidate the build cache by itself."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- cmps [] (edn/read-string (slurp (io/resource "cookie_autoaccept/cmps.edn"))))

(defn- js-array [xs] `(cljs.core/array ~@xs))

(defmacro cmp-table
  "The CMP table from cmps.edn: an array of {name, hosts, prehide} objects."
  []
  (js-array (for [{:keys [name hosts prehide]} (cmps)]
              `(cljs.core/js-obj "name" ~name "hosts" ~(js-array hosts) "prehide" ~(js-array prehide)))))

;; content.bundle.js 18.5.0: prehideSelectors of its class-based CMPs and its global didomi list. They are code
;; there, not data, so they are copied here.
(def ^:private built-in
  [".trustarc-banner-container" ".truste_popframe" ".truste_overlay" ".truste_box_overlay" "#truste-consent-track"
   "#CybotCookiebotDialog" "#CybotCookiebotDialogBodyUnderlay" "#dtcookie-container" "#cookiebanner" "#cb-cookieoverlay"
   ".modal--cookie-banner" "#cookiebanner_outer" "#CookieBanner"
   "div[id^='sp_message_container_']" ".message-overlay" "#sp_privacy_manager_container"
   "#cmpbox" "#cmpbox2"
   "#onetrust-banner-sdk" "#onetrust-consent-sdk" ".onetrust-pc-dark-filter" ".js-consent-banner"
   ".klaro" ".cmp-root"
   "#didomi-popup" ".didomi-popup-container" ".didomi-popup-notice" ".didomi-consent-popup-preferences" "#didomi-notice"
   ".didomi-popup-backdrop" ".didomi-screen-medium"])

;; Ids and classes that name a cookie banner. They can match other things; the early script releases them in seconds.
(def ^:private generic
  ["[id*=\"cookie-banner\" i]" "[class*=\"cookie-banner\" i]" "[id*=\"cookie-consent\" i]" "[class*=\"cookie-consent\" i]"
   "[id*=\"cookie-notice\" i]" "[class*=\"cookie-notice\" i]" "[id*=\"cookiebanner\" i]" "[class*=\"cookiebanner\" i]"])

(defn prehide-list
  "The banner selectors the early script hides before paint, deduplicated, in a stable order: the CMP table, the
  built-in CMPs, the vendored autoconsent rules that apply on any site, and the generic patterns."
  []
  (let [rules (get (json/read-str (slurp "vendor/autoconsent/rules.json")) "autoconsent")
        from-rules (->> rules
                        (filter #(and (seq (get % "prehideSelectors")) (contains? #{nil "" false} (get-in % ["runContext" "urlPattern"]))))
                        (mapcat #(mapcat (fn [s] (str/split s #",(?![^(]*\))")) (get % "prehideSelectors")))
                        (map str/trim)
                        (remove str/blank?))]
    (vec (distinct (concat (mapcat :prehide (cmps)) built-in from-rules generic)))))

(defmacro prehide-selectors
  "prehide-list as a JavaScript array of strings."
  []
  (js-array (prehide-list)))
