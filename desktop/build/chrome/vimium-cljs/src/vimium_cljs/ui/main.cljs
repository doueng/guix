(ns vimium-cljs.ui.main
  (:require [clojure.string :as str]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.keys :as keys]
            [vimium-cljs.config :as config]
            [vimium-cljs.rank :as rank]
            [vimium-cljs.vomnibar :as vomnibar]))

(defn- $ ^js [sel] (.querySelector js/document sel))

(defn- el
  ([tag cls] (el tag cls nil))
  ([tag cls text]
   (let [^js e (.createElement js/document tag)]
     (when cls (set! (.-className e) cls))
     (when text (set! (.-textContent e) text))
     e)))

(defn- params []
  (let [h (.-hash js/location)
        [kind q] (str/split (subs h (min 1 (count h))) #"\?" 2)]
    [kind (js/URLSearchParams. (or q ""))]))

(defn- popup? [] (= "1" (.get (second (params)) "popup")))
(defn- target-tab [] (some-> (.get (second (params)) "tabId") js/Number))
;; Chrome keeps a new tab page's focus in the address bar, so the new tab page reopens as ui.html?newtab in its own tab to get page focus.
(defn- new-tab-page? [] (= "?newtab" (.-search js/location)))

(declare reset-query!)

(defn- close! []
  (cond (new-tab-page?) (reset-query!)
        (popup?) (.close js/window)
        :else (.postMessage (.-parent js/window) "vimium-cljs:close" "*")))

(defonce vstate (atom (assoc vomnibar/initial :mode nil :loaded nil :frame nil)))

(def ^:private badge {"tab" "Tab" "bookmark" "Bookmark" "history" "History" "search" "Search" "url" "Go"})

(def ^:private svg-ns "http://www.w3.org/2000/svg")
(defn- glyph [d]
  (let [^js s (.createElementNS js/document svg-ns "svg")]
    (.setAttribute s "viewBox" "0 0 24 24")
    (.setAttribute s "fill" "none") (.setAttribute s "stroke" "currentColor") (.setAttribute s "stroke-width" "2")
    (.setAttribute s "stroke-linecap" "round")
    (set! (.-innerHTML s) d)
    s))

(def ^:private search-glyph "<circle cx='11' cy='11' r='7'/><path d='m20 20-3.5-3.5'/>")
(def ^:private globe-glyph "<circle cx='12' cy='12' r='9'/><path d='M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18'/>")

(defn- icon [{:keys [type url]}]
  (let [^js box (el "span" "icon")]
    (if (or (= type "search") (not (re-find #"^(https?|file|ftp):" (or url ""))))
      (.append box (glyph (if (= type "search") search-glyph globe-glyph)))
      (let [^js img (el "img" nil)]
        (set! (.-src img) (js/chrome.runtime.getURL (str "/_favicon/?size=32&pageUrl=" (js/encodeURIComponent url))))
        (set! (.-alt img) "")
        (.append box img)))
    box))

(defn- marked [cls s ranges]
  (let [^js e (el "span" cls)]
    (doseq [[text hit] (rank/highlight s ranges)]
      (.append e (if hit (el "mark" nil text) text)))
    e))

(defn- numbered? [mode] (and (= mode "tabs") (str/blank? (.-value ($ "#query")))))

(declare open! dispatch!)

(def ^:private search-host
  (-> (re-find #"^https?://([^/]+)" config/search-url) second (or "the web") (str/replace #"^www\." "")))

(defn- row [mode i {:keys [type title url engine match] :as item} selected]
  (let [^js li (el "li" (when (= i selected) "selected"))
        search? (= type "search")
        title (cond (and search? engine) (str engine ": " title)
                    (str/blank? title) (rank/display-url url)
                    :else title)
        ^js text (el "span" "text")]
    (.setAttribute li "role" "option")
    (set! (.. li -dataset -url) url)
    (.append text (if search? (el "span" "title" title) (marked "title" title (:title match)))
             (cond search? (el "span" "url" (when-not engine (str "Search " search-host)))
                   (= type "url") (el "span" "url" "")
                   :else (marked "url" (rank/display-url url) (:url match))))
    (when (numbered? mode) (.append li (el "span" "num" (when (< i 9) (str (inc i))))))
    (.append li (icon item) text (el "span" "badge" (badge type type)))
    (.addEventListener li "mousedown" (fn [^js e] (.preventDefault e)))
    (.addEventListener li "click" (fn [^js e] (dispatch! [:select i]) (open! (.-shiftKey e))))
    li))

(defn- render! []
  (let [{:keys [items selected mode]} @vstate
        ^js list ($ "#list")]
    (.replaceChildren list)
    (doseq [[i item] (map-indexed vector items)]
      (.appendChild list (row mode i item selected)))
    (some-> ^js (.querySelector list "li.selected") (.scrollIntoView #js {:block "nearest"}))))

(def ^:private day-ms (* 1000 60 60 24))

(defn- bookmark-leaves [^js nodes]
  (mapcat (fn [^js n] (if (.-url n)
                        [{:type "bookmark" :title (.-title n) :url (.-url n) :added (.-dateAdded n)}]
                        (bookmark-leaves (or (.-children n) #js []))))
          nodes))

(defn- tab-map [^js t]
  (when t {:id (.-id t) :title (.-title t) :url (.-url t) :windowId (.-windowId t) :lastAccessed (.-lastAccessed t)}))

(defn- current-tab
  "The tab the Vomnibar acts on: the one a popup names, else the tab this page is in (the
  page under the frame, or the new tab page)."
  []
  (if-let [id (target-tab)]
    (-> (js/chrome.tabs.get id) (.catch (fn [_] nil)))
    (-> (js/chrome.tabs.getCurrent)
        (.then (fn [t] (or t (-> (js/chrome.tabs.query #js {:active true :currentWindow true}) (.then first))))))))

(defn- load-candidates
  "Reads what the Vomnibar ranks, straight from Chrome (the latest HISTORY-MAX pages), and
  prepares it once."
  [mode history-max]
  (let [all? (not= mode "tabs")]
    (-> (js/Promise.all
         #js [(js/chrome.tabs.query #js {}) (current-tab)
              (js/chrome.storage.session.get "recency")
              (js/chrome.storage.local.get "vomnibarPicks")
              (if all? (js/chrome.history.search #js {:text "" :maxResults history-max :startTime (- (js/Date.now) (* 180 day-ms))}) #js [])
              (if all? (js/chrome.bookmarks.getTree) #js [])])
        (.then (fn [[tabs cur ^js rec ^js picks hs tree]]
                 (vomnibar/candidates
                  {:tabs (mapv tab-map tabs)
                   :cur (tab-map cur)
                   :recency (vec (or (some-> (.-recency rec) js->clj) []))
                   :picks (into {} (map (fn [[k v]] [k {:n (v "n") :t (v "t") :q (v "q")}]))
                                (js->clj (or (.-vomnibarPicks picks) #js {})))
                   :history (map (fn [^js h] {:type "history" :title (.-title h) :url (.-url h) :time (.-lastVisitTime h)
                                              :visits (.-visitCount h) :typed (.-typedCount h)})
                                 hs)
                   :bookmarks (bookmark-leaves tree)
                   :own (js/chrome.runtime.getURL "")}))))))

(defn- dispatch!
  "Steps the view through EVENTS (see vomnibar/step) and draws the rows if they changed."
  [& events]
  (let [old @vstate
        rows (fn [cands text] (vomnibar/rows cands (:mode old) text (js/Date.now)
                                             {:engines config/search-engines :search-url config/search-url}))
        new (swap! vstate #(reduce (fn [v e] (vomnibar/step v e rows)) % events))]
    (when (not= (mapv old [:items :selected :for]) (mapv new [:items :selected :for]))
      (render!)
      ;; Which text the rows are for, for the tests that time them.
      (set! (.. ($ "#list") -dataset -for) (:for new)))
    new))

(defn- refresh!
  "Shows the rows for what is typed at the next frame: keys typed before it cost one ranking."
  []
  (when-not (:frame @vstate)
    (swap! vstate assoc :frame
           (js/requestAnimationFrame
            (fn [_] (swap! vstate assoc :frame nil) (dispatch! [:show]))))))

(defn- reload!
  "Reads the candidates again. The latest pages come first, so rows show before the full
  history (3000 pages take Chrome ~90 ms) is in; :loaded resolves once it is."
  [mode]
  (let [gen (:gen (dispatch! [:reload]))
        got (fn [c] (dispatch! [:cands gen c]) (refresh!) c)
        ;; One after the other: Chrome answers history queries in turn, so the small one
        ;; would wait behind the big one.
        full (-> (load-candidates mode 40)
                 (.then got)
                 (.then #(load-candidates mode 3000))
                 (.then got))]
    (swap! vstate assoc :loaded full)
    full))

(defn- leave!
  "After a pick: the Vomnibar goes away, or on the new tab page stays frozen while the tab
  navigates (clearing it here drew the unfiltered rows until the page changed)."
  []
  (when-not (new-tab-page?) (close!)))

(defn- open!
  "Opens the highlighted row for what is typed (ranking it first if a frame has not shown it
  yet, so a fast Enter never opens a result for older text) and freezes the rows. AS-TYPED
  opens the typed text."
  ([new-tab] (open! new-tab false))
  ([new-tab as-typed]
   (-> (:loaded @vstate)
       (.then (fn [_]
                (when-not (:ended @vstate)
                  (let [text (.-value ($ "#query"))
                        v (dispatch! [:input text] [:pick])
                        item (when-not as-typed (vomnibar/picked v))]
                    (if (or item (not (str/blank? text)))
                      (-> (js/chrome.runtime.sendMessage
                           (clj->js {:type "vomnibar-open" :newTab (boolean new-tab) :query text
                                     :item (some-> item (select-keys [:type :url :tabId :windowId]))
                                     :text (when-not item text) :tabId (target-tab)}))
                          (.finally leave!))
                      (dispatch! [:reset])))))))))

(defn- reset-query! []
  (let [^js input ($ "#query")]
    (set! (.-value input) "")
    (dispatch! [:reset])
    (.dispatchEvent input (js/Event. "input"))))

(defn- show-chip! [mode q]
  (set! (.-textContent ($ "#chip"))
        (or (some-> (rank/engine-of q config/search-engines) second :name)
            (case mode "tabs" "Tabs" "bookmarks" "Bookmarks" nil))))

(defn- set-mode!
  "Shows the Vomnibar in MODE and reads its candidates."
  [mode]
  (let [^js input ($ "#query")]
    (swap! vstate assoc :mode mode)
    (dispatch! [:input (.-value input)])
    (reload! mode)
    (set! (.-placeholder input) (case mode "tabs" "Switch to tab" "bookmarks" "Search bookmarks" "Search or type a URL"))
    (show-chip! mode (.-value input))))

(defn vomnibar! [^js ps]
  (let [mode (or (.get ps "mode") "omni")
        new-tab (= "1" (.get ps "newTab"))
        ^js input ($ "#query")
        plain-paste (atom false)
        move (fn [d] (dispatch! [:move d]))]
    (when (= mode "edit") (set! (.-value input) (or (.get ps "url") "")))
    (set-mode! mode)
    (cond (popup?) (.add (.-classList js/document.body) "popup")
          (new-tab-page?) (do (.add (.-classList js/document.body) "newtab")
                              (set! (.-title js/document) "New Tab")))
    (set! (.-hidden ($ "#vomnibar")) false)
    (.addEventListener input "input" (fn [_] (show-chip! (:mode @vstate) (.-value input)) (dispatch! [:input (.-value input)]) (refresh!)))
    (.addEventListener input "paste"
                       (fn [^js e]
                         (let [t (str/trim (.getData (.-clipboardData e) "text"))]
                           (when (and (not @plain-paste) (str/blank? (.-value input)) (rank/url-like? t))
                             (.preventDefault e)
                             (set! (.-value input) t)
                             (dispatch! [:input t] [:pick])
                             (-> (js/chrome.runtime.sendMessage (clj->js {:type "vomnibar-open" :newTab new-tab :query t :text t :tabId (target-tab)}))
                                 (.finally leave!))))
                         (reset! plain-paste false)))
    (.addEventListener js/document "keydown"
                       (fn [^js e]
                         (let [k (.-key e) c (.-ctrlKey e) s (.-shiftKey e)]
                           (cond
                             (or (= k "Escape") (and c (= k "["))) (do (.preventDefault e) (when-not (new-tab-page?) (dispatch! [:escape])) (close!))
                             (= k "Enter") (do (.preventDefault e) (open! (or new-tab s) c))
                             (or (= k "ArrowDown") (and (= k "Tab") (not s)) (and c (#{"n" "j"} k)))
                             (do (.preventDefault e) (move 1))
                             (or (= k "ArrowUp") (and (= k "Tab") s) (and c (#{"p" "k"} k)))
                             (do (.preventDefault e) (move -1))
                             (and (re-matches #"[1-9]" k) (not c) (not (.-altKey e)) (numbered? (:mode @vstate))
                                  (< (dec (js/Number k)) (count (:items @vstate))))
                             (do (.preventDefault e) (dispatch! [:select (dec (js/Number k))]) (open! (or new-tab s)))
                             (and c s (= (str/lower-case k) "v")) (reset! plain-paste true)
                             (and (= k "Delete") s (= (:mode @vstate) "tabs"))
                             (when-let [t (vomnibar/picked @vstate)]
                               (.preventDefault e)
                               (-> (js/chrome.tabs.remove (:tabId t)) (.then #(reload! (:mode @vstate)))))))))
    (when-not (new-tab-page?)
      (.addEventListener js/window "blur" (fn [_] (js/setTimeout #(when-not (.hasFocus js/document) (close!)) 50))))
    ;; The page focuses the frame when it loads, which can drop the input's focus without
    ;; the window losing it; take focus back, and type a key that landed outside the input.
    (.addEventListener js/window "focus" (fn [_] (.focus input)))
    (.addEventListener input "blur" (fn [_] (js/setTimeout #(when (.hasFocus js/document) (.focus input)) 0)))
    (.addEventListener js/document "keydown"
                       (fn [^js e]
                         (when (and (not (identical? (.-target e) input)) (= 1 (count (.-key e)))
                                    (not (.-ctrlKey e)) (not (.-altKey e)) (not (.-metaKey e)))
                           (.preventDefault e)
                           (.focus input)
                           (.setRangeText input (.-key e) (.-selectionStart input) (.-selectionEnd input) "end")
                           (.dispatchEvent input (js/Event. "input"))))
                       true)
    (.focus input)
    (when (= mode "edit") (.select input))
    (when (not= js/window (.-parent js/window))
      (.addEventListener js/window "message"
                         (fn [^js e]
                           (let [^js d (.-data e)]
                             (when (and (identical? (.-source e) (.-parent js/window)) (= "vimium-cljs:keys" (some-> d .-type)))
                               (when (seq (.-text d))
                                 (set! (.-value input) (str (.-text d) (.-value input)))
                                 (.dispatchEvent input (js/Event. "input")))
                               (when (.-enter d) (open! new-tab))))))
      (.postMessage (.-parent js/window) "vimium-cljs:ready" "*"))
    (when (new-tab-page?)
      ;; Back on a new tab page that was left frozen by a pick: start over.
      (.addEventListener js/window "pageshow" (fn [_] (when (:ended @vstate) (reset-query!))))
      (.addEventListener js/document "visibilitychange"
                         (fn [_] (when (and (= "visible" (.-visibilityState js/document)) (:ended @vstate)) (reset-query!))))
      (-> (js/chrome.tabs.getCurrent)
          (.then (fn [^js me]
                   (js/chrome.runtime.onMessage.addListener
                    (fn [^js msg _ respond]
                      (when (and (= "vomnibar-show" (.-type msg)) me (= (.-tabId msg) (.-id me)))
                        (let [ok (.hasFocus js/document)]
                          (when ok
                            (when (:ended @vstate) (reset-query!))
                            (when (not= (.-mode msg) (:mode @vstate))
                              (set-mode! (.-mode msg)))
                            (.focus input) (.select input))
                          (respond ok)))
                      false))))))
    nil))

(defn- key-label [k] (str/replace k #"^<(.+)>$" "‹$1›"))

(defn help! []
  (set! (.-hidden ($ "#help")) false)
  (.addEventListener js/document "keydown"
                     (fn [^js e] (when (or (= "Escape" (.-key e)) (= "?" (.-key e)) (and (.-ctrlKey e) (= "[" (.-key e))))
                                   (.preventDefault e) (close!))))
  (.addEventListener ($ "#close") "click" (fn [_] (close!)))
  (let [ck (keys/command-keys (keys/effective-mappings (keys/mappings-text config/key-mappings)))
        ^js body ($ "#commands")]
    (doseq [[g title] commands/groups]
      (let [^js sec (el "section" nil)
            ^js tbl (el "table" nil)]
        (.appendChild sec (el "h2" nil title))
        (doseq [[name group desc] commands/table :when (= g group)
                :let [ks (get ck name)]]
          (let [^js tr (el "tr" (when (empty? ks) "unbound"))
                ^js kt (el "td" "keys")]
            (doseq [k ks] (.appendChild kt (el "kbd" nil (key-label k))))
            (.append tr kt (el "td" nil desc) (el "td" "name" name))
            (.appendChild tbl tr)))
        (.appendChild sec tbl)
        (.appendChild body sec))))
  (.focus js/window))

(defn offscreen! []
  (js/chrome.runtime.onMessage.addListener
   (fn [^js msg _ respond]
     (when (= "offscreen" (.-target msg))
       (let [^js ta ($ "#clip")]
         (case (.-type msg)
           "copy" (do (set! (.-value ta) (.-text msg)) (.select ta) (.execCommand js/document "copy") (respond true))
           "paste" (do (set! (.-value ta) "") (.focus ta) (.execCommand js/document "paste") (respond (.-value ta)))
           nil))
       false))))

(defn init []
  (if (str/ends-with? (.-pathname js/location) "offscreen.html")
    (offscreen!)
    (let [[kind ps] (params)]
      (cond
        (= kind "help") (help!)
        (new-tab-page?) (vomnibar! ps)
        (str/blank? kind) (-> (js/chrome.tabs.getCurrent)
                              (.then (fn [^js t]
                                       (-> (js/chrome.tabs.create
                                            (clj->js (cond-> {:url (js/chrome.runtime.getURL "ui.html?newtab") :active (.-active t)
                                                              :index (.-index t) :windowId (.-windowId t)}
                                                       (.-openerTabId t) (assoc :openerTabId (.-openerTabId t)))))
                                           (.then #(js/chrome.tabs.remove (.-id t)))))))
        :else (vomnibar! ps)))))
