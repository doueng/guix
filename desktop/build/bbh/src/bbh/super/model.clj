(ns bbh.super.model
  "The super-agent's state machine, pure. `decide` turns a command into events
  or refuses it, `fold` applies one event, and `changed` names the records a
  fold touched so the store can materialize them. Nothing here knows SQL,
  sockets or mailboxes."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def empty-state
  {:counters {} :threads {} :workers {} :asks {} :replies {} :outbox {} :locks {} :tokens {} :router {}})

(def entity-kinds [:counters :threads :workers :asks :replies :outbox :locks :tokens :router])

(defn- refuse [error & {:keys [message] :as more}]
  (throw (ex-info (or message (name error)) (assoc (dissoc more :message) :error error))))

;; ---- authorization ------------------------------------------------------

(def ^:private grants
  {:router #{:thread! :link! :attach-worker! :start-worker! :steer! :cancel! :note! :milestone!}
   :worker #{:ask! :note! :milestone! :answered! :finish-worker!}
   :controller #{:promote-worker! :restart-failed! :issue-token! :register-router! :outbox-sent! :outbox-acked! :worker-running! :worker-prepared! :job-exited! :health! :finish-worker! :restart-worker! :worker-restarted! :cancel-delivered! :thread! :start-worker!}})

(def default-policy
  "What policy.edn says when it is absent: the Worker cap, the locks a Worker
  may hold, the ones never granted (a system switch is always an ask), and how
  long a live Worker may leave a delivery in its mailbox before it needs you
  (a reply waits for the current turn, which can include a 10 minute job-wait),
  what each origin's Workers may do (an origin not named gets :read), and
  the capability each lock needs."
  {:workers 10 :locks ["guix-build" "repo-push"] :never-granted ["guix-system"] :stale-delivery-minutes 30
   :origins (assoc (zipmap [:owner :emacs :herdr :phone :voice] (repeat #{:read :edit-workspace :run-checks :publish})) :watchdog #{:read})
   :watchdog-after-minutes 10
   :lock-capabilities {"repo-push" :publish "guix-build" :run-checks}})

(defn authorize
  "Throw unless PRINCIPAL may run COMMAND on STATE. A Worker acts on its own
  thread and itself only; its :thread and :worker arguments default to them."
  [state principal {:keys [command args] :as cmd}]
  (let [[kind id] (if (vector? principal) principal [principal])]
    (cond (= :owner kind) cmd
          (not (contains? (grants kind) command)) (refuse :forbidden :command command)
          (= :worker kind)
          (let [thread (:thread (get-in state [:workers id]))
                asked (or (:thread args) thread)]
            (when (not= asked thread) (refuse :forbidden :command command :thread asked))
            (when (and (:worker args) (not= (:worker args) id)) (refuse :forbidden :command command :worker (:worker args)))
            (-> cmd (assoc-in [:args :thread] thread) (assoc-in [:args :worker] id)))
          :else cmd)))

;; ---- decide -------------------------------------------------------------

(defn- next-id [state kind prefix]
  (str prefix (inc (get-in state [:counters kind] 0))))

(defn- thread-of [state id]
  (or (get-in state [:threads id]) (refuse :no-such-thread :thread id)))

(defn- reply-text
  "The mailbox message that carries REPLY to its Worker."
  [state {:keys [id thread picks note dismiss approved]}]
  (let [ask-line (fn [[ask option]]
                   (let [a (get-in state [:asks ask])
                         label (or (some #(when (= option (:id %)) (:label %)) (:options a)) option)]
                     (format "- %s: %s" (:text a) label)))]
    (str/join "\n" (remove str/blank?
                            [(format "[%s reply %s]" thread id)
                             (when (seq picks) (str/join "\n" (map ask-line picks)))
                           (when (seq approved)
                             (str/join "\n" (for [[ask revision] approved]
                                               (format "Approved %s at revision %s (evidence %s)." ask revision
                                                       (subs (str (get-in state [:asks ask :evidence-sha]) "        ") 0 8)))))
                             (when (seq dismiss) (str "Not answered, do not wait: " (str/join ", " dismiss)))
                             (when-not (str/blank? note) note)
                             (format "When this reply is handled, run (super-answered! \"%s\")." id)]))))

(defmulti decide*
  "Events and result for an authorized command, or a refusal."
  (fn [_state cmd] (:command cmd)))

(defmethod decide* :default [_ {:keys [command]}] (refuse :unknown-command :command command))

(defmethod decide* :thread! [state {{:keys [title watchdog-of]} :args :keys [principal]}]
  (when (str/blank? title) (refuse :invalid :field :title))
  (when (and watchdog-of (not= :controller principal)) (refuse :forbidden :field :watchdog-of))
  (let [id (next-id state :threads "T")]
    {:events [{:kind :thread-created :thread id :data (cond-> {:id id :title title} watchdog-of (assoc :watchdog-of watchdog-of))}] :result id}))

(defmethod decide* :link! [state {{:keys [thread resource]} :args}]
  (thread-of state thread)
  (when (str/blank? resource) (refuse :invalid :field :resource))
  {:events [{:kind :thread-linked :thread thread :data {:resource resource}}] :result thread})

(declare thread-worker)

(defmethod decide* :attach-worker! [state {{:keys [thread conversation]} :args :keys [token-hash]}]
  (let [t (thread-of state thread)
        id (next-id state :workers "W")]
    (when (str/blank? conversation) (refuse :invalid :field :conversation))
    (when (:resolved-at t) (refuse :archived :thread thread :message (str thread " is archived; start a new thread")))
    (when-let [w (thread-worker state thread)] (refuse :thread-has-worker :thread thread :worker w))
    {:events [{:kind :worker-attached :thread thread
               :data {:id id :conversation conversation :generation (inc (count (:history t)))}}
              {:kind :token-issued :thread thread :data {:hash token-hash :principal [:worker id]}}]
     :result {:id id}}))

(defn- open-asks [state thread]
  (into {} (filter (fn [[_ a]] (and (= thread (:thread a)) (= :open (:state a)))) (:asks state))))

(def active-states #{:starting :working :waiting :cancelling})

(defn- gone?
  "Whether Worker W's session is gone and nothing can bring it back: the last
  health pass found no process, and it has no workflow to restart it from."
  [w]
  (and (nil? (:workflow w)) (= :needs-human (get-in w [:health :classification])) (empty? (get-in w [:health :process]))))

(defn holds-slot?
  "Whether Worker W counts against the Worker cap: it is active, its thread is
  not archived, and it is not gone. A gone Worker, or one on an archived
  thread, keeps its state until cancelled but runs nothing the cap protects."
  [state w]
  (and (active-states (:state w)) (not (get-in state [:threads (:thread w) :resolved-at])) (not (gone? w))))

(defn- active-workers [state]
  (filter #(holds-slot? state %) (vals (:workers state))))

(defn- blocked-by
  "Why WORKER cannot run now under POLICY, or nil: the cap, or a lock held by
  another Worker."
  [state policy {:keys [id locks]}]
  (let [held (keep (fn [l] (when-let [w (get-in state [:locks l])] (when (not= w id) (str l " held by " w)))) locks)]
    (cond (seq held) (str/join ", " held)
          (>= (count (remove #(= id (:id %)) (active-workers state))) (:workers policy)) (str (:workers policy) " Workers already running")
          :else nil)))

(defn id-order "The order of ids of one kind: T12 → 12, so T9 comes before T10." [id] (parse-long (subs id 1)))

(defn next-queued
  "The oldest queued Worker that may start now under POLICY, or nil."
  [state policy]
  (->> (vals (:workers state)) (filter #(= :queued (:state %))) (sort-by (comp id-order :id))
       (remove #(blocked-by state policy %)) first))

(defn- thread-worker
  "The Worker that THREAD has now, queued or active, or nil. A thread has at
  most one, and it counts from the moment it is started, not once it runs."
  [state thread]
  (some #(when (and (= thread (:thread %)) (or (= :queued (:state %)) (active-states (:state %)))) (:id %))
        (vals (:workers state))))

(defn- request-origin
  "Where a start-worker! request came from: the owner's own, or for the Router
  the origin of the message it names with FOR, which the controller recorded
  when that message arrived. The Router cannot claim an origin."
  [state principal for]
  (case principal
    :owner :owner
    :controller :watchdog
    :router (let [o (get-in state [:outbox for])]
              (when-not (and o (some? (:origin o)) (= (:conversation o) (get-in state [:router :router :conversation])))
                (refuse :invalid :field :for :message "name the Router message this Worker is for, such as :for \"o12\""))
              (:origin o))
    (refuse :forbidden :command :start-worker!)))

(defmethod decide* :start-worker! [state {{:keys [thread workflow brief repo locks check bench repro for capabilities] :as args} :args :keys [token-hash policy requires principal]}]
  (let [t (thread-of state thread)
        policy (merge default-policy policy)
        locks (set locks)
        id (next-id state :workers "W")
        origin (request-origin state principal for)
        granted (get-in policy [:origins origin] #{:read})
        caps (if capabilities (set/intersection (set capabilities) granted) granted)]
    (when (:resolved-at t) (refuse :archived :thread thread :message (str thread " is archived; start a new thread")))
    (when-let [w (thread-worker state thread)] (refuse :thread-has-worker :thread thread :worker w))
    (when-not (keyword? workflow) (refuse :invalid :field :workflow))
    (when (str/blank? brief) (refuse :invalid :field :brief))
    (when (str/blank? repo) (refuse :invalid :field :repo))
    (doseq [flag requires]
      (when (str/blank? (get args flag)) (refuse :invalid :field flag :message (str (name workflow) " needs " flag))))
    (doseq [l locks]
      (when (some #{l} (:never-granted policy)) (refuse :never-granted :lock l))
      (when-not (some #{l} (:locks policy)) (refuse :unknown-lock :lock l))
      (when-let [need (get-in policy [:lock-capabilities l])]
        (when-not (caps need) (refuse :capability :lock l :needs need :message (str l " needs " need ", which " (name origin) " does not grant")))))
    (let [worker (cond-> {:id id :generation (inc (count (:history t))) :workflow workflow :brief brief :repo repo :locks locks
                          :origin origin :capabilities caps}
                   check (assoc :check check) bench (assoc :bench bench) repro (assoc :repro repro))
          reason (blocked-by state policy (assoc worker :thread thread))]
      {:events [{:kind :worker-started :thread thread
                 :data (assoc worker :state (if reason :queued :starting) :queued-reason reason)}
                {:kind :token-issued :thread thread :data {:hash token-hash :principal [:worker id]}}]
       :result (cond-> {:id id :state (if reason :queued :starting)} reason (assoc :reason reason))})))

(defn- brief-text
  "The first mailbox message of Worker W: its brief and how it reports."
  [{:keys [id thread brief workflow origin capabilities repo]} wf-run workspace]
  (str/join "\n\n" [(format "[%s brief for %s]" thread id)
                       brief
                       (format "Requested from %s. Capabilities: %s." (some-> origin name) (str/join ", " (map name (sort capabilities))))
                       (format "You are Worker %s of thread %s. Your wf run is %s (%s workflow; WF_RUN is set, so `wf status` shows it). Use (super-ask! ...) when you need the person and (super-done!) when the run is done."
                               id thread wf-run (name workflow))
                       (format "Work only in your jj workspace %s, your current directory. %s is the main checkout: read it if you need to, but do not edit it. Commit your work there: (super-done!) lands it on the tip of the person's line and removes the workspace. When it returns :conflicts instead, resolve them in your workspace and call it again."
                               workspace repo)]))

(defn- worker-of
  "Worker WORKER of STATE, or a refusal when there is none or, with STATES, when
  it is in none of them."
  ([state worker] (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker)))
  ([state worker states]
   (let [w (worker-of state worker)]
     (when-not (states (:state w)) (refuse :not-active :worker worker :state (:state w)))
     w)))

(defmethod decide* :promote-worker! [state {:keys [token-hash policy]}]
  (if-let [w (next-queued state (merge default-policy policy))]
    {:events [{:kind :worker-promoted :thread (:thread w) :data {:id (:id w)}}
              {:kind :token-issued :thread (:thread w) :data {:hash token-hash :principal [:worker (:id w)]}}]
     :result (:id w)}
    {:events [] :result nil}))

(defmethod decide* :worker-prepared! [state {{:keys [worker] :as args} :args}]
  (let [w (worker-of state worker active-states)]
    {:events [{:kind :worker-prepared :thread (:thread w) :data args}] :result worker}))

(defmethod decide* :worker-running! [state {{:keys [worker conversation wf-run workspace buffer started-at]} :args}]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (when-not (= :starting (:state w)) (refuse :not-starting :worker worker :state (:state w)))
    {:events [{:kind :worker-running :thread (:thread w)
               :data {:id worker :conversation conversation :wf-run wf-run :workspace workspace :buffer buffer :started-at started-at
                      :outbox (next-id state :outbox "o") :text (brief-text w wf-run workspace)}}]
     :result worker}))

(defmethod decide* :finish-worker! [state {{:keys [worker state-after reason landed]} :args}]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (when (= :cancelling (:state w)) (refuse :cancelling :worker worker))
    (when-not (#{:done :failed} state-after) (refuse :invalid :field :state-after))
    (when (#{:done :failed :cancelled} (:state w)) (refuse :already-finished :worker worker))
    {:events [{:kind :worker-finished :thread (:thread w) :data (cond-> {:id worker :state state-after :reason reason} (and landed (= :done state-after)) (assoc :landed landed))}]
     :result worker}))

(defmethod decide* :cancel! [state {{:keys [worker reason]} :args}]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (when-not (or (#{:starting :working :waiting} (:state w)) (= :queued (:state w))) (refuse :not-running :worker worker))
    (when (str/blank? reason) (refuse :invalid :field :reason))
    {:events [{:kind :cancel-requested :thread (:thread w) :data {:id worker :reason reason}}]
     :result worker}))

(defmethod decide* :cancel-delivered! [state {{:keys [worker]} :args}]
  (let [w (get-in state [:workers worker])]
    (when-not (= :cancelling (:state w)) (refuse :not-cancelling))
    {:events [{:kind :worker-finished :thread (:thread w) :data {:id worker :state :cancelled :reason (:cancel-reason w)}}]
     :result worker}))

(defmethod decide* :health! [state {{:keys [worker health at]} :args}]
  (when worker (worker-of state worker))
  {:events [{:kind :health-observed :data {:worker worker :health health :at at}}] :result worker})

(defmethod decide* :restart-worker! [state {{:keys [worker at]} :args :keys [token-hash]}]
  (let [w (get-in state [:workers worker])]
    (when-not (and (#{:working :waiting :starting} (:state w)) (< (or (:restarts w) 0) 3)) (refuse :restart-limit))
    {:events [{:kind :restart-attempted :thread (:thread w) :data {:id worker :at at}}
              {:kind :token-issued :thread (:thread w) :data {:hash token-hash :principal [:worker worker]}}]
     :result worker}))

(defmethod decide* :restart-failed! [state {{:keys [worker reason]} :args}]
  (let [w (worker-of state worker)]
    {:events [{:kind :restart-failed :thread (:thread w) :data {:id worker :reason (str reason)}}] :result worker}))

(defmethod decide* :worker-restarted! [state {{:keys [worker buffer]} :args}]
  (let [w (worker-of state worker active-states)]
    {:events [{:kind :worker-restarted :thread (:thread w)
               :data {:id worker :buffer buffer :outbox (next-id state :outbox "o") :conversation (:conversation w)
                      :text (str "[Recovery] Resume the existing wf run " (:wf-run w) " and conversation. Inspect existing jobs before acting; do not relaunch surviving jobs.")}}] :result worker}))

(defn- deliverable?
  "Whether Worker W has a session that takes mailbox messages now."
  [w]
  (and (#{:working :waiting} (:state w)) (not (str/blank? (:conversation w)))))

(defn- deliverable-worker! [w]
  (when-not (deliverable? w) (refuse :not-running :worker (:id w)))
  w)

(defmethod decide* :steer! [state {{:keys [worker text]} :args}]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (deliverable-worker! w)
    (when (str/blank? text) (refuse :invalid :field :text))
    {:events [{:kind :worker-steered :thread (:thread w)
               :data {:worker worker :text text :outbox (next-id state :outbox "o")
                      :conversation (:conversation w) :mailbox-text (str "[Steering] " text)}}]
     :result worker}))

(defmethod decide* :job-exited! [state {{:keys [worker job exit log]} :args}]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (when-not (and (string? job) (not (str/blank? job)) (integer? exit) (string? log) (:conversation w))
      (refuse :invalid :field :job))
    (if (get-in w [:jobs job])
      {:events [] :result job}
      {:events [{:kind :job-exited :thread (:thread w)
                 :data {:worker worker :job job :exit exit :log log
                        :outbox (next-id state :outbox "o") :conversation (:conversation w)
                        :text (str "[Job " job " exited with status " exit ". Log: " log "]")}}]
       :result job})))

(defmethod decide* :ask! [state {{:keys [thread worker kind text options recommended stopped holds-up supersedes revision evidence-sha]} :args}]
  (let [t (thread-of state thread)
        worker (or worker (:worker t))
        open (open-asks state thread)]
    (when-not (#{:question :action :review} kind) (refuse :invalid :field :kind))
    (when (str/blank? text) (refuse :invalid :field :text))
    (when (and (= :review kind) (str/blank? revision))
      (refuse :invalid :field :revision :message "a review ask names the revision to approve"))
    (when-not (= worker (:worker t)) (refuse :no-active-worker :thread thread))
    (let [ws (get-in state [:workers worker :state])]
      (when (= :cancelling ws) (refuse :cancelling :worker worker :message (str worker " is being cancelled")))
      (when-not (#{:starting :working :waiting} ws) (refuse :no-active-worker :thread thread)))
    (doseq [a supersedes] (when-not (open a) (refuse :no-such-ask :ask a)))
    (let [id (next-id state :asks "a")]
      {:events [{:kind :ask-opened :thread thread
                 :data (cond-> {:id id :worker worker :kind kind :text text :options (vec options)
                                :stopped (boolean stopped) :supersedes (vec supersedes)}
                         recommended (assoc :recommended recommended)
                         holds-up (assoc :holds-up holds-up)
                         (= :review kind) (assoc :revision revision :evidence-sha evidence-sha))}]
       :result id})))

(defmethod decide* :reply! [state {{:keys [thread asks-version picks note dismiss]} :args :keys [id]}]
  (let [t (thread-of state thread)
        open (open-asks state thread)]
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

(defmethod decide* :approve! [state {{:keys [thread ask revision evidence-sha current-sha]} :args :keys [id]}]
  (let [t (thread-of state thread)
        open (open-asks state thread)
        a (get open ask)
        stale (fn [why] (refuse :stale :message why :current (:asks-version t) :asks (vec (sort-by :seq (vals open)))))]
    (cond (not= :review (:kind a)) (stale "no open review ask by that id")
          (not= revision (:revision a)) (stale (str "the review is of revision " (:revision a)))
          (not= evidence-sha (:evidence-sha a)) (stale "the review's evidence is not the one you saw")
          (not= current-sha (:evidence-sha a)) (stale "the evidence changed since the review was asked"))
    (when-not (:worker t) (refuse :no-active-worker :thread thread))
    (let [reply {:id id :thread thread :worker (:worker t) :picks {} :note "" :dismiss #{} :approved {ask revision}}]
      {:events [{:kind :reply-sent :thread thread
                 :data (assoc reply :outbox (next-id state :outbox "o")
                              :conversation (get-in state [:workers (:worker t) :conversation])
                              :text (reply-text state reply))}]
       :result id})))

(defn- dismissal
  "The reply-sent event that closes every open ask of THREAD without answering,
  or nil when nothing is open or no Worker listens."
  [state thread id]
  (let [t (thread-of state thread)
        open (open-asks state thread)]
    (when (and (seq open) (:worker t))
      (let [reply {:id id :thread thread :worker (:worker t) :picks {} :note "" :dismiss (set (keys open))}]
        {:kind :reply-sent :thread thread
         :data (assoc reply :outbox (next-id state :outbox "o")
                      :conversation (get-in state [:workers (:worker t) :conversation])
                      :text (reply-text state reply))}))))

(defmethod decide* :dismiss! [state {{:keys [thread]} :args :keys [id]}]
  {:events (filterv some? [(dismissal state thread id)]) :result thread})

(defmethod decide* :archive! [state {{:keys [thread]} :args :keys [id]}]
  ;; A queued Worker has no process yet, so it is new work, which an archived
  ;; thread takes none of. A running Worker carries on.
  (let [queued (some #(when (and (= thread (:thread %)) (= :queued (:state %))) (:id %)) (vals (:workers state)))]
    {:events (filterv some? [(dismissal state thread id)
                             (when queued {:kind :worker-finished :thread thread :data {:id queued :state :cancelled :reason "thread archived"}})
                             {:kind :thread-resolved :thread thread :data {}}])
     :result thread}))

;; ---- routing ------------------------------------------------------------

(def ^:private number-words
  (merge (zipmap ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"
                  "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen" "nineteen"] (range 20))
         {"twenty" 20 "thirty" 30 "forty" 40 "fifty" 50 "sixty" 60 "seventy" 70 "eighty" 80 "ninety" 90}))

(defn- spoken-number
  "The number WORDS say (\"twenty two\", \"twelve\", \"1 2\"), and the words left."
  [words]
  (loop [n nil [w & more :as ws] words]
    (let [w' (some-> w str/lower-case (str/replace #"[^a-z0-9]" ""))
          v (when w' (or (number-words w') (when (re-matches #"\d{1,2}" w') (parse-long w'))))]
      (cond (nil? v) [n ws]
            (and n (< v 10) (>= n 20) (zero? (mod n 10))) (recur (+ n v) more)
            (and n (re-matches #"\d{1,2}" w')) (recur (+ (* 10 n) v) more)
            n [n ws]
            :else (recur v more)))))

(defn parse-handle
  "[handle rest] when TEXT starts with a thread handle, typed (`T12`, `t12`)
  or spoken (`tee twelve`, `T twelve`, `tee twenty two`); else nil."
  [text]
  (let [text (str/trim (str text))]
    (if-let [[_ n rest] (re-matches #"(?is)t\s*(\d+)\b[\s,.:;-]*(.*)" text)]
      [(str "T" (parse-long n)) (str/trim rest)]
      (when-let [[_ rest] (re-matches #"(?is)(?:tee|tea|t)\b[\s,.:;-]*(.*)" text)]
        (let [[n ws] (spoken-number (str/split rest #"\s+"))]
          (when n [(str "T" n) (str/trim (str/replace (str/join " " ws) #"^[\s,.:;-]+" ""))]))))))

(defn- router-row
  "The outbox row that owes TEXT to the Router, or a refusal when none is registered."
  [state text]
  (let [conversation (or (get-in state [:router :router :conversation]) (refuse :no-router))]
    {:outbox (next-id state :outbox "o") :conversation conversation :text text}))

(defn resource-thread
  "The one unresolved thread of STATE whose linked resource TEXT names, by its
  key (`repo:engstrand/guix`) or the part after its kind (`engstrand/guix`), or
  nil when none or more than one does."
  [state text]
  (let [text (str/lower-case text)
        names? (fn [r] (some #(re-find (re-pattern (str "(?<![\\w/.-])" (java.util.regex.Pattern/quote (str/lower-case %)) "(?![\\w/-])")) text)
                             (distinct [r (str/replace r #"^[a-z]+:" "")])))
        hits (filter #(and (not (:resolved-at %)) (some names? (:resources %))) (vals (:threads state)))]
    (when (= 1 (count hits)) (:id (first hits)))))

(defmethod decide* :say! [state {{:keys [text origin]} :args :keys [id]}]
  (when (str/blank? text) (refuse :invalid :field :text))
  (when-not (#{:phone :emacs :herdr :voice} origin) (refuse :invalid :field :origin))
  (let [;; A typed or spoken handle names the thread and is stripped from the
        ;; message; a linked resource names it and the message stays whole.
        {:keys [thread message via]} (if-let [[handle rest] (parse-handle text)]
                                       {:thread handle :message rest :via :handle}
                                       {:thread (resource-thread state text) :message text :via :resource})
        t (when thread (or (get-in state [:threads thread]) (refuse :no-such-thread :thread thread)))
        w (when t (get-in state [:workers (:worker t)]))
        _ (when (and w (= :handle via)) (deliverable-worker! w))
        w (when (and w (deliverable? w)) w)]
    (if w
      {:events [{:kind :worker-steered :thread thread
                 :data (cond-> {:id id :origin origin :text message :worker (:id w) :outbox (next-id state :outbox "o")
                                :conversation (:conversation w)
                                :mailbox-text (format "[%s from %s%s] %s" thread (name origin) (if (= :voice origin) ", dictated" "") message)}
                         (get-in state [:router :router :conversation])
                         (assoc :summary {:outbox (next-id (update-in state [:counters :outbox] (fnil inc 0)) :outbox "o")
                                          :conversation (get-in state [:router :router :conversation])
                                          :text (format "[routed to %s %s from %s] %s" thread (:id w) (name origin) message)}))}]
       :result (cond-> {:thread thread :to (:id w)} (= :resource via) (assoc :via :resource))}
      (let [said (str (when (= :voice origin) "(spoken) ") (if (= :handle via) (str thread ": " message) text))]
        {:events [{:kind :router-queued :thread thread
                   :data (assoc (router-row state (format "[%s] %s" (name origin) said)) :id id :origin origin :said text)}]
         :result (cond-> {:to :router} thread (assoc :thread thread))}))))

(defmethod decide* :register-router! [state {{:keys [conversation]} :args :keys [token-hash]}]
  (when (str/blank? conversation) (refuse :invalid :field :conversation))
  {:events [{:kind :router-registered :data {:conversation conversation}}
            {:kind :token-issued :data {:hash token-hash :principal :router}}]
   :result {:conversation conversation}})

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
  (decide* state (assoc (authorize state principal cmd) :principal principal)))

;; ---- fold ---------------------------------------------------------------

(defn- bump [state kind] (update-in state [:counters kind] (fnil inc 0)))

(defn- set-states [state kind ids st]
  (reduce #(assoc-in %1 [kind %2 :state] st) state ids))

(defmulti fold*
  "STATE after EVENT, which has :seq, :at, :kind, :thread and :data."
  (fn [_state event] (:kind event)))

(def retired-events
  "Event kinds no command emits any more. An old log still holds them, so they
  fold to nothing; store/load keeps whatever they once wrote."
  #{:message-routed :router-triaged :lease-moved})

(defmethod fold* :default [state {:keys [kind]}]
  (if (retired-events kind)
    state
    (throw (ex-info (str "unknown event " kind) {:error :unknown-event :kind kind}))))

(defn fold
  "STATE after EVENT, with the thread it concerns stamped :updated-at."
  [state {:keys [thread at] :as event}]
  (let [state (fold* state event)]
    (cond-> state
      (get-in state [:threads thread]) (assoc-in [:threads thread :updated-at] at))))

(defmethod fold* :thread-created [state {{:keys [id title watchdog-of]} :data :keys [seq]}]
  (-> state (bump :threads)
      (assoc-in [:threads id] (cond-> {:id id :title title :resources #{} :worker nil :history []
                                       :execution :none :asks-version seq :resolved-at nil :summary nil
                                       :milestone-seq seq}
                                watchdog-of (assoc :watchdog-of watchdog-of)))))

(defmethod fold* :thread-linked [state {{:keys [resource]} :data :keys [thread]}]
  (update-in state [:threads thread :resources] conj resource))

(defmethod fold* :worker-attached [state {{:keys [id conversation generation]} :data :keys [thread]}]
  (-> state (bump :workers)
      (assoc-in [:workers id] {:id id :thread thread :generation generation :conversation conversation
                               :state :working})
      (assoc-in [:threads thread :worker] id)
      (assoc-in [:threads thread :execution] :working)))

(defn- take-locks
  "STATE with Worker ID holding its locks. A Worker holds them from the moment
  it is :starting, so another start in its launch window sees them."
  [state id]
  (update state :locks merge (zipmap (get-in state [:workers id :locks]) (repeat id))))

(defmethod fold* :worker-started [state {{:keys [id] :as worker} :data :keys [thread]}]
  (-> state (bump :workers)
      (assoc-in [:workers id] (assoc worker :thread thread :conversation nil :wf-run nil :workspace nil :buffer nil))
      (cond-> (= :queued (:state worker)) (assoc-in [:threads thread :execution] :queued)
              (= :starting (:state worker)) (take-locks id))))

(defmethod fold* :worker-promoted [state {{:keys [id]} :data}]
  (-> state (update-in [:workers id] assoc :state :starting :queued-reason nil) (take-locks id)))

(defmethod fold* :worker-prepared [state {{:keys [worker] :as args} :data}]
  (update-in state [:workers worker] merge (dissoc args :worker)))

(defmethod fold* :worker-running [state {{:keys [id outbox text conversation] :as running} :data :keys [thread]}]
  (let [w (get-in state [:workers id])]
    (-> state (bump :outbox)
        (update-in [:workers id] merge (dissoc running :id :outbox :text) {:state :working :queued-reason nil})
        (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation :text text
                                    :state :pending :message nil})
        (take-locks id)
        (assoc-in [:threads thread :worker] id)
        (assoc-in [:threads thread :execution] :working))))

(defmethod fold* :worker-finished [state {{:keys [id reason landed] outcome :state} :data :keys [thread seq]}]
  (let [t (get-in state [:threads thread])]
    (-> state
        (update-in [:workers id] #(cond-> (assoc % :state outcome :reason reason) landed (assoc :landed landed)))
        (update :tokens #(into {} (map (fn [[h t]] [h (cond-> t (= [:worker id] (:principal t)) (assoc :revoked true))]) %)))
        ;; Nobody is left to read an answer, whatever the outcome.
        (update :asks #(into {} (map (fn [[aid a]] [aid (cond-> a (and (= id (:worker a)) (= :open (:state a))) (assoc :state :superseded))]) %)))
        (update :locks #(into {} (remove (fn [[_ holder]] (= holder id)) %)))
        (cond-> (contains? #{id nil} (:worker t))
          (-> (assoc-in [:threads thread :worker] nil)
              (update-in [:threads thread :history] conj id)
              (assoc-in [:threads thread :execution] outcome)
              (assoc-in [:threads thread :milestone-seq] seq))))))

(defmethod fold* :cancel-requested [state {{:keys [id reason]} :data}]
  (update-in state [:workers id] assoc :state :cancelling :cancel-reason reason))

(defmethod fold* :health-observed [state {{:keys [worker health at]} :data}]
  (cond-> (assoc-in state [:router :heartbeat] {:id :heartbeat :at at})
    worker (assoc-in [:workers worker :health] health)))

(defmethod fold* :restart-attempted [state {{:keys [id at]} :data}]
  (-> state (update-in [:workers id :restarts] (fnil inc 0))
      (assoc-in [:workers id :restart-at] at)
      (update :tokens #(into {} (map (fn [[h t]] [h (cond-> t (= [:worker id] (:principal t)) (assoc :revoked true))]) %)))))

(defmethod fold* :restart-failed [state {{:keys [id reason]} :data}]
  (assoc-in state [:workers id :restart-error] reason))

(defmethod fold* :worker-restarted [state {{:keys [id buffer outbox conversation text]} :data :keys [thread]}]
  (-> state (bump :outbox) (assoc-in [:workers id :buffer] buffer)
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation
                                 :text text :state :pending :message nil})))

(defmethod fold* :job-exited [state {{:keys [worker job exit log outbox conversation text]} :data :keys [thread]}]
  (-> state (bump :outbox)
      (assoc-in [:workers worker :jobs job] {:id job :exit exit :log log})
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation
                                 :text text :state :pending :message nil})))

(defmethod fold* :token-issued [state {{:keys [hash principal]} :data}]
  (assoc-in state [:tokens hash] {:id hash :principal principal :revoked false}))

(defn- resume
  "STATE with THREAD's Worker working again when no stopped ask is open. A
  Worker on an archived thread resumes too, and the thread stays archived."
  [state thread]
  (let [t (get-in state [:threads thread])
        stopped? (some #(and (= thread (:thread %)) (= :open (:state %)) (:stopped %)) (vals (:asks state)))]
    (cond-> state
      (and (:worker t) (not stopped?) (not= :cancelling (get-in state [:workers (:worker t) :state])))
      (-> (assoc-in [:threads thread :execution] :working)
          (assoc-in [:workers (:worker t) :state] :working)))))

(defmethod fold* :ask-opened [state {{:keys [id stopped supersedes] :as ask} :data :keys [thread seq]}]
  (-> state (bump :asks)
      (set-states :asks supersedes :superseded)
      (assoc-in [:asks id] (assoc ask :thread thread :seq seq :state :open))
      (assoc-in [:threads thread :asks-version] seq)
      (assoc-in [:threads thread :milestone-seq] seq)
      (cond-> stopped (-> (assoc-in [:threads thread :execution] :waiting)
                          (assoc-in [:workers (:worker ask) :state] :waiting))
              (not stopped) (resume thread))))

(defmethod fold* :reply-sent [state {{:keys [id picks dismiss outbox conversation text] :as reply} :data :keys [thread seq]}]
  (-> state (bump :outbox)
      (assoc-in [:replies id] (-> reply (dissoc :outbox :conversation :text) (assoc :state :sent :outbox outbox :seq seq)))
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply id :conversation conversation :text text
                                  :state :pending :message nil})
      (set-states :asks (concat (keys picks) (keys (:approved reply))) :sent)
      (set-states :asks dismiss :dismissed)
      (assoc-in [:threads thread :asks-version] seq)
      (resume thread)))

(defn linked
  "The record KIND/ID of STATE with the ids it links, so a reader can hop from
  one to the next: an ask's :reply, a reply's :asks, a Worker's :asks and
  :replies. Nil when there is no such record."
  [state kind id]
  (when-let [r (get-in state [kind id])]
    (case kind
      :asks (assoc r :reply (some (fn [p] (when (or (contains? (:picks p) id) (contains? (:approved p) id) (contains? (set (:dismiss p)) id)) (:id p)))
                                  (sort-by :seq (vals (:replies state)))))
      :replies (assoc r :asks (vec (sort (distinct (concat (keys (:picks r)) (keys (:approved r)) (:dismiss r))))))
      :workers (assoc r :asks (vec (sort (keep #(when (= id (:worker %)) (:id %)) (vals (:asks state)))))
                        :replies (vec (sort (keep #(when (= id (:worker %)) (:id %)) (vals (:replies state))))))
      r)))

;; ---- sections -----------------------------------------------------------

(def section-order [:stopped :holding-up :waiting :needs-attention :sent :new :working :quiet :archived])

(def section-titles
  {:stopped "Stopped on you" :holding-up "Holding up work" :waiting "Waiting on you"
   :needs-attention "Needs attention" :sent "Sent" :new "New" :working "Working"
   :quiet "Quiet" :archived "Archived"})

(defn section
  "THREAD's section in STATE, by the doc's rule, first match wins. CURSOR is
  the last seq this client has seen on the thread."
  [state {:keys [id execution resolved-at milestone-seq]} cursor]
  (let [asks (filter #(= id (:thread %)) (vals (:asks state)))
        open (filter #(= :open (:state %)) asks)
        replies (filter #(= id (:thread %)) (vals (:replies state)))]
    (cond (some :stopped open) :stopped
          (some :holds-up open) :holding-up
          (seq open) :waiting
          resolved-at :archived
          (and (or (= :failed execution) (= :needs-human (get-in state [:workers (:worker (get-in state [:threads id])) :health :classification]))) (empty? open)) :needs-attention
          (some #(#{:sent :delivered} (:state %)) replies) :sent
          (> (or milestone-seq 0) (or cursor 0)) :new
          (#{:working :queued} execution) :working
          :else :quiet)))

(defn ask-line
  "The open ask of THREAD to show on its row, or nil."
  [state thread-id]
  (->> (vals (:asks state))
       (filter #(and (= thread-id (:thread %)) (= :open (:state %))))
       (sort-by :seq) last))

(defn sections
  "Threads of STATE grouped in the doc's order, each with its open ask and
  whether an archived one came back. CURSORS maps thread id to the seq the
  client last saw."
  [state cursors]
  (let [rows (for [t (sort-by (comp id-order :id) (vals (:threads state)))
                   :let [s (section state t (get cursors (:id t)))
                         ask (ask-line state (:id t))]]
               (assoc t :section s :ask ask
                      :line (or (:text ask)
                                (get-in state [:workers (:worker t) :health :problem])
                                (when (= :failed (:execution t)) (get-in state [:workers (peek (:history t)) :reason]))
                                (:summary t))
                      :came-back (boolean (and (:resolved-at t) (> (:milestone-seq t) (:resolved-at t))))))
        by (group-by :section rows)]
    (vec (for [s section-order :when (seq (by s))]
           {:section s :title (section-titles s) :threads (vec (by s))}))))

(defn order
  "The display order of SECTIONS as [[thread-id section] ...]."
  [sections]
  (vec (for [{:keys [section threads]} sections t threads] [(:id t) section])))

(defn hold
  "SECTIONS as a client that last showed SHOWN ([[id section] ...]) should see
  them while you look: every shown thread keeps its section and place, with
  current data and :now naming the section it has moved to, and threads not
  shown yet wait, counted in :fresh."
  [shown sections]
  (let [current (into {} (for [{:keys [section threads]} sections t threads] [(:id t) (assoc t :section section)]))
        shown-ids (set (map first shown))
        held (group-by :section (for [[id section] shown :let [t (current id)] :when t]
                                  (cond-> (assoc t :section section) (not= section (:section t)) (assoc :now (:section t)))))]
    {:sections (vec (for [s section-order :when (seq (held s))]
                      {:section s :title (section-titles s) :threads (vec (held s))}))
     :fresh (count (remove shown-ids (keys current)))}))

(defn badge
  "How many threads need you: the first three sections."
  [sections]
  (reduce + (map #(count (:threads %)) (filter #(#{:stopped :holding-up :waiting} (:section %)) sections))))

(defmethod fold* :thread-resolved [state {:keys [thread seq]}]
  (update-in state [:threads thread] assoc :resolved-at seq :execution :none))

(defmethod fold* :worker-steered [state {{:keys [summary outbox conversation mailbox-text]} :data :keys [thread] :as event}]
  (let [state (-> state (bump :outbox)
                  (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation :text mailbox-text
                                              :state :pending :message nil}))]
    (cond-> (assoc-in state [:outbox (get-in event [:data :outbox]) :delivery] :steer)
      summary (-> (bump :outbox)
                  (assoc-in [:outbox (:outbox summary)] {:id (:outbox summary) :thread thread :reply nil :conversation (:conversation summary)
                                                         :text (:text summary) :state :pending :message nil :delivery :summary})))))

(defmethod fold* :router-queued [state {{:keys [outbox conversation text origin]} :data :keys [thread]}]
  (-> state (bump :outbox)
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation :text text
                                  :state :pending :message nil :origin origin})))

(defmethod fold* :router-registered [state {{:keys [conversation]} :data}]
  (-> state
      (update :tokens #(into {} (map (fn [[h t]] [h (cond-> t (= :router (:principal t)) (assoc :revoked true))]) %)))
      (assoc-in [:router :router] {:id :router :conversation conversation})))

(defmethod fold* :note [state {{:keys [text]} :data :keys [thread]}]
  (assoc-in state [:threads thread :summary] text))

(defmethod fold* :milestone [state {{:keys [text]} :data :keys [thread seq]}]
  (-> state (assoc-in [:threads thread :summary] text) (assoc-in [:threads thread :milestone-seq] seq)))

(defmethod fold* :outbox-sent [state {{:keys [id message]} :data :keys [at]}]
  ;; Only a pending row becomes sent; a late repeat never moves one back.
  (cond-> state
    (= :pending (get-in state [:outbox id :state])) (update-in [:outbox id] assoc :state :sent :message message :sent-at at)))

(defmethod fold* :outbox-acked [state {{:keys [id]} :data}]
  ;; An ack that arrives after the Worker answered must not undo the answer.
  (let [reply (get-in state [:replies (get-in state [:outbox id :reply])])
        asks (filter #(= :sent (get-in state [:asks % :state])) (concat (keys (:picks reply)) (keys (:approved reply))))]
    (cond-> state
      (#{:pending :sent} (get-in state [:outbox id :state])) (assoc-in [:outbox id :state] :acked)
      (= :sent (:state reply)) (-> (assoc-in [:replies (:id reply) :state] :delivered)
                                   (set-states :asks asks :delivered)))))

(defmethod fold* :reply-answered [state {{:keys [id]} :data :keys [thread]}]
  (let [reply (get-in state [:replies id])]
    (-> state (assoc-in [:replies id :state] :answered)
        (assoc-in [:outbox (:outbox reply) :state] :processed)
        (set-states :asks (concat (keys (:picks reply)) (keys (:approved reply))) :answered)
        (resume thread))))

(defn changed
  "[kind id record] for every record that differs between BEFORE and AFTER,
  with record nil for one AFTER no longer has."
  [before after]
  (concat
   (for [kind entity-kinds
         [id record] (kind after)
         :when (not= record (get-in before [kind id]))]
     [kind id record])
   (for [kind entity-kinds
         id (keys (kind before))
         :when (not (contains? (kind after) id))]
     [kind id nil])))
