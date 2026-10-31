(ns app
  (:require [bbh.web.conversation :as conversation]
            [bbh.web.markdown :as md]
            [clojure.string :as str]
            [replicant.dom :as r]))

(def poll-ms 2000)

(defonce state (atom {:agents nil :current nil :conversations {} :error nil}))

;; Typing must not repaint the page, so drafts live outside `state`.
(defonce drafts (atom {}))

(def markdown (memoize md/render))

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

(defn- select!
  "Show session ID. Replacing the hash keeps tabs out of the browser's history."
  [id]
  (.replaceState js/history nil "" (str "#" id))
  (swap! state assoc :current id)
  (to-bottom!)
  (.scrollIntoView (.querySelector js/document ".tab.current")
                   #js {:inline "center" :block "nearest" :behavior "smooth"})
  (sync-conversation! id))

(defn- load-earlier! [id]
  (let [first-index (:i (first (get-in @state [:conversations id :messages])))
        el (thread)
        height (.-scrollHeight el)]
    (-> (request (str (conversation-path id) "?before=" first-index) {})
        (.then (fn [page]
                 (store-page! id page)
                 (set! (.-scrollTop el) (+ (.-scrollTop el) (- (.-scrollHeight el) height))))))))

(defn- send! [id]
  (let [text (str/trim (get @drafts id ""))]
    (when (seq text)
      (-> (request (conversation-path id)
                   {:method "POST"
                    :headers {"Content-Type" "application/json"}
                    :body (js/JSON.stringify (clj->js {:text text}))})
          (.then (fn [message]
                   (swap! drafts dissoc id)
                   (when-let [input (.querySelector js/document ".composer textarea")]
                     (set! (.-value input) ""))
                   (swap! state update-in [:conversations id :pending] (fnil conj []) message)
                   (to-bottom!)))
          (.catch (fn [e] (swap! state assoc :error (.-message e))))))))

(defn- first-line [code]
  (first (str/split-lines (str code " "))))

(defn- tool-view [{:keys [id code]} result]
  [:details.tool {:replicant/key id}
   [:summary (first-line code)]
   [:pre code]
   (when result [:pre result])])

(defn- assistant-view [{:keys [i text calls]} results]
  (into [:div.message.assistant {:replicant/key i}]
        (concat (markdown text)
                (map (fn [call] (tool-view call (get results (:id call)))) calls))))

(defn- message-view [called results {:keys [i role text call] :as message}]
  (case role
    "user" [:div.message.user {:replicant/key i} text]
    "tool" (when-not (called call)
             [:div.message {:replicant/key i}
              [:details.tool [:summary "Tool result"] [:pre text]]])
    (assistant-view message results)))

(defn- pending-view [{:keys [id text]}]
  [:div {:replicant/key id}
   [:div.message.user.queued text]
   [:p.note "Waiting for the agent"]])

(defn- thread-view [id {:keys [messages pending]}]
  (let [results (into {} (for [m messages :when (= "tool" (:role m))] [(:call m) (:text m)]))
        called (set (for [m messages call (:calls m)] (:id call)))]
    [:main#thread.thread
     (when (pos? (:i (first messages) 0))
       [:button.earlier {:on {:click #(load-earlier! id)}} "Load earlier"])
     (map #(message-view called results %) messages)
     (map pending-view pending)]))

(defn- tab-view [current {:keys [id project title busy]}]
  [:button.tab {:replicant/key id
                :class (when (= id current) "current")
                :on {:click #(select! id)}}
   [:span.tab-project [:span.dot {:class (when busy "busy")}] project]
   [:span.tab-title title]])

(defn- composer-view [id]
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
   [:button {:type "submit"} "Send"]])

(defn- status-view [live busy error]
  (cond error [:div.status.error error]
        (not live) [:div.status "This session ended"]
        busy [:div.status "Working"]))

(defn- app-view [{:keys [agents current conversations error] :as s}]
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
     (when live (composer-view current))]))

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
