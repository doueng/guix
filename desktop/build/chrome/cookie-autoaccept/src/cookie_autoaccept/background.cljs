(ns cookie-autoaccept.background
  "Service worker: answers the autoconsent content script, runs vendored eval snippets in the page, keeps a per-tab
  report for the popup, and keeps the early content script and the blocking rules in step with the settings.
  Nothing leaves the browser."
  (:require ["../vendor/autoconsent/autoconsent.esm.js" :refer [evalSnippets]]
            [clojure.string :as str]
            [cookie-autoaccept.cmps :as cmps]
            [cookie-autoaccept.policy :as p]))

(defn- ->clj [x] (js->clj x :keywordize-keys true))

(def ^:private rules-promise (atom nil))
(defn- load-rules []
  (or @rules-promise
      (reset! rules-promise
              (-> (js/fetch (js/chrome.runtime.getURL "vendor/autoconsent/rules.json"))
                  (.then #(.json ^js %))
                  (.then #(.-autoconsent ^js %))))))

(defn- get-settings []
  (-> (js/chrome.storage.local.get "settings")
      (.then #(p/normalize-settings (->clj (.-settings ^js %))))))

(defn- cmp-hosts [] (mapcat #(array-seq (.-hosts ^js %)) cmps/cmps))

(def ^:private applying (atom (js/Promise.resolve)))
(defn- apply-settings []
  (swap! applying
         (fn [prev]
           (-> prev
               (.then get-settings)
               (.then (fn [settings]
                        (let [script (p/early-script settings)]
                          (-> (js/chrome.scripting.getRegisteredContentScripts #js {:ids #js [(:id script)]})
                              (.then (fn [^js have]
                                       (if (pos? (.-length have))
                                         (js/chrome.scripting.updateContentScripts (clj->js [script]))
                                         (js/chrome.scripting.registerContentScripts (clj->js [script])))))
                              (.then #(js/chrome.declarativeNetRequest.getDynamicRules))
                              (.then (fn [^js old]
                                       (js/chrome.declarativeNetRequest.updateDynamicRules
                                        #js {:removeRuleIds (.map old #(.-id ^js %))
                                             :addRules (clj->js (p/block-rules settings (cmp-hosts)))})))))))
               (.catch #(js/console.error "cookie-autoaccept: applying settings failed" %))))))

(defn- release [tab-id]
  (-> (js/chrome.tabs.sendMessage tab-id #js {:type "prehideRelease"}) (.catch (fn [_]))))

(defn- report-key [tab-id] (str "tab:" tab-id))

;; Reports change from many frames at once; chain the updates per tab so none is lost.
(def ^:private pending (atom {}))
(defn- update-report
  "Applies f (report or nil -> report, or a promise of one) to the tab's stored report. Writes and sets the badge
  only when f returned something other than its argument."
  [tab-id f]
  (let [next (-> (get @pending tab-id (js/Promise.resolve))
                 (.then (fn []
                          (let [key (report-key tab-id)]
                            (-> (js/chrome.storage.session.get key)
                                (.then (fn [res]
                                         (let [before (->clj (unchecked-get res key))]
                                           (-> (js/Promise.resolve (f before))
                                               (.then (fn [after]
                                                        (if (identical? after before)
                                                          after
                                                          (-> (js/chrome.storage.session.set (js-obj key (clj->js after)))
                                                              (.then (fn []
                                                                       (-> (js/chrome.action.setBadgeText
                                                                            #js {:tabId tab-id :text (p/badge-for after)})
                                                                           (.catch (fn [_])))
                                                                       after)))))))))))))))]
    (swap! pending assoc tab-id (.catch next (fn [_])))
    next))

(defn- with-settings
  "f of the stored settings, as a promise."
  [f]
  (.then (get-settings) f))

(def ^:private report-messages
  #{"cmpDetected" "popupFound" "optInResult" "optOutResult" "autoconsentDone" "fallbackHidden"
    "fallbackClicked" "fallbackScanned" "preanswered"})

(defn- handle
  "One message; a promise of the answer (nil for none)."
  [{:keys [type] :as msg} ^js sender]
  (if (= type "applySettings")
    ;; From the popup: the tab it reloads next must already get the new early script.
    (-> (js/chrome.storage.local.set #js {:settings (clj->js (p/normalize-settings (:settings msg)))})
        (.then apply-settings)
        (.then (constantly true)))
    (let [^js tab (.-tab sender)
          tab-id (some-> tab .-id)
          frame-id (or (.-frameId sender) 0)
          main? (zero? frame-id)]
      (if (nil? tab-id)
        (js/Promise.resolve nil)
        (let [site (p/site-key (.-url tab))]
          (cond
            (= type "pageStart")
            (if main?
              (-> (update-report tab-id (fn [_] (with-settings #(p/start-report nil {:type "pageStart" :mainFrame true
                                                                                     :site site :settings %}))))
                  (.then (constantly nil)))
              (js/Promise.resolve nil))

            (= type "init")
            (with-settings
              (fn [settings]
                (let [enabled (p/enabled-for? settings site)]
                  (-> (update-report tab-id #(p/start-report % {:type "init" :mainFrame main? :site site
                                                                :settings settings}))
                      (.then #(if enabled
                                (.then (load-rules) (fn [rules] (p/filter-rules rules {:url (if (js/Boolean (:url msg)) (:url msg) (.-url sender))
                                                                                       :mainFrame main?})))
                                #js []))
                      (.then (fn [rules]
                               (js/chrome.tabs.sendMessage
                                tab-id
                                #js {:type "initResp"
                                     :rules #js {:autoconsent rules}
                                     :config (clj->js (p/autoconsent-config settings enabled))}
                                #js {:frameId frame-id})))
                      (.then (constantly nil))))))

            (= type "eval")
            ;; Only vendored snippet functions run in the page; a bare code string is refused.
            (let [f (when-let [id (:snippetId msg)] (unchecked-get evalSnippets id))]
              (-> (if f
                    (-> (js/chrome.scripting.executeScript #js {:target #js {:tabId tab-id :frameIds #js [frame-id]}
                                                                :world "MAIN"
                                                                :func f})
                        (.then (fn [^js res] (some-> (aget res 0) .-result)))
                        (.catch (constantly false)))
                    (js/Promise.resolve false))
                  (.then (fn [result]
                           (js/chrome.tabs.sendMessage tab-id #js {:type "evalResp" :id (:id msg) :result result}
                                                       #js {:frameId frame-id})))
                  (.then (constantly nil))))

            (= type "earlyQuery")
            (with-settings (fn [settings] {:enabled (p/enabled-for? settings site)}))

            (= type "fallbackQuery")
            (-> (update-report tab-id #(or % (with-settings (fn [s] (p/empty-report site s)))))
                (.then p/fallback-answer))

            (and (report-messages type)
                 (not (and (#{"preanswered" "fallbackScanned"} type) (not main?))))
            (-> (update-report tab-id (fn [r] (if r
                                                (p/reduce-report r msg)
                                                (with-settings #(p/reduce-report (p/empty-report site %) msg)))))
                (.then (fn [_]
                         (when (or (= type "fallbackScanned") (and (str/ends-with? type "Result") (not (js/Boolean (:result msg)))))
                           (release tab-id))
                         nil)))

            :else (js/Promise.resolve nil)))))))

(def ^:private answered #{"fallbackQuery" "earlyQuery" "applySettings"})

(defn init []
  (js/chrome.runtime.onInstalled.addListener (fn [_] (apply-settings)))
  (js/chrome.runtime.onStartup.addListener (fn [] (apply-settings)))
  (js/chrome.storage.onChanged.addListener
   (fn [^js changes area]
     (when (and (= area "local") (.-settings changes)) (apply-settings))))
  (js/chrome.runtime.onMessage.addListener
   (fn [raw sender send-response]
     (let [msg (->clj raw)
           msg (if (map? msg) msg {})]
       ;; Through a promise, so that a throw anywhere in handle is a rejection, as in an async function.
       (let [answer (.then (js/Promise.resolve) #(handle msg sender))]
         (if (answered (:type msg))
           (do (.then answer #(send-response (clj->js %)) #(send-response nil))
               true)
           (do (.catch answer (fn [_]))
               false))))))
  ;; Close autoconsent's unused port at once: Chrome closes it on bfcache entry and logs an unchecked runtime.lastError.
  (js/chrome.runtime.onConnect.addListener (fn [^js port] (.disconnect port)))
  (js/chrome.tabs.onRemoved.addListener (fn [tab-id] (js/chrome.storage.session.remove (report-key tab-id)))))
