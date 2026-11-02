(ns bbh.super.model
  "The super-agent's state machine, pure. `decide` turns a command into events
  or refuses it, `fold` applies one event, and `changed` names the records a
  fold touched so the store can materialize them. Nothing here knows SQL,
  sockets or mailboxes."
  (:require [clojure.string :as str]))

(def empty-state
  {:counters {} :threads {} :workers {} :asks {} :replies {} :outbox {} :locks {} :tokens {}})

(def entity-kinds [:counters :threads :workers :asks :replies :outbox :locks :tokens])

(defn- refuse [error & {:as more}]
  (throw (ex-info (name error) (assoc more :error error))))

;; ---- authorization ------------------------------------------------------

(def ^:private grants
  {:router #{:thread! :link! :attach-worker!}
   :worker #{:ask! :note! :milestone! :answered!}
   :controller #{:issue-token! :outbox-sent! :outbox-acked!}})

(defn authorize
  "Throw unless PRINCIPAL may run COMMAND on STATE. A Worker acts on its own
  thread only, and its :thread argument defaults to it."
  [state principal {:keys [command args] :as cmd}]
  (let [[kind id] (if (vector? principal) principal [principal])]
    (cond (= :owner kind) cmd
          (not (contains? (grants kind) command)) (refuse :forbidden :command command)
          (= :worker kind)
          (let [thread (:thread (get-in state [:workers id]))
                asked (or (:thread args) thread)]
            (when (not= asked thread) (refuse :forbidden :command command :thread asked))
            (-> cmd (assoc-in [:args :thread] thread) (assoc-in [:args :worker] id)))
          :else cmd)))

;; ---- decide -------------------------------------------------------------

(defn- next-id [state kind prefix]
  (str prefix (inc (get-in state [:counters kind] 0))))

(defn- thread-of [state id]
  (or (get-in state [:threads id]) (refuse :no-such-thread :thread id)))

(defn- reply-text
  "The mailbox message that carries REPLY to its Worker."
  [state {:keys [id thread picks note dismiss]}]
  (let [ask-line (fn [[ask option]]
                   (let [a (get-in state [:asks ask])
                         label (or (some #(when (= option (:id %)) (:label %)) (:options a)) option)]
                     (format "- %s: %s" (:text a) label)))]
    (str/join "\n" (remove str/blank?
                            [(format "[%s reply %s]" thread id)
                             (when (seq picks) (str/join "\n" (map ask-line picks)))
                             (when (seq dismiss) (str "Not answered, do not wait: " (str/join ", " dismiss)))
                             (when-not (str/blank? note) note)
                             (format "When this reply is handled, run (super/answered! \"%s\")." id)]))))

(defmulti decide*
  "Events and result for an authorized command, or a refusal."
  (fn [_state cmd] (:command cmd)))

(defmethod decide* :default [_ {:keys [command]}] (refuse :unknown-command :command command))

(defmethod decide* :thread! [state {{:keys [title]} :args}]
  (when (str/blank? title) (refuse :invalid :field :title))
  (let [id (next-id state :threads "T")]
    {:events [{:kind :thread-created :thread id :data {:id id :title title}}] :result id}))

(defmethod decide* :link! [state {{:keys [thread resource]} :args}]
  (thread-of state thread)
  (when (str/blank? resource) (refuse :invalid :field :resource))
  {:events [{:kind :thread-linked :thread thread :data {:resource resource}}] :result thread})

(defmethod decide* :attach-worker! [state {{:keys [thread conversation]} :args :keys [token-hash]}]
  (let [t (thread-of state thread)
        id (next-id state :workers "W")]
    (when (str/blank? conversation) (refuse :invalid :field :conversation))
    (when (:worker t) (refuse :thread-has-worker :thread thread :worker (:worker t)))
    {:events [{:kind :worker-attached :thread thread
               :data {:id id :conversation conversation :generation (inc (count (:history t)))}}
              {:kind :token-issued :thread thread :data {:hash token-hash :principal [:worker id]}}]
     :result {:id id}}))

(defmethod decide* :ask! [state {{:keys [thread worker kind text options recommended stopped holds-up]} :args}]
  (let [t (thread-of state thread)
        worker (or worker (:worker t))]
    (when-not (#{:question :action :review} kind) (refuse :invalid :field :kind))
    (when (str/blank? text) (refuse :invalid :field :text))
    (when-not (= worker (:worker t)) (refuse :no-active-worker :thread thread))
    (let [id (next-id state :asks "a")]
      {:events [{:kind :ask-opened :thread thread
                 :data (cond-> {:id id :worker worker :kind kind :text text :options (vec options)
                                :stopped (boolean stopped)}
                         recommended (assoc :recommended recommended)
                         holds-up (assoc :holds-up holds-up))}]
       :result id})))

(defmethod decide* :reply! [state {{:keys [thread asks-version picks note dismiss]} :args :keys [id]}]
  (let [t (thread-of state thread)
        open (into {} (filter (fn [[_ a]] (and (= thread (:thread a)) (= :open (:state a)))) (:asks state)))]
    (when (not= asks-version (:asks-version t))
      (refuse :stale :current (:asks-version t) :asks (vec (sort-by :seq (vals open)))))
    (doseq [[ask option] picks]
      (let [a (or (open ask) (refuse :no-such-ask :ask ask))]
        (when-not (some #(= option (:id %)) (:options a)) (refuse :no-such-option :ask ask :option option))))
    (doseq [ask dismiss] (when-not (open ask) (refuse :no-such-ask :ask ask)))
    (when (and (empty? picks) (empty? dismiss) (str/blank? note)) (refuse :invalid :field :picks))
    (when-not (:worker t) (refuse :no-active-worker :thread thread))
    (let [reply {:id id :thread thread :worker (:worker t) :picks (or picks {}) :note (or note "")
                 :dismiss (set dismiss)}
          outbox (next-id state :outbox "o")]
      {:events [{:kind :reply-sent :thread thread
                 :data (assoc reply :outbox outbox
                              :conversation (get-in state [:workers (:worker t) :conversation])
                              :text (reply-text state reply))}]
       :result id})))

(defmethod decide* :note! [state {{:keys [thread text]} :args}]
  (thread-of state thread)
  {:events [{:kind :note :thread thread :data {:text text}}] :result thread})

(defmethod decide* :milestone! [state {{:keys [thread text]} :args}]
  (thread-of state thread)
  {:events [{:kind :milestone :thread thread :data {:text text}}] :result thread})

(defmethod decide* :answered! [state {{:keys [thread reply]} :args}]
  (let [r (or (get-in state [:replies reply]) (refuse :no-such-reply :reply reply))]
    (when (not= thread (:thread r)) (refuse :forbidden :reply reply))
    {:events [{:kind :reply-answered :thread thread :data {:id reply}}] :result reply}))

(defmethod decide* :issue-token! [_ {{:keys [principal]} :args :keys [token-hash]}]
  {:events [{:kind :token-issued :data {:hash token-hash :principal principal}}] :result :issued})

(defmethod decide* :outbox-sent! [state {{:keys [outbox message]} :args}]
  (let [o (or (get-in state [:outbox outbox]) (refuse :no-such-outbox :outbox outbox))]
    {:events [{:kind :outbox-sent :thread (:thread o) :data {:id outbox :message message}}] :result outbox}))

(defmethod decide* :outbox-acked! [state {{:keys [outbox]} :args}]
  (let [o (or (get-in state [:outbox outbox]) (refuse :no-such-outbox :outbox outbox))]
    {:events [{:kind :outbox-acked :thread (:thread o) :data {:id outbox}}] :result outbox}))

(defn decide
  "Authorize and decide COMMAND for PRINCIPAL on STATE. Returns {:events :result}
  without :seq or :at on the events; the store assigns them. Throws ex-info
  with :error on refusal."
  [state principal cmd]
  (decide* state (authorize state principal cmd)))

;; ---- fold ---------------------------------------------------------------

(defn- bump [state kind] (update-in state [:counters kind] (fnil inc 0)))

(defn- set-states [state kind ids st]
  (reduce #(assoc-in %1 [kind %2 :state] st) state ids))

(defmulti fold
  "STATE after EVENT, which has :seq, :at, :kind, :thread and :data."
  (fn [_state event] (:kind event)))

(defmethod fold :thread-created [state {{:keys [id title]} :data :keys [seq]}]
  (-> state (bump :threads)
      (assoc-in [:threads id] {:id id :title title :resources #{} :worker nil :history []
                               :execution :none :asks-version seq :resolved-at nil :summary nil
                               :milestone-seq seq})))

(defmethod fold :thread-linked [state {{:keys [resource]} :data :keys [thread]}]
  (update-in state [:threads thread :resources] conj resource))

(defmethod fold :worker-attached [state {{:keys [id conversation generation]} :data :keys [thread]}]
  (-> state (bump :workers)
      (assoc-in [:workers id] {:id id :thread thread :generation generation :conversation conversation
                               :state :working :lease :router})
      (assoc-in [:threads thread :worker] id)
      (assoc-in [:threads thread :execution] :working)))

(defmethod fold :token-issued [state {{:keys [hash principal]} :data}]
  (assoc-in state [:tokens hash] {:id hash :principal principal :revoked false}))

(defmethod fold :ask-opened [state {{:keys [id stopped] :as ask} :data :keys [thread seq]}]
  (-> state (bump :asks)
      (assoc-in [:asks id] (assoc ask :thread thread :seq seq :state :open))
      (assoc-in [:threads thread :asks-version] seq)
      (assoc-in [:threads thread :milestone-seq] seq)
      (cond-> stopped (-> (assoc-in [:threads thread :execution] :waiting)
                          (assoc-in [:workers (:worker ask) :state] :waiting)))))

(defmethod fold :reply-sent [state {{:keys [id picks dismiss outbox conversation text] :as reply} :data :keys [thread seq]}]
  (-> state (bump :outbox)
      (assoc-in [:replies id] (-> reply (dissoc :outbox :conversation :text) (assoc :state :sent :outbox outbox :seq seq)))
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply id :conversation conversation :text text
                                  :state :pending :message nil})
      (set-states :asks (keys picks) :sent)
      (set-states :asks dismiss :dismissed)
      (assoc-in [:threads thread :asks-version] seq)))

(defmethod fold :note [state {{:keys [text]} :data :keys [thread]}]
  (assoc-in state [:threads thread :summary] text))

(defmethod fold :milestone [state {{:keys [text]} :data :keys [thread seq]}]
  (-> state (assoc-in [:threads thread :summary] text) (assoc-in [:threads thread :milestone-seq] seq)))

(defmethod fold :outbox-sent [state {{:keys [id message]} :data}]
  (update-in state [:outbox id] assoc :state :sent :message message))

(defmethod fold :outbox-acked [state {{:keys [id]} :data}]
  (let [reply (get-in state [:replies (get-in state [:outbox id :reply])])]
    (-> state (assoc-in [:outbox id :state] :acked)
        (assoc-in [:replies (:id reply) :state] :delivered)
        (set-states :asks (keys (:picks reply)) :delivered))))

(defmethod fold :reply-answered [state {{:keys [id]} :data :keys [thread]}]
  (let [reply (get-in state [:replies id])
        t (get-in state [:threads thread])
        still-open? (some #(and (= thread (:thread %)) (= :open (:state %)) (:stopped %)) (vals (:asks state)))]
    (-> state (assoc-in [:replies id :state] :answered)
        (assoc-in [:outbox (:outbox reply) :state] :processed)
        (set-states :asks (keys (:picks reply)) :answered)
        (cond-> (and (:worker t) (not still-open?))
          (-> (assoc-in [:threads thread :execution] :working)
              (assoc-in [:workers (:worker t) :state] :working))))))

(defn changed
  "[kind id record] for every record that differs between BEFORE and AFTER."
  [before after]
  (for [kind entity-kinds
        [id record] (kind after)
        :when (not= record (get-in before [kind id]))]
    [kind id record]))
