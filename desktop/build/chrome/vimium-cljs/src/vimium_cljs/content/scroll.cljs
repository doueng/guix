;; Scrolling. Picks the element to scroll the way Vimium's scroller.js does:
;; from the last clicked or focused element up to the first ancestor that can
;; move that way, else the document, else the largest scrollable element.
;; Smooth scrolling eases toward a target that repeated keys keep pushing.
(ns vimium-cljs.content.scroll
  (:require [vimium-cljs.content.dom :as dom]))

(defonce activated (atom nil))

(defn note-activated! [el] (reset! activated el))

(defn- largest-scrollable [axis dir]
  (let [best (atom nil) best-area (atom 0)]
    (when-let [body (.-body js/document)]
      (dom/walk-elements body
                         (fn [^js el]
                           (when (dom/can-scroll? el axis dir)
                             (when-let [^js r (dom/in-viewport-rect el)]
                               (let [a (* (- (.-right r) (.-left r)) (- (.-bottom r) (.-top r)))]
                                 (when (> a @best-area) (reset! best el) (reset! best-area a))))))))
    @best))

(defn scroller [axis dir]
  (let [root (or (.-scrollingElement js/document) (.-documentElement js/document))
        start (or (let [^js a @activated] (when (and a (.-isConnected a)) a))
                  (let [f (dom/deep-active-element)] (when-not (identical? f (.-body js/document)) f)))]
    (or (loop [^js el start]
          (cond (nil? el) nil
                (identical? el root) nil
                (and (dom/element? el) (not (dom/editable? el)) (dom/can-scroll? el axis dir)) el
                :else (recur (or (.-parentElement el) (some-> (.getRootNode el) .-host)))))
        (when (dom/can-scroll? root axis dir) root)
        (largest-scrollable axis dir)
        root)))

;; ---- smooth movement --------------------------------------------------------

(defonce ^:private anim (atom nil))

(defn- tick [_]
  (let [{:keys [^js el tx ty]} @anim]
    (if-not el
      (reset! anim nil)
      (let [cx (.-scrollLeft el) cy (.-scrollTop el)
            dx (- tx cx) dy (- ty cy)
            sx (if (< (js/Math.abs dx) 2) dx (* dx 0.35))
            sy (if (< (js/Math.abs dy) 2) dy (* dy 0.35))]
        (set! (.-scrollLeft el) (+ cx sx))
        (set! (.-scrollTop el) (+ cy sy))
        (if (or (and (zero? sx) (zero? sy))
                (and (= cx (.-scrollLeft el)) (= cy (.-scrollTop el))))
          (reset! anim nil)
          (js/requestAnimationFrame tick))))))

(defn scroll-by!
  [axis amount smooth?]
  (let [dir (if (neg? amount) -1 1)
        ^js el (scroller axis dir)]
    (if-not smooth?
      (if (= axis :y) (.scrollBy el 0 amount) (.scrollBy el amount 0))
      (let [{cur :el :as a} @anim
            same (identical? cur el)
            base-x (if same (:tx a) (.-scrollLeft el))
            base-y (if same (:ty a) (.-scrollTop el))
            clamp (fn [v mx] (max 0 (min v mx)))
            tx (clamp (if (= axis :x) (+ base-x amount) base-x) (- (.-scrollWidth el) (.-clientWidth el)))
            ty (clamp (if (= axis :y) (+ base-y amount) base-y) (- (.-scrollHeight el) (.-clientHeight el)))
            running (some? a)]
        (reset! anim {:el el :tx tx :ty ty})
        (when-not running (js/requestAnimationFrame tick))))))

(defn scroll-to! [axis where]
  (reset! anim nil)
  (let [^js el (scroller axis (if (= where :start) -1 1))]
    (if (= axis :y)
      (set! (.-scrollTop el) (if (= where :start) 0 (.-scrollHeight el)))
      (set! (.-scrollLeft el) (if (= where :start) 0 (.-scrollWidth el))))))

(defn viewport-size [axis]
  (let [^js el (scroller axis 1)
        root? (identical? el (.-scrollingElement js/document))]
    (if (= axis :y)
      (if root? (.-innerHeight js/window) (.-clientHeight el))
      (if root? (.-innerWidth js/window) (.-clientWidth el)))))
