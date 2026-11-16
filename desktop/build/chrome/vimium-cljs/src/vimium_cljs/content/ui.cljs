;; Hint markers keep Vimium's class names so userDefinedLinkHintCss from a Vimium export still applies.
(ns vimium-cljs.content.ui
  (:require [clojure.string :as str]))

(def base-css "
:host { all: initial; }
.hud { position: fixed; right: 12px; bottom: 8px; z-index: 2147483647; max-width: 60vw;
  font: 13px/1.4 system-ui, sans-serif; color: #222; background: #fff; border: 1px solid #bbb;
  border-radius: 4px; padding: 3px 8px; box-shadow: 0 2px 8px rgba(0,0,0,.2); }
.hud.find { left: 12px; right: auto; display: flex; gap: 6px; align-items: center; }
.hud input { all: unset; min-width: 16em; font: 13px monospace; color: #000; }
.hud .count { color: #777; }
.markers { position: fixed; inset: 0; z-index: 2147483647; pointer-events: none; }
.vimiumHintMarker { position: fixed; padding: 0 3px; border-radius: 3px; white-space: nowrap; overflow: hidden;
  background: linear-gradient(#fff785, #ffc542); border: 1px solid #e3be23;
  box-shadow: 0 1px 3px rgba(0,0,0,.3); font: bold 12px/1.25 Helvetica, Arial, sans-serif; color: #302505; }
.vimiumHintMarker span { color: #302505; text-transform: uppercase; }
.vimiumHintMarker .matchingCharacter { color: #d4ac3a; }
.vimiumHintMarker.hidden { display: none; }
iframe.ui { position: fixed; z-index: 2147483647; border: 0; background: transparent; color-scheme: normal; }
iframe.vomnibar { left: 50%; top: 54px; width: min(964px, calc(80vw + 64px)); height: 600px; transform: translateX(-50%); }
iframe.help { left: 50%; top: 4vh; width: 90vw; max-width: 1000px; height: 92vh; transform: translateX(-50%); }
.mode { position: fixed; left: 8px; bottom: 8px; z-index: 2147483646; pointer-events: none; user-select: none;
  font: 600 11px/18px system-ui, -apple-system, 'Segoe UI', sans-serif; letter-spacing: .04em; padding: 0 7px;
  border-radius: 6px; background: #f1f3f4; color: var(--c, #6b6f76); border: 1px solid rgba(0,0,0,.08);
  box-shadow: 0 1px 4px rgba(0,0,0,.10); opacity: .92; transition: opacity .15s; }
.mode::before { content: ''; display: inline-block; width: 6px; height: 6px; border-radius: 50%;
  background: currentColor; margin-right: 5px; vertical-align: 1px; }
.mode.normal { opacity: .8; }
.mode.insert { --c: #188038; } .mode.hints { --c: #b06000; } .mode.visual, .mode.visual-line { --c: #8430ce; }
.mode.vomnibar { --c: #1a66d6; } .mode.pass, .mode.mark { --c: #c5221f; }
.mode.off, .mode.find { display: none; }
.mode.near { opacity: 0; }
.mode.right { left: auto; right: 8px; }
@media (prefers-color-scheme: dark) {
  .mode { background: #303134; border-color: rgba(255,255,255,.13); color: var(--c, #9aa0a6); }
  .mode.insert { --c: #81c995; } .mode.hints { --c: #fdd663; } .mode.visual, .mode.visual-line { --c: #c58af9; }
  .mode.vomnibar { --c: #8ab4f8; } .mode.pass, .mode.mark { --c: #f28b82; }
}
")

(defonce ^:private state (atom {}))

(defn host
  "The shadow host element (created on first use)."
  []
  (:host @state))

(defn root ^js []
  (or (:root @state)
      (let [^js h (.createElement js/document "vimium-cljs-ui")
            ^js r (.attachShadow h #js {:mode "closed"})
            ^js style (.createElement js/document "style")]
        (set! (.-textContent style) base-css)
        (.appendChild r style)
        (let [^js user (.createElement js/document "style")]
          (.appendChild r user)
          (swap! state assoc :user-style user))
        (swap! state assoc :host h :root r)
        r)))

(defn attach! []
  (let [r (root) h (:host @state)]
    (when-not (.-isConnected h)
      (.appendChild (.-documentElement js/document) h))
    r))

(defn set-user-css! [css]
  (root)
  (set! (.-textContent ^js (:user-style @state)) (or css "")))

(defn el
  ([tag cls] (el tag cls nil))
  ([tag cls text]
   (let [^js e (.createElement js/document tag)]
     (when cls (set! (.-className e) cls))
     (when text (set! (.-textContent e) text))
     e)))

(defn hide-hud! []
  (when-let [^js h (:hud @state)] (.remove h))
  (when-let [t (:hud-timer @state)] (js/clearTimeout t))
  (swap! state dissoc :hud :hud-timer))

(defn hud!
  "Shows TEXT at the bottom right; for MS milliseconds when given. An open
  find bar keeps its place."
  ([text] (hud! text nil))
  ([text ms]
   (when-not (some-> ^js (:hud @state) .-classList (.contains "find"))
     (hide-hud!)
     (let [^js h (el "div" "hud" text)]
       ;; beside the mode badge when that sits in the bottom right corner
       (when-let [^js b (:mode @state)]
         (when (.contains (.-classList b) "right")
           (set! (.. h -style -right) (str (+ 16 (.-width (.getBoundingClientRect b))) "px"))))
       (.appendChild (attach!) h)
       (swap! state assoc :hud h
              :hud-timer (when ms (js/setTimeout hide-hud! ms)))))))

(defn find-bar!
  "A find bar with a text input. Calls ON-INPUT with the text, ON-DONE with
  :enter or :escape. Returns {:input :count} elements."
  [initial on-input on-done]
  (hide-hud!)
  (let [^js bar (el "div" "hud find")
        ^js input (el "input" nil)
        ^js cnt (el "span" "count")]
    (.append bar (el "span" nil "/") input cnt)
    (set! (.-value input) (or initial ""))
    (.addEventListener input "input" (fn [_] (on-input (.-value input))))
    (.addEventListener input "keydown"
                       (fn [^js e]
                         (.stopPropagation e)
                         (cond (or (= "Escape" (.-key e)) (and (.-ctrlKey e) (= "[" (.-key e))))
                               (do (.preventDefault e) (on-done :escape (.-value input)))
                               (= "Enter" (.-key e)) (do (.preventDefault e) (on-done :enter (.-value input)))
                               (and (= "Backspace" (.-key e)) (= "" (.-value input)))
                               (do (.preventDefault e) (on-done :escape "")))))
    (doseq [t ["keyup" "keypress"]]
      (.addEventListener input t (fn [^js e] (.stopPropagation e))))
    (.appendChild (attach!) bar)
    (swap! state assoc :hud bar)
    (.focus input)
    {:input input :count cnt}))

(defn show-markers!
  "Draws markers for [[hint rect] ...]; returns their elements."
  [pairs]
  (when-let [^js old (:markers @state)] (.remove old))
  (let [^js layer (el "div" "markers")
        els (mapv (fn [[hint ^js r]]
                    (let [^js m (el "div" "vimiumHintMarker")]
                      (set! (.. m -style -left) (str (.-left r) "px"))
                      (set! (.. m -style -top) (str (.-top r) "px"))
                      (doseq [c hint] (.appendChild m (el "span" nil (str c))))
                      (.appendChild layer m)
                      m))
                  pairs)]
    (.appendChild (attach!) layer)
    (swap! state assoc :markers layer)
    els))

(defn filter-markers!
  "Hides markers whose hint does not start with TYPED; marks matched characters."
  [els hints typed]
  (doseq [[^js m h] (map vector els hints)]
    (if (str/starts-with? h typed)
      (do (.remove (.-classList m) "hidden")
          (doseq [[i ^js s] (map-indexed vector (array-seq (.-children m)))]
            (set! (.-className s) (if (< i (count typed)) "matchingCharacter" ""))))
      (.add (.-classList m) "hidden"))))

(defn hide-markers! []
  (when-let [^js old (:markers @state)] (.remove old))
  (swap! state dissoc :markers))

(defn close-frame!
  "Removes the framed page and gives focus back to the element that had it, unless REFOCUS is
  false (the focus already went somewhere else)."
  ([] (close-frame! true))
  ([refocus]
   (when-let [^js f (:frame @state)]
     (let [^js back (:focus-back @state)]
       (.remove f)
       (swap! state dissoc :frame :frame-kind :focus-back :typed)
       (when refocus
         (.focus js/window)
         (when (and back (.-isConnected back) (not= back (.-body js/document)))
           (.focus back #js {:preventScroll true})))))))

(defn frame-kind [] (:frame-kind @state))

(defn open-frame!
  "Shows the extension page ui.html#KIND?QUERY in a frame over the page."
  [kind query]
  (close-frame!)
  (swap! state assoc :focus-back (.-activeElement js/document))
  (let [^js f (el "iframe" (str "ui " kind))]
    (set! (.-src f) (str (js/chrome.runtime.getURL "ui.html") "#" kind (when (seq query) (str "?" query))))
    (.setAttribute f "allow" "clipboard-write")
    ;; The page inside focuses its own input; refocusing the frame would drop keys typed meanwhile.
    (.addEventListener f "load" (fn [_] (when-not (identical? f (.-activeElement js/document)) (.focus f))))
    (.appendChild (attach!) f)
    (swap! state assoc :frame f :frame-kind kind :typed {:text "" :enter false})
    f))

(def ^:private mode-labels
  {"normal" "NORMAL" "insert" "INSERT" "hints" "HINTS" "visual" "VISUAL" "visual-line" "VISUAL LINE"
   "vomnibar" "VOMNIBAR" "find" "FIND" "pass" "PASS KEY" "mark" "MARK" "off" ""})

(def ^:private clickable
  "What the badge should not sit on."
  "a, button, input, select, textarea, [role=button], [role=link], [role=tab], [role=menuitem], [onclick], [contenteditable=true]")

(defn- covers-clickable?
  "Whether badge B, where it is now, lies over something clickable (sampled at its corners and
  middle; the badge itself takes no pointer, so the page answers)."
  [^js b]
  (let [^js r (.getBoundingClientRect b)]
    (and (pos? (.-width r))
         (boolean (some (fn [[x y]] (some-> (.elementFromPoint js/document x y) (.closest clickable)))
                        [[(+ (.-left r) 2) (+ (.-top r) 2)] [(- (.-right r) 2) (+ (.-top r) 2)]
                         [(+ (.-left r) 2) (- (.-bottom r) 2)] [(- (.-right r) 2) (- (.-bottom r) 2)]
                         [(/ (+ (.-left r) (.-right r)) 2) (/ (+ (.-top r) (.-bottom r)) 2)]])))))

(defn- place-badge!
  "Keeps badge B in its bottom corner (the left one at first) while nothing clickable is under
  it there, else moves it to the other corner if that one is clear (from the Sheets add-sheet
  button, say)."
  [^js b]
  (when (covers-clickable? b)
    (let [cl (.-classList b) right? (.contains cl "right")]
      (.toggle cl "right" (not right?))
      (if (covers-clickable? b)
        (.toggle cl "right" right?)
        (swap! state assoc :right (not right?))))))

(defn indicator!
  "Shows the mode badge in a bottom corner (see dispatch/indicator for MODE, or \"vomnibar\").
  It takes no clicks, keeps off what is clickable where it can and fades while the pointer is
  near, so it never hides what is under it. An open find bar stands in for it."
  [mode]
  (let [^js b (or (:mode @state)
                  (let [^js b (el "div" "mode")]
                    (.setAttribute b "aria-hidden" "true")
                    (swap! state assoc :mode b)
                    b))]
    (when-not (.-isConnected b) (.appendChild (attach!) b))
    (when (not= mode (:shown @state))
      (swap! state assoc :shown mode)
      (set! (.-className b) (str "mode " mode (when (:near @state) " near") (when (:right @state) " right")))
      (set! (.-textContent b) (get mode-labels mode mode)))
    ;; the page can grow a button under it after it was placed (Sheets draws its bar late), so
    ;; look again on later draws too, at most once a second
    (let [now (js/Date.now)]
      (when (> now (+ 1000 (or (:placed @state) 0)))
        (swap! state assoc :placed now :mode-size nil)
        (place-badge! b)))))

(defn pointer-at!
  "Fades the badge while the pointer at X, Y (viewport pixels) is within 48px of it. It sits
  8px from a bottom corner; its size and corner are measured once per mode, not on every move."
  [x y]
  (when-let [^js b (:mode @state)]
    (let [[w h right] (or (:mode-size @state)
                          (let [^js r (.getBoundingClientRect b)
                                s [(.-width r) (.-height r) (.contains (.-classList b) "right")]]
                            (swap! state assoc :mode-size s)
                            s))
          bottom (- (.-innerHeight js/window) 8)
          vw (.-innerWidth js/window)
          near (and (pos? w)
                    (if right (> x (- vw 8 w 48)) (< x (+ 8 w 48)))
                    (> y (- bottom h 48)))]
      (when (not= near (boolean (:near @state)))
        (swap! state assoc :near near)
        (.toggle (.-classList b) "near" near)))))

(defn watch-frame!
  "Calls F with the kind of the frame over the page (or nil) whenever it changes."
  [f]
  (add-watch state ::frame (fn [_ _ o n] (when (not= (:frame-kind o) (:frame-kind n)) (f (:frame-kind n))))))

;; Keys typed before the Vomnibar frame loads reach the page; keep them (:typed) and hand them over when it is ready.

(defn typing-ahead?
  "Whether keys should be kept for a frame that is still loading."
  []
  (boolean (and (:typed @state) (= "vomnibar" (:frame-kind @state)))))

(defn type-ahead!
  "Keeps KEY (a character, Backspace or Enter) for the loading frame."
  [key]
  (swap! state update :typed
         (fn [{:keys [text] :as t}]
           (case key
             "Backspace" (assoc t :text (subs text 0 (max 0 (dec (count text)))))
             "Enter" (assoc t :enter true)
             (if (:enter t) t (assoc t :text (str text key)))))))

(defn more-keys!
  "Keys typed in a child frame while it asked the top page for the Vomnibar: they came before
  anything typed here, so they go first, into what is kept or straight to the frame."
  [text enter]
  (if (:typed @state)
    (swap! state update :typed (fn [t] (-> t (update :text #(str text %)) (update :enter #(or % enter)))))
    (when-let [^js w (some-> ^js (:frame @state) .-contentWindow)]
      (.postMessage w #js {:type "vimium-cljs:keys" :text text :enter enter} "*"))))

(defn frame-ready!
  "The frame has loaded: give it the keys kept for it and stop keeping them."
  []
  (when-let [{:keys [text enter]} (:typed @state)]
    (swap! state dissoc :typed)
    (when-let [^js w (some-> ^js (:frame @state) .-contentWindow)]
      (.postMessage w #js {:type "vimium-cljs:keys" :text text :enter enter} "*"))))

(defn frame-window [] (some-> ^js (:frame @state) .-contentWindow))
