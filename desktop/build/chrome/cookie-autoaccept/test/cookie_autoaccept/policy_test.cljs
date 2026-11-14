(ns cookie-autoaccept.policy-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string :as str]
            ["fs" :as fs]
            [cookie-autoaccept.policy :as p]))

(deftest default-is-accept-enabled-everywhere
  (let [s (p/normalize-settings nil)]
    (is (= "accept" (:mode s)))
    (is (true? (p/enabled-for? s "example.com")))
    (is (= "optIn" (:autoAction (p/autoconsent-config s true))))))

(deftest garbage-settings-are-repaired-rather-than-trusted
  (is (= {:mode "accept" :disabledSites ["a.com"] :blockSites []}
         (p/normalize-settings {:mode "eat" :disabledSites ["a.com" "a.com" 3 ""] :blockSites "x"}))))

(deftest reject-mode-opts-out-and-lets-the-heuristic-click-reject-buttons
  (let [c (p/autoconsent-config {:mode "reject"} true)]
    (is (= "optOut" (:autoAction c)))
    (is (not= "off" (:heuristicMode c)))))

(deftest accept-mode-keeps-the-heuristic-off-since-upstream-heuristic-has-no-opt-in
  (is (= "off" (:heuristicMode (p/autoconsent-config {:mode "accept"} true)))))

(deftest the-early-script-carries-the-mode-and-stays-off-disabled-sites-and-their-subdomains
  (let [s (p/set-site-enabled {:mode "reject"} "news.com" false)
        e (p/early-script (p/set-site-enabled s "127.0.0.1" false))]
    (is (= "js/early-reject.js" (first (:js e))))
    (is (= "js/early.js" (last (:js e))))
    (is (= ["*://news.com/*" "*://*.news.com/*" "*://127.0.0.1/*"] (:excludeMatches e)))
    (is (= "document_start" (:runAt e)))
    (is (true? (:allFrames e)))
    (is (= "js/early-accept.js" (first (:js (p/early-script nil)))))
    (is (= ["*://localhost/*" "*://*.localhost/*"] (p/site-patterns "localhost")))))

(deftest blocking-is-off-until-a-site-turns-it-on-and-then-only-for-that-site
  (let [hosts ["cookielaw.org" "cookiebot.com"]
        on (p/set-site-blocking {} "shop.com" true)
        [rule] (p/block-rules on hosts)]
    (is (= [] (p/block-rules nil hosts)))
    (is (= "block" (get-in rule [:action :type])))
    (is (= ["shop.com"] (get-in rule [:condition :initiatorDomains])))
    (is (= hosts (get-in rule [:condition :requestDomains])))
    (is (= [] (p/block-rules (p/set-site-blocking on "shop.com" false) hosts)))))

(deftest site-key-strips-www-and-ignores-non-web-pages
  (is (= "bbc.co.uk" (p/site-key "https://www.bbc.co.uk/news")))
  (is (= "shop.example.com" (p/site-key "http://shop.example.com:8080/x")))
  (is (nil? (p/site-key "chrome://extensions")))
  (is (nil? (p/site-key "not a url"))))

(deftest per-site-toggle-turns-one-site-off-and-back-on
  (let [s (p/set-site-enabled nil "news.com" false)]
    (is (false? (p/enabled-for? s "news.com")))
    (is (true? (p/enabled-for? s "other.com")))
    (is (false? (:enabled (p/autoconsent-config s (p/enabled-for? s "news.com")))))
    (let [s (p/set-site-enabled s "news.com" true)]
      (is (true? (p/enabled-for? s "news.com")))
      (is (= 1 (count (:disabledSites (p/set-site-enabled (p/set-site-enabled s "x.com" false) "x.com" false))))))))

(def rules
  (clj->js [{:name "generic"}
            {:name "frame-only" :runContext {:main false :frame true}}
            {:name "both" :runContext {:main true :frame true}}
            {:name "site" :runContext {:urlPattern "^https://(www\\.)?shop\\.com/"}}
            {:name "broken-pattern" :runContext {:urlPattern "("}}]))

