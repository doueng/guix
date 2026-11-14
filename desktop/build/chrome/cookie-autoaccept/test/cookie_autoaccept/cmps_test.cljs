(ns cookie-autoaccept.cmps-test
  (:require [cljs.test :refer [deftest is]]
            [cookie-autoaccept.cmps :as c]))

(def now (js/Date.UTC 2026 10 14 12 0 0))

(defn- writes
  "{\"store:name\" value} of one CMP's writes."
  [cmp mode]
  (into {} (map (fn [^js w] [(str (.-store w) ":" (.-name w)) (.-value w)])) (c/writes cmp mode now "id1")))

(defn- decode-tc
  "Field layout of the IAB TCF v2 core segment, from the spec, independent of the encoder."
  [s]
  (let [bin (js/atob (-> s (.replace (js/RegExp. "-" "g") "+") (.replace (js/RegExp. "_" "g") "/")))
        bits (apply str (map #(.padStart (.toString (.charCodeAt % 0) 2) 8 "0") bin))
        i (atom 0)
        int (fn [n] (let [v (js/parseInt (subs bits @i (+ @i n)) 2)] (swap! i + n) v))
        flags (fn [n] (let [f (mapv #(js/Number %) (subs bits @i (+ @i n)))] (swap! i + n) f))
        letters #(str (char (+ 65 (int 6))) (char (+ 65 (int 6))))
        out (array-map :version (int 6) :created (int 36) :updated (int 36) :cmpId (int 12) :cmpVersion (int 12)
                       :screen (int 6))
        out (assoc out :language (letters) :vendorList (int 12) :policy (int 6) :serviceSpecific (int 1)
                   :nonStandard (int 1))
        out (assoc out :specialFeatures (flags 12) :purposes (flags 24) :purposesLI (flags 24) :purposeOne (int 1))
        out (assoc out :country (letters) :maxVendor (int 16))
        vendors (if (= 1 (int 1))
                  (set (mapcat (fn [_] (if (= 1 (int 1))
                                         (let [a (int 16) b (int 16)] (range a (inc b)))
                                         [(int 16)]))
                               (range (int 12))))
                  (set (keep-indexed (fn [k f] (when (= 1 f) (inc k))) (flags (:maxVendor out)))))]
    (assoc out :vendors vendors)))

(deftest the-tc-string-says-yes-to-every-purpose-and-vendor-for-accept-and-no-to-all-for-reject
  (let [yes (decode-tc (c/tc-string true now 10))
        no (decode-tc (c/tc-string false now 10))]
    (is (= 2 (:version yes)))
    (is (= 10 (:cmpId yes)))
    (is (= (/ now 100) (:created yes)))
    (is (= "EN" (:language yes)))
    (is (= (repeat 11 1) (take 11 (:purposes yes))))
    (is (= [1 1] (take 2 (:specialFeatures yes))))
    (is (and (contains? (:vendors yes) 1) (contains? (:vendors yes) 755) (= (count (:vendors yes)) (:maxVendor yes))))
    (is (and (every? zero? (:purposes no)) (every? zero? (:specialFeatures no))))
    (is (= 0 (count (:vendors no))))
    (is (re-matches #"[A-Za-z0-9_-]+" (c/tc-string true now 10)))))

(deftest each-cmp-gets-its-own-consent-values-matching-the-mode
  (is (re-find #"groups=C0001%3A1%2CC0002%3A1%2CC0003%3A1%2CC0004%3A1" (get (writes "onetrust" "accept") "cookie:OptanonConsent")))
  (is (re-find #"groups=C0001%3A1%2CC0002%3A0%2CC0003%3A0%2CC0004%3A0" (get (writes "onetrust" "reject") "cookie:OptanonConsent")))
  (is (= (.toISOString (js/Date. now)) (get (writes "onetrust" "accept") "cookie:OptanonAlertBoxClosed")))
  (is (re-find #"marketing:true" (get (writes "cookiebot" "accept") "cookie:CookieConsent")))
  (is (re-find #"marketing:false.*method:%27explicit%27" (get (writes "cookiebot" "reject") "cookie:CookieConsent")))
  (let [^js didomi (js/JSON.parse (js/atob (get (writes "didomi" "reject") "local:didomi_token")))]
    (is (= 0 (.-length (.. didomi -purposes -enabled))))
    (is (.includes (.. didomi -purposes -disabled) "cookies")))
  (is (= 0 (count (:vendors (decode-tc (get (writes "quantcast" "reject") "cookie:euconsent-v2"))))))
  (is (= 1 (first (:purposes (decode-tc (get (writes "quantcast" "accept") "cookie:euconsent-v2"))))))
  (is (= "true" (get (writes "usercentrics" "accept") "local:uc_user_interaction"))))

(defn- cmp-name [host] (some-> (c/cmp-for-host host) .-name))

(deftest a-cmp-is-recognised-by-its-script-host-and-subdomains-not-by-look-alikes
  (is (= "onetrust" (cmp-name "cdn.cookielaw.org")))
  (is (= "onetrust" (cmp-name "optanon.blob.core.windows.net")))
  (is (nil? (c/cmp-for-host "other.blob.core.windows.net")))
  (is (= "cookiebot" (cmp-name "consent.cookiebot.com")))
  (is (= "didomi" (cmp-name "sdk.privacy-center.org")))
  (is (= "usercentrics" (cmp-name "web.cmp.usercentrics.eu")))
  (is (= "quantcast" (cmp-name "cmp.inmobi.com")))
  (is (nil? (c/cmp-for-host "evilcookielaw.org")))
  (is (nil? (c/cmp-for-host "cookielaw.org.example.com")))
  (is (nil? (c/cmp-for-host ""))))

(deftest consent-cookies-are-first-party-site-wide-and-kept-for-a-year
  (is (= "a=b; path=/; max-age=31536000; SameSite=Lax" (c/cookie-string #js {:name "a" :value "b"}))))
