(ns app
  (:require [bbh.web.conversation :as conversation]
            [bbh.web.markdown :as md]
            [clojure.string :as str]
            [replicant.dom :as r]))

(def poll-ms 2000)
(def image-px 2048)
(def image-quality 0.85)

(defonce state (atom {:agents nil :current nil :conversations {} :error nil
                      :view nil :inbox nil :inbox-at nil :inbox-error nil :threads {} :picks {} :conflicts {} :busy-super #{}}))

(def stale-ms (* 3 60 1000))

;; Typing must not repaint the page, so drafts live outside `state`.
(defonce drafts (atom {}))

(defn- token-view [{:keys [content color italic]}]
  [:span {:style (cond-> {:color color} italic (assoc :font-style "italic"))} content])

(defn- highlight
  "CODE in LANG as children of a code element, plain when it cannot be highlighted."
  [lang code]
  (let [tokens (when-let [tokenize (.-highlightCode js/window)]
                 (tokenize code lang))]
    (if tokens
      (vec (interpose "\n" (map (fn [line] (into [:span] (map token-view) line))
                                 (js->clj tokens :keywordize-keys true))))
      [code])))

(def highlighted (memoize highlight))

(def markdown (memoize #(md/render % highlighted)))

(defn- request [path options]
  (-> (js/fetch path (clj->js options))
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [body]
                            (if (.-ok response)
                              (js->clj body :keywordize-keys true)
                              (throw (js/Error. (or (.-error body) "Request failed")))))))))))

(defn- conversation-path [id]
  (str "/api/agents/" id "/messages"))

(defn- store-page! [id page]
  (swap! state update-in [:conversations id] conversation/merge-page page))

(defn- thread []
  (.getElementById js/document "thread"))

(defn- to-bottom! []
  (when-let [el (thread)]
    (set! (.-scrollTop el) (.-scrollHeight el))))

(defn- sync-conversation!
  "Fetch what is new in session ID."
  [id]
  (let [total (get-in @state [:conversations id :total])]
    (-> (request (str (conversation-path id) (when total (str "?after=" total))) {})
        (.then (fn [page]
                 (store-page! id page)
                 (when-not total (to-bottom!)))))))