(defn- names [rs] (mapv #(.-name ^js %) rs))

(deftest main-frame-gets-generic-and-matching-site-rules-not-frame-only-ones
  (is (= ["generic" "both" "site"] (names (p/filter-rules rules {:url "https://shop.com/a" :mainFrame true}))))
  (is (= ["generic" "both"] (names (p/filter-rules rules {:url "https://elsewhere.org/" :mainFrame true})))))

(deftest subframes-get-only-rules-that-ask-for-frames
  (is (= ["frame-only" "both"] (names (p/filter-rules rules {:url "https://cmp.example/" :mainFrame false})))))

(deftest vendored-rules-cover-the-major-cmps-and-filtering-keeps-the-payload-small
  (let [all (.-autoconsent (js/JSON.parse (fs/readFileSync "vendor/autoconsent/rules.json" "utf8")))
        main (p/filter-rules all {:url "https://example.org/" :mainFrame true})
        frame (p/filter-rules all {:url "https://example.org/" :mainFrame false})
        all-names (str/join " " (map #(str/lower-case (.-name ^js %)) all))]
    (is (and (< (.-length main) (.-length all)) (< (.-length frame) (.-length main))))
    (is (< (.-length (js/JSON.stringify frame)) 200000))
    (doseq [cmp ["didomi" "quantcast" "trustarc" "usercentrics" "sourcepoint"]]
      (is (str/includes? all-names cmp) cmp))
    (is (every? #(.-optIn ^js %) all) "full rules carry optIn steps")))

(defn- fold [r & msgs] (reduce p/reduce-report r msgs))

(deftest a-handled-popup-produces-an-accepted-report-and-a-badge
  (let [r (p/empty-report "news.com" {})
        _ (is (true? (p/should-run-fallback? r)))
        r (fold r {:type "cmpDetected" :cmp "onetrust"} {:type "popupFound" :cmp "onetrust"})
        _ (is (false? (p/should-run-fallback? r)))
        r (fold r {:type "optInResult" :cmp "onetrust" :result true} {:type "autoconsentDone" :cmp "onetrust"})
        r (fold r {:type "fallbackScanned" :afterResult (:afterResult (p/fallback-answer r))})]
    (is (= "Accepted cookies (onetrust)." (p/describe-report r)))
    (is (= "\u2713" (p/badge-for r)))))

(deftest a-failed-click-is-reported-as-such-with-no-badge
  (let [r (fold (p/empty-report "a.com" {:mode "reject"})
                {:type "popupFound" :cmp "Didomi"} {:type "optOutResult" :cmp "Didomi" :result false})]
    (is (= "Found Didomi but could not click it." (p/describe-report r)))
    (is (= "" (p/badge-for r)))))

(deftest the-fallback-is-reported-when-it-hid-banners-and-never-runs-on-a-disabled-site
  (let [r (fold (p/empty-report "a.com" {}) {:type "fallbackHidden" :count 2})
        off (p/empty-report "a.com" {:disabledSites ["a.com"]})]
    (is (= "Hid 2 cookie banners (generic)." (p/describe-report r)))
    (is (= "\u2713" (p/badge-for r)))
    (is (false? (p/should-run-fallback? off)))
    (is (= "Off for this site." (p/describe-report off)))
    (is (= "" (p/badge-for (fold off {:type "fallbackHidden" :count 1}))))))

(deftest the-fallback-waits-while-autoconsent-works-then-may-still-act-on-a-banner-a-rule-left-up
  (let [r (fold (p/empty-report "cookiebot.com" {}) {:type "popupFound" :cmp "Cybotcookiebot"})
        _ (is (false? (p/should-run-fallback? r)))
        r (fold r {:type "optInResult" :cmp "Cybotcookiebot" :result true})
        _ (is (true? (p/should-run-fallback? r)))
        r (fold r {:type "fallbackClicked" :count 1})]
    (is (= "Accepted cookies (generic button, after Cybotcookiebot)." (p/describe-report r)))
    (is (= "\u2713" (p/badge-for r)))))

;; Found by Bombadil: the popup said 'Accepted cookies (Cybotcookiebot).' while the banner was still up,
;; because the rule reports success without clicking anything; the fallback only clicks seconds later.
(deftest a-rules-claim-is-not-reported-until-the-page-was-scanned-after-it
  (let [r (fold (p/empty-report "shop.com" {}) {:type "popupFound" :cmp "Cybotcookiebot"})
        before (p/fallback-answer r)
        r (fold r {:type "optInResult" :cmp "Cybotcookiebot" :result true})
        _ (is (= "Found Cybotcookiebot, checking..." (p/describe-report r)))
        _ (is (= "" (p/badge-for r)))
        ;; A scan that started before the result checks nothing.
        r (fold r {:type "fallbackScanned" :afterResult (:afterResult before)})
        _ (is (= "Found Cybotcookiebot, checking..." (p/describe-report r)))
        after (p/fallback-answer r)
        _ (is (true? (:run after)))
        r (fold r {:type "fallbackScanned" :afterResult (:afterResult after)})]
    (is (= "Accepted cookies (Cybotcookiebot)." (p/describe-report r)))
    (is (= "\u2713" (p/badge-for r)))))

(deftest in-reject-mode-a-banner-hidden-after-a-rules-claim-is-reported-as-hidden-not-rejected
  (let [r (fold (p/empty-report "shop.com" {:mode "reject"})
                {:type "popupFound" :cmp "Cybotcookiebot"}
                {:type "optOutResult" :cmp "Cybotcookiebot" :result true}
                {:type "fallbackHidden" :count 1}
                {:type "fallbackScanned" :afterResult true})]
    (is (= "Hid 1 cookie banner (generic, after Cybotcookiebot)." (p/describe-report r)))))

;; Found by Bombadil: the Sourcepoint frame accepted before the main frame's init, which then wiped the
;; report, so the popup said 'No cookie popup seen.'
(deftest a-subframes-result-survives-the-main-frames-later-init-a-new-page-starts-afresh
  (let [ctx {:site "news.com" :settings {}}
        r (p/start-report nil (assoc ctx :type "pageStart" :mainFrame true))
        r (p/start-report r (assoc ctx :type "init" :mainFrame false))
        r (fold r {:type "popupFound" :cmp "Sourcepoint-frame"}
                {:type "optInResult" :cmp "Sourcepoint-frame" :result true}
                {:type "fallbackScanned" :afterResult true})
        r (p/start-report r (assoc ctx :type "init" :mainFrame true))
        _ (is (= "Accepted cookies (Sourcepoint-frame)." (p/describe-report r)))
        r (p/start-report r (assoc ctx :type "pageStart" :mainFrame true))]
    (is (= "No cookie popup seen." (p/describe-report r)))))

(deftest a-cmp-answered-before-it-ran-is-reported-in-the-mode-it-was-answered-in
  (let [r (fold (p/empty-report "shop.com" {:mode "reject"}) {:type "preanswered" :cmp "onetrust"})]
    (is (= "Rejected cookies before the banner showed (onetrust)." (p/describe-report r)))
    (is (= "\u2713" (p/badge-for r)))
    (is (= "No cookie popup seen." (p/describe-report (p/empty-report "shop.com" {}))))))

(deftest unknown-messages-leave-the-report-unchanged
  (let [r (p/empty-report "a.com" {})]
    (is (identical? r (p/reduce-report r {:type "report" :state {}})))))
