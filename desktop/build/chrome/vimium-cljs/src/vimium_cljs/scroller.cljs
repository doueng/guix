;; Which element a scroll command moves, ported from Vimium's content_scripts/scroller.js
;; (MIT): findScrollableElement and firstScrollableElement. Pure over an ENV of DOM
;; accessors so cljs.test can drive it with a fake tree; content/scroll.cljs supplies the
;; real one. ENV keys:
;;   :root        the page's scrolling element (document.scrollingElement)
;;   :does-scroll (fn [el axis delta]) whether EL moves by DELTA (1 or -1) when asked, tested
;;                by really scrolling it and putting it back: scrollHeight and overflow lie
;;   :should      (fn [el axis]) whether EL may be scrolled: not overflow hidden, visible,
;;                displayed, not a text field
;;   :containing  (fn [el]) the element EL is laid out in: its slot, parent or shadow host
;;   :children    (fn [el]) EL's rendered children (its shadow root's, a slot's assigned ones)
;;   :area        (fn [el]) the area of EL's box inside the viewport, or nil when not visible
(ns vimium-cljs.scroller)

(defn sign [x] (cond (or (nil? x) (js/isNaN x) (zero? x)) 0 (neg? x) -1 :else 1))

(defn delta
  "The direction to test a scroll in: the sign of AMOUNT * FACTOR, or -1 when that is
  zero (an absolute scroll to the start)."
  [amount factor]
  (let [s (sign (* factor amount))] (if (zero? s) -1 s)))

(defn scrollable?
  "Vimium's isScrollableElement: EL moves that way and may be scrolled."
  [{:keys [does-scroll should]} el axis d]
  (boolean (and (does-scroll el axis d) (should el axis))))

(defn find-scrollable
  "From EL up through its containing elements (across shadow roots), the first that
  scrolls by D along AXIS, else the root."
  [{:keys [root containing] :as env} el axis d]
  (loop [el el]
    (cond (or (nil? el) (identical? el root)) root
          (scrollable? env el axis d) el
          :else (recur (or (containing el) root)))))

(defn first-scrollable
  "Vimium's firstScrollableElement: the root when the page itself scrolls vertically,
  else, from BODY down, the first element that scrolls vertically, visiting children
  largest visible area first. So a big main column wins over a narrow sidebar."
  ([{:keys [root does-scroll] :as env} body]
   (if (or (does-scroll root :y 1) (does-scroll root :y -1))
     root
     (first-scrollable env body ::descend)))
  ([{:keys [does-scroll should children area] :as env} el _]
   (if (and (or (does-scroll el :y 1) (does-scroll el :y -1)) (should el :y))
     el
     (some #(first-scrollable env % ::descend)
           (->> (children el)
                (keep (fn [c] (when-let [a (area c)] [c a])))
                (sort-by (fn [[_ a]] (- a)))
                (map first))))))
