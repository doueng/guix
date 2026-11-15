(ns vimium-cljs.panel.main)

(defn- el [tag cls & kids]
  (let [e (js/document.createElement tag)]
    (when cls (set! (.-className e) cls))
    (doseq [k kids] (.append e k))
    e))

(defn- row [^js t]
  (let [icon (el "img" "icon")
        title (el "span" "title" (or (not-empty (.-title t)) (.-url t) ""))
        close (el "button" "close" "×")
        r (el "div" (str "tab" (when (.-active t) " active") (when (.-pinned t) " pinned")
                       (when (.-discarded t) " discarded"))
              icon title close)]
    (set! (.-src icon) (or (not-empty (.-favIconUrl t)) "icons/icon128.png"))
    (set! (.-title r) (str (.-title t) "\n" (.-url t)))
    (set! (.-onclick close) (fn [e] (.stopPropagation e) (js/chrome.tabs.remove (.-id t))))
    (set! (.-onclick r) (fn [_] (js/chrome.tabs.update (.-id t) #js {:active true})))
    (set! (.-onauxclick r) (fn [^js e] (when (= 1 (.-button e)) (js/chrome.tabs.remove (.-id t)))))
    r))

(defonce window-id (atom nil))

(defn- render! []
  (when-let [w @window-id]
    (-> (js/chrome.tabs.query #js {:windowId w})
        (.then (fn [tabs]
                 (let [list (js/document.getElementById "tabs")]
                   (.replaceChildren list)
                   (doseq [t (sort-by (fn [^js t] (.-index t)) tabs)] (.append list (row t)))
                   (some-> (.querySelector list ".active") (.scrollIntoView #js {:block "nearest"}))))))))

(defn init []
  (-> (js/chrome.windows.getCurrent)
      (.then (fn [^js w] (reset! window-id (.-id w)) (render!))))
  (doseq [ev ["onCreated" "onRemoved" "onUpdated" "onActivated" "onMoved" "onAttached" "onDetached" "onReplaced"]]
    (.addListener (unchecked-get js/chrome.tabs ev) (fn [& _] (render!)))))