(defn- live? [{:keys [agents]} id]
  (some #(= id (:id %)) agents))

(defn- pick-current
  "The session to show. It never changes by itself once chosen, so a draft can
  not go to another agent when this one ends."
  [{:keys [current agents]}]
  (let [hash-id (subs (.. js/location -hash) 1)]
    (or current (some #{hash-id} (map :id agents)) (:id (first agents)))))

;; ---- the inbox: what the controller knows, read through /api/super ----------

(defn- local
  "The JSON under KEY in localStorage as data, or DEFAULT."
  [key default]
  (if-let [s (.getItem js/localStorage key)]
    (js->clj (js/JSON.parse s) :keywordize-keys false)
    default))

(defn- local! [key value]
  (.setItem js/localStorage key (js/JSON.stringify (clj->js value))))

(defn- cursor-query []
  (str/join "," (for [[t n] (local "super-cursors" {})] (str t ":" n))))

(defn- sync-thread! [id]
  (-> (request (str "/api/super/threads/" id) {})
      (.then (fn [t]
               (local! "super-cursors" (assoc (local "super-cursors" {}) id (:milestone-seq t)))
               (swap! state assoc-in [:threads id] t)
               (swap! state assoc :inbox-error nil)))
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))))

(defn- shown-query []
  (str/join "," (for [[t s] (local "super-shown" [])] (str t ":" s))))

(defn- sync-inbox! []
  (-> (request (str "/api/super/threads?cursors=" (cursor-query)
                    (when (seq (local "super-shown" [])) (str "&shown=" (shown-query)))) {})
      (.then (fn [inbox]
               ;; The list holds the order it first showed until you ask for the new rows.
               (when (empty? (local "super-shown" [])) (local! "super-shown" (:order inbox)))
               (swap! state assoc :inbox inbox :inbox-at (js/Date.now) :inbox-error nil)
               (set! (.-title js/document) (if (pos? (:badge inbox 0)) (str "(" (:badge inbox) ") Agents") "Agents"))
               (when-let [[_ id] (let [v (:view @state)] (when (vector? v) v))]
                 (sync-thread! id))))
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))))

(defonce syncing (atom false))

(defn- sync!
  []
  (when-not (or @syncing (.-hidden js/document))
    (reset! syncing true)
    (sync-inbox!)
    (-> (request "/api/agents" {})
        (.then (fn [agents]
                 (swap! state (fn [s] (let [s (assoc s :agents agents :error nil)]
                                        (assoc s :current (pick-current s)))))
                 (let [{:keys [current] :as s} @state]
                   (when (live? s current)
                     (sync-conversation! current)))))
        (.catch (fn [e] (swap! state assoc :error (.-message e))))
        (.finally (fn [] (reset! syncing false))))))

(defn- center-tab!
  "Scroll the strip to the current tab. scrollIntoView would also scroll the page."
  []
  (let [strip (.querySelector js/document ".tabs")
        tab (.querySelector js/document ".tab.current")
        left (- (+ (.-scrollLeft strip)
                   (- (.-left (.getBoundingClientRect tab)) (.-left (.getBoundingClientRect strip))))
                (/ (- (.-clientWidth strip) (.-offsetWidth tab)) 2))]
    (.scrollTo strip #js {:left left :behavior "smooth"})))

(defn- select!
  "Show session ID. Replacing the hash keeps tabs out of the browser's history."
  [id]
  (.replaceState js/history nil "" (str "#" id))
  (swap! state assoc :current id :view nil)
  (to-bottom!)
  (center-tab!)
  (sync-conversation! id))

(defn- load-earlier! [id]
  (let [first-index (:i (first (get-in @state [:conversations id :messages])))
        el (thread)
        height (.-scrollHeight el)]
    (-> (request (str (conversation-path id) "?before=" first-index) {})
        (.then (fn [page]
                 (store-page! id page)
                 (set! (.-scrollTop el) (+ (.-scrollTop el) (- (.-scrollHeight el) height))))))))

(defn- jpeg-of
  "The loaded IMAGE as a JPEG data URL, its longest side at most image-px."
  [image]
  (let [width (.-naturalWidth image)
        height (.-naturalHeight image)
        scale (min 1 (/ image-px (max width height)))
        canvas (.createElement js/document "canvas")
        context (.getContext canvas "2d")]
    (set! (.-width canvas) (js/Math.round (* scale width)))
    (set! (.-height canvas) (js/Math.round (* scale height)))
    (set! (.-fillStyle context) "#fff")
    (.fillRect context 0 0 (.-width canvas) (.-height canvas))
    (.drawImage context image 0 0 (.-width canvas) (.-height canvas))
    (.toDataURL canvas "image/jpeg" image-quality)))

(defn- shrink
  "A promise of FILE as a JPEG data URL. This also turns HEIC and PNG into JPEG."
  [file]
  (js/Promise.
   (fn [resolve reject]
     (let [reader (js/FileReader.)
           image (js/Image.)]
       (set! (.-onerror image) #(reject (js/Error. "Could not read that image")))
       (set! (.-onload image) #(resolve (jpeg-of image)))
       (set! (.-onerror reader) #(reject (js/Error. "Could not read that image")))
       (set! (.-onload reader) #(set! (.-src image) (.-result reader)))
       (.readAsDataURL reader file)))))

(defn- attach! [id input]
  (let [files (js/Array.from (.-files input))]
    (-> (js/Promise.all (.map files shrink))
        (.then (fn [urls]
                 (swap! state update-in [:attachments id] (fnil into []) urls)
                 (set! (.-value input) "")))
        (.catch (fn [e] (swap! state assoc :error (.-message e)))))))

(defn- detach! [id n]
  (swap! state update-in [:attachments id]
         (fn [urls] (into (subvec urls 0 n) (subvec urls (inc n))))))

(defn- base64-of [data-url]
  (subs data-url (inc (str/index-of data-url ","))))

(defn- send! [id]
  (let [text (str/trim (get @drafts id ""))
        urls (get-in @state [:attachments id])]
    (when (and (or (seq text) (seq urls)) (not (:sending @state)))
      (swap! state assoc :sending true :error nil)
      (-> (request (conversation-path id)
                   {:method "POST"
                    :headers {"Content-Type" "application/json"}
                    :body (js/JSON.stringify (clj->js {:text text :images (map base64-of urls)}))})
          (.then (fn [message]
                   (swap! drafts dissoc id)
                   (when-let [input (.querySelector js/document ".composer textarea")]
                     (set! (.-value input) ""))
                   (swap! state (fn [s] (-> s
                                            (update :attachments dissoc id)
                                            (update-in [:conversations id :pending] (fnil conj []) message))))
                   (to-bottom!)))
          (.catch (fn [e] (swap! state assoc :error (.-message e))))
          (.finally (fn [] (swap! state assoc :sending false)))))))

(defn- first-line [code]
  (first (str/split-lines (str code " "))))

(defn- tool-view [{:keys [id code]} result]
  [:details.tool {:replicant/key id}
   [:summary (first-line code)]
   [:pre (into [:code] (highlighted "clojure" code))]
   (when result [:pre result])])

(defn- assistant-view [{:keys [i text calls]} results]
  (into [:div.message.assistant {:replicant/key i}]
        (concat (markdown text)
                (map (fn [call] (tool-view call (get results (:id call)))) calls))))

(defn- images-view [id images]
  (when (seq images)
    (into [:div.photos]
          (map (fn [file]
                 [:img {:src (str "/api/agents/" id "/images/" file)
                        :alt "Attached image" :loading "lazy"}]))
          images)))

(defn- message-view [id called results {:keys [i role text call images] :as message}]
  (case role
    "user" [:div.message.user {:replicant/key i} text (images-view id images)]
    "tool" (when-not (called call)
             [:div.message {:replicant/key i}
              [:details.tool [:summary "Tool result"] [:pre text]]])
    (assistant-view message results)))

(defn- pending-view [session {:keys [id text images]}]
  [:div {:replicant/key id}
   [:div.message.user.queued text (images-view session images)]
   [:p.note "Waiting for the agent"]])

(defn- thread-view [id {:keys [messages pending]}]
  (let [results (into {} (for [m messages :when (= "tool" (:role m))] [(:call m) (:text m)]))
        called (set (for [m messages call (:calls m)] (:id call)))]
    [:main#thread.thread
     (when (pos? (:i (first messages) 0))
       [:button.earlier {:on {:click #(load-earlier! id)}} "Load earlier"])
     (map #(message-view id called results %) messages)
     (map #(pending-view id %) pending)]))

(defn- tab-view [current {:keys [id project title busy]}]
  [:button.tab {:replicant/key id
                :class (when (= id current) "current")
                :on {:click #(select! id)}}
   [:span.tab-project [:span.dot {:class (when busy "busy")}] project]
   [:span.tab-title title]])

(defn- attachments-view [id urls]
  (when (seq urls)
    (into [:div.attachments]
          (map-indexed (fn [n url]
                         [:div.attachment {:replicant/key url}
                          [:img {:src url :alt "Image to send"}]
                          [:button {:type "button" :aria-label "Remove image"
                                    :on {:click #(detach! id n)}} "×"]]))
          urls)))

(defn- composer-view [id sending]
  [:form.composer {:replicant/key id
                   :on {:submit (fn [e] (.preventDefault e) (send! id))}}
   [:textarea {:placeholder "Message"
               :aria-label "Message"
               :replicant/on-mount (fn [{:replicant/keys [node]}]
                                     (set! (.-value node) (get @drafts id "")))
               :on {:input (fn [e] (swap! drafts assoc id (.. e -target -value)))
                    :keydown (fn [e]
                               (when (and (= "Enter" (.-key e)) (or (.-metaKey e) (.-ctrlKey e)))
                                 (.preventDefault e)
                                 (send! id)))}}]
   [:div.composer-actions
    [:button {:type "submit" :disabled sending} (if sending "Sending" "Send")]
    [:label.attach
     [:input {:type "file" :accept "image/*" :multiple true
              :on {:change (fn [e] (attach! id (.-target e)))}}]
     "Photo"]]])

(defn- status-view [live busy error]
  (cond error [:div.status.error error]
        (not live) [:div.status "This session ended"]
        busy [:div.status "Working"]))

(defn- show! [view]
  (.replaceState js/history nil "" (if (vector? view) (str "#" (second view)) "#inbox"))
  (swap! state assoc :view view)
  (when (vector? view) (sync-thread! (second view))))

(defn- age-text [iso]
  (when iso
    (let [s (quot (- (js/Date.now) (.getTime (js/Date. iso))) 1000)]
      (cond (< s 60) "now" (< s 3600) (str (quot s 60) "m") (< s 86400) (str (quot s 3600) "h") :else (str (quot s 86400) "d")))))

(defn- first-line [s] (first (str/split-lines (str s))))

(defn- row-view [{:keys [id title line came-back updated-at now]}]
  [:button.row {:replicant/key id :on {:click #(show! [:thread id])}}
   [:span.row-head [:span.handle id] [:span.row-title title]
    (when now [:span.moved (str "→ " (str/replace (str now) "-" " "))])
    [:span.age (age-text updated-at)]]
   (when line
     [:span.row-line (first-line line) (when came-back " · came back")])])

(defn- say!
  "Send the Inbox composer's text to the Router, or to the thread it names."
  []
  (let [text (str/trim (get @drafts :say ""))]
    (when-not (str/blank? text)
      (swap! state assoc :saying true)
      (-> (request "/api/super/say" {:method "POST" :headers {"Content-Type" "application/json"}
                                     :body (js/JSON.stringify (clj->js {:id (str "s-" (random-uuid)) :text text}))})
          (.then (fn [r]
                   (swap! drafts dissoc :say)
                   (when-let [el (.querySelector js/document ".say textarea")] (set! (.-value el) ""))
                   (swap! state assoc :said (if (:thread r) (str "Sent to " (:thread r)) "Sent to the Router"))))
          (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))
          (.finally (fn [] (swap! state assoc :saying false) (sync-inbox!)))))))

(defn- say-view [{:keys [saying said]}]
  [:form.composer.say {:on {:submit (fn [e] (.preventDefault e) (say!))}}
   [:textarea {:placeholder "Tell the Router, or \"T12 ...\" for a thread" :aria-label "Message to the Router"
               :replicant/on-mount (fn [{:replicant/keys [node]}] (set! (.-value node) (get @drafts :say "")))
               :on {:input (fn [e] (swap! drafts assoc :say (.. e -target -value)))
                    :keydown (fn [e] (when (and (= "Enter" (.-key e)) (or (.-metaKey e) (.-ctrlKey e))) (.preventDefault e) (say!)))}}]
   [:div.composer-actions
    [:button {:type "submit" :disabled saying} (if saying "Sending" "Send")]
    (when said [:span.said said])]])

(defn- reveal! []
  (local! "super-shown" [])
  (sync-inbox!))

(defn- inbox-view [{:keys [inbox inbox-at inbox-error]}]
  (let [old? (and inbox-at (> (- (js/Date.now) inbox-at) stale-ms))]
    [:main#thread.thread.inbox
     (cond inbox-error [:p.empty inbox-error]
           (nil? inbox) [:p.empty "Loading the inbox"]
           (empty? (:sections inbox)) [:p.empty "Nothing in the inbox. Start a thread from the Router pane."])
     (when old? [:p.stale (str "Data from " (age-text (.toISOString (js/Date. inbox-at))) " ago")])
     (when (pos? (:fresh inbox 0))
       [:button.fresh {:type "button" :on {:click reveal!}} (str (:fresh inbox) " new")])
     (for [{:keys [section title threads]} (:sections inbox)]
       [:section.group {:replicant/key section}
        [:h2 {:class (when (#{"stopped" "holding-up" "waiting"} section) "needs-you")} title]
        (into [:div] (map row-view) threads)])]))

(defn- picks! [picks]
  (local! "super-picks" picks)
  (swap! state assoc :picks picks))

(defn- pick! [thread ask option]
  (picks! (assoc-in (:picks @state) [thread ask] option))
  (swap! state update :conflicts dissoc thread))

(defn- option-label [{:keys [options]} option]
  (or (some #(when (= option (:id %)) (:label %)) options) option))

(defn- picked-labels
  "The labels of PICKS, a map of ask id to option id, looked up in ASKS."
  [asks picks]
  (for [[ask option] picks]
    (option-label (some #(when (= (name ask) (:id %)) %) asks) option)))

(defn- ask-card [thread picks replies {:keys [id text options recommended state stopped kind]}]
  (let [open? (= "open" state)
        picked (if open?
                 (get-in picks [thread id])
                 (some #(get-in % [:picks (keyword id)]) (reverse replies)))]
    [:div.ask {:replicant/key id :class (when stopped "stopped")}
     [:div.ask-label (str (name kind) (when stopped " · stopped") (when-not open? (str " · " state)))]
     [:div.ask-text text]
     (into [:div.choices]
           (map (fn [{oid :id label :label}]
                  [:button.choice {:replicant/key oid :type "button" :disabled (not open?)
                                   :class [(when (= oid recommended) "rec") (when (= oid picked) "picked")]
                                   :on {:click #(pick! thread id oid)}}
                   label
                   (when (= oid recommended) [:span.rectag "recommended"])
                   (when (= oid picked) [:span.rectag.picked-tag "picked"])]))
           options)]))

(defn- event-view [thread picks asks replies {n :seq :keys [kind data]}]
  (let [ask (when (= "ask-opened" kind) (some #(when (= (:id data) (:id %)) %) asks))]
    (case kind
      "thread-created" [:div.ev {:replicant/key n} [:span.ev-who "Thread"] (:title data)]
      "worker-attached" [:div.ev {:replicant/key n} [:span.ev-who (:id data)] "Worker attached"]
      "ask-opened" (when ask (ask-card thread picks replies ask))
      "reply-sent" [:div.ev.you {:replicant/key n} [:span.ev-who "You"]
                    (str/join "; " (remove str/blank? [(when (seq (:picks data)) (str "picked " (str/join ", " (picked-labels asks (:picks data)))))
                                                        (when (seq (:dismiss data)) "marked read")
                                                        (:note data)]))]
      "reply-answered" [:div.ev {:replicant/key n} [:span.ev-who "Worker"] "read your reply"]
      "note" [:div.ev {:replicant/key n} [:span.ev-who "Progress"] (:text data)]
      "milestone" [:div.ev.milestone {:replicant/key n} [:span.ev-who "Milestone"] (:text data)]
      "thread-resolved" [:div.ev {:replicant/key n} [:span.ev-who "Archived"]]
      nil)))

(defn- post-super! [thread verb body]
  (swap! state update :busy-super conj thread)
  (-> (request (str "/api/super/threads/" thread "/" verb)
               {:method "POST" :headers {"Content-Type" "application/json"}
                :body (js/JSON.stringify (clj->js body))})
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))
      (.finally (fn []
                  (swap! state update :busy-super disj thread)
                  (sync-inbox!)))))

(defn- send-reply!
  "Send the saved picks and note on THREAD as one reply, with an id kept until
  the controller has it, so a retry is the same reply."
  [thread]
  (let [t (get-in @state [:threads thread])
        picks (get (:picks @state) thread {})
        note (get @drafts [:note thread] "")
        pending (local "super-pending" {})
        draft (js/JSON.stringify (clj->js {:picks picks :note note :asks-version (:asks-version t)}))
        ;; A retry of the same draft keeps its id, so the controller sees one reply.
        ;; A changed draft is a new reply; reusing the id would return the old one.
        id (or (when (= draft (get-in pending [thread "draft"])) (get-in pending [thread "id"]))
               (str "p-" (random-uuid)))]
    (local! "super-pending" (assoc pending thread {"id" id "draft" draft}))
    (swap! state update :busy-super conj thread)
    (-> (js/fetch (str "/api/super/threads/" thread "/reply")
                  (clj->js {:method "POST" :headers {"Content-Type" "application/json"}
                            :body (js/JSON.stringify (clj->js {:id id :asks-version (:asks-version t) :picks picks :note note}))}))
        (.then (fn [response]
                 (-> (.json response)
                     (.then (fn [body]
                              (let [body (js->clj body :keywordize-keys true)]
                                (cond (.-ok response)
                                      (do (local! "super-pending" (dissoc (local "super-pending" {}) thread))
                                          (picks! (dissoc (:picks @state) thread))
                                          (swap! drafts dissoc [:note thread]))
                                      (= 409 (.-status response))
                                      (do (local! "super-pending" (dissoc (local "super-pending" {}) thread))
                                          (picks! (dissoc (:picks @state) thread))
                                          (swap! state assoc-in [:conflicts thread] body))
                                      :else (swap! state assoc :inbox-error (:error body)))))))))
        (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))
        (.finally (fn []
                    (swap! state update :busy-super disj thread)
                    (sync-inbox!))))))

(defn- conflict-view [thread {:keys [asks]} t]
  (let [answered (remove #(= "open" (:state %)) (:asks t))
        label (fn [{:keys [options picks]}] (str/join ", " (map (fn [[_ o]] (or (some #(when (= o (:id %)) (:label %)) options) o)) picks)))]
    [:p.conflict
     (str "Already answered"
          (when-let [a (first (filter #(#{"sent" "delivered" "answered"} (:state %)) answered))]
            (let [reply (last (filter #(contains? (:picks %) (:id a)) (:replies t)))]
              (when reply (str ": " (label {:options (:options a) :picks (:picks reply)})))))
          (when (empty? asks) ". Nothing is open."))]))

(defn- super-thread-view [{:keys [threads picks conflicts busy-super inbox-error]} id]
  (let [t (get threads id)
        busy (contains? busy-super id)
        open (filter #(= "open" (:state %)) (:asks t))
        archived (:resolved-at t)]
    [:div.super-thread
     [:main#thread.thread
      [:button.crumb {:on {:click #(show! :inbox)}} "‹ Inbox"]
      (if t
        (list [:h2.ttitle (str id " · " (:title t))]
              [:p.tmeta (str "execution " (:execution t) (when-let [w (last (:workers t))] (str " · " (:id w) " " (:state w))))]
              (when-let [c (get conflicts id)] (conflict-view id c t))
              (when inbox-error [:p.conflict inbox-error])
              (into [:div.events] (keep #(event-view id picks (:asks t) (:replies t) %)) (:events t)))
        [:p.empty (or inbox-error "Loading the thread")])]
     (when (and t (not archived))
       [:div.bar
        [:textarea.note-box {:placeholder "Note to the Worker" :aria-label "Note"
                             :replicant/on-mount (fn [{:replicant/keys [node]}] (set! (.-value node) (get @drafts [:note id] "")))
                             :on {:input (fn [e] (swap! drafts assoc [:note id] (.. e -target -value)))}}]
        [:div.bar-actions
         (when-let [r (last (filter #(= "review" (:kind %)) open))]
           [:button.send {:type "button" :disabled busy
                          :on {:click #(post-super! id "approve" {:id (str "ap-" (random-uuid)) :ask (:id r)
                                                                  :revision (:revision r) :evidence-sha (:evidence-sha r)})}}
            (str "Approve " (subs (str (:revision r)) 0 (min 8 (count (str (:revision r))))))])
         [:button.quiet {:type "button" :disabled (or busy (empty? open)) :on {:click #(post-super! id "dismiss" {:id (str "d-" (random-uuid))})}} "Mark read"]
         [:button.quiet {:type "button" :disabled busy :on {:click #(post-super! id "archive" {:id (str "a-" (random-uuid))})}} "Archive"]
         [:button.send {:type "button" :disabled (or busy (and (empty? (get picks id)) (str/blank? (get @drafts [:note id] ""))))
                        :on {:click #(send-reply! id)}} (if busy "Sending" "Send")]]])]))

(defn- app-view [{:keys [agents current conversations error attachments sending view inbox] :as s}]
  (let [conversation (get conversations current)
        live (live? s current)
        badge (:badge inbox 0)]
    (into [:div.shell
           [:header.masthead [:h1 "Agents"]]
           (into [:nav.tabs
                  [:button.tab.inbox-tab {:class (when view "current") :on {:click #(show! :inbox)}}
                   [:span.tab-project "Inbox" (when (pos? badge) [:span.badge badge])]]]
                 (map #(tab-view (when-not view current) %)) agents)]
          (cond (= :inbox view) [(inbox-view s) (say-view s)]
                (vector? view) [(super-thread-view s (second view))]
                :else [(cond current (thread-view current conversation)
                             (nil? agents) [:main#thread.thread (when error [:p.empty error])]
                             :else [:main#thread.thread
                                    [:p.empty "No live sessions. Start emacs-bbh and it appears here."]])
                       (when current (status-view live (:busy conversation) error))
                       (when live (attachments-view current (get attachments current)))
                       (when live (composer-view current sending))]))))

;; Dragging must not repaint the page, so the touch lives outside `state`.
(defonce touch (atom nil))

(def edge-px 24)
(def lock-px 10)
(def swipe-px 70)

(defn- scrolls-sideways? [node]
  (when-let [el (.closest node "pre, .table")]
    (> (.-scrollWidth el) (.-clientWidth el))))

(defn- swipeable? [event x]
  (let [node (.-target event)]
    (and (= 1 (.. event -touches -length))
         (.closest node "#thread")
         (< edge-px x (- js/innerWidth edge-px))
         (not (scrolls-sideways? node)))))

(defn- neighbour
  "The session STEP tabs from the current one, or nil at either end."
  [{:keys [agents current view]} step]
  (when-let [i (when-not view (first (keep-indexed (fn [i a] (when (= current (:id a)) i)) agents)))]
    (:id (get (vec agents) (+ i step)))))

(defn- drag! [dx]
  (let [end? (nil? (neighbour @state (if (neg? dx) 1 -1)))]
    (set! (.. (thread) -style -transform)
          (str "translateX(" (if end? (* 0.25 dx) dx) "px)"))))

(defn- touch-start [event]
  (let [finger (aget (.-touches event) 0)
        x (.-clientX finger)]
    (reset! touch (when (swipeable? event x)
                    {:x x :y (.-clientY finger) :dx 0 :axis nil}))))

(defn- touch-move [event]
  (when-let [{:keys [x y axis]} @touch]
    (let [finger (aget (.-touches event) 0)
          dx (- (.-clientX finger) x)
          dy (- (.-clientY finger) y)
          axis (or axis
                   (when (< lock-px (max (js/Math.abs dx) (js/Math.abs dy)))
                     (if (> (js/Math.abs dx) (js/Math.abs dy)) :x :y)))]
      (swap! touch assoc :dx dx :axis axis)
      (when (= :x axis) (drag! dx)))))

(defn- touch-cancel [_]
  (reset! touch nil)
  (when-let [el (thread)]
    (set! (.. el -style -transform) "")))

(defn- touch-end [event]
  (let [{:keys [dx axis]} @touch]
    (touch-cancel event)
    (when (and (= :x axis) (< swipe-px (js/Math.abs dx)))
      (when-let [id (neighbour @state (if (neg? dx) 1 -1))]
        (select! id)))))

(defn- near-bottom? []
  (when-let [el (thread)]
    (< (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 120)))

(defn- paint [_ _ _ new-state]
  (let [stick? (near-bottom?)]
    (r/render (.getElementById js/document "app") (app-view new-state))
    (when stick? (to-bottom!))))

(defonce started
  (do
    (swap! state assoc :picks (local "super-picks" {}))
    (let [hash (subs (.. js/location -hash) 1)]
      (cond (= "inbox" hash) (swap! state assoc :view :inbox)
            (re-matches #"T\d+" hash) (swap! state assoc :view [:thread hash])))
    (add-watch state :paint paint)
    (paint nil nil nil @state)
    (.addEventListener js/document "visibilitychange" sync!)
    (doseq [[event handler] {"touchstart" touch-start "touchmove" touch-move
                             "touchend" touch-end "touchcancel" touch-cancel}]
      (.addEventListener js/document event handler #js {:passive true}))
    (js/setInterval sync! poll-ms)
    (sync!)
    true))
