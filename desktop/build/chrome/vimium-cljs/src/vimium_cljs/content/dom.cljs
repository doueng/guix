;; DOM helpers for the content script: editable detection through shadow roots,
;; visibility, clickable elements, scrollable elements. The clickable rules
;; follow Vimium's LocalHints (content_scripts/link_hints.js, MIT).
(ns vimium-cljs.content.dom
  (:require [clojure.string :as str]))

(def text-input-types
  #{"" "text" "search" "email" "url" "number" "password" "date" "datetime-local"
    "month" "time" "week" "tel"})

(defn tag [^js el] (some-> (.-tagName el) str/lower-case))

(defn element? [^js x] (and x (= 1 (.-nodeType x))))

(defn editable?
  "Whether keys typed at EL are text for the page: text inputs, textareas,
  selects, contenteditable and ARIA textboxes."
  [^js el]
  (boolean
   (when (element? el)
     (case (tag el)
       "input" (and (text-input-types (str/lower-case (or (.getAttribute el "type") "")))
                    (not (.-disabled el)) (not (.-readOnly el)))
       "textarea" (and (not (.-disabled el)) (not (.-readOnly el)))
       "select" true
       (or (.-isContentEditable el)
           (= "textbox" (.getAttribute el "role")))))))

(defn deep-active-element
  "document.activeElement, followed into open shadow roots."
  []
  (loop [^js el (.-activeElement js/document)]
    (if-let [^js inner (some-> el .-shadowRoot .-activeElement)]
      (recur inner)
      el)))

(defn event-target
  "The innermost target of event E, through open shadow roots."
  [^js e]
  (let [path (.composedPath e)]
    (if (pos? (.-length path)) (aget path 0) (.-target e))))

(defn in-viewport-rect
  "The first client rect of EL that is at least 3px each way and overlaps the
  viewport, clipped to it, or nil."
  [^js el]
  (let [w (.-innerWidth js/window) h (.-innerHeight js/window)
        rects (.getClientRects el)]
    (loop [i 0]
      (when (< i (.-length rects))
        (let [^js r (aget rects i)
              l (max 0 (.-left r)) t (max 0 (.-top r))
              rr (min w (.-right r)) b (min h (.-bottom r))]
          (if (and (>= (- rr l) 3) (>= (- b t) 3))
            #js {:left l :top t :right rr :bottom b}
            (recur (inc i))))))))

(defn visible-rect
  "Like in-viewport-rect but also requires visibility:visible, opacity > 0 and
  that the element at the rect's centre is EL or inside it (not covered)."
  [^js el]
  (when-let [^js r (in-viewport-rect el)]
    (let [^js style (js/getComputedStyle el)]
      (when (and (not= "hidden" (.-visibility style))
                 (not= "0" (.-opacity style)))
        (let [x (/ (+ (.-left r) (.-right r)) 2)
              y (/ (+ (.-top r) (.-bottom r)) 2)
              root (.getRootNode el)
              ^js hit (if (.-elementFromPoint root) (.elementFromPoint root x y) (.elementFromPoint js/document x y))]
          ;; A hit outside EL means something covers its centre. Vimium then tries
          ;; the corners; we accept a hit on any ancestor-or-descendant.
          (when (or (nil? hit) (identical? hit el) (.contains el hit) (.contains hit el)
                    (let [corner (.elementFromPoint root (+ (.-left r) 1) (+ (.-top r) 1))]
                      (or (identical? corner el) (and corner (.contains el corner)))))
            r))))))

