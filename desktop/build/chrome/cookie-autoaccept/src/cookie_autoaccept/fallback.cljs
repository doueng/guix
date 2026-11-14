(ns cookie-autoaccept.fallback
  "Generic fallback for banners autoconsent did not handle, in light and open shadow DOM and in small consent frames.
  Accept mode clicks the banner's accept button when it has one; otherwise (and in reject mode) it hides the banner.
  It asks the service worker first, and stops watching for late banners after 30 s."
  (:require [cookie-autoaccept.fallback-core :as core]))

(def ^:private retries-ms #js [3000 7000 12000])
(def ^:private watch-ms 30000)
(def ^:private debounce-ms 700)
(def ^:private max-walk 10000)
(def ^:private hint (str "[id*=cookie i],[class*=cookie i],[id*=consent i],[class*=consent i],[id*=gdpr i],[class*=gdpr i],"
                         "[role=dialog],[role=alertdialog],[aria-modal=true],dialog[open]"))
(def ^:private controls-sel "button,[role=button],input[type=button],input[type=submit],a")
(def ^:private fields-sel (str "textarea,input:not([type]),input[type=text],input[type=email],input[type=password],"
                               "input[type=tel],input[type=search],input[type=number]"))
(def ^:private done-attr "data-cookie-autoaccept")
(def ^:private in-frame (not (identical? js/window.top js/window)))

(defn- push-all [^js to from] (.forEach from (fn [x] (.push to x))) to)

(defn- label
  "c.innerText || c.value || c.getAttribute('aria-label') || ''"
  [^js c]
  (let [t (.-innerText c) v (.-value c) a (.getAttribute c "aria-label")]
    (cond (js/Boolean t) t (js/Boolean v) v (js/Boolean a) a :else "")))

(defn- shadow-roots
  "The open shadow roots under root, depth first, looking at no more than max-walk elements per tree."
  [root]
  (let [found #js []
        walker (js/document.createTreeWalker root js/NodeFilter.SHOW_ELEMENT)]
    (loop [n 0 ^js el (.-currentNode walker)]
      (when (and el (< n max-walk))
        (when-let [sr (.-shadowRoot el)]
          (.push found sr)
          (push-all found (shadow-roots sr)))
        (recur (inc n) (.nextNode walker))))
    found))

(defn- parent-across [^js el]
  (or (.-parentElement el)
      (let [r (.getRootNode el)] (when (instance? js/ShadowRoot r) (.-host r)))))

(defn- page-edge? [n] (or (identical? n js/document.body) (identical? n js/document.documentElement)))

(defn- overlay-root
  "The outermost fixed or sticky ancestor (crossing shadow hosts), else a dialog-like element itself."
  [^js el]
  (let [best (loop [^js n el best nil]
               (if (and n (not (page-edge? n)))
                 (let [pos (.-position (js/getComputedStyle n))]
                   (recur (parent-across n) (if (or (identical? pos "fixed") (identical? pos "sticky")) n best)))
                 best))]
    (or best (when (.matches el "[role=dialog],[role=alertdialog],[aria-modal=true],dialog[open]") el))))

(defn- visible? [^js el]
  (let [r (.getBoundingClientRect el)]
    (and (> (.-width r) 0) (> (.-height r) 0) (not (identical? "hidden" (.-visibility (js/getComputedStyle el)))))))

(defn- contents
  "Text, visible controls, their labels and the number of visible text fields of a banner, looking into open shadow
  roots below it."
  [^js el]
  (let [text #js [(or (.-innerText el) "")]
        controls (js/Array.from (.querySelectorAll el controls-sel))
        fields (js/Array.from (.querySelectorAll el fields-sel))
        roots (if-let [sr (.-shadowRoot el)] (push-all #js [sr] (shadow-roots sr)) (shadow-roots el))]
    (.forEach roots (fn [^js sr]
                      (.forEach (js/Array.from (.-children sr)) (fn [^js child] (.push text " " (or (.-innerText child) ""))))
                      (push-all controls (.querySelectorAll sr controls-sel))
                      (push-all fields (.querySelectorAll sr fields-sel))))
    (let [shown (.filter controls visible?)]
      #js {:text (.slice (.join text "") 0 4000)
           :controls shown
           :labels (.map shown label)
           :fields (.-length (.filter fields visible?))})))

(defn- unlock-scroll []
  (.forEach #js [js/document.documentElement js/document.body]
            (fn [^js el]
              (when (and el (identical? "hidden" (.-overflow (js/getComputedStyle el))))
                (.setProperty (.-style el) "overflow" "auto" "important")))))

(defn- act
  "One banner: returns click, hide or nil."
  [^js root mode page-text]
  (let [^js c (contents root)
        text (.-text c)
        ^js controls (.-controls c)
        page-share (if page-text (/ (.-length text) (js/Math.max (page-text) 1)) 0)]
    (when (core/consent-banner? text true (.-length controls) (.-fields c) page-share)
      (let [accept-index (core/pick-accept-button (.-labels c))
            action (core/fallback-action mode accept-index (and (identical? root js/document.body) in-frame))]
        (.setAttribute root done-attr action)
        (case action
          "click" (.click (aget controls accept-index))
          "hide" (.setProperty (.-style root) "display" "none" "important")
          nil)
        (when-not (identical? action "none") action)))))

(defn- hide-backdrops
  "A modal's dimmed backdrop is often a separate element, and would still block the page once its banner is hidden."
  []
  (loop [i 0]
    (when (< i 3)
      (let [w js/innerWidth h js/innerHeight
            found (loop [^js n (js/document.elementFromPoint (/ w 2) (/ h 2)) found nil]
                    (if (and n (not (page-edge? n)))
                      (recur (parent-across n)
                             (if (identical? "fixed" (.-position (js/getComputedStyle n)))
                               (let [r (.getBoundingClientRect n)
                                     media (or (.test #"^(IFRAME|VIDEO|CANVAS|IMG|svg)$" (.-tagName n))
                                               (some? (.querySelector n "iframe,video,canvas,img,svg")))]
                                 (if (core/backdrop? (/ (* (.-width r) (.-height r)) (* w h)) (.-innerText n) media) n found))
                               found))
                      found))]
        (when found
          (.setAttribute found done-attr "backdrop")
          (.setProperty (.-style found) "display" "none" "important")
          (recur (inc i)))))))

(defn- scan
  "Looks at the page once in mode; returns #js [clicked hidden]."
  [mode]
  (let [tally #js [0 0]
        count! (fn [a] (case a "click" (aset tally 0 (inc (aget tally 0))) "hide" (aset tally 1 (inc (aget tally 1))) nil))
        body js/document.body]
    (if in-frame
      ;; A small frame that is all banner, such as a CMP message iframe.
      (when (and body (not (.hasAttribute body done-attr)) (visible? body))
        (count! (act body mode nil)))
      (let [roots (push-all #js [js/document] (shadow-roots js/document.documentElement))
            candidates (js/Set.)
            seen (js/Set.)
            body-length #js [nil]
            page-text (fn []
                        (when (nil? (aget body-length 0))
                          (aset body-length 0 (.-length (or (some-> js/document.body .-innerText) ""))))
                        (aget body-length 0))]
        (.forEach roots (fn [^js r] (.forEach (.querySelectorAll r hint) (fn [el] (.add candidates el)))))
        (when body (.forEach (js/Array.from (.-children body)) (fn [el] (.add candidates el))))
        (.forEach candidates
                  (fn [c]
                    (let [^js root (overlay-root c)]
                      (when (and root (not (.has seen root)) (not (.hasAttribute root done-attr)) (visible? root))
                        (.add seen root)
                        (count! (act root mode page-text))))))
        (when (pos? (aget tally 1)) (hide-backdrops))
        (when (or (pos? (aget tally 0)) (pos? (aget tally 1))) (unlock-scroll))))
    tally))

(defn- send! [msg] (-> (js/chrome.runtime.sendMessage msg) (.catch (fn [_]))))

(defn- run-once []
  (-> (js/chrome.runtime.sendMessage #js {:type "fallbackQuery"})
      (.catch (fn [_] nil))
      (.then (fn [^js answer]
               (when (and answer (.-run answer))
                 (let [tally (scan (.-mode answer))]
                   (when (pos? (aget tally 0)) (send! #js {:type "fallbackClicked" :count (aget tally 0)}))
                   (when (pos? (aget tally 1)) (send! #js {:type "fallbackHidden" :count (aget tally 1)}))
                   ;; The page was looked at after the rule's result, so the popup may now repeat the rule's claim.
                   (when-not in-frame
                     (send! #js {:type "fallbackScanned" :afterResult (js/Boolean (.-afterResult answer))}))))))))

(defn init []
  (let [timer #js [0]
        schedule (fn []
                   (js/clearTimeout (aget timer 0))
                   (aset timer 0 (js/setTimeout run-once debounce-ms)))
        observer (js/MutationObserver. schedule)]
    (.forEach retries-ms (fn [ms] (js/setTimeout run-once ms)))
    (js/setTimeout (fn []
                     (when js/document.documentElement
                       (.observe observer js/document.documentElement #js {:childList true :subtree true})))
                   (aget retries-ms 0))
    (js/setTimeout (fn []
                     (.disconnect observer)
                     (js/clearTimeout (aget timer 0)))
                   watch-ms)))
