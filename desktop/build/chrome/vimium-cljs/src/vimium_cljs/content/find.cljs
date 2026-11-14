;; Find mode. Like Vimium's mode_find.js the query is smart-case (\\i and \\I
;; force it) and may be a regexp; unlike it, this searches the text nodes
;; itself instead of window.find, so it can highlight every match with the
;; CSS Custom Highlight API and reach open shadow roots.
(ns vimium-cljs.content.find
  (:require [clojure.string :as str]
            [vimium-cljs.content.dom :as dom]
            [vimium-cljs.content.ui :as ui]))

(defonce state (atom {:query nil :ranges [] :current -1}))

(defn- skip-text? [^js node own-host]
  (let [^js p (.-parentElement node)]
    (or (nil? p)
        (#{"script" "style" "noscript" "template" "textarea" "select" "option"} (dom/tag p))
        (and own-host (.contains own-host p)))))

(defn- text-nodes
  "Text nodes in document order, through open shadow roots."
  [own-host]
  (let [out (array)]
    (letfn [(walk [^js root]
              (let [w (.createTreeWalker js/document root (bit-or js/NodeFilter.SHOW_ELEMENT js/NodeFilter.SHOW_TEXT))]
                (loop []
                  (when-let [^js n (.nextNode w)]
                    (if (= 3 (.-nodeType n))
                      (when-not (skip-text? n own-host) (.push out n))
                      (when-let [sr (.-shadowRoot n)] (walk sr)))
                    (recur)))))]
      (when-let [b (.-body js/document)] (walk b)))
    out))

(defn parse-query
  "[pattern-source flags] for QUERY, or nil. Smart case unless \\i / \\I."
  [query regex?]
  (let [ci (cond (str/includes? query "\\I") false
                 (str/includes? query "\\i") true
                 :else (= query (str/lower-case query)))
        q (str/replace query #"\\[iI]" "")]
    (when (seq q)
      [(if regex? q (str/replace q #"[.*+?^${}()|\[\]\\]" "\\$&")) (if ci "gi" "g")])))

(defn search
  "Ranges of QUERY's matches in the page."
  [query regex? own-host]
  (when-let [[src flags] (parse-query query regex?)]
    (when-let [re (try (js/RegExp. src flags) (catch :default _ nil))]
      (let [nodes (text-nodes own-host)
            starts (array)
            text (let [sb (array)]
                   (loop [i 0 pos 0]
                     (when (< i (.-length nodes))
                       (let [t (.-data ^js (aget nodes i))]
                         (.push starts pos)
                         (.push sb t)
                         (recur (inc i) (+ pos (count t))))))
                   (.join sb ""))
            locate (fn [pos]
                     ;; binary search for the node holding offset POS
                     (loop [lo 0 hi (dec (.-length starts))]
                       (if (>= lo hi)
                         [lo (- pos (aget starts lo))]
                         (let [mid (js/Math.ceil (/ (+ lo hi) 2))]
                           (if (<= (aget starts mid) pos) (recur mid hi) (recur lo (dec mid)))))))
            out (array)]
        (loop [n 0]
          (when-let [^js m (when (< n 5000) (.exec re text))]
            (let [len (count (aget m 0))]
              (if (zero? len)
                (set! (.-lastIndex re) (inc (.-lastIndex re)))
                (let [[si so] (locate (.-index m))
                      [ei eo] (locate (+ (.-index m) len -1))
                      ^js r (.createRange js/document)]
                  (.setStart r (aget nodes si) so)
                  (.setEnd r (aget nodes ei) (inc eo))
                  (when (pos? (.-length (.getClientRects r))) (.push out r))))
              (recur (inc n)))))
        (vec out)))))

(defn- highlights ^js [] (some-> js/CSS .-highlights))

(defn clear! []
  (when-let [h (highlights)]
    (.delete h "vimium-cljs-find")
    (.delete h "vimium-cljs-find-current"))
  (swap! state assoc :ranges [] :current -1))

(defn- paint! [ranges current]
  (when-let [h (highlights)]
    (let [^js all (js/Highlight.)]
      (doseq [r ranges] (.add all r))
      (.set h "vimium-cljs-find" all))
    (if-let [r (get ranges current)]
      (.set h "vimium-cljs-find-current" (js/Highlight. r))
      (.delete h "vimium-cljs-find-current"))))

(defn- reveal! [^js r]
  (let [^js n (.-startContainer r)
        ^js el (if (= 3 (.-nodeType n)) (.-parentElement n) n)
        ^js rect (.getBoundingClientRect r)]
    (when (or (< (.-top rect) 0) (> (.-bottom rect) (.-innerHeight js/window))
              (< (.-left rect) 0) (> (.-right rect) (.-innerWidth js/window)))
      (.scrollIntoView el #js {:block "center" :inline "nearest"}))))

(defn- first-below-viewport-top [ranges]
  (or (first (keep-indexed (fn [i ^js r] (when (>= (.-top (.getBoundingClientRect r)) 0) i)) ranges)) 0))

(defn count-text [] (let [{:keys [ranges current]} @state]
                      (if (empty? ranges) "no matches" (str (inc current) "/" (count ranges)))))

(defn update-query!
  "Searches as the user types: highlights all, makes the first match at or below
  the top of the viewport current."
  [query regex? own-host]
  (let [ranges (or (search query regex? own-host) [])
        cur (if (seq ranges) (first-below-viewport-top ranges) -1)]
    (swap! state assoc :query query :ranges ranges :current cur)
    (paint! ranges cur)
    (when-let [r (get ranges cur)] (reveal! r))
    (count ranges)))

(defn- select! [^js r]
  (let [^js sel (js/getSelection)]
    (.removeAllRanges sel)
    (.addRange sel r)))

(defn focus-found-link!
  "After find, focus a link that contains the current match, as Vimium does."
  []
  (when-let [^js r (get (:ranges @state) (:current @state))]
    (let [^js n (.-startContainer r)]
      (when-let [^js a (some-> (if (= 3 (.-nodeType n)) (.-parentElement n) n) (.closest "a[href]"))]
        (.focus a)))))

(defn finish! [selected?]
  (when selected?
    (when-let [r (get (:ranges @state) (:current @state))] (select! r))))

(defn- cmp [^js r anchor]
  ;; Ranges in another tree (a shadow root) cannot be compared; they count as after.
  (try (.compareBoundaryPoints r js/Range.START_TO_START anchor) (catch :default _ 1)))

(defn step!
  "Moves to the next (DIR 1) or previous (-1) match COUNT times, searching
  again first since the page may have changed. Returns false without a query."
  [dir times regex? own-host]
  (if-let [q (:query @state)]
    (let [^js sel (js/getSelection)
          anchor (when (pos? (.-rangeCount sel)) (.getRangeAt sel 0))
          ranges (or (search q regex? own-host) [])
          n (count ranges)]
      (if (zero? n)
        (do (clear!) (swap! state assoc :query q) 0)
        (let [start (if anchor
                      (if (pos? dir)
                        (or (first (keep-indexed (fn [i ^js r] (when (pos? (cmp r anchor)) i)) ranges)) 0)
                        (or (last (keep-indexed (fn [i ^js r] (when (neg? (cmp r anchor)) i)) ranges)) (dec n)))
                      (if (pos? dir) 0 (dec n)))
              cur (mod (+ start (* dir (dec times))) n)]
          (swap! state assoc :ranges ranges :current cur)
          (paint! ranges cur)
          (select! (get ranges cur))
          (reveal! (get ranges cur))
          n)))
    false))

(defn set-query! [q] (swap! state assoc :query q))
