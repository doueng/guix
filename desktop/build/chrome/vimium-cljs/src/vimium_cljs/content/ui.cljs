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
  "Removes the framed page and gives focus back to the element that had it."
  []
  (when-let [^js f (:frame @state)]
    (let [^js back (:focus-back @state)]
      (.remove f)
      (swap! state dissoc :frame :frame-kind :focus-back :typed)
      (.focus js/window)
      (when (and back (.-isConnected back) (not= back (.-body js/document)))
        (.focus back #js {:preventScroll true})))))

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
