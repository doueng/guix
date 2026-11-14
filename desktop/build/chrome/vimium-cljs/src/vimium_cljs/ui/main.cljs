;; Extension pages shown in a frame over the page (the Vomnibar and the help
;; dialog) and the offscreen clipboard document. One bundle; the location
;; picks the page. The Vomnibar follows Vimium's pages/vomnibar_page.js (MIT).
(ns vimium-cljs.ui.main
  (:require [clojure.string :as str]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.keys :as keys]
            [vimium-cljs.config :as config]))

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

(defonce vstate (atom {:items [] :selected -1 :seq 0}))

(def ^:private type-labels {"tab" "tab" "bookmark" "bookmark" "history" "history" "search" "search" "url" "open"})

(defn- render! []
  (let [{:keys [items selected]} @vstate
        ^js list ($ "#list")]
    (set! (.-textContent list) "")
    (doseq [[i {:keys [type title url]}] (map-indexed vector items)]
      (let [^js li (el "li" (when (= i selected) "selected"))]
        (.append li (el "span" (str "kind " type) (type-labels type type))
                 (el "span" "title" (if (str/blank? title) url title))
                 (el "span" "url" url))
        (.addEventListener li "mousedown" (fn [^js e] (.preventDefault e) (swap! vstate assoc :selected i)
                                              (render!)))
        (.appendChild list li)))))

(defn- query! [mode q]
  (let [n (:seq (swap! vstate update :seq inc))]
    (-> (js/chrome.runtime.sendMessage (clj->js {:type "vomnibar-query" :mode mode :query q :tabId (target-tab)}))
        (.then (fn [items]
                 (when (= n (:seq @vstate))
                   (let [items (js->clj (or items #js []) :keywordize-keys true)]
                     (swap! vstate assoc :items items
                            :selected (if (and (seq items) (or (= mode "tabs") (not (str/blank? q)))) 0 -1))
                     (render!))))))))

(defn- open! [new-tab text]
  (let [{:keys [items selected]} @vstate
        item (get items selected)]
    (-> (js/chrome.runtime.sendMessage (clj->js {:type "vomnibar-open" :newTab (boolean new-tab)
                                                  :item item :text (when-not item text) :tabId (target-tab)}))
        (.finally close!))))

(defn vomnibar! [^js ps]
  (let [mode (or (.get ps "mode") "omni")
        new-tab (= "1" (.get ps "newTab"))
        ^js input ($ "#query")
        move (fn [d] (let [n (count (:items @vstate))]
                       (when (pos? n)
                         (swap! vstate update :selected #(mod (+ (if (neg? %) (if (pos? d) -1 0) %) d) n))
                         (render!))))]
    (set! (.-hidden ($ "#vomnibar")) false)
    (set! (.-placeholder input) (case mode "tabs" "Search open tabs" "bookmarks" "Search bookmarks" "Search or type a URL"))
    (when (= mode "edit") (set! (.-value input) (or (.get ps "url") "")))
    (.addEventListener input "input" (fn [_] (query! mode (.-value input))))
    (.addEventListener js/document "keydown"
                       (fn [^js e]
                         (let [k (.-key e) c (.-ctrlKey e)]
                           (cond
                             (or (= k "Escape") (and c (= k "["))) (do (.preventDefault e) (close!))
                             (= k "Enter") (do (.preventDefault e) (open! (or new-tab (.-shiftKey e)) (.-value input)))
                             (or (= k "ArrowDown") (and (= k "Tab") (not (.-shiftKey e))) (and c (#{"n" "j"} k)))
                             (do (.preventDefault e) (move 1))
                             (or (= k "ArrowUp") (and (= k "Tab") (.-shiftKey e)) (and c (#{"p" "k"} k)))
                             (do (.preventDefault e) (move -1))
                             (and (= k "Delete") (.-shiftKey e) (= mode "tabs"))
                             (let [{:keys [items selected]} @vstate]
                               (when-let [t (get items selected)]
                                 (.preventDefault e)
                                 (-> (js/chrome.tabs.remove (:tabId t)) (.then #(query! mode (.-value input))))))))))
    (.addEventListener js/window "blur" (fn [_] (js/setTimeout #(when-not (.hasFocus js/document) (close!)) 50)))
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
