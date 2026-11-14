;; Scrolling, ported from Vimium's content_scripts/scroller.js (MIT). The element to
;; scroll starts from the activated element: the last one the user clicked, tabbed to or
;; picked with a hint, or, when there is none, the page's scrolling element if the page
;; scrolls, else the largest visible scrollable element (vimium-cljs.scroller). From there
;; it walks up, across shadow roots and slots, to the first element that really moves
;; that way. An in-page URL change (history.pushState, as YouTube does on every
;; navigation) forgets the activated element, as Vimium's Scroller.reset does (#3119).
;; Smooth scrolling eases toward a target that repeated keys keep pushing.
(ns vimium-cljs.content.scroll
  (:require [vimium-cljs.content.dom :as dom]
            [vimium-cljs.scroller :as sc]))

(defonce activated (atom nil))

(defn note-activated! [el] (when (dom/element? el) (reset! activated el)))

(defn reset-activated! [] (reset! activated nil))

;; Vimium's specialScrollingElementMap: sites whose page scrolls inside one element.
(def special-scrolling
  {"twitter.com" "div.permalink-container div.permalink[role=main]"
   "reddit.com" "#overlayScrollContainer"
   "new.reddit.com" "#overlayScrollContainer"
   "www.reddit.com" "#overlayScrollContainer"
   "web.telegram.org" ".MessageList"})

(defn scrolling-element []
  (or (some->> (get special-scrolling (.-host js/location)) (.querySelector js/document))
      (.-scrollingElement js/document)
      (.-body js/document)))

(defn- pos [^js el axis] (if (= axis :y) (.-scrollTop el) (.-scrollLeft el)))

