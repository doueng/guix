(ns app
  (:require [bbh.web.conversation :as conversation]
            [bbh.web.markdown :as md]
            [clojure.string :as str]
            [replicant.dom :as r]))

(def poll-ms 2000)
(def image-px 2048)
(def image-quality 0.85)

(defonce state (atom {:agents nil :current nil :conversations {} :error nil}))

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

(defonce syncing (atom false))

(defn- sync!
  []
  (when-not (or @syncing (.-hidden js/document))
    (reset! syncing true)
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
  (swap! state assoc :current id)
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

(defn- app-view [{:keys [agents current conversations error attachments sending] :as s}]
  (let [conversation (get conversations current)
        live (live? s current)]
    [:div.shell
     [:header.masthead [:h1 "Agents"]]
     (when (seq agents)
       (into [:nav.tabs] (map #(tab-view current %)) agents))
     (cond current (thread-view current conversation)
           (nil? agents) [:main#thread.thread (when error [:p.empty error])]
           :else [:main#thread.thread
                  [:p.empty "No live sessions. Start emacs-bbh and it appears here."]])
     (when current (status-view live (:busy conversation) error))
     (when live (attachments-view current (get attachments current)))
     (when live (composer-view current sending))]))

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
  [{:keys [agents current]} step]
  (when-let [i (first (keep-indexed (fn [i a] (when (= current (:id a)) i)) agents))]
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
    (add-watch state :paint paint)
    (paint nil nil nil @state)
    (.addEventListener js/document "visibilitychange" sync!)
    (doseq [[event handler] {"touchstart" touch-start "touchmove" touch-move
                             "touchend" touch-end "touchcancel" touch-cancel}]
      (.addEventListener js/document event handler #js {:passive true}))
    (js/setInterval sync! poll-ms)
    (sync!)
    true))
