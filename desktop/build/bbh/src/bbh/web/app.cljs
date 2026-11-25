(ns app
  (:require [bbh.web.conversation :as conversation]
            [bbh.web.markdown :as md]
            [clojure.string :as str]
            [replicant.dom :as r]))

(def poll-ms 2000)
(def image-px 2048)
(def image-quality 0.85)

(defonce state (atom {:agents nil :current nil :conversations {} :error nil :live false :filter "all"
                      :view [:inbox] :inbox nil :inbox-at nil :inbox-error nil :threads {} :conflicts {}
                      :busy-super #{} :outgoing {} :kept-unread {} :docs nil :docs-error nil :doc-query ""}))

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
  (let [hash-id (str/replace (.. js/location -hash) #"^#s/" "")]
    (or current (some #{hash-id} (map :id agents)) (:id (first agents)))))

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
  (.replaceState js/history nil "" (str "#s/" id))
  (swap! state assoc :current id :view [:session id])
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

(def fallback-ms 30000)
(def undo-ms 4000)

(defn- route
  "The view a location HASH names: the inbox, a thread, a session, the docs or a doc."
  [hash]
  (let [h (str/replace (str hash) #"^#" "")]
    (cond (re-matches #"T\d+" h) [:thread h]
          (str/starts-with? h "s/") [:session (subs h 2)]
          (= h "docs") [:docs]
          (str/starts-with? h "doc/") [:doc (js/decodeURIComponent (subs h 4))]
          :else [:inbox])))

(defonce depth (atom 0))

(defn- go!
  "Open HASH as a new history entry, so the phone's back button returns."
  [hash]
  (swap! depth inc)
  (set! (.-hash js/location) hash))

(defn- back!
  "Back to where the page came from, else to HASH, the inbox by default."
  [& [hash]]
  (if (pos? @depth)
    (do (swap! depth dec) (.back js/history))
    (do (.replaceState js/history nil "" (or hash "#inbox"))
        (.dispatchEvent js/window (js/HashChangeEvent. "hashchange")))))

(declare sync-inbox! sync-agents!)

(defn- post-super! [thread verb body]
  (swap! state update :busy-super conj thread)
  (-> (request (str "/api/super/threads/" thread "/" verb)
               {:method "POST" :headers {"Content-Type" "application/json"}
                :body (js/JSON.stringify (clj->js body))})
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))
      (.finally (fn []
                  (swap! state update :busy-super disj thread)
                  (sync-inbox!)))))

(defn- sync-thread!
  "Fetch thread ID, which is open, and mark it read in the controller, which
  every view shares."
  [id]
  (-> (request (str "/api/super/threads/" id) {})
      (.then (fn [t]
               (swap! state assoc-in [:threads id] t)
               (swap! state assoc :inbox-error nil)
               (when (and (> (:milestone-seq t 0) (:read-seq t 0)) (not (get-in @state [:kept-unread id])))
                 (post-super! id "read" {}))))
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))))

(defn- open-thread [{:keys [view]}]
  (when (= :thread (first view)) (second view)))

(defn- sync-inbox! []
  (-> (request "/api/super/threads" {})
      (.then (fn [inbox]
               (swap! state assoc :inbox inbox :inbox-at (js/Date.now) :inbox-error nil)
               (set! (.-title js/document) (if (pos? (:badge inbox 0)) (str "(" (:badge inbox) ") bbh") "bbh"))))
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))))

(defn- sync-super!
  "Refetch the list and the open thread."
  []
  (sync-inbox!)
  (when-let [id (open-thread @state)] (sync-thread! id)))

(defonce stream (atom nil))
(defonce soon (atom nil))

(defn- listen!
  "Follow the controller's changes. A burst of them refetches once."
  []
  (when-not @stream
    (let [es (js/EventSource. "/api/super/stream")]
      (reset! stream es)
      (set! (.-onopen es) (fn [] (swap! state assoc :live true) (sync-super!)))
      (set! (.-onerror es) (fn [] (swap! state assoc :live false)))
      (.addEventListener es "changed"
                         (fn [] (js/clearTimeout @soon)
                           (reset! soon (js/setTimeout #(do (sync-super!) (sync-agents!)) 250)))))))

(def glyphs {"on-you" "●" "working" "◐" "stuck" "✖" "done" "✓" "archived" "▪" "quiet" "·"})

(def needs-you #{"stopped" "holding-up" "waiting" "needs-attention"})

(defn- age-text [iso]
  (when iso
    (let [s (quot (- (js/Date.now) (.getTime (js/Date. iso))) 1000)]
      (cond (< s 60) "now" (< s 3600) (str (quot s 60) "m") (< s 86400) (str (quot s 3600) "h") :else (str (quot s 86400) "d")))))


(defn- row-view [selected {:keys [id title line status unread updated-at came-back model final-preview]}]
  [:a.row {:replicant/key id :href (str "#" id)
           :class [(when unread "unread") (when (= id selected) "sel")]
           :on {:click (fn [e] (.preventDefault e) (go! (str "#" id)))}}
   [:span.g {:class status} (glyphs status "·")]
   [:span.id id]
   [:span.t title (when model [:span.model {:title model} (str " · " (last (str/split model #"/")))])]
   [:span.age (age-text updated-at)]
   (cond (and line (#{"on-you" "stuck"} status))
         [:span.ln (first (str/split-lines (str line))) (when came-back " · came back")]
         final-preview [:span.ln.last final-preview])])

(defn- filter! [f]
  (.setItem js/localStorage "inbox-filter" f)
  (swap! state assoc :filter f))

(defn- chip [current f label]
  [:button.chip {:type "button" :class (when (= f current) "on") :on {:click #(filter! f)}} label])

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
                   (when-let [el (.querySelector js/document ".say input")] (set! (.-value el) ""))
                   (swap! state assoc :said (if (:thread r) (str "Sent to " (:thread r)) "Sent to the Router"))))
          (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))
          (.finally (fn [] (swap! state assoc :saying false) (sync-inbox!)))))))

(defn- say-view [{:keys [saying said]}]
  [:form.say {:on {:submit (fn [e] (.preventDefault e) (say!))}}
   [:input.txt {:placeholder (or said "Tell the Router…") :aria-label "Message to the Router" :enterkeyhint "send"
                :replicant/on-mount (fn [{:replicant/keys [node]}] (set! (.-value node) (get @drafts :say "")))
                :on {:input (fn [e] (swap! drafts assoc :say (.. e -target -value)))}}]
   [:button.send {:type "submit" :disabled saying} (if saying "…" "Send")]])

(defn- list-view [{:keys [inbox inbox-error live agents] :as s}]
  (let [f (:filter s "all")
        sections (:sections inbox)
        rows-of (fn [pred] (mapcat :threads (filter #(pred (:section %)) sections)))
        n-you (count (rows-of needs-you))
        n-archived (count (rows-of #{"archived"}))
        shown (case f
                "you" (filter #(needs-you (:section %)) sections)
                "archived" (filter #(= "archived" (:section %)) sections)
                (remove #(= "archived" (:section %)) sections))
        selected (open-thread s)]
    [:section.list
     [:header.lh
      [:h1 (if (pos? n-you) (str n-you " need you") "bbh")]
      (when (seq agents)
        [:a.link {:href (str "#s/" (:id (first agents)))
                  :on {:click (fn [e] (.preventDefault e) (go! (str "#s/" (or (:current s) (:id (first agents))))))}}
         "Sessions"])
      [:a.link {:href "#docs" :on {:click (fn [e] (.preventDefault e) (go! "#docs"))}} "Docs"]
      [:span.live {:class (when-not live "off")} (if live "live" "offline")]]
     [:div.chips (chip f "all" "All") (chip f "you" (str "Needs you " n-you)) (chip f "archived" (str "Archived " n-archived))]
     (cond inbox-error [:p.empty inbox-error]
           (nil? inbox) [:p.empty "Loading"]
           (empty? shown) [:p.empty (if (= f "you") "Nothing needs you" "No threads")])
     (for [{:keys [section title threads]} shown]
       [:div {:replicant/key section}
        [:div.sec {:class (when (needs-you section) "you")} title]
        (map #(row-view selected %) threads)])
     (when (and (= f "all") (pos? n-archived))
       [:button.fold {:type "button" :on {:click #(filter! "archived")}} (str "▸ " n-archived " archived")])
     (say-view s)]))

(defonce timers (atom {}))

(defn- send-reply!
  "Send BODY as THREAD's reply now. A stale reply is shown as a conflict."
  [thread body]
  (swap! state update :busy-super conj thread)
  (-> (js/fetch (str "/api/super/threads/" thread "/reply")
                (clj->js {:method "POST" :headers {"Content-Type" "application/json"}
                          :body (js/JSON.stringify (clj->js body))}))
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [b]
                            (let [b (js->clj b :keywordize-keys true)]
                              (cond (.-ok response) (swap! state update :conflicts dissoc thread)
                                    (= 409 (.-status response)) (swap! state assoc-in [:conflicts thread] b)
                                    :else (swap! state assoc :inbox-error (:error b)))))))))
      (.catch (fn [e] (swap! state assoc :inbox-error (.-message e))))
      (.finally (fn []
                  (swap! state update :busy-super disj thread)
                  (sync-super!)))))

(defn- queue-reply!
  "Send THREAD a reply after undo-ms, unless Undo comes first. LABEL says
  what is being sent. A second answer replaces the first one still waiting."
  [thread label body]
  (let [t (get-in @state [:threads thread])
        body (merge {:id (str "p-" (random-uuid)) :asks-version (:asks-version t) :picks {} :note ""} body)]
    (js/clearTimeout (get @timers thread))
    (swap! timers assoc thread
           (js/setTimeout (fn []
                            (swap! timers dissoc thread)
                            (swap! state update :outgoing dissoc thread)
                            (send-reply! thread body))
                          undo-ms))
    (swap! state assoc-in [:outgoing thread] {:label label :body body})))

(defn- undo! [thread]
  (js/clearTimeout (get @timers thread))
  (swap! timers dissoc thread)
  (swap! state update :outgoing dissoc thread))

(defn- ask-card [thread outgoing busy {:keys [id text options recommended stopped kind details evidence revision evidence-sha]}]
  (let [waiting (when outgoing (some (fn [[k v]] (when (= (name k) id) v)) (get-in outgoing [:body :picks])))
        answer-key [:answer thread id]]
    [:div.ask {:replicant/key id :class (when stopped "stopped")}
     [:div.kind (str (name kind) (when stopped " · stopped"))]
     [:div.q text]
     (when (or details evidence)
       [:details [:summary "Details"]
        (when details [:p.detail details])
        (when evidence [:p.evidence evidence])])
     (when (= "review" (name kind))
       [:button.opt.rec {:type "button" :disabled busy
                         :on {:click #(post-super! thread "approve" {:id (str "ap-" (random-uuid)) :ask id
                                                                     :revision revision :evidence-sha evidence-sha})}}
        (str "Approve " (subs (str revision) 0 (min 8 (count (str revision)))))])
     (into [:div.opts]
           (map (fn [{oid :id label :label}]
                  [:button.opt {:replicant/key oid :type "button" :disabled busy
                                :class [(when (= oid recommended) "rec") (when (= oid waiting) "picked")]
                                :on {:click #(queue-reply! thread label {:picks {id oid}})}}
                   [:span label]
                   (when (= oid recommended) [:span.tag "★ recommended"])]))
           options)
     [:form.own {:on {:submit (fn [e]
                                (.preventDefault e)
                                (let [v (str/trim (get @drafts answer-key ""))]
                                  (when-not (str/blank? v)
                                    (swap! drafts dissoc answer-key)
                                    (set! (.-value (.querySelector (.-target e) "input")) "")
                                    (queue-reply! thread v {:answers {id v}}))))}}
      [:input.txt {:placeholder "Your own answer" :aria-label (str "Answer to " id) :enterkeyhint "send"
                   :replicant/on-mount (fn [{:replicant/keys [node]}] (set! (.-value node) (get @drafts answer-key "")))
                   :on {:input (fn [e] (swap! drafts assoc answer-key (.. e -target -value)))}}]
      [:button.send {:type "submit" :disabled busy} "Send"]]]))

(def progress-kinds
  {"milestone" "milestone" "note" "note" "ask-opened" "asked" "reply-sent" "replied" "worker-started" "started"
   "worker-finished" "finished" "worker-steered" "steered" "asks-answered-in-chat" "in chat"})

(defn- progress-text [asks {:keys [kind data]}]
  (case kind
    ("milestone" "note") (:text data)
    "ask-opened" (:text (some #(when (= (:id data) (:id %)) %) asks) (:text data))
    "reply-sent" (str/join "; " (remove str/blank? (concat (for [[a o] (:picks data)
                                                                   :let [ask (some #(when (= (name a) (:id %)) %) asks)]]
                                                               (or (some #(when (= o (:id %)) (:label %)) (:options ask)) o))
                                                           (vals (:answers data))
                                                           [(when (seq (:dismiss data)) "dismissed") (:note data)])))
    "worker-started" (str (:id data) " started")
    "worker-finished" (str (:id data) " " (:state data) (when-let [l (:landed data)] (str ", landed " (subs l 0 (min 8 (count l))))))
    "worker-steered" "by the Router"
    "asks-answered-in-chat" "answered in the Worker's chat"
    ""))

(defn- clock [iso]
  (when iso
    (let [d (js/Date. iso)
          pad #(.padStart (str %) 2 "0")]
      (str (pad (.getHours d)) ":" (pad (.getMinutes d))))))

(defn- progress-view [t]
  (let [evs (take-last 8 (filter (comp progress-kinds :kind) (:events t)))]
    (when (seq evs)
      (list [:h2 "Progress"]
            (for [{n :seq :keys [kind at] :as e} evs]
              [:div.ev {:replicant/key n :class (when (= "milestone" kind) "ms")}
               [:span.w (clock at)]
               [:span [:span.k (progress-kinds kind)] " " (progress-text (:asks t) e)]])))))

(defn- note-view [id busy]
  [:form.own.noted {:on {:submit (fn [e]
                                  (.preventDefault e)
                                  (let [v (str/trim (get @drafts [:note id] ""))]
                                    (when-not (str/blank? v)
                                      (swap! drafts dissoc [:note id])
                                      (set! (.-value (.querySelector (.-target e) "textarea")) "")
                                      (queue-reply! id "note" {:note v}))))}}
   [:textarea {:placeholder "Note to the Worker" :aria-label "Note" :rows 2
               :replicant/on-mount (fn [{:replicant/keys [node]}] (set! (.-value node) (get @drafts [:note id] "")))
               :on {:input (fn [e] (swap! drafts assoc [:note id] (.. e -target -value)))}}]
   [:button.send {:type "submit" :disabled busy} "Send"]])

(defn- status-text [{:keys [status execution]}]
  (case status "on-you" "waiting on you" "stuck" "needs attention" (or execution status)))

(defn- detail-view [{:keys [threads conflicts busy-super inbox-error outgoing agents inbox live]} id]
  (let [t (get threads id)
        row (some #(when (= id (:id %)) %) (mapcat :threads (:sections inbox)))
        busy (contains? busy-super id)
        open (sort-by :seq (filter #(= "open" (:state %)) (:asks t)))
        w (last (:workers t))
        session (some #(when (= (:conversation w) (:id %)) (:id %)) agents)]
    [:section.detail
     [:header.dh
      [:div.top
       [:button.back {:type "button" :on {:click #(back!)}} "‹ Inbox"]
       [:span.id id]
       [:span.live {:class (when-not live "off")} (if live "live" "offline")]]
      [:h1 (or (:title t) (:title row))]
      [:div.meta
       [:span.g {:class (:status row)} (glyphs (:status row) "·")] " " (status-text (merge {:execution (:execution t)} row))
       (when w (str " · " (:id w) " " (name (:state w))))
       (when session
         (list " · " [:a {:href (str "#s/" session)
                          :on {:click (fn [e] (.preventDefault e) (go! (str "#s/" session)))}} "Transcript ›"]))]]
     [:div.body
      (cond (nil? t) [:p.empty (or inbox-error "Loading")]
            :else
            (list
             (when-let [c (get conflicts id)]
               [:p.conflict (str "Already answered elsewhere" (when (empty? (:asks c)) "; nothing is open"))])
             (when inbox-error [:p.conflict inbox-error])
             (map #(ask-card id (get outgoing id) busy %) open)
             (when (and (empty? open) (:summary t)) [:p.summary (:summary t)])
             (when-let [text (:final t)]
               [:div.final [:h2 (str "Last message from " (:final-from t))] (into [:div.md] (markdown text))])
             (progress-view t)
             (when-not (:resolved-at t)
               (list
                (note-view id busy)
                [:div.acts
                 [:button {:type "button" :disabled busy
                           :on {:click (fn [] (swap! state assoc-in [:kept-unread id] true)
                                         (post-super! id "read" {:read false}))}} "Unread"]
                 (when (seq open)
                   [:button {:type "button" :disabled busy :on {:click #(post-super! id "dismiss" {:id (str "d-" (random-uuid))})}} "Dismiss"])
                 [:button {:type "button" :disabled busy :on {:click #(post-super! id "archive" {:id (str "a-" (random-uuid))})}} "Archive"]]))))]]))

(defn- toast-view [{:keys [outgoing]}]
  (when-let [[thread {:keys [label]}] (first outgoing)]
    [:div.toast [:span "Sending " [:b label]]
     [:button {:type "button" :on {:click #(undo! thread)}} "Undo"]]))

(defn- empty-detail [{:keys [inbox]}]
  [:section.detail.none
   [:p.empty (if (pos? (:badge inbox 0)) "Pick a thread" (str "Nothing needs you · " (clock (.toISOString (js/Date.)))))]])

(defn- session-view [{:keys [agents current conversations error attachments sending] :as s}]
  (let [conversation (get conversations current)
        live (live? s current)]
    [:div.shell
     [:header.masthead [:button.back {:type "button" :on {:click #(back!)}} "‹ Inbox"] [:h1 "Sessions"]]
     (into [:nav.tabs] (map #(tab-view current %)) agents)
     (cond current (thread-view current conversation)
           (nil? agents) [:main#thread.thread (when error [:p.empty error])]
           :else [:main#thread.thread [:p.empty "No live sessions."]])
     (when current (status-view live (:busy conversation) error))
     (when live (attachments-view current (get attachments current)))
     (when live (composer-view current sending))]))

(defn- sync-docs! []
  (-> (request "/api/docs" {})
      (.then (fn [docs] (swap! state assoc :docs docs :docs-error nil)))
      (.catch (fn [e] (swap! state assoc :docs-error (.-message e))))))

(defn- doc-hash [path]
  (str "#doc/" (js/encodeURIComponent path)))

(defn- home-path [path]
  (str/replace path #"^/home/[^/]+/" "~/"))

(defn- doc-row [{:keys [path title description thread modified temporary]}]
  [:a.row.doc {:replicant/key path :href (doc-hash path)
               :on {:click (fn [e] (.preventDefault e) (go! (doc-hash path)))}}
   [:span.t title]
   [:span.age (age-text modified)]
   (when (seq description) [:span.ds description])
   [:span.src (when thread [:b thread]) (when temporary [:b.tmp "temporary"]) (home-path path)]])

(defn- matches? [query doc]
  (str/includes? (str/lower-case (str/join " " ((juxt :title :description :path :thread) doc))) query))

(defn- docs-view [{:keys [docs docs-error doc-query]}]
  (let [q (str/lower-case (str/trim (str doc-query)))
        shown (filter #(matches? q %) docs)]
    [:div.shell.docs
     [:header.masthead [:button.back {:type "button" :on {:click #(back!)}} "‹ Inbox"] [:h1 "Docs"]]
     [:input.txt.search {:type "search" :placeholder "Search docs" :aria-label "Search docs"
                         :replicant/on-mount (fn [{:replicant/keys [node]}] (set! (.-value node) doc-query))
                         :on {:input (fn [e] (swap! state assoc :doc-query (.. e -target -value)))}}]
     [:main.doclist
      (cond docs-error [:p.empty docs-error]
            (nil? docs) [:p.empty "Loading"]
            (empty? shown) [:p.empty (if (seq q) "No doc matches" "No docs yet")])
      (map doc-row shown)]]))

(defn- doc-view [{:keys [docs]} path]
  (let [{:keys [title temporary]} (some #(when (= path (:path %)) %) docs)]
    [:div.reader
     [:header.masthead [:button.back {:type "button" :on {:click #(back! "#docs")}} "‹ Docs"]
      [:h1 (or title (home-path path))]
      (when temporary [:b.tmp "temporary"])]
     [:iframe.page {:src (str "/docs/view?path=" (js/encodeURIComponent path)) :title (or title "Doc")}]]))

(defn- app-view [{:keys [view] :as s}]
  (case (first view)
    :session (session-view s)
    :docs (docs-view s)
    :doc (doc-view s (second view))
    (let [id (open-thread s)]
      [:div.app {:class (when id "show-detail")}
       (list-view s)
       (if id (detail-view s id) (empty-detail s))
       (toast-view s)])))

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
  (when-let [i (when (= :session (first view)) (first (keep-indexed (fn [i a] (when (= current (:id a)) i)) agents)))]
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

(defn- sync-agents! []
  (-> (request "/api/agents" {})
      (.then (fn [agents]
               (swap! state (fn [s] (let [s (assoc s :agents agents :error nil)]
                                      (cond-> s (= :session (first (:view s))) (assoc :current (pick-current s))))))
               (let [{:keys [current view] :as s} @state]
                 (when (and (= :session (first view)) (live? s current))
                   (sync-conversation! current)))))
      (.catch (fn [e] (swap! state assoc :error (.-message e))))))

(defonce syncing (atom false))

(defn- tick!
  "Poll what the stream does not push: a session while one is shown, and
  everything while the stream is down."
  []
  (when-not (or @syncing (.-hidden js/document))
    (let [{:keys [view live inbox-at]} @state]
      (when (or (= :session (first view)) (not live))
        (when (or (= :session (first view)) (> (- (js/Date.now) (or inbox-at 0)) fallback-ms))
          (reset! syncing true)
          (when-not live (sync-super!))
          (-> (sync-agents!) (.finally #(reset! syncing false))))))))

(defn- on-route! []
  (let [view (route (.. js/location -hash))]
    (when (= [:inbox] view) (reset! depth 0))
    (swap! state assoc :view view :kept-unread {})
    (case (first view)
      :thread (sync-thread! (second view))
      :session (do (swap! state assoc :current (second view))
                   (to-bottom!)
                   (sync-agents!))
      :docs (sync-docs!)
      :doc (when-not (:docs @state) (sync-docs!))
      nil)))

(defn- near-bottom? []
  (when-let [el (thread)]
    (< (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el)) 120)))

(defn- paint [_ _ _ new-state]
  (let [stick? (near-bottom?)]
    (r/render (.getElementById js/document "app") (app-view new-state))
    (when stick? (to-bottom!))))

(defonce started
  (do
    (swap! state assoc :filter (or (.getItem js/localStorage "inbox-filter") "all"))
    (add-watch state :paint paint)
    (on-route!)
    (.addEventListener js/window "hashchange" on-route!)
    (.addEventListener js/document "visibilitychange"
                       #(when-not (.-hidden js/document) (sync-super!) (sync-agents!)))
    (doseq [[event handler] {"touchstart" touch-start "touchmove" touch-move
                             "touchend" touch-end "touchcancel" touch-cancel}]
      (.addEventListener js/document event handler #js {:passive true}))
    (js/setInterval tick! poll-ms)
    (listen!)
    (sync-super!)
    (sync-agents!)
    true))
