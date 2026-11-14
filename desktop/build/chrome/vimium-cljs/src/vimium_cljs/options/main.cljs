;; The options page: edit settings, import a Vimium options export, export.
(ns vimium-cljs.options.main
  (:require [clojure.string :as str]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.keys :as keys]
            [vimium-cljs.settings :as settings]))

(defn- $ ^js [sel] (.querySelector js/document sel))

(defn- el
  ([tag cls] (el tag cls nil))
  ([tag cls text]
   (let [^js e (.createElement js/document tag)]
     (when cls (set! (.-className e) cls))
     (when text (set! (.-textContent e) text))
     e)))

(defonce stored (atom {}))

;; field id -> [setting kind]
(def fields
  [["keyMappings" :text] ["linkHintCharacters" :string] ["scrollStepSize" :number]
   ["smoothScroll" :bool] ["grabBackFocus" :bool] ["regexFindMode" :bool] ["ignoreKeyboardLayout" :bool]
   ["hideHud" :bool] ["searchUrl" :string] ["searchEngines" :text] ["newTabDestination" :string]
   ["newTabCustomUrl" :string] ["previousPatterns" :string] ["nextPatterns" :string]
   ["userDefinedLinkHintCss" :text]])

(defn- rules->text [rules]
  (str/join "\n" (map (fn [r] (str/trim (str (get r "pattern") " " (get r "passKeys")))) rules)))

(defn- text->rules [t]
  (vec (keep (fn [line]
               (let [[pat & pass] (str/split (str/trim line) #"\s+")]
                 (when (seq pat) {"pattern" pat "passKeys" (str/join " " pass)})))
             (str/split-lines t))))

(defn- fill! [s]
  (let [m (settings/merged s)]
    (doseq [[k kind] fields
            :let [^js input ($ (str "#" k))]
            :when input]
      (if (= kind :bool)
        (set! (.-checked input) (boolean (get m k)))
        (set! (.-value input) (str (get m k)))))
    (set! (.-value ($ "#exclusionRules")) (rules->text (get m "exclusionRules")))))

(defn- read-form []
  (into {"exclusionRules" (text->rules (.-value ($ "#exclusionRules")))}
        (for [[k kind] fields :let [^js input ($ (str "#" k))] :when input]
          [k (case kind
               :bool (.-checked input)
               :number (let [n (js/parseFloat (.-value input))] (if (js/isNaN n) (settings/defaults k) n))
               (.-value input))])))

(defn- only-changes
  "Keeps values that differ from the defaults, so new defaults reach the user."
  [s]
  (into {} (remove (fn [[k v]] (= v (settings/defaults k)))) s))

(defn- render-summary! [s]
  (let [{:keys [mapped unmapped errors exclusions hint-chars engines]} (settings/summary s)
        ^js box ($ "#summary")
        li (fn [^js ul t] (.appendChild ul (el "li" nil t)))]
    (set! (.-textContent box) "")
    (let [^js ul (el "ul" nil)]
      (doseq [{:keys [key command desc]} mapped] (li ul (str key "  →  " command (when desc (str " (" desc ")")))))
      (doseq [k unmapped] (li ul (str "unmap " k)))
      (when (and (empty? mapped) (empty? unmapped)) (li ul "Vimium's default keys, unchanged"))
      (.append box (el "h3" nil "Your key mappings") ul))
    (when (seq errors)
      (let [^js ul (el "ul" "errors" nil)]
        (doseq [e errors] (li ul e))
        (.append box (el "h3" nil "Mapping errors") ul)))
    (.append box (el "p" nil (str "Link hint characters: " hint-chars)))
    (.append box (el "p" nil (str "Excluded: " (if (empty? exclusions) "none" (str/join ", " (map #(get % "pattern") exclusions))))))
    (.append box (el "p" nil (str "Search engines: " (if (empty? engines) "none" (str/join ", " (map (fn [[k v]] (str k " (" (:name v) ")")) engines))))))))

(defn- status! [t] (set! (.-textContent ($ "#status")) t) (js/setTimeout #(set! (.-textContent ($ "#status")) "") 3000))

(defn- save! [s]
  (let [s (only-changes s)]
    (-> (js/chrome.storage.local.set (clj->js {"settings" s}))
        (.then (fn [] (reset! stored s) (render-summary! s) (status! "Saved."))))))

(defn- import! [text]
  (let [{:keys [settings notes errors]} (settings/import-vimium text)
        ^js out ($ "#import-report")]
    (set! (.-textContent out) "")
    (let [^js ul (el "ul" nil)]
      (doseq [k (sort (keys settings))] (.appendChild ul (el "li" nil (str "imported " k))))
      (doseq [n notes] (.appendChild ul (el "li" nil n)))
      (doseq [e errors] (.appendChild ul (el "li" "errors" e)))
      (.appendChild out ul))
    (when (seq settings)
      (let [s (merge @stored settings)]
        (fill! s)
        (save! s)))))

(defn- export! []
  (let [blob (js/Blob. #js [(js/JSON.stringify (clj->js (settings/merged @stored)) nil 2)] #js {:type "application/json"})
        ^js a (el "a" nil)]
    (set! (.-href a) (js/URL.createObjectURL blob))
    (set! (.-download a) "vimium-cljs-options.json")
    (.click a)))

(defn- check-mappings! []
  (let [errs (:errors (keys/parse-mappings (.-value ($ "#keyMappings"))))]
    (set! (.-textContent ($ "#mapping-errors")) (str/join "\n" errs))))

(defn init []
  (let [^js ul ($ "#left-out")]
    (doseq [[c why] commands/left-out] (.appendChild ul (el "li" nil (str c ": " why)))))
  (-> (js/chrome.storage.local.get "settings")
      (.then (fn [^js r]
               (let [s (js->clj (or (.-settings r) #js {}))]
                 (reset! stored s)
                 (fill! s)
                 (render-summary! s)
                 (check-mappings!)))))
  (.addEventListener ($ "#keyMappings") "input" check-mappings!)
  (.addEventListener ($ "#save") "click" (fn [_] (save! (read-form))))
  (.addEventListener ($ "#export") "click" export!)
  (.addEventListener ($ "#import-file") "change"
                     (fn [^js e]
                       (when-let [^js f (aget (.. e -target -files) 0)]
                         (-> (.text f) (.then import!)))))
  (.addEventListener ($ "#import-paste") "click" (fn [_] (import! (.-value ($ "#import-text")))))
  (.addEventListener ($ "#reset") "click" (fn [_] (when (js/confirm "Reset every option to its default?")
                                                     (fill! {}) (save! {})))))
