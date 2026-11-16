(ns vimium-cljs.content.main
  (:require [clojure.string :as str]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.content.dom :as dom]
            [vimium-cljs.content.find :as find]
            [vimium-cljs.content.scroll :as scroll]
            [vimium-cljs.content.ui :as ui]
            [vimium-cljs.content.visual :as visual]
            [vimium-cljs.exclusions :as exclusions]
            [vimium-cljs.keys :as keys]
            [vimium-cljs.config :as config]))

(def top? (identical? js/window (.-top js/window)))

(defonce key-state (atom keys/initial))
(defonce mode (atom {:name :normal}))
(defonce top-rule (atom nil))
(defonce suppressed (atom #{}))
(defonce user-acted (atom false))
(defonce hints (atom nil))
(defonce previous-position (atom nil))
(defonce hints-requested (atom false))

(defn send!
  "Message to the service worker; resolves to its reply, or nil if the
  extension was reloaded under us."
  [msg]
  (try
    (-> (js/chrome.runtime.sendMessage (clj->js msg))
        (.catch (fn [_] nil)))
    (catch :default _ (js/Promise.resolve nil))))

(defn hud! [text] (when-not config/hide-hud (ui/hud! text 2000)))

(def conf
  (let [m (keys/effective-mappings (keys/mappings-text config/key-mappings))
        t (keys/trie m)]
    {:trie t :mapkeys (:mapkeys m)
     :vim-escape? (not (contains? t "<c-[>"))
     :ignore-layout config/ignore-keyboard-layout}))

(defn rule
  "{:enabled :pass-keys} for this frame, merging the tab URL's rule."
  []
  (let [rules config/exclusions
        own (exclusions/rule-for-url rules (.-href js/location))
        top (when-not top? (when-let [u @top-rule] (exclusions/rule-for-url rules u)))
        rs (remove nil? [own top])]
    (cond (empty? rs) {:enabled true :pass-keys ""}
          (some #(not (:enabled %)) rs) {:enabled false :pass-keys ""}
          :else {:enabled true :pass-keys (apply str (map :pass-keys rs))})))

(defn- consume! [^js e]
  (.preventDefault e)
  (.stopImmediatePropagation e)
  (swap! suppressed conj (.-code e)))

(defn- ev->map [^js e]
  {:key (.-key e) :code (.-code e) :shift (.-shiftKey e) :ctrl (.-ctrlKey e)
   :alt (.-altKey e) :meta (.-metaKey e) :key-code (.-keyCode e)})

(defn test-page?
  "Fixture pages opt in with <meta name=vimium-cljs-test>; the content script
  then mirrors its mode and hints into DOM attributes for the test probe."
  []
  (some? (.querySelector js/document "meta[name=vimium-cljs-test]")))

(defn set-mode! [m & {:as more}]
  (reset! mode (merge {:name m} more))
  (reset! key-state keys/initial)
  (when (test-page?) (.setAttribute (.-documentElement js/document) "data-vimium-cljs-mode" (name m))))

(defn exit-mode! []
  (ui/hide-hud!)
  (set-mode! :normal))

(defn- hint-candidates [mode]
  (let [all (dom/clickables (ui/host))]
    (if (= mode "copy") (filterv (fn [[el]] (dom/link-href el)) all) all)))

(defn hints-collect! [mode]
  (let [cands (if (and (> (.-innerWidth js/window) 3) (> (.-innerHeight js/window) 3)) (hint-candidates mode) [])]
    (reset! hints {:mode mode :cands cands})
    (count cands)))

(defn request-hints! [mode]
  (reset! hints-requested true)
  (send! {:type "hints-start" :mode mode}))

(defn hints-show! [hint-strings]
  (reset! hints-requested false)
  (if (empty? hint-strings)
    (reset! hints nil)
    (let [{:keys [cands]} @hints
          els (ui/show-markers! (map (fn [h [_ r]] [h r]) hint-strings cands))]
      (when (test-page?)
        (doseq [[h [^js el]] (map vector hint-strings cands)] (.setAttribute el "data-vimium-cljs-hint" h)))
      (swap! hints assoc :strings hint-strings :markers els)))
  (set-mode! :hints))

(defn hints-exit! []
  (reset! hints-requested false)
  (ui/hide-markers!)
  (when (test-page?)
    (doseq [[^js el] (:cands @hints)] (.removeAttribute el "data-vimium-cljs-hint")))
  (reset! hints nil)
  (when (= :hints (:name @mode)) (set-mode! :normal)))

(defn activate-element! [^js el mode]
  (dom/flash! el)
  (scroll/note-activated! el)
  (let [href (dom/link-href el)
        js-href? (and href (str/starts-with? (str/lower-case href) "javascript:"))]
    (case mode
      "copy" (when href
               (send! {:type "clipboard-write" :text href})
               (hud! (str "Yanked " href)))
      ("newtab" "newtab-fg" "queue")
      (if (and href (not js-href?))
        (send! {:type "open-url" :url href :active (= mode "newtab-fg") :next true})
        (dom/simulate-click! el {:ctrl true}))
      (cond
        (and (dom/editable? el) (not= "select" (dom/tag el))) (.focus el)
        (and href (not js-href?) (= "_blank" (str/lower-case (or (.getAttribute el "target") ""))))
        (send! {:type "open-url" :url href :active true :next true})
        :else (do (when (#{"select" "details" "summary"} (dom/tag el)) (.focus el))
                  (dom/simulate-click! el))))))

(defn hints-activate! [index mode]
  (let [el (first (get (:cands @hints) index))]
    (hints-exit!)
    (when el (activate-element! el mode))))

(defn- hints-key! [^js e ks esc?]
  (consume! e)
  (cond esc? (do (hints-exit!) (send! {:type "hints-key" :key "Escape"}))
        (= "Backspace" (.-key e)) (send! {:type "hints-key" :key "Backspace"})
        (and ks (= 1 (count ks))) (send! {:type "hints-key" :key (str/lower-case ks)})))

(defn- page-key [] (str "marks|" (first (str/split (.-href js/location) #"#"))))

(defn- position [] {:x (.-scrollX js/window) :y (.-scrollY js/window)})

(defn remember-position! [] (reset! previous-position (position)))

(defn- jump-to! [{:keys [x y]}]
  (let [here (position)]
    (.scrollTo js/window x y)
    (reset! previous-position here)))

(defn- marks-key! [^js e ks esc? create?]
  (consume! e)
  (exit-mode!)
  (cond
    esc? nil
    (and create? ks (re-matches #"[a-z]" ks))
    (let [k (page-key)]
      (-> (js/chrome.storage.local.get k)
          (.then (fn [^js r]
                   (let [m (js->clj (or (aget r k) #js {}))]
                     (js/chrome.storage.local.set (clj->js {k (assoc m ks (position))}))))))
      (hud! (str "Created local mark '" ks "'")))
    (and create? ks (re-matches #"[A-Z]" ks))
    (do (send! {:type "mark-set-global" :letter ks :url (.-href js/location) :scroll (position)})
        (hud! (str "Created global mark '" ks "'")))
    (and (not create?) (#{"`" "'"} ks))
    (if-let [p @previous-position] (jump-to! p) (hud! "No previous position"))
    (and (not create?) ks (re-matches #"[a-z]" ks))
    (let [k (page-key)]
      (-> (js/chrome.storage.local.get k)
          (.then (fn [^js r]
                   (if-let [p (get (js->clj (or (aget r k) #js {}) :keywordize-keys false) ks)]
                     (do (jump-to! {:x (get p "x") :y (get p "y")}) (hud! (str "Jumped to local mark '" ks "'")))
                     (hud! (str "Local mark not set: '" ks "'")))))))
    (and (not create?) ks (re-matches #"[A-Z]" ks))
    (-> (send! {:type "mark-goto-global" :letter ks})
        (.then (fn [r] (when-not r (hud! (str "Global mark not set: '" ks "'"))))))))

(defn- regex? [] (boolean config/regex-find-mode))

(defn enter-find! []
  (let [before (position)
        bar (atom nil)
        on-input (fn [q]
                   (find/update-query! q (regex?) (ui/host))
                   (when-let [^js c (:count @bar)]
                     (set! (.-textContent c) (if (seq q) (find/count-text) ""))))
        on-done (fn [how q]
                  (exit-mode!)
                  (if (and (= how :enter) (seq q))
                    (do (find/finish! true)
                        (find/focus-found-link!)
                        (reset! previous-position before))
                    (find/clear!)))]
    (set-mode! :find)
    (reset! bar (ui/find-bar! "" on-input on-done))))

(defn perform-find! [dir times]
  (let [r (find/step! dir times (regex?) (ui/host))]
    (cond (false? r) (hud! "No previous find")
          (zero? r) (hud! (str "No matches for '" (:query @find/state) "'"))
          :else (hud! (str "/" (:query @find/state) "  " (find/count-text))))))

(defn find-selected! [dir]
  (let [t (str/trim (str (js/getSelection)))]
    (if (empty? t)
      (hud! "Nothing selected")
      (do (find/set-query! t) (perform-find! dir 1)))))

(defn enter-visual! [line?]
  (if (visual/enter! line? (ui/host))
    (do (set-mode! :visual :line line? :count 0 :pending nil)
        (ui/hud! (if line? "Visual mode (line)" "Visual mode")))
    (hud! "No usable text on this page")))

(defn- visual-key! [^js e ks esc?]
  (let [{:keys [line pending] cnt :count} @mode
        times (max 1 cnt)]
    (cond
      (nil? ks) nil
      esc? (do (consume! e) (visual/exit!) (exit-mode!))
      (and (re-matches #"[0-9]" ks) (or (pos? cnt) (not= ks "0")))
      (do (consume! e) (swap! mode assoc :count (+ (* 10 cnt) (js/parseInt ks 10))))
      (= pending "g") (do (consume! e) (swap! mode assoc :pending nil :count 0)
                            (when (= ks "g") (visual/move! "gg" 1 line)))
      (= ks "g") (do (consume! e) (swap! mode assoc :pending "g"))
      (visual/movements ks) (do (consume! e) (visual/move! ks times line) (swap! mode assoc :count 0))
      (= ks "o") (do (consume! e) (visual/swap-ends!))
      (= ks "y") (do (consume! e)
                     (let [t (visual/selected-text)]
                       (send! {:type "clipboard-write" :text t})
                       (visual/exit!) (exit-mode!)
                       (hud! (str "Yanked " (count t) " characters"))))
      (= ks "V") (do (consume! e) (if line (do (visual/exit!) (exit-mode!)) (enter-visual! true)))
      (= ks "v") (do (consume! e) (if line (enter-visual! false) (do (visual/exit!) (exit-mode!))))
      (and (= 1 (count ks)) (not (.-ctrlKey e)) (not (.-metaKey e)) (not (.-altKey e))) (consume! e)
      :else nil)))

(defn focus-input! [times]
  (let [inputs (dom/text-inputs)]
    (if (empty? inputs)
      (hud! "There are no inputs to focus.")
      (let [i (min (dec (count inputs)) (max 0 (dec (or times 1))))
            ^js el (nth inputs i)]
        (.focus el)
        (when (.-select el) (try (.select el) (catch :default _ nil)))
        (set-mode! :focus-input :inputs inputs :index i)))))

(defn- focus-input-key! [^js e]
  (if (= "Tab" (.-key e))
    (let [{:keys [inputs index]} @mode
          i (mod (+ index (if (.-shiftKey e) -1 1)) (count inputs))
          ^js el (nth inputs i)]
      (consume! e)
      (.focus el)
      (when (.-select el) (try (.select el) (catch :default _ nil)))
      (swap! mode assoc :index i)
      true)
    (do (set-mode! :normal) false)))

(defn url-up [href times]
  (let [u (js/URL. href)
        parts (vec (remove empty? (str/split (.-pathname u) #"/")))
        keep (max 0 (- (count parts) times))]
    (set! (.-hash u) "")
    (set! (.-search u) "")
    (set! (.-pathname u) (str "/" (str/join "/" (take keep parts)) (when (pos? keep) "/")))
    (.-href u)))

(defn- follow-rel! [patterns rel]
  (let [^js by-rel (.querySelector js/document (str "link[rel~=" rel "][href], a[rel~=" rel "][href]"))]
    (if by-rel
      (do (set! (.-href js/location) (.-href by-rel)) true)
      (let [pats (remove str/blank? (map str/trim (str/split (or patterns "") #",")))
            cands (filter (fn [[el]] (#{"a" "button"} (dom/tag el))) (dom/clickables (ui/host)))
            text-of (fn [^js el] (str/lower-case (str/trim (str (or (.-innerText el) "") " " (or (.getAttribute el "aria-label") "")))))
            matches? (fn [p t]
                       (if (re-find #"^\w+$" p)
                         (re-find (re-pattern (str "\\b" p "\\b")) t)
                         (str/includes? t p)))]
        (if-let [el (some (fn [p] (some (fn [[el]] (when (matches? (str/lower-case p) (text-of el)) el)) cands)) pats)]
          (do (dom/flash! el) (dom/simulate-click! el) true)
          false)))))

(defn- step-size [] config/scroll-step)
(defn- smooth? [] (boolean config/smooth-scroll))

(defn open-ui! [kind query]
  (ui/open-frame! kind query))

(defn vomnibar! [m new-tab]
  (open-ui! "vomnibar" (str "mode=" m (when new-tab "&newTab=1")
                             (when (= m "edit") (str "&url=" (js/encodeURIComponent (.-href js/location)))))))

(declare run-command!)

(def local-commands
  {"scrollDown" (fn [n] (scroll/scroll-by! :y (step-size) n (smooth?)))
   "scrollUp" (fn [n] (scroll/scroll-by! :y (step-size) (- n) (smooth?)))
   "scrollLeft" (fn [n] (scroll/scroll-by! :x (step-size) (- n) (smooth?)))
   "scrollRight" (fn [n] (scroll/scroll-by! :x (step-size) n (smooth?)))
   "scrollPageDown" (fn [n] (scroll/scroll-by! :y :view-size (/ n 2) (smooth?)))
   "scrollPageUp" (fn [n] (scroll/scroll-by! :y :view-size (- (/ n 2)) (smooth?)))
   "scrollFullPageDown" (fn [n] (scroll/scroll-by! :y :view-size n (smooth?)))
   "scrollFullPageUp" (fn [n] (scroll/scroll-by! :y :view-size (- n) (smooth?)))
   "scrollToTop" (fn [_] (remember-position!) (scroll/scroll-to! :y :start))
   "scrollToBottom" (fn [_] (remember-position!) (scroll/scroll-to! :y :end))
   "scrollToLeft" (fn [_] (scroll/scroll-to! :x :start))
   "scrollToRight" (fn [_] (scroll/scroll-to! :x :end))
   "goBack" (fn [n] (.go js/history (- n)))
   "goForward" (fn [n] (.go js/history n))
   "goUp" (fn [n] (set! (.-href js/location) (url-up (.-href js/location) n)))
   "goToRoot" (fn [_] (set! (.-href js/location) (str (.-origin js/location) "/")))
   "goNext" (fn [_] (when-not (follow-rel! config/next-patterns "next") (hud! "No next link")))
   "goPrevious" (fn [_] (when-not (follow-rel! config/previous-patterns "prev") (hud! "No previous link")))
   "enterInsertMode" (fn [_] (set-mode! :insert) (ui/hud! "Insert mode"))
   "passNextKey" (fn [n] (set-mode! :pass-next :left n) (ui/hud! "Pass next key"))
   "focusInput" (fn [n] (focus-input! n))
   "enterVisualMode" (fn [_] (enter-visual! false))
   "enterVisualLineMode" (fn [_] (enter-visual! true))
   "enterFindMode" (fn [_] (enter-find!))
   "performFind" (fn [n] (remember-position!) (perform-find! 1 n))
   "performBackwardsFind" (fn [n] (remember-position!) (perform-find! -1 n))
   "findSelected" (fn [_] (find-selected! 1))
   "findSelectedBackwards" (fn [_] (find-selected! -1))
   "Marks.activateCreateMode" (fn [_] (set-mode! :marks-create) (ui/hud! "Create mark…"))
   "Marks.activateGotoMode" (fn [_] (set-mode! :marks-goto) (ui/hud! "Go to mark…"))
   "LinkHints.activateMode" (fn [_] (request-hints! "open"))
   "LinkHints.activateModeToOpenInNewTab" (fn [_] (request-hints! "newtab"))
   "LinkHints.activateModeToOpenInNewForegroundTab" (fn [_] (request-hints! "newtab-fg"))
   "LinkHints.activateModeWithQueue" (fn [_] (request-hints! "queue"))
   "LinkHints.activateModeToCopyLinkUrl" (fn [_] (request-hints! "copy"))
   "Vomnibar.activate" (fn [_] (vomnibar! "omni" false))
   "Vomnibar.activateInNewTab" (fn [_] (vomnibar! "omni" true))
   "Vomnibar.activateBookmarks" (fn [_] (vomnibar! "bookmarks" false))
   "Vomnibar.activateBookmarksInNewTab" (fn [_] (vomnibar! "bookmarks" true))
   "Vomnibar.activateTabSelection" (fn [_] (vomnibar! "tabs" false))
   "Vomnibar.activateEditUrl" (fn [_] (vomnibar! "edit" false))
   "Vomnibar.activateEditUrlInNewTab" (fn [_] (vomnibar! "edit" true))
   "showHelp" (fn [_] (if (= "help" (ui/frame-kind)) (ui/close-frame!) (open-ui! "help" nil)))
   "mainFrame" (fn [_] (.focus js/window) (dom/flash! (.-documentElement js/document)))
   "copyCurrentUrl" (fn [_] (send! {:type "command" :name "copyCurrentUrl"}) (hud! "Yanked the URL"))
   "openCopiedUrlInCurrentTab" (fn [_] (send! {:type "command" :name "openCopiedUrlInCurrentTab"}))
   "openCopiedUrlInNewTab" (fn [_] (send! {:type "command" :name "openCopiedUrlInNewTab"}))
   "toggleViewSource" (fn [_] (send! {:type "command" :name "toggleViewSource"}))})

(defonce ^:private forwarding (atom nil))

(defn- forward-keys! []
  (when-let [{:keys [text enter timer]} @forwarding]
    (reset! forwarding nil)
    (js/clearTimeout timer)
    (when (or (seq text) enter) (send! {:type "vomnibar-keys" :text text :enter enter}))))

(defn- start-forwarding! []
  (forward-keys!)
  (reset! forwarding {:text "" :enter false :timer (js/setTimeout forward-keys! 1500)})
  (.addEventListener js/window "blur" forward-keys! #js {:once true}))

(defn run-command!
  [{:keys [command options]} count]
  (let [info (commands/by-name command)
        n (or (get options "count") 1)
        n (if (:once info) 1 (* n (or count 1)))]
    (cond
      (:bg info) (send! {:type "command" :name command :count n :explicit (some? count) :options options})
      (and (:top info) (not top?)) (do (when (str/starts-with? command "Vomnibar.") (start-forwarding!))
                                       (send! {:type "top-command" :name command :count n}))
      :else (when-let [f (local-commands command)]
              (f n)))))

(defn- own-ui? [^js e] (when-let [h (ui/host)] (identical? (.-target e) h)))

(defn- normal-key! [^js e ks esc?]
  (let [{:keys [enabled pass-keys]} (rule)]
    (when enabled
      (let [{:keys [state result] :as r} (keys/step (:trie conf) @key-state ks esc? pass-keys)]
        (reset! key-state state)
        (case result
          :command (do (consume! e) (run-command! (:command r) (:count r)))
          (:pending :reset) (consume! e)
          :pass (when esc?
                  (cond @hints-requested
                        (do (consume! e) (reset! hints-requested false) (send! {:type "hints-key" :key "Escape"}))
                        (ui/frame-kind) (do (consume! e) (ui/close-frame!))
                        (seq (:ranges @find/state)) (do (consume! e) (find/clear!))
                        :else nil)))))))

(defn- type-ahead-key!
  "Keeps a key typed for a Vomnibar that is still loading (here or, from a child frame, in the
  top page). True when it took the key."
  [^js e]
  (let [k (.-key e)]
    (cond
      (or (.-ctrlKey e) (.-altKey e) (.-metaKey e)) false
      (not (or (= 1 (count k)) (#{"Backspace" "Enter"} k))) false
      @forwarding (do (consume! e)
                      (swap! forwarding (fn [{:keys [text enter] :as f}]
                                          (case k
                                            "Backspace" (assoc f :text (subs text 0 (max 0 (dec (count text)))))
                                            "Enter" (assoc f :enter true)
                                            (if enter f (assoc f :text (str text k))))))
                      true)
      (ui/typing-ahead?) (do (consume! e) (ui/type-ahead! k) true)
      :else false)))

(defn on-keydown [^js e]
  ;; A key consumed here has its keypress and keyup suppressed until that keyup. When the keyup
  ;; went elsewhere (focus moved into the Vomnibar between down and up), this new keydown says
  ;; so: forget it, or the key's next keypress in a field would be dropped.
  (when (.-isTrusted e) (swap! suppressed disj (.-code e)))
  (when (and (.-isTrusted e) (not (own-ui? e))
             (not (and (or @forwarding (ui/typing-ahead?)) (type-ahead-key! e))))
    (reset! user-acted true)
    (let [ev (ev->map e)
          {:keys [mapkeys vim-escape? ignore-layout]} conf
          ks (keys/key-string ev mapkeys ignore-layout)
          esc? (keys/escape? ev ks vim-escape?)
          target (dom/event-target e)
          editing? (or (dom/editable? target) (dom/editable? (dom/deep-active-element)))]
      (case (:name @mode)
        :hints (hints-key! e ks esc?)
        :visual (visual-key! e ks esc?)
        :marks-create (when ks (marks-key! e ks esc? true))
        :marks-goto (when ks (marks-key! e ks esc? false))
        :insert (when esc? (consume! e) (exit-mode!))
        :pass-next (when ks
                     (let [left (dec (:left @mode))]
                       (if (pos? left) (swap! mode assoc :left left) (exit-mode!))))
        :find nil
        :focus-input (when-not (focus-input-key! e)
                       (when esc? (consume! e) (.blur ^js (dom/deep-active-element))))
        (cond
          (nil? ks) nil
          editing? (when esc?
                     (consume! e)
                     (let [^js a (dom/deep-active-element)] (when a (.blur a)))
                     (.focus js/window))
          :else (normal-key! e ks esc?))))))

(defn- on-keyup [^js e]
  (when (contains? @suppressed (.-code e))
    (when (= "keyup" (.-type e)) (swap! suppressed disj (.-code e)))
    (.preventDefault e)
    (.stopImmediatePropagation e)))

(defn- on-focus [^js e]
  (when (and config/grab-back-focus (not @user-acted) (.-isTrusted e))
    (let [t (dom/event-target e)]
      (when (dom/editable? t) (.blur ^js t)))))

(defn- on-message [^js msg _sender respond]
  (case (.-type msg)
    "hints-collect" (do (respond (hints-collect! (.-mode msg))) false)
    "hints-show" (do (hints-show! (vec (.-hints msg))) false)
    "hints-filter" (let [{:keys [markers strings]} @hints]
                       (when markers (ui/filter-markers! markers strings (.-typed msg))) false)
    "hints-activate" (do (hints-activate! (.-index msg) (.-mode msg)) false)
    "hints-exit" (do (hints-exit!) false)
    "hud" (do (hud! (.-text msg)) false)
    "top-command" (do (when-let [f (local-commands (.-name msg))] (f (or (.-count msg) 1))) false)
    "vomnibar-keys" (do (ui/more-keys! (.-text msg) (.-enter msg)) false)
    ;; A page without focus (the address bar has it) cannot take it back; answering false makes the worker open the popup.
    "vomnibar" (let [ok (.hasFocus js/document)] (when ok (vomnibar! (or (.-mode msg) "omni") (.-newTab msg))) (respond ok) false)
    "focus-frame" (do (.focus js/window) (dom/flash! (.-documentElement js/document)) false)
    "scroll-to" (do (jump-to! {:x (.-x msg) :y (.-y msg)}) false)
    "ping" (do (respond true) false)
    "url-changed" (do (scroll/url-changed!) false)
    false))

(defn- on-window-message [^js e]
  (when (identical? (.-source e) (ui/frame-window))
    (case (.-data e)
      "vimium-cljs:close" (ui/close-frame!)
      "vimium-cljs:ready" (ui/frame-ready!)
      nil)))

(defn- mark-ready! []
  (when (test-page?)
    (.setAttribute (.-documentElement js/document) "data-vimium-cljs-ready" "1")))

;; On extension reload Chrome leaves the old script running, cut off from the worker, which injects a new one.
;; The orphan must step aside, or it swallows keys in the capture phase before the new script sees them.

(defonce ^:private listening (js/AbortController.))

(defn orphaned?
  "True once this script's extension was reloaded, updated or removed."
  []
  (nil? (some-> js/chrome .-runtime .-id)))

(defn- retire-if-orphaned! []
  (when (orphaned?)
    (.abort listening)
    (when (identical? listening (.-__vimiumCljs js/window)) (set! (.-__vimiumCljs js/window) nil))
    true))

(def ^:private takeover-event "vimium-cljs:takeover")

(defn- listen! [^js target type f capture?]
  (.addEventListener target type f #js {:capture capture? :signal (.-signal listening)}))

(defn init []
  ;; Tell an orphan to retire (synchronously, so its window flag is gone before we look).
  (.dispatchEvent js/window (js/Event. takeover-event))
  (when-not (.-__vimiumCljs js/window)
    (set! (.-__vimiumCljs js/window) listening)
    (listen! js/window takeover-event (fn [_] (retire-if-orphaned!)) false)
    (listen! js/window "keydown" (fn [e] (when-not (retire-if-orphaned!) (on-keydown e))) true)
    (listen! js/window "keyup" on-keyup true)
    (listen! js/window "keypress" on-keyup true)
    (listen! js/window "focus" on-focus true)
    (listen! js/window "mousedown" (fn [^js e] (when (.-isTrusted e) (reset! user-acted true))) true)
    (scroll/install!)
    (listen! js/window "message" on-window-message false)
    (.addEventListener js/document "DOMContentLoaded" mark-ready!)
    (js/chrome.runtime.onMessage.addListener on-message)
    (ui/set-user-css! config/link-hint-css)
    (when-not (= "loading" (.-readyState js/document)) (mark-ready!))
    (when-not top?
      (-> (send! {:type "tab-url"}) (.then (fn [u] (reset! top-rule u)))))))
