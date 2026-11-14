;; The MV3 service worker: tab commands, the link-hint coordinator that spans
;; frames, tab recency, global marks, the clipboard (through an offscreen
;; document) and Vomnibar queries. After Vimium's background_scripts (MIT).
;; State that must survive the worker sleeping lives in chrome.storage.session.
(ns vimium-cljs.bg.main
  (:require [clojure.string :as str]
            [vimium-cljs.hints :as hints]
            [vimium-cljs.rank :as rank]
            [vimium-cljs.settings :as settings]))

(defn- p [x] (js/Promise.resolve x))

(defn get-settings []
  (-> (js/chrome.storage.local.get "settings")
      (.then (fn [^js r] (settings/merged (js->clj (or (.-settings r) #js {})))))))

(defn- to-frame [tab-id frame-id msg]
  (-> (js/chrome.tabs.sendMessage tab-id (clj->js msg) #js {:frameId frame-id})
      (.catch (fn [_] nil))))

(defn- frame-ids [tab-id]
  (-> (js/chrome.webNavigation.getAllFrames #js {:tabId tab-id})
      (.then (fn [frames]
               (let [ids (map (fn [^js f] (.-frameId f)) (or frames #js []))]
                 (vec (cons 0 (sort (remove zero? ids)))))))
      (.catch (fn [_] [0]))))

(defn- window-tabs [^js tab]
  (-> (js/chrome.tabs.query #js {:windowId (.-windowId tab)})
      (.then (fn [tabs] (vec (sort-by (fn [^js t] (.-index t)) tabs))))))

(defn- activate-tab! [^js t]
  (js/chrome.tabs.update (.-id t) #js {:active true})
  (js/chrome.windows.update (.-windowId t) #js {:focused true}))

;; ---- tab recency ---------------------------------------------------------------

(defn- recency [] (-> (js/chrome.storage.session.get "recency")
                      (.then (fn [^js r] (vec (or (some-> (.-recency r) js->clj) []))))))

(defn- note-active! [tab-id]
  (-> (recency)
      (.then (fn [ids] (js/chrome.storage.session.set
                        #js {:recency (clj->js (vec (take 200 (cons tab-id (remove #{tab-id} ids)))))})))))

;; ---- clipboard -------------------------------------------------------------------

(defn- ensure-offscreen []
  (-> (js/chrome.offscreen.hasDocument)
      (.then (fn [has]
               (when-not has
                 (js/chrome.offscreen.createDocument
                  #js {:url "offscreen.html" :reasons #js ["CLIPBOARD"]
                       :justification "Copy and paste for the yank and paste commands"}))))))

(defn clipboard-write [text]
  (-> (ensure-offscreen)
      (.then #(js/chrome.runtime.sendMessage #js {:target "offscreen" :type "copy" :text text}))
      (.catch (fn [e] (js/console.warn "vimium-cljs: copy failed" e)))))

(defn clipboard-read []
  (-> (ensure-offscreen)
      (.then #(js/chrome.runtime.sendMessage #js {:target "offscreen" :type "paste"}))
      (.then (fn [t] (str/trim (or t ""))))))

;; ---- opening URLs ------------------------------------------------------------------

(defn open-url! [^js tab url {:keys [new-tab active]}]
  (cond
    (str/starts-with? (str/lower-case (str/trim url)) "javascript:") nil
    new-tab (js/chrome.tabs.create #js {:url url :active (boolean active) :index (inc (.-index tab)) :openerTabId (.-id tab)})
    :else (js/chrome.tabs.update (.-id tab) #js {:url url})))

(defn- typed->url [text]
  (-> (get-settings)
      (.then (fn [s] (rank/->url text (settings/search-engines (get s "searchEngines")) (get s "searchUrl"))))))

;; ---- commands --------------------------------------------------------------------

(def ^:private zoom-levels [0.25 0.33 0.5 0.67 0.75 0.8 0.9 1 1.1 1.25 1.5 1.75 2 2.5 3 4 5])

(defn- zoom! [^js tab dir n]
  (-> (js/chrome.tabs.getZoom (.-id tab))
      (.then (fn [z]
               (let [i (or (first (keep-indexed (fn [i l] (when (>= l (- z 0.001)) i)) zoom-levels)) (dec (count zoom-levels)))
                     i (if (and (pos? dir) (> (nth zoom-levels i) (+ z 0.001))) (dec i) i)
                     j (max 0 (min (dec (count zoom-levels)) (+ i (* dir n))))]
                 (js/chrome.tabs.setZoom (.-id tab) (nth zoom-levels j)))))))

(defn- select-relative! [^js tab delta]
  (-> (window-tabs tab)
      (.then (fn [tabs]
               (let [n (count tabs)
                     i (.indexOf (clj->js (map (fn [^js t] (.-id t)) tabs)) (.-id tab))]
                 (activate-tab! (nth tabs (mod (+ i delta) n))))))))

(defn- close-where! [^js tab pred]
  (-> (window-tabs tab)
      (.then (fn [tabs]
               (let [ids (keep (fn [^js t] (when (and (not (.-pinned t)) (pred t)) (.-id t))) tabs)]
                 (when (seq ids) (js/chrome.tabs.remove (clj->js ids))))))))

(defn- next-frame! [^js tab from-frame n]
  (-> (frame-ids (.-id tab))
      (.then (fn [ids]
               (js/Promise.all (clj->js (map (fn [id] (-> (to-frame (.-id tab) id {:type "ping"}) (.then (fn [ok] (when ok id))))) ids)))))
      (.then (fn [live]
               (let [live (vec (remove nil? live))
                     i (max 0 (.indexOf (clj->js live) from-frame))]
                 (to-frame (.-id tab) (nth live (mod (+ i n) (count live))) {:type "focus-frame"}))))))

(defn run-command! [name ^js tab frame-id n explicit options]
  (let [id (.-id tab)]
    (case name
      "reload" (js/chrome.tabs.reload id #js {:bypassCache (boolean (get options "hard"))})
      "createTab" (-> (get-settings)
                      (.then (fn [s]
                               (let [url (when (= "customUrl" (get s "newTabDestination"))
                                           (not-empty (get s "newTabCustomUrl")))]
                                 (dotimes [i n]
                                   (js/chrome.tabs.create (clj->js (cond-> {:index (+ (.-index tab) 1 i)} url (assoc :url url)))))))))
      "nextTab" (select-relative! tab n)
      "previousTab" (select-relative! tab (- n))
      "firstTab" (-> (window-tabs tab) (.then (fn [ts] (activate-tab! (nth ts (min (dec (count ts)) (dec n)))))))
      "lastTab" (-> (window-tabs tab) (.then (fn [ts] (activate-tab! (nth ts (max 0 (- (count ts) n)))))))
      "visitPreviousTab" (-> (recency)
                             (.then (fn [ids]
                                      (let [ids (remove #{id} ids)]
                                        (-> (js/chrome.tabs.query #js {})
                                            (.then (fn [tabs]
                                                     (let [by-id (into {} (map (fn [^js t] [(.-id t) t])) tabs)
                                                           live (keep by-id ids)]
                                                       (when-let [t (nth live (dec n) nil)] (activate-tab! t))))))))))
      "duplicateTab" (dotimes [_ n] (js/chrome.tabs.duplicate id))
      "togglePinTab" (js/chrome.tabs.update id #js {:pinned (not (.-pinned tab))})
      "toggleMuteTab" (js/chrome.tabs.update id #js {:muted (not (some-> tab .-mutedInfo .-muted))})
      "removeTab" (-> (window-tabs tab)
                      (.then (fn [ts] (js/chrome.tabs.remove
                                       (clj->js (->> ts (drop-while (fn [^js t] (not= id (.-id t)))) (take n) (map (fn [^js t] (.-id t)))))))))
      "restoreTab" (dotimes [_ n] (js/chrome.sessions.restore))
      "moveTabToNewWindow" (js/chrome.windows.create #js {:tabId id})
      "closeTabsOnLeft" (close-where! tab (fn [^js t] (and (< (.-index t) (.-index tab))
                                                             (or (not explicit) (>= (.-index t) (- (.-index tab) n))))))
      "closeTabsOnRight" (close-where! tab (fn [^js t] (and (> (.-index t) (.-index tab))
                                                              (or (not explicit) (<= (.-index t) (+ (.-index tab) n))))))
      "closeOtherTabs" (close-where! tab (fn [^js t] (not= id (.-id t))))
      "moveTabLeft" (js/chrome.tabs.move id #js {:index (max 0 (- (.-index tab) n))})
      "moveTabRight" (js/chrome.tabs.move id #js {:index (+ (.-index tab) n)})
      "zoomIn" (zoom! tab 1 n)
      "zoomOut" (zoom! tab -1 n)
      "zoomReset" (js/chrome.tabs.setZoom id 0)
      "nextFrame" (next-frame! tab frame-id n)
      "copyCurrentUrl" (clipboard-write (.-url tab))
      "openCopiedUrlInCurrentTab" (-> (clipboard-read) (.then (fn [t] (when (seq t) (-> (typed->url t) (.then #(open-url! tab % {})))))))
      "openCopiedUrlInNewTab" (-> (clipboard-read) (.then (fn [t] (when (seq t) (-> (typed->url t) (.then #(open-url! tab % {:new-tab true :active true})))))))
      "toggleViewSource" (let [u (.-url tab)]
                             (open-url! tab (if (str/starts-with? u "view-source:") (subs u 12) (str "view-source:" u)) {:new-tab true :active true}))
      nil)))

;; ---- link hints across frames --------------------------------------------------------

(defonce sessions (atom {}))   ; tab-id -> {:mode :typed :all [{:frame :index :hint}] :key-frame}

(defn- hints-exit! [tab-id]
  (when-let [{:keys [all]} (get @sessions tab-id)]
    (swap! sessions dissoc tab-id)
    (doseq [f (distinct (cons 0 (map :frame all)))] (to-frame tab-id f {:type "hints-exit"}))))

(defonce ^:private starts (atom 0))

(defn- exit-all-frames! [tab-id]
  (-> (frame-ids tab-id)
      (.then (fn [ids] (doseq [f ids] (to-frame tab-id f {:type "hints-exit"}))))))

(defn hints-start! [tab-id from-frame mode]
  ;; A start that Escape (or a newer start) overtakes while frames are still
  ;; counting their links is dropped instead of shown.
  (let [n (swap! starts inc)]
    (swap! sessions assoc tab-id {:pending n})
  (-> (js/Promise.all #js [(frame-ids tab-id) (get-settings)])
      (.then (fn [[ids s]]
               (-> (js/Promise.all (clj->js (map (fn [f] (-> (to-frame tab-id f {:type "hints-collect" :mode mode})
                                                                (.then (fn [n] #js [f (or n 0)])))) ids)))
                   (.then (fn [pairs]
                            (let [counts (mapv vec pairs)
                                  total (reduce + (map second counts))
                                  assigned (hints/assign (get s "linkHintCharacters") (filter (comp pos? second) counts))]
                              (cond
                                (not= n (:pending (get @sessions tab-id)))
                                (doseq [[f _] counts] (to-frame tab-id f {:type "hints-exit"}))
                                (zero? total)
                                (do (swap! sessions dissoc tab-id)
                                    (doseq [[f _] counts] (to-frame tab-id f {:type "hints-exit"}))
                                    (to-frame tab-id from-frame {:type "hud" :text "No links to select."}))
                                :else
                                (do
                                  (swap! sessions assoc tab-id
                                         {:mode mode :typed ""
                                          :frames (mapv first counts)
                                          :all (vec (for [[f hs] assigned [i h] (map-indexed vector hs)] {:frame f :index i :hint h}))})
                                  (doseq [[f _] counts]
                                    (to-frame tab-id f {:type "hints-show" :hints (get assigned f [])})))))))))))))

(defn hints-key! [tab-id key]
  (when (= key "Escape")
    ;; Clear every frame even without a session: the worker may have restarted,
    ;; or a start may still be counting.
    (swap! sessions dissoc tab-id)
    (exit-all-frames! tab-id))
  (when-let [{:keys [typed all mode frames]} (let [s (get @sessions tab-id)] (when (:all s) s))]
    (let [broadcast (fn [t] (doseq [f frames] (to-frame tab-id f {:type "hints-filter" :typed t})))]
      (case key
        "Escape" nil
        "Backspace" (let [t (subs typed 0 (max 0 (dec (count typed))))]
                      (swap! sessions assoc-in [tab-id :typed] t)
                      (broadcast t))
        (let [t (str typed key)
              ms (filter #(str/starts-with? (:hint %) t) all)]
          (cond
            (empty? ms) nil
            (and (= 1 (count ms)) (= t (:hint (first ms))))
            (let [{:keys [frame index]} (first ms)]
              (swap! sessions dissoc tab-id)
              (doseq [f frames :when (not= f frame)] (to-frame tab-id f {:type "hints-exit"}))
              (-> (to-frame tab-id frame {:type "hints-activate" :index index :mode mode})
                  (.then (fn [_] (when (= mode "queue")
                                   (js/setTimeout #(hints-start! tab-id 0 mode) 150))))))
            :else (do (swap! sessions assoc-in [tab-id :typed] t)
                      (broadcast t))))))))

;; ---- global marks ------------------------------------------------------------------

(defn- strip-hash [u] (first (str/split (or u "") #"#")))

(defn- scroll-when-loaded! [tab-id scroll]
  (letfn [(listener [id ^js info]
            (when (and (= id tab-id) (= "complete" (.-status info)))
              (js/chrome.tabs.onUpdated.removeListener listener)
              (to-frame tab-id 0 (assoc scroll :type "scroll-to"))))]
    (js/chrome.tabs.onUpdated.addListener listener)))

(defn mark-goto! [letter]
  (-> (js/chrome.storage.local.get "globalMarks")
      (.then (fn [^js r]
               (if-let [{:strs [url tabId scroll]} (get (js->clj (or (.-globalMarks r) #js {})) letter)]
                 (let [scroll {:x (get scroll "x") :y (get scroll "y")}]
                   (-> (js/chrome.tabs.query #js {})
                       (.then (fn [tabs]
                                (let [same (fn [^js t] (= (strip-hash (.-url t)) (strip-hash url)))
                                      t (or (first (filter (fn [^js t] (and (= tabId (.-id t)) (same t))) tabs))
                                            (first (filter same tabs)))]
                                  (if t
                                    (do (activate-tab! t) (to-frame (.-id ^js t) 0 (assoc scroll :type "scroll-to")))
                                    (-> (js/chrome.tabs.create #js {:url url})
                                        (.then (fn [^js nt] (scroll-when-loaded! (.-id nt) scroll)))))
                                  true)))))
                 false)))))

;; ---- Vomnibar ---------------------------------------------------------------------

(defn- tab-item [^js t] {:type "tab" :title (.-title t) :url (.-url t) :tabId (.-id t) :windowId (.-windowId t)})

(defn- tab-items [query ^js current]
  (-> (js/Promise.all #js [(js/chrome.tabs.query #js {}) (recency)])
      (.then (fn [[tabs ids]]
               (let [order (into {} (map-indexed (fn [i id] [id i]) ids))
                     sorted (sort-by (fn [^js t] [(if (= (.-id t) (some-> current .-id)) 1 0) (get order (.-id t) 1e6)]) tabs)]
                 (rank/rank query (map tab-item sorted) (js/Date.now)))))))

(defn- bookmark-items [query]
  (-> (if (str/blank? query) (js/chrome.bookmarks.getRecent 50) (js/chrome.bookmarks.search query))
      (.then (fn [bs] (rank/rank query (keep (fn [^js b] (when (.-url b) {:type "bookmark" :title (.-title b) :url (.-url b)})) bs) (js/Date.now))))))

(defn- history-items [query]
  (-> (js/chrome.history.search #js {:text query :maxResults 300 :startTime 0})
      (.then (fn [hs] (rank/rank query (map (fn [^js h] {:type "history" :title (.-title h) :url (.-url h) :time (.-lastVisitTime h)}) hs) (js/Date.now))))))

(defn vomnibar-query [mode query ^js tab]
  (case mode
    "tabs" (-> (tab-items query tab) (.then #(vec (take 20 %))))
    "bookmarks" (-> (bookmark-items query) (.then #(vec (take 20 %))))
    ;; omni and edit
    (-> (js/Promise.all #js [(tab-items query tab) (bookmark-items query) (history-items query) (typed->url query)])
        (.then (fn [[ts bs hs typed]]
                 (let [typed-item (when-not (str/blank? query)
                                    {:type (if (rank/url-like? query) "url" "search") :title query :url typed})
                       items (concat (when typed-item [typed-item])
                                     (when-not (str/blank? query) (take 3 ts))
                                     (take 4 bs) hs)]
                   (->> items
                        (reduce (fn [[seen acc] i] (if (seen (:url i)) [seen acc] [(conj seen (:url i)) (conj acc i)])) [#{} []])
                        second
                        (take 12)
                        vec)))))))

(defn vomnibar-open! [^js tab ^js msg]
  (let [item (js->clj (.-item msg) :keywordize-keys true)
        new-tab (.-newTab msg)]
    (cond
      (= "tab" (:type item)) (activate-tab! (clj->js {:id (:tabId item) :windowId (:windowId item)}))
      (:url item) (open-url! tab (:url item) {:new-tab new-tab :active true})
      (seq (.-text msg)) (-> (typed->url (.-text msg)) (.then #(open-url! tab % {:new-tab new-tab :active true}))))))

;; ---- messages ------------------------------------------------------------------------

(defn handle [^js msg ^js sender]
  (let [^js tab (.-tab sender)
        tab-id (some-> tab .-id)
        frame (.-frameId sender)]
    (when (not= "offscreen" (.-target msg))
      (case (.-type msg)
        "command" (do (run-command! (.-name msg) tab frame (or (.-count msg) 1) (.-explicit msg) (js->clj (.-options msg))) nil)
        "top-command" (do (to-frame tab-id 0 {:type "top-command" :name (.-name msg) :count (.-count msg)}) nil)
        "tab-url" (.-url tab)
        "hints-start" (do (hints-start! tab-id frame (.-mode msg)) nil)
        "hints-key" (do (hints-key! tab-id (.-key msg)) nil)
        "open-url" (do (open-url! tab (.-url msg) {:new-tab true :active (.-active msg)}) nil)
        "clipboard-write" (do (clipboard-write (.-text msg)) nil)
        "mark-set-global" (-> (js/chrome.storage.local.get "globalMarks")
                              (.then (fn [^js r]
                                       (let [m (js->clj (or (.-globalMarks r) #js {}))]
                                         (js/chrome.storage.local.set
                                          (clj->js {"globalMarks" (assoc m (.-letter msg) {"url" (.-url msg) "tabId" tab-id
                                                                                              "scroll" (js->clj (.-scroll msg))})}))))))
        "mark-goto-global" (mark-goto! (.-letter msg))
        "vomnibar-query" (-> (vomnibar-query (.-mode msg) (or (.-query msg) "") tab) (.then clj->js))
        "vomnibar-open" (do (vomnibar-open! tab msg) nil)
        nil))))

(defn init []
  (js/chrome.runtime.onMessage.addListener
   (fn [msg sender respond]
     (let [r (try (handle msg sender) (catch :default e (js/console.error e) nil))]
       (if (instance? js/Promise r)
         (do (.then r respond (fn [e] (js/console.error e) (respond nil))) true)
         (do (when (some? r) (respond r)) false)))))
  (js/chrome.tabs.onActivated.addListener (fn [^js info] (note-active! (.-tabId info))))
  (js/chrome.tabs.onRemoved.addListener (fn [id] (swap! sessions dissoc id)))
  (js/chrome.tabs.onUpdated.addListener (fn [id ^js info] (when (= "loading" (.-status info)) (swap! sessions dissoc id))))
  (js/chrome.runtime.onInstalled.addListener
   (fn [^js details]
     (when (= "install" (.-reason details))
       (js/chrome.runtime.openOptionsPage)))))
