;; After Vimium's mode_visual.js (MIT).
(ns vimium-cljs.content.visual
  (:require [clojure.string :as str]
            [vimium-cljs.content.dom :as dom]))

(def movements
  {"h" ["backward" "character"] "l" ["forward" "character"]
   "<left>" ["backward" "character"] "<right>" ["forward" "character"]
   "j" ["forward" "line"] "k" ["backward" "line"]
   "<down>" ["forward" "line"] "<up>" ["backward" "line"]
   "w" ["forward" "word"] "e" ["forward" "word"] "b" ["backward" "word"]
   "0" ["backward" "lineboundary"] "$" ["forward" "lineboundary"]
   ")" ["forward" "sentence"] "(" ["backward" "sentence"]
   "}" ["forward" "paragraph"] "{" ["backward" "paragraph"]
   "G" ["forward" "documentboundary"]})

(defn- sel ^js [] (js/getSelection))

(defn- first-visible-text
  "The first text node in the viewport with visible non-space text, and the offset of that text."
  [own-host]
  (let [w (.createTreeWalker js/document (or (.-body js/document) (.-documentElement js/document)) js/NodeFilter.SHOW_TEXT)
        h (.-innerHeight js/window)]
    (loop [n 0]
      (when-let [^js node (when (< n 20000) (.nextNode w))]
        (let [data (.-data node)
              i (.search data #"\S")]
          (if (and (>= i 0) (not (and own-host (.contains own-host node))))
            (let [^js r (.createRange js/document)]
              (.setStart r node i)
              (.setEnd r node (inc i))
              (let [^js b (.getBoundingClientRect r)]
                (if (and (pos? (.-height b)) (>= (.-top b) 0) (< (.-top b) h))
                  [node i]
                  (recur (inc n)))))
            (recur (inc n))))))))

(defn has-selection? []
  (let [s (sel)] (and (pos? (.-rangeCount s)) (not (.-isCollapsed s)))))

(defn- extend-lines! []
  (let [s (sel)
        forward? (let [^js r (.createRange js/document)]
                   (.setStart r (.-anchorNode s) (.-anchorOffset s))
                   (try (.setEnd r (.-focusNode s) (.-focusOffset s)) (catch :default _ nil))
                   (not (.-collapsed r)))
        [a b] (if forward? ["backward" "forward"] ["forward" "backward"])]
    (.setBaseAndExtent s (.-focusNode s) (.-focusOffset s) (.-anchorNode s) (.-anchorOffset s))
    (.modify s "extend" a "lineboundary")
    (.setBaseAndExtent s (.-focusNode s) (.-focusOffset s) (.-anchorNode s) (.-anchorOffset s))
    (.modify s "extend" b "lineboundary")))

(defn enter!
  "Starts visual (or LINE) mode. Returns false when there is no text to select."
  [line? own-host]
  (let [s (sel)]
    (if (has-selection?)
      (do (when line? (extend-lines!)) true)
      (if-let [[node i] (or (when (pos? (.-rangeCount s))
                              (let [^js r (.getRangeAt s 0)
                                    n (.-startContainer r)]
                                (when (and (= 3 (.-nodeType n)) (dom/in-viewport-rect (.-parentElement n)))
                                  [n (.-startOffset r)])))
                            (first-visible-text own-host))]
        (do (.setBaseAndExtent s node i node i)
            (.modify s "extend" "forward" "character")
            (when line? (extend-lines!))
            true)
        false))))

(defn move!
  "Applies movement key K COUNT times; gg is handled by the caller."
  [k times line?]
  (let [s (sel)]
    (when-let [[dir gran] (if (= k "gg") ["backward" "documentboundary"] (movements k))]
      (dotimes [_ (max 1 times)] (.modify s "extend" dir gran))
      (when line? (extend-lines!))
      (when (pos? (.-rangeCount s))
        (let [^js n (.-focusNode s)
              ^js el (if (= 3 (.-nodeType n)) (.-parentElement n) n)]
          (when (and el (not (dom/in-viewport-rect el)))
            (.scrollIntoView el #js {:block "nearest"}))))
      true)))

(defn swap-ends! []
  (let [s (sel)]
    (.setBaseAndExtent s (.-focusNode s) (.-focusOffset s) (.-anchorNode s) (.-anchorOffset s))))

(defn selected-text [] (str (sel)))

(defn exit! []
  (let [s (sel)]
    (when (pos? (.-rangeCount s))
      (.collapseToEnd s))))