(defn- perform-scroll!
  "Scrolls EL by AMOUNT along AXIS at once; whether it moved."
  [^js el axis amount]
  (let [before (pos el axis)]
    (.scrollBy el (if (= axis :y) #js {:top amount :behavior "instant"} #js {:left amount :behavior "instant"}))
    (not= before (pos el axis))))

(defn- does-scroll? [el axis d]
  (and (perform-scroll! el axis d) (perform-scroll! el axis (- d))))

(defn- should-scroll? [^js el axis]
  (let [^js s (js/getComputedStyle el)]
    (and (not= "hidden" (if (= axis :y) (.-overflowY s) (.-overflowX s)))
         (not (#{"hidden" "collapse"} (.-visibility s)))
         (not= "none" (.-display s))
         ;; Keys typed in a field are the page's, so a field is never the target.
         (not (dom/editable? el)))))

(defn- containing [^js el]
  (or (.-assignedSlot el)
      (.-parentElement el)
      (let [^js r (.getRootNode el)] (when (instance? js/ShadowRoot r) (.-host r)))))

(defn- rendered-children
  "EL's children in the flat tree: its shadow root's, a slot's assigned elements, and the
  children of a display:contents child in its place."
  [^js el]
  (let [kids (cond (.-shadowRoot el) (.. el -shadowRoot -children)
                   (and (= "slot" (dom/tag el)) (pos? (.-length (.assignedElements el #js {:flatten true}))))
                   (.assignedElements el #js {:flatten true})
                   :else (.-children el))]
    (mapcat (fn [^js c] (if (= "contents" (.-display (js/getComputedStyle c))) (rendered-children c) [c]))
            (array-seq kids))))

(defn- area [el]
  (when-let [^js r (dom/in-viewport-rect el)]
    (* (- (.-right r) (.-left r)) (- (.-bottom r) (.-top r)))))

(defn env []
  {:root (scrolling-element) :does-scroll does-scroll? :should should-scroll?
   :containing containing :children rendered-children :area area})

(defn- dimension
  "Vimium's getDimension: AMOUNT in pixels for EL, where :view-size is the viewport for
  the page and the box for an element, and :max the scroll size."
  [^js el axis amount]
  (case amount
    :view-size (if (identical? el (scrolling-element))
                 (if (= axis :y) (.-innerHeight js/window) (.-innerWidth js/window))
                 (if (= axis :y) (.-clientHeight el) (.-clientWidth el)))
    :max (if (= axis :y) (.-scrollHeight el) (.-scrollWidth el))
    amount))

(defn- activated-element [e]
  (let [^js a @activated]
    (when (and a (not (.-isConnected a))) (reset-activated!)))
  (or @activated
      (reset! activated (or (when-let [b (.-body js/document)] (sc/first-scrollable e b))
                             (:root e)))))

(defn scroller
  "The element a scroll by AMOUNT (pixels, :view-size or :max) times FACTOR along AXIS
  moves."
  ([axis amount] (scroller axis amount 1))
  ([axis amount factor]
   (let [e (env)
         d (sc/delta (case amount (:view-size :max) 1 amount) factor)]
     (sc/find-scrollable e (activated-element e) axis d))))

(defn- check-visibility!
  "Vimium's checkVisibility: once the activated element is scrolled out of view, its
  scrolling shows nothing, so EL (what just scrolled) becomes the activated element."
  [el]
  (when-let [^js a @activated]
    (let [^js r (.getBoundingClientRect a)]
      (when (or (< (.-bottom r) 0) (> (.-top r) (.-innerHeight js/window))
                (< (.-right r) 0) (> (.-left r) (.-innerWidth js/window)))
        (reset! activated el)))))

;; ---- smooth movement --------------------------------------------------------

(defonce ^:private anim (atom nil))

(defn- jump! [^js el x y] (.scrollTo el #js {:left x :top y :behavior "instant"}))

(defn- tick [_]
  (let [{:keys [^js el tx ty]} @anim]
    (if-not el
      (reset! anim nil)
      (let [cx (.-scrollLeft el) cy (.-scrollTop el)
            dx (- tx cx) dy (- ty cy)
            sx (if (< (js/Math.abs dx) 2) dx (* dx 0.35))
            sy (if (< (js/Math.abs dy) 2) dy (* dy 0.35))]
        (jump! el (+ cx sx) (+ cy sy))
        (if (or (and (zero? sx) (zero? sy))
                (and (= cx (.-scrollLeft el)) (= cy (.-scrollTop el))))
          (do (reset! anim nil) (check-visibility! el))
          (js/requestAnimationFrame tick))))))

(defn scroll-by!
  "Scrolls by AMOUNT (pixels or :view-size) times FACTOR along AXIS."
  [axis amount factor smooth?]
  (let [^js el (scroller axis amount factor)
        px (* factor (dimension el axis amount))]
    (if-not smooth?
      (do (perform-scroll! el axis px) (check-visibility! el))
      (let [{cur :el :as a} @anim
            same (identical? cur el)
            base-x (if same (:tx a) (.-scrollLeft el))
            base-y (if same (:ty a) (.-scrollTop el))
            clamp (fn [v mx] (max 0 (min v mx)))
            tx (clamp (if (= axis :x) (+ base-x px) base-x) (- (.-scrollWidth el) (.-clientWidth el)))
            ty (clamp (if (= axis :y) (+ base-y px) base-y) (- (.-scrollHeight el) (.-clientHeight el)))
            running (some? a)]
        (reset! anim {:el el :tx tx :ty ty})
        (when-not running (js/requestAnimationFrame tick))))))

(defn scroll-to!
  "Scrolls to the start or :end of AXIS."
  [axis where]
  (reset! anim nil)
  (let [amount (if (= where :start) 0 :max)
        ^js el (scroller axis amount)
        target (dimension el axis amount)]
    (if (= axis :y) (jump! el (.-scrollLeft el) target) (jump! el target (.-scrollTop el)))
    (check-visibility! el)))

;; ---- what the user activated --------------------------------------------------

(defonce ^:private last-url (atom nil))
(defonce ^:private tab-at (atom 0))

(defn url-changed!
  "Forgets the activated element when this frame's URL really changed: the user has
  navigated, even though the document stayed (Vimium's checkEnabledAfterURLChange)."
  []
  (let [href (.-href js/location)]
    (when (not= href @last-url)
      (reset! last-url href)
      (reset-activated!))))

(defn install!
  "Tracks the activated element: the target of a trusted mouse press (clicks, and drags
  of a scrollbar), and an element focused with Tab. A page moving focus by itself (YouTube
  focuses its guide button on load) does not count."
  []
  (reset! last-url (.-href js/location))
  (.addEventListener js/window "mousedown" (fn [^js e] (when (.-isTrusted e) (note-activated! (dom/event-target e)))) true)
  (.addEventListener js/window "keydown" (fn [^js e] (when (and (.-isTrusted e) (= "Tab" (.-key e))) (reset! tab-at (js/Date.now)))) true)
  (.addEventListener js/window "focusin" (fn [^js e] (when (< (- (js/Date.now) @tab-at) 1000) (note-activated! (dom/event-target e)))) true)
  (.addEventListener js/window "hashchange" url-changed! true)
  (.addEventListener js/window "popstate" url-changed! true))
