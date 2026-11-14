(ns cookie-autoaccept.popup
  (:require [cookie-autoaccept.policy :as p]))

(defn- by-id [id] (js/document.getElementById id))
(defn- ->clj [x] (js->clj x :keywordize-keys true))

(defn- show [^js tab settings report]
  (let [site (p/site-key (or (some-> tab .-url) ""))
        settings (atom settings)
        save (fn [next]
               (reset! settings next)
               ;; The worker stores the settings and re-registers the early script before the reload, so the page
               ;; gets them.
               (-> (js/chrome.runtime.sendMessage #js {:type "applySettings" :settings (clj->js next)})
                   (.then (fn []
                            (when (and (some? (some-> tab .-id)) (some? site)) (js/chrome.tabs.reload (.-id tab)))
                            (js/window.close)))))
        modes (js/document.querySelectorAll "input[name=mode]")]
    (set! (.-textContent (by-id "site")) (or site ""))
    (set! (.-textContent (by-id "status")) (p/describe-report (if (nil? site) {:site nil} report)))
    (set! (.-checked (by-id "enabled")) (p/enabled-for? @settings site))
    (set! (.-disabled (by-id "enabled")) (nil? site))
    (set! (.-checked (by-id "blocking")) (p/blocking-on? @settings site))
    (set! (.-disabled (by-id "blocking")) (nil? site))
    (doseq [^js r (array-seq modes)] (set! (.-checked r) (= (.-value r) (:mode @settings))))
    (.addEventListener (by-id "enabled") "change"
                       #(save (p/set-site-enabled @settings site (.. ^js % -target -checked))))
    (.addEventListener (by-id "blocking") "change"
                       #(save (p/set-site-blocking @settings site (.. ^js % -target -checked))))
    (doseq [^js r (array-seq modes)]
      (.addEventListener r "change" #(save (assoc @settings :mode (.-value r)))))))

(defn init []
  (-> (js/chrome.tabs.query #js {:active true :currentWindow true})
      (.then (fn [^js tabs]
               (let [^js tab (aget tabs 0)
                     site (p/site-key (or (some-> tab .-url) ""))
                     key (str "tab:" (some-> tab .-id))]
                 (-> (js/Promise.all #js [(js/chrome.storage.local.get "settings") (js/chrome.storage.session.get key)])
                     (.then (fn [^js [local session]]
                              (let [settings (p/normalize-settings (->clj (.-settings ^js local)))
                                    report (or (->clj (unchecked-get session key)) (p/empty-report site settings))]
                                (show tab settings report))))))))))