(def ^:private clickable-roles
  #{"button" "tab" "link" "checkbox" "menuitem" "menuitemcheckbox" "menuitemradio" "radio" "textbox"})

(defn clickable?
  [^js el]
  (let [t (tag el)]
    (or (case t
          "a" true
          "textarea" (and (not (.-disabled el)) (not (.-readOnly el)))
          "input" (and (not= "hidden" (some-> (.getAttribute el "type") str/lower-case))
                       (not (.-disabled el)))
          ("button" "select") (not (.-disabled el))
          ("object" "embed" "details" "summary") true
          "label" (some-> ^js (.-control el) .-disabled not)
          false)
        (.hasAttribute el "onclick")
        (some-> (.getAttribute el "role") str/lower-case clickable-roles)
        (let [ce (.getAttribute el "contenteditable")]
          (and ce (#{"" "true" "contenteditable"} (str/lower-case ce))))
        (when-let [ja (.getAttribute el "jsaction")]
          (some (fn [rule]
                  (let [parts (str/split (str/trim rule) #":")
                        [ev handler] (if (= 1 (count parts)) ["click" (first parts)] parts)
                        [ns action] (str/split (str/trim (or handler "")) #"\.")]
                    (and (= "click" ev) (not (#{"none" "_"} ns)) (not= "_" action))))
                (str/split ja #";")))
        (and (.hasAttribute el "tabindex")
             (let [ti (js/parseInt (.getAttribute el "tabindex") 10)] (and (not (js/isNaN ti)) (>= ti 0)))))))

(defn walk-elements
  "Calls F on every element under ROOT, descending into open shadow roots."
  [^js root f]
  (let [walker (.createTreeWalker js/document root js/NodeFilter.SHOW_ELEMENT)]
    (loop []
      (when-let [^js el (.nextNode walker)]
        (f el)
        (when-let [sr (.-shadowRoot el)] (walk-elements sr f))
        (recur)))))

(defn clickables
  "Visible clickable elements in this frame, in document order, with their
  rects: [[el rect] ...]. Drops an element whose only role is to wrap one
  clickable child with the same box, as Vimium does for false positives."
  [own-host]
  (let [out (array)]
    (when-let [body (.-body js/document)]
      (walk-elements body
                     (fn [^js el]
                       (when (and (not (identical? el own-host)) (clickable? el))
                         (when-let [r (visible-rect el)]
                           (.push out #js [el r]))))))
    ;; Drop a hint whose element contains the next hinted element at the same spot.
    (let [n (.-length out)]
      (vec (keep-indexed
            (fn [i ^js pair]
              (let [^js el (aget pair 0) ^js r (aget pair 1)
                    ^js nxt (when (< (inc i) n) (aget out (inc i)))]
                (when-not (and nxt (not= "a" (tag el))
                               (.contains el (aget nxt 0))
                               (let [^js r2 (aget nxt 1)]
                                 (and (< (js/Math.abs (- (.-left r) (.-left r2))) 2)
                                      (< (js/Math.abs (- (.-top r) (.-top r2))) 2))))
                  [el r])))
            out)))))

(defn text-inputs
  "Visible text inputs in document order, through open shadow roots."
  []
  (let [out (array)]
    (when-let [body (.-body js/document)]
      (walk-elements body (fn [^js el]
                            (when (and (editable? el) (not= "select" (tag el)) (in-viewport-rect el))
                              (.push out el)))))
    (vec out)))

(defn link-href [^js el]
  (when (= "a" (tag el))
    (let [h (.-href el)] (when (and (string? h) (seq h)) h))))

(defn flash!
  "Outlines EL's box briefly."
  [^js el]
  (when-let [^js r (in-viewport-rect el)]
    (let [^js d (.createElement js/document "div")]
      (set! (.. d -style -cssText)
            (str "position:fixed;z-index:2147483647;pointer-events:none;box-sizing:border-box;"
                 "border:2px solid #f2c94c;left:" (.-left r) "px;top:" (.-top r) "px;width:"
                 (- (.-right r) (.-left r)) "px;height:" (- (.-bottom r) (.-top r)) "px"))
      (.appendChild (.-documentElement js/document) d)
      (js/setTimeout #(.remove d) 400))))

(defn simulate-click!
  "Mouse events Vimium sends on a hint: over, down, up, click. MODS may hold
  :ctrl/:shift/:meta to open links in a new tab the browser's way."
  ([el] (simulate-click! el {}))
  ([^js el {:keys [ctrl shift meta]}]
   (doseq [t ["mouseover" "mousedown" "mouseup" "click"]]
     (.dispatchEvent el (js/MouseEvent. t #js {:bubbles true :cancelable true :composed true :view js/window
                                              :ctrlKey (boolean ctrl) :shiftKey (boolean shift) :metaKey (boolean meta)
                                              :detail 1 :button 0})))))

