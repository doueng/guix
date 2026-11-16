;; After Vimium's background_scripts (MIT). State that must survive the worker
;; sleeping lives in chrome.storage.session.
(ns vimium-cljs.bg.main
  (:require [clojure.string :as str]
            [vimium-cljs.hints :as hints]
            [vimium-cljs.rank :as rank]
            [vimium-cljs.config :as config]))

(defn- p [x] (js/Promise.resolve x))

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

(defn- recency [] (-> (js/chrome.storage.session.get "recency")
                      (.then (fn [^js r] (vec (or (some-> (.-recency r) js->clj) []))))))

(defn- note-active! [tab-id]
  (-> (recency)
      (.then (fn [ids] (js/chrome.storage.session.set
                        #js {:recency (clj->js (vec (take 200 (cons tab-id (remove #{tab-id} ids)))))})))))

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

(defn open-url! [^js tab url {:keys [new-tab active]}]
  (cond
    (str/starts-with? (str/lower-case (str/trim url)) "javascript:") nil
    new-tab (js/chrome.tabs.create #js {:url url :active (boolean active) :index (inc (.-index tab)) :openerTabId (.-id tab)})
    :else (js/chrome.tabs.update (.-id tab) #js {:url url})))

(defn- typed->url [text]
  (p (rank/->url text config/search-engines config/search-url)))

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
      "createTab" (let [url (or (not-empty config/new-tab-url) (js/chrome.runtime.getURL "ui.html?newtab"))]
                    (dotimes [i n]
                      (js/chrome.tabs.create (clj->js (cond-> {:index (+ (.-index tab) 1 i)} url (assoc :url url))))))
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

(defonce sessions (atom {}))

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
   (-> (frame-ids tab-id)
       (.then (fn [ids]
                (-> (js/Promise.all (clj->js (map (fn [f] (-> (to-frame tab-id f {:type "hints-collect" :mode mode})
                                                              (.then (fn [n] #js [f (or n 0)])))) ids)))
                    (.then (fn [pairs]
                             (let [counts (mapv vec pairs)
                                   total (reduce + (map second counts))
                                   assigned (hints/assign config/link-hint-characters (filter (comp pos? second) counts))]
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

(defn- picks []
  (-> (js/chrome.storage.local.get "vomnibarPicks")
      (.then (fn [^js r] (into {} (map (fn [[k v]] [k {:n (v "n") :t (v "t") :q (v "q")}]))
                               (js->clj (or (.-vomnibarPicks r) #js {})))))))

(defn- note-pick! [url query]
  (-> (picks)
      (.then (fn [ps]
               (let [k (rank/pick-key url)
                     q (str/lower-case (str/trim (or query "")))
                     old (get ps k)
                     ps (assoc ps k {:n (inc (or (:n old) 0)) :t (js/Date.now)
                                     :q (vec (take 5 (distinct (remove str/blank? (cons q (:q old))))))})]
                 (js/chrome.storage.local.set
                  (clj->js {"vomnibarPicks" (into {} (take 300 (sort-by (comp - :t val) ps)))})))))))

(defn- same-page [url]
  (str/replace (rank/pick-key url) #"#.*$" ""))

(defn vomnibar-open!
  "Opens the picked item, or the typed text. A URL already open in another tab switches
  to that tab instead of opening it twice."
  [^js tab ^js msg]
  (let [item (js->clj (.-item msg) :keywordize-keys true)
        new-tab (.-newTab msg)
        query (.-query msg)]
    (-> (if (:url item) (p (:url item)) (typed->url (or (.-text msg) "")))
        (.then (fn [url]
                 (when (seq url)
                   (note-pick! url query)
                   (if (= "tab" (:type item))
                     (activate-tab! (clj->js {:id (:tabId item) :windowId (:windowId item)}))
                     (-> (js/chrome.tabs.query #js {})
                         (.then (fn [tabs]
                                  (if-let [t (first (filter (fn [^js t] (and (not= (.-id t) (some-> tab .-id))
                                                                             (= (same-page (.-url t)) (same-page url))))
                                                            tabs))]
                                    (activate-tab! t)
                                    (open-url! tab url {:new-tab new-tab :active true}))))))))))))

;; The command arrives even with focus in the address bar, which the page cannot take back, and tabs
;; without our content script have no page UI: both get the Vomnibar in a popup acting on that tab.

(defn- target-tab
  "The tab a Vomnibar message acts on: the one it names (a popup Vomnibar) or the sender's."
  [^js msg ^js sender-tab]
  (if-let [id (.-tabId msg)]
    (-> (js/chrome.tabs.get id) (.catch (fn [_] sender-tab)))
    (p sender-tab)))

(defn- vomnibar-popup! [mode ^js tab new-tab]
  (js/chrome.windows.create
   #js {:url (js/chrome.runtime.getURL (str "ui.html#vomnibar?mode=" mode "&popup=1"
                                            (when tab (str "&tabId=" (.-id tab)))
                                            (when new-tab "&newTab=1")))
        :type "popup" :width 900 :height 520 :focused true}))

(defn- new-tab-page?
  "Whether TAB shows our new tab page (ui.html?newtab, where chrome://newtab sends it)."
  [^js tab]
  (str/starts-with? (or (.-url tab) "") (js/chrome.runtime.getURL "ui.html?newtab")))

(defonce ^:private swapped (atom #{}))

(defn- swap-incognito-new-tab!
  "Chrome never applies chrome_url_overrides in incognito, so an incognito window's new tab page is Chrome's own
  and has no keys. Replace TAB, when it is one, with ours, in a new tab as the override does so the page gets focus."
  [^js tab]
  (when (and (.-incognito tab) (= "chrome://newtab/" (or (not-empty (.-pendingUrl tab)) (.-url tab)))
             (not (@swapped (.-id tab))))
    (swap! swapped conj (.-id tab))
    (-> (js/chrome.tabs.create (clj->js (cond-> {:url (js/chrome.runtime.getURL "ui.html?newtab") :active (.-active tab)
                                                 :index (.-index tab) :windowId (.-windowId tab)}
                                          (.-openerTabId tab) (assoc :openerTabId (.-openerTabId tab)))))
        (.then #(js/chrome.tabs.remove (.-id tab)))
        (.catch (fn [e] (js/console.warn "vimium-cljs: incognito new tab" e))))))

(defn- show-in-tab
  "Asks TAB to show the Vomnibar in MODE; resolves to whether it could (it has focus).
  Our new tab page is already an empty tab, so there a pick opens in place even for NEW-TAB."
  [mode ^js tab new-tab]
  (-> (if (new-tab-page? tab)
        (js/chrome.runtime.sendMessage #js {:type "vomnibar-show" :mode mode :tabId (.-id tab)})
        (js/chrome.tabs.sendMessage (.-id tab) #js {:type "vomnibar" :mode mode :newTab (boolean new-tab)} #js {:frameId 0}))
      (.catch (fn [_] false))))

(defn- vomnibar-command!
  "Open the Vomnibar in MODE (\"omni\", \"tabs\") over TAB's page, or in a popup when the page can't take it.
  With NEW-TAB a pick opens in a new tab, as Shift+Enter does."
  [mode ^js tab & [new-tab]]
  (-> (if tab (p tab) (-> (js/chrome.tabs.query #js {:active true :lastFocusedWindow true}) (.then first)))
      (.then (fn [^js tab]
               (if-not tab
                 (vomnibar-popup! mode nil new-tab)
                 (-> (show-in-tab mode tab new-tab)
                     (.then (fn [shown] (when-not shown (vomnibar-popup! mode tab new-tab))))))))))

(defn handle [^js msg ^js sender]
  (let [^js tab (.-tab sender)
        tab-id (some-> tab .-id)
        frame (.-frameId sender)]
    (when (not= "offscreen" (.-target msg))
      (case (.-type msg)
        "command" (do (run-command! (.-name msg) tab frame (or (.-count msg) 1) (.-explicit msg) (js->clj (.-options msg))) nil)
        "top-command" (do (to-frame tab-id 0 {:type "top-command" :name (.-name msg) :count (.-count msg)}) nil)
        "top-hud" (do (to-frame tab-id 0 {:type "hud" :text (.-text msg)}) nil)
        "indicator" (do (to-frame tab-id 0 {:type "indicator" :mode (.-mode msg)}) nil)
        "vomnibar-keys" (do (to-frame tab-id 0 {:type "vomnibar-keys" :text (.-text msg) :enter (.-enter msg)}) nil)
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
        "vomnibar-open" (do (.then (target-tab msg tab) #(vomnibar-open! % msg)) nil)
        nil))))

(defn- inject-open-tabs!
  "Chrome does not inject content scripts into tabs that were open when the extension was
  installed, reloaded or updated, so their keys would do nothing until a page reload. Inject
  into every frame now; the old script in each frame steps aside (content/main init).
  Pages the extension may not script (chrome://, the Web Store) just fail."
  []
  (-> (js/chrome.tabs.query #js {})
      (.then (fn [tabs]
               (doseq [^js t tabs :when (not (.-discarded t))]
                 (let [target #js {:tabId (.-id t) :allFrames true}]
                   (-> (js/chrome.scripting.insertCSS #js {:target target :files #js ["content.css"]})
                       (.catch (fn [_] nil)))
                   (-> (js/chrome.scripting.executeScript #js {:target target :files #js ["js/content.js"]})
                       (.catch (fn [_] nil)))))))))

(defn- reread-manifest!
  "ext-reload restarts an unpacked extension by disabling and enabling it, which rereads its scripts but keeps the
  manifest Chrome first loaded, so a new command never gets its key. Reload once for each manifest.json that differs."
  []
  (-> (js/fetch (js/chrome.runtime.getURL "manifest.json"))
      (.then #(.text %))
      (.then (fn [text]
               (when (not= (js->clj (js/JSON.parse text)) (js->clj (js/chrome.runtime.getManifest)))
                 (-> (js/chrome.storage.local.get "reloadedForManifest")
                     (.then (fn [^js got]
                              (when (not= text (.-reloadedForManifest got))
                                (-> (js/chrome.storage.local.set #js {:reloadedForManifest text})
                                    (.then #(js/chrome.runtime.reload))))))))))
      (.catch (fn [_] nil))))

(defn init []
  (js/chrome.runtime.onInstalled.addListener (fn [_] (inject-open-tabs!)))
  (reread-manifest!)
  (js/chrome.runtime.onMessage.addListener
   (fn [msg sender respond]
     (let [r (try (handle msg sender) (catch :default e (js/console.error e) nil))]
       (if (instance? js/Promise r)
         (do (.then r respond (fn [e] (js/console.error e) (respond nil))) true)
         (do (when (some? r) (respond r)) false)))))
  ;; sidePanel.open needs the user gesture, so it runs right in the command handler.
  (js/chrome.commands.onCommand.addListener
   (fn [name ^js tab]
     (case name
       "vomnibar" (vomnibar-command! "omni" tab)
       "vomnibar-tabs" (vomnibar-command! "tabs" tab)
       "vomnibar-new-tab" (vomnibar-command! "omni" tab true)
       "tab-panel" (-> (js/chrome.sidePanel.open #js {:windowId (.-windowId tab)})
                       (.catch (fn [e] (js/console.warn "vimium-cljs: side panel" e))))
       nil)))
  ;; DevTools key events never reach Chrome's shortcuts, so the e2e suite runs the command here.
  (unchecked-set js/globalThis "vimiumCljsVomnibarCommand" (fn [tab mode new-tab] (vomnibar-command! (or mode "omni") tab new-tab)))
  (js/chrome.tabs.onActivated.addListener (fn [^js info] (note-active! (.-tabId info))))
  (js/chrome.tabs.onRemoved.addListener (fn [id] (swap! sessions dissoc id) (swap! swapped disj id)))
  (js/chrome.tabs.onUpdated.addListener (fn [id ^js info ^js tab]
                                          (when (= "loading" (.-status info)) (swap! sessions dissoc id))
                                          (when (.-url info) (swap-incognito-new-tab! tab))))
  ;; Split incognito mode: this worker serves only incognito windows there, and a new window's first tab can
  ;; exist before the worker starts.
  (js/chrome.tabs.onCreated.addListener swap-incognito-new-tab!)
  (when (.-inIncognitoContext js/chrome.extension)
    (-> (js/chrome.tabs.query #js {}) (.then #(run! swap-incognito-new-tab! %))))
  ;; The content script cannot see history.pushState, so tell the frame its URL changed.
  (let [url-changed (fn [^js d] (to-frame (.-tabId d) (.-frameId d) {:type "url-changed"}))]
    (js/chrome.webNavigation.onHistoryStateUpdated.addListener url-changed)
    (js/chrome.webNavigation.onReferenceFragmentUpdated.addListener url-changed)))
