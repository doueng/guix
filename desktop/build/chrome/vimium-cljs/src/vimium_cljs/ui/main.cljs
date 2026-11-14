;; Extension pages shown in a frame over the page (the Vomnibar and the help
;; dialog) and the offscreen clipboard document. One bundle; the location
;; picks the page. The Vomnibar follows Vimium's pages/vomnibar_page.js (MIT).
(ns vimium-cljs.ui.main
  (:require [clojure.string :as str]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.keys :as keys]
            [vimium-cljs.config :as config]
            [vimium-cljs.rank :as rank]))

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

;; The worker opens the Vomnibar in its own popup window, naming the tab to act on, when
;; the active tab runs no content script (chrome:// pages, the Web Store).
(defn- popup? [] (= "1" (.get (second (params)) "popup")))
(defn- target-tab [] (some-> (.get (second (params)) "tabId") js/Number))

(defn- close! []
  (if (popup?)
    (.close js/window)
    (.postMessage (.-parent js/window) "vimium-cljs:close" "*")))

;; ---- Vomnibar -------------------------------------------------------------------
;; Keys: Enter opens the selected row (Shift: new tab, Ctrl: the typed text as is), Tab or
;; Ctrl-n/j and Shift-Tab or Ctrl-p/k move, 1-9 pick a row in tab search while the query
;; is empty, Ctrl+V of a URL into the empty input opens it (Ctrl+Shift+V only pastes).
;; Alt+digits stay with Hyprland's workspaces.

(defonce vstate (atom {:items [] :selected 0 :seq 0 :pending nil}))

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

(declare open!)

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
    (when (and (numbered? mode) (< i 9)) (.append li (el "span" "num" (str (inc i)))))
    (.append li (icon item) text (el "span" "badge" (badge type type)))
    (.addEventListener li "mousedown" (fn [^js e] (.preventDefault e)))
    (.addEventListener li "click" (fn [^js e] (swap! vstate assoc :selected i) (open! (.-shiftKey e))))
    li))

(defn- render! []
  (let [{:keys [items selected mode]} @vstate
        ^js list ($ "#list")]
    (.replaceChildren list)
    (doseq [[i item] (map-indexed vector items)]
      (.appendChild list (row mode i item selected)))
    (some-> ^js (.querySelector list "li.selected") (.scrollIntoView #js {:block "nearest"}))))

(defn- query! [mode q]
  (let [n (:seq (swap! vstate update :seq inc))
        pr (-> (js/chrome.runtime.sendMessage (clj->js {:type "vomnibar-query" :mode mode :query q :tabId (target-tab)}))
               (.then (fn [items]
                        (when (= n (:seq @vstate))
                          (swap! vstate assoc :items (js->clj (or items #js []) :keywordize-keys true) :selected 0 :pending nil :for q)
                          (render!)))))]
    (swap! vstate assoc :pending pr)
    pr))

(defn- open!
  "Opens the selected row once the results for what is typed have arrived, so a fast
  Enter never opens a result for an older query. AS-TYPED opens the typed text."
  ([new-tab] (open! new-tab false))
  ([new-tab as-typed]
   (let [text (.-value ($ "#query"))
         mode (:mode @vstate)]
     (-> (if (= text (:for @vstate)) (js/Promise.resolve (:pending @vstate)) (query! mode text))
         (.then (fn [_]
                  (let [{:keys [items selected]} @vstate
                        item (when-not as-typed (get items selected))]
                    (when (or item (not (str/blank? text)))
                      (-> (js/chrome.runtime.sendMessage (clj->js {:type "vomnibar-open" :newTab (boolean new-tab) :query text
                                                                    :item item :text (when-not item text) :tabId (target-tab)}))
                          (.finally close!))))))))))

(defn- show-chip! [mode q]
  (set! (.-textContent ($ "#chip"))
        (or (some-> (rank/engine-of q config/search-engines) second :name)
            (case mode "tabs" "Tabs" "bookmarks" "Bookmarks" nil))))

(defn vomnibar! [^js ps]
  (let [mode (or (.get ps "mode") "omni")
        new-tab (= "1" (.get ps "newTab"))
        ^js input ($ "#query")
        plain-paste (atom false)
        move (fn [d] (let [n (count (:items @vstate))]
                       (when (pos? n)
                         (swap! vstate update :selected #(mod (+ % d) n))
                         (render!))))]
    (swap! vstate assoc :mode mode)
    (when (popup?) (.add (.-classList js/document.body) "popup"))
    (set! (.-hidden ($ "#vomnibar")) false)
    (set! (.-placeholder input) (case mode "tabs" "Switch to tab" "bookmarks" "Search bookmarks" "Search or type a URL"))
    (when (= mode "edit") (set! (.-value input) (or (.get ps "url") "")))
    (show-chip! mode (.-value input))
    (.addEventListener input "input" (fn [_] (show-chip! mode (.-value input)) (query! mode (.-value input))))
    (.addEventListener input "paste"
                       (fn [^js e]
                         (let [t (str/trim (.getData (.-clipboardData e) "text"))]
                           (when (and (not @plain-paste) (str/blank? (.-value input)) (rank/url-like? t))
                             (.preventDefault e)
                             (set! (.-value input) t)
                             (-> (js/chrome.runtime.sendMessage (clj->js {:type "vomnibar-open" :newTab new-tab :query t :text t :tabId (target-tab)}))
                                 (.finally close!))))
                         (reset! plain-paste false)))
    (.addEventListener js/document "keydown"
                       (fn [^js e]
                         (let [k (.-key e) c (.-ctrlKey e) s (.-shiftKey e)]
                           (cond
                             (or (= k "Escape") (and c (= k "["))) (do (.preventDefault e) (close!))
                             (= k "Enter") (do (.preventDefault e) (open! (or new-tab s) c))
                             (or (= k "ArrowDown") (and (= k "Tab") (not s)) (and c (#{"n" "j"} k)))
                             (do (.preventDefault e) (move 1))
                             (or (= k "ArrowUp") (and (= k "Tab") s) (and c (#{"p" "k"} k)))
                             (do (.preventDefault e) (move -1))
                             (and (re-matches #"[1-9]" k) (not c) (not (.-altKey e)) (numbered? mode)
                                  (< (dec (js/Number k)) (count (:items @vstate))))
                             (do (.preventDefault e) (swap! vstate assoc :selected (dec (js/Number k))) (open! (or new-tab s)))
                             (and c s (= (str/lower-case k) "v")) (reset! plain-paste true)
                             (and (= k "Delete") s (= mode "tabs"))
                             (let [{:keys [items selected]} @vstate]
                               (when-let [t (get items selected)]
                                 (.preventDefault e)
                                 (-> (js/chrome.tabs.remove (:tabId t)) (.then #(query! mode (.-value input))))))))))
    (.addEventListener js/window "blur" (fn [_] (js/setTimeout #(when-not (.hasFocus js/document) (close!)) 50)))
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
    (query! mode (.-value input))))

;; ---- help ---------------------------------------------------------------------------

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

;; ---- offscreen clipboard ---------------------------------------------------------------

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
      (case kind
        "help" (help!)
        (vomnibar! ps)))))
