(ns cookie-autoaccept.policy
  "Pure decision logic shared by the service worker and the popup. No chrome.* here, so the node tests can run it.
  Settings and tab reports are maps with the camelCase keys of their stored JSON."
  (:require [clojure.string :as str]))

(def modes ["accept" "reject"])
(def default-settings {:mode "accept" :disabledSites [] :blockSites []})

(defn- site-list [v]
  (if (vector? v) (vec (distinct (filter #(and (string? %) (not= % "")) v))) []))

(defn normalize-settings
  "Settings as stored, repaired: unknown mode falls back to accept, site lists deduplicated."
  [raw]
  (let [raw (when (map? raw) raw)
        mode (:mode raw)]
    {:mode (if (some #{mode} modes) mode (:mode default-settings))
     :disabledSites (site-list (:disabledSites raw))
     :blockSites (site-list (:blockSites raw))}))

(defn site-key
  "The key a per-site toggle is stored under: the http(s) hostname without a leading www., else nil."
  [url]
  (when-let [u (try (js/URL. url) (catch :default _ nil))]
    (when (#{"http:" "https:"} (.-protocol u))
      (let [host (str/replace (.-hostname u) #"^www\." "")]
        (when (not= host "") host)))))

(defn enabled-for? [settings site]
  (and (some? site) (not (some #{site} (:disabledSites (normalize-settings settings))))))

(defn set-site-enabled [settings site enabled]
  (let [s (normalize-settings settings)
        rest (filterv #(not= % site) (:disabledSites s))]
    (assoc s :disabledSites (if enabled rest (conj rest site)))))

(defn blocking-on? [settings site]
  (and (some? site) (boolean (some #{site} (:blockSites (normalize-settings settings))))))

(defn set-site-blocking [settings site on]
  (let [s (normalize-settings settings)
        rest (filterv #(not= % site) (:blockSites s))]
    (assoc s :blockSites (if on (conj rest site) rest))))

(defn site-patterns
  "Match patterns for a site key: the host and its subdomains (an IP address has none)."
  [site]
  (if (or (re-find #"^[\d.]+$" site) (str/includes? site ":"))
    [(str "*://" site "/*")]
    [(str "*://" site "/*") (str "*://*." site "/*")]))

(defn early-script
  "The early content script (js/early.js) for these settings: the mode's file first, so the mode is known at
  document_start without a storage read, and no disabled site."
  [settings]
  (let [s (normalize-settings settings)]
    {:id "early"
     :js [(str "js/early-" (:mode s) ".js") "js/early.js"]
     :matches ["<all_urls>"]
     :excludeMatches (vec (mapcat site-patterns (:disabledSites s)))
     :runAt "document_start"
     :allFrames true
     :persistAcrossSessions true}))

(defn block-rules
  "declarativeNetRequest rules that block the CMP script hosts on the sites with blocking on, and nowhere else."
  [settings cmp-hosts]
  (let [sites (:blockSites (normalize-settings settings))]
    (if (empty? sites)
      []
      [{:id 1
        :priority 1
        :action {:type "block"}
        :condition {:requestDomains (vec (distinct cmp-hosts))
                    :initiatorDomains sites
                    :resourceTypes ["script" "sub_frame"]}}])))

(defn autoconsent-config
  "The autoconsent Config for a frame. Accept uses optIn with heuristics off, because the upstream heuristic rule
  has no optIn and throws; reject uses optOut with the tier1 heuristic (reject or acknowledge-only banners)."
  [settings enabled]
  (let [reject? (= "reject" (:mode (normalize-settings settings)))]
    {:enabled enabled
     :autoAction (if reject? "optOut" "optIn")
     :disabledCmps []
     :enablePrehide true
     :enableCosmeticRules true
     :enableGeneratedRules true
     :enableHeuristicDetection reject?
     :heuristicMode (if reject? "tier1" "off")
     :enablePopupMutationObserver true
     :detectRetries 20
     :isMainWorld false
     :prehideTimeout 2000
     :visualTest false
     :performanceLoggingEnabled false
     :logs {:lifecycle false :rulesteps false :detectionsteps false :evals false :errors false :messages false
            :waits false}}))

(defn filter-rules
  "Rules that can apply in this frame: the content script would skip the rest, so do not send them. rules is the
  vendored JS array, filtered as is, since converting 1600 rules per frame would cost more than the filter."
  [^js rules {:keys [url mainFrame]}]
  (.filter rules
           (fn [^js rule]
             (let [^js ctx (or (.-runContext rule) #js {})
                   pattern (.-urlPattern ctx)]
               (cond
                 (if mainFrame (false? (.-main ctx)) (not (true? (.-frame ctx)))) false
                 (not (js/Boolean pattern)) true
                 :else (try (.test (js/RegExp. pattern) url) (catch :default _ false)))))))

(defn empty-report [site settings]
  {:site site
   :mode (:mode (normalize-settings settings))
   :disabled (not (enabled-for? settings site))
   :cmp nil
   :popup false
   :result nil
   :done false
   ;; Whether the main frame's fallback scanned the page after the rule reported its result.
   :checked false
   ;; The CMP the early script answered before its script ran, if any.
   :preanswered nil
   :hidden 0
   :clicked 0})

(defn start-report
  "The tab's report when a frame starts: a new main-frame document starts a fresh one, and otherwise (a frame's
  init) it keeps what other frames reported, since a CMP in a subframe can finish before the main frame's init."
  [report {:keys [type mainFrame site settings]}]
  (if (and (= type "pageStart") mainFrame)
    (empty-report site settings)
    (or report (empty-report site settings))))

(defn- count-of
  "A message's count as JavaScript's Number(x) || 0 reads it."
  [x]
  (let [n (js/Number x)] (if (js/Boolean n) n 0)))

(defn reduce-report
  "Fold one content-script message into the tab's report. Unknown messages return the report itself."
  [report {:keys [type cmp] :as msg}]
  (case type
    "cmpDetected" (if (:cmp report) report (assoc report :cmp cmp))
    "popupFound" (assoc report :cmp cmp :popup true)
    ("optInResult" "optOutResult") (assoc report :cmp cmp :result (js/Boolean (:result msg)) :checked false)
    "autoconsentDone" (assoc report :cmp cmp :done true :result (if (nil? (:result report)) true (:result report)))
    "fallbackHidden" (update report :hidden + (count-of (:count msg)))
    "fallbackClicked" (assoc report :clicked (+ (or (:clicked report) 0) (count-of (:count msg))))
    "preanswered" (assoc report :preanswered cmp)
    "fallbackScanned" (if (and (js/Boolean (:afterResult msg)) (some? (:result report)) (not (:checked report)))
                        (assoc report :checked true)
                        report)
    report))

(defn should-run-fallback?
  "The generic fallback runs where the extension is on, except while autoconsent is working on a popup it found.
  After autoconsent finishes it may still run: some upstream rules report success yet leave the banner up."
  [report]
  (boolean (and report
                (not (:disabled report))
                (not (and (:popup report) (nil? (:result report)) (not (:done report)))))))

(defn fallback-answer
  "The service worker's answer to the fallback's query. afterResult says the rule's result was in when the scan
  started, so that scan checks the rule's claim: some rules report success and leave the banner up."
  [report]
  {:run (should-run-fallback? report)
   :mode (:mode report)
   :disabled (:disabled report)
   :afterResult (some? (:result report))})

(defn- banners [n] (str n " cookie banner" (when (> n 1) "s")))
(defn- positive? [n] (and (number? n) (pos? n)))

(defn describe-report
  "One line for the popup."
  [{:keys [site disabled mode clicked cmp hidden result popup checked preanswered done] :as report}]
  (let [verb (if (= mode "accept") "Accepted" "Rejected")]
    (cond
      (or (nil? report) (nil? site)) "Not a web page."
      disabled "Off for this site."
      (positive? clicked) (str "Accepted cookies (generic button" (when cmp (str ", after " cmp)) ").")
      ;; A banner the fallback still had to hide after a rule's result means the rule's claim did not hold.
      (and (positive? hidden) (some? result)) (str "Hid " (banners hidden) " (generic, after " cmp ").")
      (and popup (true? result) (not checked)) (str "Found " cmp ", checking...")
      (and popup (true? result)) (str verb " cookies (" cmp ").")
      (and popup (false? result)) (str "Found " cmp " but could not click it.")
      popup (str "Found " cmp ", working...")
      (positive? hidden) (str "Hid " (banners hidden) " (generic).")
      preanswered (str verb " cookies before the banner showed (" preanswered ").")
      done (str "Handled " cmp " (hidden, no popup shown).")
      :else "No cookie popup seen.")))

(defn badge-for
  "Badge text for the toolbar icon."
  [{:keys [disabled popup result checked preanswered hidden clicked] :as report}]
  (if (or (nil? report) disabled)
    ""
    (if (or (and popup result checked) preanswered (positive? hidden) (positive? clicked)) "✓" "")))
