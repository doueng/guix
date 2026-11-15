(ns bbh.super.model
  "The super-agent's state machine, pure. `decide` turns a command into events
  or refuses it, and `fold` applies one event, so the state is a function of the
  event log alone. Nothing here knows SQL, sockets or mailboxes."
  (:require [clojure.string :as str]))

(def empty-state
  {:counters {} :threads {} :workers {} :asks {} :replies {} :outbox {} :tokens {} :router {}})

(defn- refuse [error & {:keys [message] :as more}]
  (throw (ex-info (or message (name error)) (assoc (dissoc more :message) :error error))))

(def ^:private grants
  {:router #{:thread! :start-worker! :steer! :cancel! :note! :milestone!}
   :worker #{:ask! :note! :milestone! :answered! :finish-worker!}
   :controller #{:promote-worker! :issue-token! :register-router! :outbox-sent! :outbox-acked! :outbox-dropped! :worker-running! :worker-prepared! :job-exited! :health! :machine-observed! :finish-worker! :cancel-delivered! :effect-done! :effect-failed! :timer-fired!}})

(def default-policy
  "What policy.edn says when it is absent: the Worker cap, how long a live
  Worker may leave a delivery in its mailbox before it needs you (a reply waits
  for the current turn, which can include a 10 minute job-wait)."
  {:workers 10 :stale-delivery-minutes 30 :worker-models []})

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

(defn- next-id [state kind prefix]
  (str prefix (inc (get-in state [:counters kind] 0))))

(defn unread?
  "Whether thread T has a milestone the owner has not read."
  [{:keys [milestone-seq read-seq]}]
  (> (or milestone-seq 0) (or read-seq 0)))

(defn- thread-of [state id]
  (or (get-in state [:threads id]) (refuse :no-such-thread :thread id :message (str "no thread " (pr-str id)))))

(defn- target-thread
  "The thread a Router command acts on. ARGS name it by :thread, which may
  also be a Worker's id, or by :worker, the shape a Router REPL loaded before
  T104 still sends. A Worker id names its thread only while it is the
  thread's Worker, so an old id cannot reach its successor."
  [state {:keys [thread worker]}]
  (let [id (or thread worker)
        w (get-in state [:workers id])]
    (cond (get-in state [:threads id]) id
          w (if (= id (get-in state [:threads (:thread w) :worker]))
              (:thread w)
              (refuse :not-running :worker id :message (str id " is no longer " (:thread w) "'s Worker; name the thread")))
          :else (refuse :no-such-thread :thread id :message (str "no thread or Worker " (pr-str id))))))

(defn- reply-text
  "The mailbox message that carries REPLY to its Worker."
  [state {:keys [id thread picks answers note dismiss approved]}]
  (let [ask-line (fn [[ask option]]
                   (let [a (get-in state [:asks ask])
                         label (or (some #(when (= option (:id %)) (:label %)) (:options a)) option)]
                     (format "- %s: %s" (:text a) label)))]
    (str/join "\n" (remove str/blank?
                            [(format "[%s reply %s]" thread id)
                             (when (seq picks) (str/join "\n" (map ask-line picks)))
                             (when (seq answers) (str/join "\n" (for [[ask text] answers] (format "- %s: %s" (get-in state [:asks ask :text]) text))))
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

(defmethod decide* :thread! [state {{:keys [title]} :args}]
  (when (str/blank? title) (refuse :invalid :field :title))
  (let [id (next-id state :threads "T")]
    {:events [{:kind :thread-created :thread id :data {:id id :title title}}] :result id}))

(defn- open-asks [state thread]
  (into {} (filter (fn [[_ a]] (and (= thread (:thread a)) (= :open (:state a)))) (:asks state))))

(def active-states
  "The states of a Worker that has not finished. Waiting is not one: a Worker
  waits on the person while it is :working with a stopped ask open, which
  `waiting?` reads from the asks."
  #{:starting :working :cancelling})

(defn waiting?
  "Whether Worker W waits on the person: it is working and one of its stopped
  asks is open. The asks alone say so, so no event has to keep a second copy
  in step with them."
  [state w]
  (boolean (and (= :working (:state w))
                (some #(and (= (:id w) (:worker %)) (= :open (:state %)) (:stopped %)) (vals (:asks state))))))

(defn shown-state
  "Worker W's state as reads show it: :waiting while it waits on the person
  and its session was not seen in a turn, else the state it holds."
  [state w]
  (if (and (waiting? state w) (not (get-in w [:health :busy]))) :waiting (:state w)))

(defn session-ended?
  "Whether health found Worker W's session gone while it was working or
  cancelling, so it runs nothing until the person resumes or finishes it.
  It reads the process health saw, not the classification it shows, which can
  name a lost job or a missing wf run instead."
  [w]
  (and (#{:working :cancelling} (:state w)) (some? (:health w)) (empty? (get-in w [:health :process]))))

(defn holds-slot?
  "Whether Worker W counts against the Worker cap: it is active, its session
  has not ended and its thread is not archived. One on an archived thread keeps
  its state until cancelled but runs nothing the cap protects."
  [state w]
  (and (active-states (:state w)) (not (session-ended? w)) (not (get-in state [:threads (:thread w) :resolved-at]))))

(defn- active-workers [state]
  (filter #(holds-slot? state %) (vals (:workers state))))

(defn- blocked-by
  "Why WORKER cannot run now under POLICY, or nil: the cap is full."
  [state policy {:keys [id]}]
  (when (>= (count (remove #(= id (:id %)) (active-workers state))) (:workers policy))
    (str (:workers policy) " Workers already running")))

(defn id-order "The order of ids of one kind: T12 → 12, so T9 comes before T10." [id] (parse-long (subs id 1)))

(defn message-id
  "The mailbox message of outbox row OUTBOX. It is the row's alone, so sending
  the row twice leaves one message, and the names sort in row order."
  [outbox]
  (format "%010d-%s" (id-order outbox) outbox))

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
    :router (let [o (get-in state [:outbox for])]
              (when-not (and o (some? (:origin o)) (= (:conversation o) (get-in state [:router :router :conversation])))
                (refuse :invalid :field :for :message "name a message the controller recorded for the Router, such as :for \"o12\""))
              (:origin o))
    (refuse :forbidden :command :start-worker!)))

(defn- authority
  "{:for :origin} for the log: the person's message FOR that PRINCIPAL acts
  on, or nil when it names none. The Router may name only a message the
  controller recorded for it."
  [state principal for]
  (when for {:for for :origin (request-origin state principal for)}))

(declare fold* worker-of)

(def effect-order
  "The effects a Worker's end can leave to the controller, in the order it
  runs them: its jobs stop, its wf run closes, the person's working copy moves
  onto what it landed and its workspace goes. Its session is not among them:
  the reaper ends it from the process table, once its turn is over."
  [:stop-jobs :close-wf :advance :retire])

(def effect-tries "How often the controller tries an effect before it needs the person." 3)

(defn- finished
  "The event that ends Worker W with DATA, its :state, :reason and the rest.
  :owes names the effects its end leaves, from what it holds."
  [w data]
  (let [owed (cond-> #{}
               (:conversation w) (conj :stop-jobs)
               (:wf-run w) (conj :close-wf)
               (:landed data) (conj :advance)
               (:workspace w) (conj :retire))]
    {:kind :worker-finished :thread (:thread w)
     :data (assoc data :id (:id w) :owes (filterv owed effect-order))}))

(defn obligations
  "The effects finished Worker W still owes, in order: those its end left that
  are neither done nor failed `effect-tries` times. Its workspace goes at once
  when it is done; a failed or cancelled one's stays to be inspected until its
  thread is archived."
  [state w]
  (vec (for [kind (:owes w)
             :let [e (get-in w [:effects kind])]
             :when (and (not (:done e)) (< (:tries e 0) effect-tries))
             :when (or (not= :retire kind) (= :done (:state w)) (get-in state [:threads (:thread w) :resolved-at]))]
         kind)))

(defn stuck-effect
  "{:kind :error} of the first effect Worker W owes that failed `effect-tries`
  times, so the person has to look, or nil."
  [w]
  (some (fn [kind] (let [e (get-in w [:effects kind])]
                     (when (and (not (:done e)) (>= (:tries e 0) effect-tries)) {:kind kind :error (:error e)})))
        (:owes w)))

(def deadlines
  "How long a Worker may stay in a state, in ms, before the person is told."
  {:starting 600000 :cancelling 600000})

(defn overdue
  "What to tell the person when Worker W has stayed past the deadline of the
  state it is in, or nil."
  [w]
  (when (and (:overdue w) (= (:overdue w) (:state w)))
    (let [minutes (quot (deadlines (:state w)) 60000)]
      (case (:state w)
        :starting (format "%s has been starting for %d minutes and its session has not opened." (:id w) minutes)
        :cancelling (format "%s was told to stop %d minutes ago and has not." (:id w) minutes)))))

(defn due
  "The timer commands due in STATE at NOW, epoch ms, each under an id that is a
  function of the timer, so it commits once."
  [state now]
  (vec (for [w (sort-by (comp id-order :id) (vals (:workers state)))
             :let [limit (deadlines (:state w))]
             :when (and limit (:state-at w) (not (overdue w)) (>= (- now (:state-at w)) limit))]
         {:id (str "timer-" (name (:state w)) "-" (:id w)) :command :timer-fired! :args {:kind (:state w) :subject (:id w)}})))

(defmethod decide* :timer-fired! [state {{:keys [kind subject since] :as args} :args}]
  (let [w (get-in state [:workers subject])
        moot? (if (= :notify kind) (= since (get-in state [:notified subject])) (not= kind (:state w)))]
    {:events (if moot? [] [{:kind :timer-fired :thread (:thread w) :data args}])
     :result subject}))

(defn- owed-effect!
  "The finished Worker that owes effect KIND, or a refusal."
  [state worker kind]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (when-not (some #{kind} (:owes w)) (refuse :not-owed :worker worker :kind kind))
    w))

(defmethod decide* :effect-done! [state {{:keys [worker kind]} :args}]
  (let [w (owed-effect! state worker kind)]
    {:events (if (get-in w [:effects kind :done]) [] [{:kind :effect-done :thread (:thread w) :data {:worker worker :kind kind}}])
     :result kind}))

(defmethod decide* :effect-failed! [state {{:keys [worker kind error]} :args}]
  (let [w (owed-effect! state worker kind)]
    {:events (if (get-in w [:effects kind :done]) [] [{:kind :effect-failed :thread (:thread w) :data {:worker worker :kind kind :error (str error)}}])
     :result kind}))

(defn- clearing
  "The events that clear THREAD for a new Worker NEW: an archived thread is
  reopened, and a Worker whose session ended fails, replaced. A Worker still
  running or queued keeps the thread; cancel it first."
  [state principal thread for new]
  (let [t (thread-of state thread)
        old (some->> (thread-worker state thread) (get (:workers state)))]
    (when (and old (not (session-ended? old)))
      (refuse :thread-has-worker :thread thread :worker (:id old)
              :message (str thread " has Worker " (:id old) " running; cancel it first, or steer it")))
    (cond-> []
      (:resolved-at t) (conj {:kind :thread-reopened :thread thread :data (or (authority state principal for) {})})
      old (conj (finished old (merge {:state :failed :reason (str "Replaced by " new (when for (str " as " for " asked")))}
                                     (authority state principal for)))))))

(defn- worker-model
  "The model Worker start ARGS runs on: its :model, which must be one of
  POLICY's :worker-models, else the replaced Worker's when it names :replaces,
  else nil, the session's default."
  [state {:keys [model replaces]} policy]
  (cond (nil? model) (when replaces (get-in state [:workers replaces :model]))
        (some #{model} (:worker-models policy)) model
        :else (refuse :invalid :field :model
                      :message (str "unknown model " (pr-str model) "; allowed: "
                                    (if (seq (:worker-models policy)) (str/join ", " (:worker-models policy)) "none, add it to :worker-models in policy.edn")))))

(defmethod decide* :start-worker! [state {{:keys [thread workflow brief repo check bench repro for replaces] :as args} :args :keys [token-hash policy requires principal]}]
  (let [id (next-id state :workers "W")
        t (thread-of state thread)
        _ (when (and replaces (not= replaces (:worker t)))
            (refuse :invalid :field :replaces :message (str replaces " is not " thread "'s Worker")))
        origin (request-origin state principal for)
        cleared (clearing state principal thread for id)
        state (reduce fold* state cleared)
        t (thread-of state thread)
        policy (merge default-policy policy)
        model (worker-model state args policy)]
    (when-not (keyword? workflow) (refuse :invalid :field :workflow))
    (when (str/blank? brief) (refuse :invalid :field :brief))
    (when (str/blank? repo) (refuse :invalid :field :repo))
    (doseq [flag requires]
      (when (str/blank? (get args flag)) (refuse :invalid :field flag :message (str (name workflow) " needs " flag))))
    (let [worker (cond-> {:id id :generation (inc (count (:history t))) :workflow workflow :brief brief :repo repo
                          :origin origin}
                   check (assoc :check check) bench (assoc :bench bench) repro (assoc :repro repro) model (assoc :model model))
          reason (blocked-by state policy (assoc worker :thread thread))]
      {:events (into cleared
                     [{:kind :worker-started :thread thread
                       :data (assoc worker :state (if reason :queued :starting) :queued-reason reason)}
                      {:kind :token-issued :thread thread :data {:hash token-hash :principal [:worker id]}}])
       :result (cond-> {:id id :state (if reason :queued :starting)} reason (assoc :reason reason))})))

(defn- brief-text
  "The first mailbox message of Worker W: its brief and how it reports."
  [{:keys [id thread brief workflow origin repo]} wf-run workspace]
  (str/join "\n\n" [(format "[%s brief for %s]" thread id)
                    brief
                    (format "Requested from %s." (some-> origin name))
                    (format "You are Worker %s of thread %s. Your wf run is %s (%s workflow; WF_RUN is set, so `wf status` shows it). Use (super-ask! ...) when you need the person and (super-done!) when the run is done."
                            id thread wf-run (name workflow))
                    (format "Work only in your jj workspace %s, your current directory. %s is the main checkout: read it if you need to, but do not edit it. Its working-copy change is described [<thread>] wip(<worker>) so jj log shows whose it is; commit your work there with a real description that starts with [<thread>], as in jj commit -m '[T12] bbh super: ...' (landing adds the prefix to any that lack it): (super-done!) lands it on the tip of the person's line and removes the workspace. When it returns :conflicts instead, resolve them in your workspace and call it again."
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
    {:events [(finished w (cond-> {:state state-after :reason reason} (and landed (= :done state-after)) (assoc :landed landed)))]
     :result worker}))

(defmethod decide* :cancel! [state {{:keys [reason for] :as args} :args :keys [principal]}]
  (let [thread (target-thread state args)
        worker (or (thread-worker state thread) (refuse :no-worker :thread thread :message (str thread " has no Worker to cancel")))
        w (get-in state [:workers worker])]
    (when-not (#{:queued :starting :working} (:state w)) (refuse :not-running :worker worker))
    (when (str/blank? reason) (refuse :invalid :field :reason))
    {:events [{:kind :cancel-requested :thread (:thread w)
               :data (merge {:id worker :reason reason} (authority state principal for)
                            (when (:conversation w)
                              {:outbox (next-id state :outbox "o") :conversation (:conversation w)
                               :text (str "[Cancelled] " reason)}))}]
     :result worker}))

(defn cancel-owed?
  "Whether Worker W's session has yet to take its cancellation."
  [state w]
  (boolean (some #(and (= :cancel (:delivery %)) (= (:conversation w) (:conversation %)) (#{:pending :sent} (:state %)))
                 (vals (:outbox state)))))

(defmethod decide* :cancel-delivered! [state {{:keys [worker]} :args}]
  (let [w (get-in state [:workers worker])]
    (when-not (= :cancelling (:state w)) (refuse :not-cancelling))
    {:events [(finished w {:state :cancelled :reason (:cancel-reason w)})]
     :result worker}))

(defn health
  "Worker W's health at NOW from OBS, what the controller just read from the
  world: :agent ({:pid :start} of its live session, or nil), :jobs, :wf
  ({:status :phase} of its wf run, or nil), :repl? (whether its REPL answers)
  and :waits, the milliseconds each sent row's file has waited in its mailbox,
  by row id; a row the session took has none. A row sent to a live Worker that
  waits LIMIT-MS or longer is stuck."
  [state w {:keys [agent jobs wf repl? waits]} now limit-ms]
  (let [unidentified (first (filter #(#{:unknown :lost} (:state %)) jobs))
        stale (when repl?
                (->> (vals (:outbox state))
                     (filter #(and (= :sent (:state %)) (= (:conversation w) (:conversation %))))
                     (keep #(when-let [ms (get waits (:id %))] (when (>= ms limit-ms) (assoc % :waited ms))))
                     (sort-by :waited >) first))
        [classification problem]
        (cond (= :queued (:state w)) [:waiting]
              unidentified [:needs-human (format "Job %s can no longer be identified as the process that started it; check it before anything kills or reruns it."
                                                 (:id unidentified))]
              (= :missing (:status wf)) [:needs-human (format "Its wf run %s is missing, so it cannot be resumed." (:wf-run w))]
              (and agent (not repl?)) [:needs-human (format "Its session (pid %s) is alive but its REPL does not answer." (:pid agent))]
              stale [:needs-human (format "Delivery %s has waited %d min in its mailbox while the Worker runs; it is not taking messages."
                                          (:id stale) (quot (:waited stale) 60000))]
              agent [(if (and (waiting? state w) (not (:busy agent))) :waiting :running)]
              (#{:working :cancelling} (:state w))
              [:session-ended (format "Its session ended. Resume it with u in the Inbox or bbh inbox resume %s." (:thread w))]
              :else [:starting])
        same? (= [classification problem] [(get-in w [:health :classification]) (get-in w [:health :problem])])]
    {:at now :process (select-keys agent [:pid :start]) :repl (boolean repl?)
     :busy (boolean (:busy agent)) :prompted-at (:prompted-at agent)
     :workflow wf :classification classification :problem problem
     :since (if same? (or (get-in w [:health :since]) now) now)
     :oldest-unacked (some->> (vals (:outbox state))
                              (filter #(and (= (:thread w) (:thread %)) (#{:pending :sent} (:state %))))
                              (sort-by (comp id-order :id)) first :id)
     :oldest-stopped-ask (some->> (vals (:asks state)) (filter #(and (= (:thread w) (:thread %)) (= :open (:state %)) (:stopped %))) (sort-by :seq) first :seq)
     :jobs (mapv #(select-keys % [:id :state :pid :start]) jobs)}))

(defn health-changed?
  "Whether HEALTH, a new observation of Worker W, says anything W's stored
  health does not. Its :at alone is no change."
  [w health]
  (not= (dissoc health :at) (dissoc (:health w) :at)))

(defn- event-ms
  "Epoch milliseconds of an event's :at, an ISO instant or a number, or nil."
  [at]
  (cond (number? at) (long at)
        (string? at) (try (.toEpochMilli (java.time.Instant/parse at)) (catch Exception _ nil))))

(defn answered-in-chat
  "The ids of Worker W's open asks that the person answered in its chat: asks
  opened before PROMPTED-AT, the epoch ms of the last prompt the person typed
  into W's session. Such a turn answers them, though no reply was sent."
  [state w prompted-at]
  (when (and prompted-at (= :working (:state w)))
    (->> (vals (:asks state))
         (filter #(and (= (:id w) (:worker %)) (= :open (:state %))
                       (some-> (:opened-at %) (< prompted-at))))
         (sort-by :seq) (mapv :id) not-empty)))

(defmethod decide* :health! [state {{:keys [worker health at]} :args}]
  (let [w (worker-of state worker)
        answered (answered-in-chat state w (:prompted-at health))]
    {:events (cond-> [{:kind :health-observed :data {:worker worker :health health :at at}}]
               answered (conj {:kind :asks-answered-in-chat :thread (:thread w)
                               :data {:worker worker :asks answered :prompted-at (:prompted-at health)}}))
     :result worker}))

(defmethod decide* :machine-observed! [_ {:keys [args]}]
  {:events [{:kind :machine-observed :data args}] :result nil})

(defn- rebooted-jobs
  "The ids of Worker W's jobs that an earlier boot killed, as its last health
  pass saw them."
  [w]
  (keep #(when (= :rebooted (:state %)) (:id %)) (get-in w [:health :jobs])))

(defmethod decide* :resume! [state {{:keys [thread]} :args :keys [token-hash policy]}]
  (let [t (thread-of state thread)
        w (or (get-in state [:workers (:worker t)]) (refuse :no-worker :thread thread))
        rebooted (rebooted-jobs w)]
    (when-not (and (session-ended? w) (= :working (:state w)))
      (refuse :not-ended :worker (:id w) :message (str (:id w) "'s session has not ended")))
    (when (= :missing (get-in w [:health :workflow :status]))
      (refuse :wf-missing :worker (:id w) :message (str (:id w) "'s wf run " (:wf-run w) " is missing, so it cannot be resumed")))
    (when-let [reason (blocked-by state (merge default-policy policy) w)]
      (refuse :cap-full :message (str reason "; resume " thread " when one finishes")))
    {:events [{:kind :worker-resumed :thread thread
               :data {:id (:id w) :outbox (next-id state :outbox "o") :conversation (:conversation w)
                      :text (str "[Recovery] Your session ended and the person resumed it. Continue the existing wf run " (:wf-run w)
                                 " and conversation. Inspect existing jobs before acting; do not relaunch surviving jobs."
                                 (when (seq rebooted)
                                   (str " The machine rebooted, so jobs " (str/join ", " rebooted) " died unfinished; do not wait on them, and rerun one only if you still need it, mindful of resources.")))}}
              {:kind :token-issued :thread thread :data {:hash token-hash :principal [:worker (:id w)]}}]
     :result (:id w)}))

(defn- deliverable?
  "Whether Worker W has a session that takes mailbox messages now."
  [w]
  (and (= :working (:state w)) (not (str/blank? (:conversation w)))))

(defn- deliverable-worker! [w]
  (when-not (deliverable? w) (refuse :not-running :worker (:id w)))
  w)

(defmethod decide* :steer! [state {{:keys [text for] :as args} :args :keys [principal] :as cmd}]
  (let [thread (target-thread state args)
        t (thread-of state thread)
        w (or (get-in state [:workers (:worker t)]) (refuse :no-worker :thread thread :message (str thread " has no running Worker; start one")))
        _ (when (str/blank? text) (refuse :invalid :field :text))
        resumed (when (session-ended? w) (:events (decide* state (assoc cmd :command :resume! :args {:thread thread}))))
        state (reduce fold* state resumed)
        w (deliverable-worker! (get-in state [:workers (:id w)]))]
    {:events (into (vec resumed)
                   [{:kind :worker-steered :thread thread
                     :data (merge {:worker (:id w) :text text :outbox (next-id state :outbox "o")
                                   :conversation (:conversation w) :mailbox-text (str "[Steering] " text)}
                                  (authority state principal for))}])
     :result (cond-> {:worker (:id w)} resumed (assoc :resumed true))}))

(defmethod decide* :job-exited! [state {{:keys [worker job exit log seen]} :args}]
  (let [w (or (get-in state [:workers worker]) (refuse :no-such-worker :worker worker))]
    (when-not (and (string? job) (not (str/blank? job)) (integer? exit) (string? log) (:conversation w))
      (refuse :invalid :field :job))
    (if (get-in w [:jobs job])
      {:events [] :result job}
      {:events [{:kind :job-exited :thread (:thread w)
                 :data (cond-> {:worker worker :job job :exit exit :log log}
                         (not seen) (assoc :outbox (next-id state :outbox "o") :conversation (:conversation w)
                                           :text (str "[Job " job " exited with status " exit ". Log: " log "]")))}]
       :result job})))

(def ask-text-max
  "The longest ask :text: one line the person can decide from at a glance.
  Reasoning, evidence and risk go in :details or the :evidence file."
  160)

(defmethod decide* :ask! [state {{:keys [thread worker kind text options recommended stopped holds-up supersedes revision evidence-sha details evidence]} :args}]
  (let [t (thread-of state thread)
        worker (or worker (:worker t))
        open (open-asks state thread)]
    (when-not (#{:question :action :review} kind) (refuse :invalid :field :kind))
    (when (str/blank? text) (refuse :invalid :field :text))
    (when (or (str/includes? (str/trim text) "\n") (> (count (str/trim text)) ask-text-max))
      (refuse :invalid :field :text :message (str "an ask's :text is one line of at most " ask-text-max " characters; put the rest in :details or an :evidence file")))
    (when (and (= :review kind) (str/blank? revision))
      (refuse :invalid :field :revision :message "a review ask names the revision to approve"))
    (when-not (= worker (:worker t)) (refuse :no-active-worker :thread thread))
    (let [ws (get-in state [:workers worker :state])]
      (when (= :cancelling ws) (refuse :cancelling :worker worker :message (str worker " is being cancelled")))
      (when-not (#{:starting :working} ws) (refuse :no-active-worker :thread thread)))
    (doseq [a supersedes] (when-not (open a) (refuse :no-such-ask :ask a)))
    (let [id (next-id state :asks "a")]
      {:events [{:kind :ask-opened :thread thread
                 :data (cond-> {:id id :worker worker :kind kind :text (str/trim text) :options (vec options)
                                :stopped (boolean stopped) :supersedes (vec supersedes)}
                         recommended (assoc :recommended recommended)
                         (not (str/blank? details)) (assoc :details (str/trim details))
                         (not (str/blank? evidence)) (assoc :evidence (str/trim evidence))
                         holds-up (assoc :holds-up holds-up)
                         (= :review kind) (assoc :revision revision :evidence-sha evidence-sha))}]
       :result id})))

(defmethod decide* :reply! [state {{:keys [thread asks-version picks answers note dismiss]} :args :keys [id]}]
  (let [t (thread-of state thread)
        open (open-asks state thread)]
    (when (not= asks-version (:asks-version t))
      (refuse :stale :current (:asks-version t) :asks (vec (sort-by :seq (vals open)))))
    (doseq [[ask option] picks]
      (let [a (or (open ask) (refuse :no-such-ask :ask ask))]
        (when-not (some #(= option (:id %)) (:options a)) (refuse :no-such-option :ask ask :option option))))
    (doseq [[ask text] answers]
      (when-not (open ask) (refuse :no-such-ask :ask ask))
      (when (str/blank? text) (refuse :invalid :field :answers :ask ask)))
    (doseq [ask dismiss] (when-not (open ask) (refuse :no-such-ask :ask ask)))
    (when (and (empty? picks) (empty? answers) (empty? dismiss) (str/blank? note)) (refuse :invalid :field :picks))
    (when-not (:worker t) (refuse :no-active-worker :thread thread))
    (let [reply (cond-> {:id id :thread thread :worker (:worker t) :picks (or picks {}) :note (or note "")
                         :dismiss (set dismiss)}
                  (seq answers) (assoc :answers (into {} (map (fn [[a s]] [a (str/trim s)])) answers)))
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

(defmethod decide* :draft! [state {{:keys [thread pick answer note]} :args}]
  (let [t (thread-of state thread)
        current (:asks-version t)
        draft (or (:draft t) {:picks {} :note "" :asks-version current})]
    (when-let [[ask] answer]
      (when (not= current (:asks-version draft))
        (refuse :stale :message "Draft is stale; clear it before answering again" :current current))
      (or (get (open-asks state thread) ask) (refuse :no-such-ask :ask ask)))
    (when-let [[ask option] pick]
      (when (not= current (:asks-version draft))
        (refuse :stale :message "Draft is stale; clear it before choosing again" :current current))
      (let [a (or (get (open-asks state thread) ask) (refuse :no-such-ask :ask ask))]
        (when-not (some #(= option (:id %)) (:options a)) (refuse :no-such-option :ask ask :option option))))
    (let [draft (cond-> draft
                  pick (assoc-in [:picks (first pick)] (second pick))
                  answer (as-> d (let [[ask text] answer text (str/trim (str text))]
                                   (if (str/blank? text)
                                     (let [d (update d :answers dissoc ask)] (if (empty? (:answers d)) (dissoc d :answers) d))
                                     (assoc-in d [:answers ask] text))))
                  note (assoc :note (str/trim (str note))))]
      {:events [(if (and (empty? (:picks draft)) (empty? (:answers draft)) (str/blank? (:note draft)))
                  {:kind :draft-discarded :data {:thread thread}}
                  {:kind :draft-saved :data {:thread thread :draft draft}})]
       :result draft})))

(defmethod decide* :discard-draft! [state {{:keys [thread]} :args}]
  (thread-of state thread)
  {:events [{:kind :draft-discarded :data {:thread thread}}] :result thread})

(defmethod decide* :dismiss! [state {{:keys [thread]} :args :keys [id]}]
  {:events (filterv some? [(dismissal state thread id)]) :result thread})

(defmethod decide* :read! [state {{:keys [threads read]} :args}]
  (let [ts (mapv #(thread-of state %) threads)]
    {:events (vec (for [t ts :when (= (boolean read) (unread? t))]
                    {:kind :thread-read :data {:thread (:id t) :seq (if read (or (:milestone-seq t) 0) 0)}}))
     :result (mapv :id ts)}))

(defmethod decide* :archive! [state {{:keys [thread]} :args :keys [id]}]
  (let [queued (some #(when (and (= thread (:thread %)) (= :queued (:state %))) %) (vals (:workers state)))]
    {:events (filterv some? [(dismissal state thread id)
                             (when queued (finished queued {:state :cancelled :reason "thread archived"}))
                             {:kind :thread-resolved :thread thread :data {}}])
     :result thread}))

(def ^:private number-words
  (merge (zipmap ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"
                  "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen" "nineteen"] (range 20))
         {"twenty" 20 "thirty" 30 "forty" 40 "fifty" 50 "sixty" 60 "seventy" 70 "eighty" 80 "ninety" 90}))

(defn- spoken-tokens
  "The words of S, each with its value as a number word or digits, whether
  punctuation after it ends the number (a hyphen only beside a space, as
  twenty-two is one number), and the offset where the text after
  it starts."
  [s]
  (let [m (re-matcher #"([^\s,.:;!?-]+)([\s,.:;!?-]*)" s)]
    (loop [acc []]
      (if (.find m)
        (let [w (str/lower-case (.group m 1))
              sep (.group m 2)]
          (recur (conj acc {:v (or (number-words w) (when (re-matches #"\d{1,3}" w) (parse-long w)))
                            :digits? (boolean (re-matches #"\d{1,3}" w))
                            :word w
                            :stop? (boolean (re-find #"[,.:;!?]|\s-|-\s" sep))
                            :end (.end m)})))
        acc))))

(defn- spoken-number
  "The number the words of S say, and the text after it: \"twelve\", \"twenty
  two\", \"1 2\", \"170\", \"one seventy\" and \"one hundred and seventy\".
  Punctuation after a word ends the number, so \"twelve, 3 files\" is 12."
  [s]
  (let [ts (spoken-tokens s)
        at (fn [i] (let [t (get ts i)] (when (and t (or (zero? i) (not (:stop? (get ts (dec i)))))) t)))
        below-100 (fn [i]
                    (when-let [{:keys [v digits?]} (at i)]
                      (cond (or (nil? v) digits?) nil
                            (and (>= v 20) (let [u (at (inc i))] (and u (:v u) (< 0 (:v u) 10))))
                            [(+ v (:v (at (inc i)))) (+ i 2)]
                            :else [v (inc i)])))
        digits (fn []
                 (loop [n (:v (at 0)) i 1]
                   (let [t (at i)]
                     (if (and t (:digits? t) (< (* n 10) 1000)) (recur (parse-long (str n (:word t))) (inc i)) [n i]))))
        hundreds (fn []
                   (let [u (:v (at 0))]
                     (when (and u (< 0 u 10) (not (:digits? (at 0))))
                       (if (= "hundred" (:word (at 1)))
                         (let [i (if (= "and" (:word (at 2))) 3 2)]
                           (if-let [[r j] (below-100 i)] [(+ (* 100 u) r) j] [(* 100 u) 2]))
                         (when-let [[r j] (below-100 1)] (when (>= r 10) [(+ (* 100 u) r) j]))))))
        [n i] (cond (nil? (:v (at 0))) nil
                    (:digits? (at 0)) (digits)
                    :else (or (hundreds) (below-100 0)))]
    (when n [n (if (zero? i) s (subs s (:end (get ts (dec i)))))])))

(defn parse-handle
  "[handle rest] when TEXT starts with a thread handle, typed (`T12`, `t12`)
  or spoken (`tee twelve`, `T twelve`, `tee twenty two`, `tee one seventy`); else nil."
  [text]
  (let [text (str/trim (str text))]
    (if-let [[_ n rest] (re-matches #"(?is)t\s*(\d+)\b[\s,.:;-]*(.*)" text)]
      [(str "T" (parse-long n)) (str/trim rest)]
      (when-let [[_ rest] (re-matches #"(?is)(?:tee|tea|t)\b[\s,.:;-]*(.*)" text)]
        (when-let [[n more] (spoken-number rest)]
          [(str "T" n) (str/trim (str/replace more #"^[\s,.:;-]+" ""))])))))

(defn- router-row
  "The outbox row that owes TEXT to the Router, or a refusal when none is registered."
  [state text]
  (let [conversation (or (get-in state [:router :router :conversation]) (refuse :no-router))]
    {:outbox (next-id state :outbox "o") :conversation conversation :text text}))

(defmethod decide* :say! [state {{:keys [text origin]} :args :keys [id]}]
  (when (str/blank? text) (refuse :invalid :field :text))
  (when-not (#{:phone :emacs :herdr :voice} origin) (refuse :invalid :field :origin))
  (let [[thread message] (parse-handle text)
        t (when thread (or (get-in state [:threads thread]) (refuse :no-such-thread :thread thread)))
        w (some-> (get-in state [:workers (:worker t)]) deliverable-worker!)]
    (if w
      {:events [{:kind :worker-steered :thread thread
                 :data (cond-> {:id id :origin origin :text message :worker (:id w) :outbox (next-id state :outbox "o")
                                :conversation (:conversation w)
                                :mailbox-text (format "[%s from %s%s] %s" thread (name origin) (if (= :voice origin) ", dictated" "") message)}
                         (get-in state [:router :router :conversation])
                         (assoc :summary {:outbox (next-id (update-in state [:counters :outbox] (fnil inc 0)) :outbox "o")
                                          :conversation (get-in state [:router :router :conversation])
                                          :text (format "[routed to %s %s from %s] %s" thread (:id w) (name origin) message)}))}]
       :result {:thread thread :to (:id w)}}
      (let [said (str (when (= :voice origin) "(spoken) ") (if thread (str thread ": " message) text))]
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
    {:events (if (= :closed (:state r)) [] [{:kind :reply-answered :thread thread :data {:id reply}}]) :result reply}))

(defmethod decide* :issue-token! [_ {{:keys [principal]} :args :keys [token-hash]}]
  {:events [{:kind :token-issued :data {:hash token-hash :principal principal}}] :result :issued})

(defmethod decide* :outbox-sent! [state {{:keys [outbox message]} :args}]
  (let [o (or (get-in state [:outbox outbox]) (refuse :no-such-outbox :outbox outbox))]
    {:events [{:kind :outbox-sent :thread (:thread o) :data {:id outbox :message message}}] :result outbox}))

(defmethod decide* :outbox-acked! [state {{:keys [outbox]} :args}]
  (let [o (or (get-in state [:outbox outbox]) (refuse :no-such-outbox :outbox outbox))]
    {:events [{:kind :outbox-acked :thread (:thread o) :data {:id outbox}}] :result outbox}))

(defn dead-letter?
  "Whether outbox row O waits for a conversation whose Worker has finished, so
  nobody will ever read it."
  [state o]
  (and (#{:pending :sent} (:state o))
       (let [ws (filter #(= (:conversation o) (:conversation %)) (vals (:workers state)))]
         (and (seq ws) (every? #(#{:done :failed :cancelled} (:state %)) ws)))))

(defmethod decide* :outbox-dropped! [state {{:keys [outbox]} :args}]
  (let [o (or (get-in state [:outbox outbox]) (refuse :no-such-outbox :outbox outbox))]
    (if (dead-letter? state o)
      {:events [{:kind :outbox-dropped :thread (:thread o) :data {:id outbox}}] :result outbox}
      {:events [] :result outbox})))

(defn decide
  "Authorize and decide COMMAND for PRINCIPAL on STATE. Returns {:events :result}
  without :seq or :at on the events; the store assigns them. Throws ex-info
  with :error on refusal."
  [state principal cmd]
  (decide* state (assoc (authorize state principal cmd) :principal principal)))

(defn- bump [state kind] (update-in state [:counters kind] (fnil inc 0)))

(defn- set-states [state kind ids st]
  (reduce #(assoc-in %1 [kind %2 :state] st) state ids))

(defmulti fold*
  "STATE after EVENT, which has :seq, :at, :kind, :thread and :data."
  (fn [_state event] (:kind event)))

(def retired-events
  "Event kinds no command emits any more. An old log still holds them, so they
  fold to nothing."
  #{:message-routed :router-triaged :lease-moved :lease-granted :restart-attempted :restart-failed :worker-restarted
    :thread-linked :worker-attached})

(defmethod fold* :default [state {:keys [kind]}]
  (if (retired-events kind)
    state
    (throw (ex-info (str "unknown event " kind) {:error :unknown-event :kind kind}))))

(def ^:private versions
  "The format version of each event kind that has changed since version 1."
  {:snapshot 2})

(defn version-of "The format version events of KIND are written in." [kind] (get versions kind 1))

(def ^:private upcasts
  "{[kind version] f}: F turns an event of KIND in VERSION into the next
  version. Change an event's format by raising its entry in `versions` and
  adding the step here, so a log written before the change still folds."
  {[:snapshot 1] (fn [event]
                   (update-in event [:data :state :workers] update-vals
                              #(cond-> % (= :waiting (:state %)) (assoc :state :working))))})

(defn upcast
  "EVENT, as an old log holds it, in the format `fold` reads today."
  [{:keys [kind v] :as event}]
  (let [v (or v 1)]
    (if-let [f (get upcasts [kind v])]
      (recur (assoc (f event) :v (inc v)))
      event)))

(defmethod fold* :snapshot [_ {{:keys [state]} :data}] state)

(defn fold
  "STATE after EVENT, with the thread it concerns stamped :updated-at and the
  Worker it moved to another state stamped :state-at, which timers count from."
  [before {:keys [thread at data] :as event}]
  (let [state (fold* before event)
        w (or (:id data) (:worker data))
        moved? (and (not= :snapshot (:kind event)) (get-in state [:workers w])
                    (not= (get-in before [:workers w :state]) (get-in state [:workers w :state])))]
    (cond-> state
      moved? (assoc-in [:workers w :state-at] (event-ms at))
      (get-in state [:threads thread]) (assoc-in [:threads thread :updated-at] at))))

(defmethod fold* :machine-observed [state _] state)

(defmethod fold* :thread-created [state {{:keys [id title]} :data :keys [seq]}]
  (-> state (bump :threads)
      (assoc-in [:threads id] {:id id :title title :resources #{} :worker nil :history []
                               :asks-version seq :resolved-at nil :summary nil
                               :milestone-seq seq})))

(defmethod fold* :worker-started [state {{:keys [id] :as worker} :data :keys [thread]}]
  (-> state (bump :workers)
      (assoc-in [:workers id] (assoc worker :thread thread :conversation nil :wf-run nil :workspace nil :buffer nil))))

(defmethod fold* :worker-promoted [state {{:keys [id]} :data}]
  (-> state (update-in [:workers id] assoc :state :starting :queued-reason nil)))

(defmethod fold* :worker-prepared [state {{:keys [worker] :as args} :data}]
  (update-in state [:workers worker] merge (dissoc args :worker)))

(defmethod fold* :worker-running [state {{:keys [id outbox text conversation] :as running} :data :keys [thread]}]
  (let [w (get-in state [:workers id])]
    (-> state (bump :outbox)
        (update-in [:workers id] merge (dissoc running :id :outbox :text) {:state :working :queued-reason nil})
        (assoc-in [:threads thread :summary] nil)
        (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation :text text
                                    :state :pending :message nil})
        (assoc-in [:threads thread :worker] id))))

(defn- finish-line
  "Thread T's summary once Worker W ends with OUTCOME: how it ended, then the
  last note W wrote, if any. A queued Worker never ran, so the note is not its."
  [t w id outcome reason landed]
  (let [note (when (:conversation w) (:summary t))]
    (str id " " (name outcome)
         (when landed (str ", landed " (subs landed 0 (min 8 (count landed)))))
         (when-not (str/blank? reason) (str ": " reason))
         (when note (str ". Last note: " note)))))

(defmethod fold* :effect-done [state {{:keys [worker kind]} :data}]
  (assoc-in state [:workers worker :effects kind] {:done true}))

(defmethod fold* :effect-failed [state {{:keys [worker kind error]} :data}]
  (update-in state [:workers worker :effects kind] #(-> % (update :tries (fnil inc 0)) (assoc :error error))))

(defmethod fold* :worker-finished [state {{:keys [id reason landed owes] outcome :state} :data :keys [thread seq]}]
  (let [t (get-in state [:threads thread])
        w (get-in state [:workers id])]
    (-> state
        (update-in [:workers id] #(cond-> (assoc % :state outcome :reason reason) landed (assoc :landed landed) (not-empty owes) (assoc :owes owes)))
        (update :tokens #(into {} (map (fn [[h t]] [h (cond-> t (= [:worker id] (:principal t)) (assoc :revoked true))]) %)))
        (update :asks #(into {} (map (fn [[aid a]] [aid (cond-> a (and (= id (:worker a)) (= :open (:state a))) (assoc :state :superseded))]) %)))
        (update :replies update-vals #(cond-> % (and (= id (:worker %)) (= :sent (:state %))) (assoc :state :closed)))
        (cond-> (contains? #{id nil} (:worker t))
          (-> (assoc-in [:threads thread :worker] nil)
              (update-in [:threads thread :history] conj id)
              (assoc-in [:threads thread :milestone-seq] seq)
              (assoc-in [:threads thread :summary] (finish-line t w id outcome reason landed)))))))

(defmethod fold* :cancel-requested [state {{:keys [id reason outbox conversation text]} :data :keys [thread]}]
  (cond-> (update-in state [:workers id] assoc :state :cancelling :cancel-reason reason)
    outbox (-> (bump :outbox)
               (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation :text text
                                           :state :pending :message nil :delivery :cancel}))))

(defmethod fold* :timer-fired [state {{:keys [kind subject since]} :data}]
  (if (= :notify kind)
    (assoc-in state [:notified subject] since)
    (assoc-in state [:workers subject :overdue] kind)))

(defmethod fold* :health-observed [state {{:keys [worker health]} :data}]
  (cond-> state worker (assoc-in [:workers worker :health] health)))

(defmethod fold* :worker-resumed [state {{:keys [id outbox conversation text]} :data :keys [thread]}]
  (-> state (bump :outbox)
      (update-in [:workers id] dissoc :health)
      (update :tokens #(into {} (map (fn [[h t]] [h (cond-> t (= [:worker id] (:principal t)) (assoc :revoked true))]) %)))
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation
                                  :text text :state :pending :message nil})))

(defmethod fold* :job-exited [state {{:keys [worker job exit log outbox conversation text]} :data :keys [thread]}]
  (cond-> (assoc-in state [:workers worker :jobs job] {:id job :exit exit :log log})
    outbox (-> (bump :outbox)
               (assoc-in [:outbox outbox] {:id outbox :thread thread :reply nil :conversation conversation
                                           :text text :state :pending :message nil}))))

(defmethod fold* :token-issued [state {{:keys [hash principal]} :data}]
  (assoc-in state [:tokens hash] {:id hash :principal principal :revoked false}))

(defmethod fold* :ask-opened [state {{:keys [id supersedes] :as ask} :data :keys [thread seq at]}]
  (-> state (bump :asks)
      (set-states :asks supersedes :superseded)
      (assoc-in [:asks id] (assoc ask :thread thread :seq seq :state :open :opened-at (event-ms at)))
      (assoc-in [:threads thread :asks-version] seq)
      (assoc-in [:threads thread :milestone-seq] seq)))

(defmethod fold* :asks-answered-in-chat [state {{:keys [asks]} :data}]
  (set-states state :asks asks :superseded))

(defmethod fold* :reply-sent [state {{:keys [id picks dismiss outbox conversation text] :as reply} :data :keys [thread seq]}]
  (-> state (bump :outbox)
      (assoc-in [:replies id] (-> reply (dissoc :outbox :conversation :text) (assoc :state :sent :outbox outbox :seq seq)))
      (assoc-in [:outbox outbox] {:id outbox :thread thread :reply id :conversation conversation :text text
                                  :state :pending :message nil})
      (set-states :asks (concat (keys picks) (keys (:answers reply)) (keys (:approved reply))) :sent)
      (set-states :asks dismiss :dismissed)
      (assoc-in [:threads thread :asks-version] seq)
      (update-in [:threads thread] dissoc :draft)))

(defn linked
  "The record KIND/ID of STATE with the ids it links, so a reader can hop from
  one to the next: an ask's :reply, a reply's :asks, a Worker's :asks and
  :replies. Nil when there is no such record."
  [state kind id]
  (when-let [r (get-in state [kind id])]
    (case kind
      :asks (assoc r :reply (some (fn [p] (when (or (contains? (:picks p) id) (contains? (:answers p) id) (contains? (:approved p) id) (contains? (set (:dismiss p)) id)) (:id p)))
                                  (sort-by :seq (vals (:replies state)))))
      :replies (assoc r :asks (vec (sort (distinct (concat (keys (:picks r)) (keys (:answers r)) (keys (:approved r)) (:dismiss r))))))
      :workers (assoc r :state (shown-state state r) :asks (vec (sort (keep #(when (= id (:worker %)) (:id %)) (vals (:asks state)))))
                        :replies (vec (sort (keep #(when (= id (:worker %)) (:id %)) (vals (:replies state))))))
      r)))

(def section-order [:stopped :holding-up :waiting :needs-attention :sent :new :working :quiet :archived])

(def section-titles
  {:stopped "Stopped on you" :holding-up "Holding up work" :waiting "Waiting on you"
   :needs-attention "Needs attention" :sent "Sent" :new "New" :working "Working"
   :quiet "Quiet" :archived "Archived"})

(def ^:private execution-of
  {:queued :queued :starting :working :working :working :cancelling :working :waiting :waiting
   :done :done :failed :failed :cancelled :cancelled})

(defn execution
  "What THREAD's work is doing, from its latest Worker: :none without one,
  :queued, :working, :waiting, or how it finished. An archived thread shows
  :none unless its Worker is queued, starting, working or waiting."
  [state thread]
  (let [t (get-in state [:threads thread])
        w (->> (vals (:workers state)) (filter #(= thread (:thread %))) (sort-by (comp id-order :id)) last)
        e (get execution-of (shown-state state w) :none)]
    (if (and (:resolved-at t) (not (#{:queued :starting :working} (:state w)))) :none e)))

(defn thread-view
  "Thread record T as reads show it, with its derived :execution."
  [state t]
  (assoc t :execution (execution state (:id t))))

(defn section
  "THREAD's section in STATE, by the doc's rule, first match wins. Its
  :read-seq is the last milestone the owner has read."
  [state {:keys [id resolved-at] :as t}]
  (let [execution (execution state id)
        asks (filter #(= id (:thread %)) (vals (:asks state)))
        open (filter #(= :open (:state %)) asks)
        replies (filter #(= id (:thread %)) (vals (:replies state)))]
    (cond (some :stopped open) :stopped
          (some :holds-up open) :holding-up
          (seq open) :waiting
          resolved-at :archived
          (and (or (= :failed execution) (#{:needs-human :session-ended} (get-in state [:workers (:worker (get-in state [:threads id])) :health :classification]))) (empty? open)) :needs-attention
          (some (some-fn stuck-effect overdue) (filter #(= id (:thread %)) (vals (:workers state)))) :needs-attention
          (some #(= :sent (:state %)) replies) :sent
          (unread? t) :new
          (#{:working :queued} execution) :working
          :else :quiet)))

(defn status
  "The one status every view shows for a thread in SECTION whose work is
  EXECUTION: :on-you while it waits on the person, :stuck when it failed or
  needs attention, :working, then :archived, :done or :quiet."
  [section execution]
  (cond (or (#{:stopped :holding-up :waiting} section) (= :waiting execution)) :on-you
        (or (= :needs-attention section) (= :failed execution)) :stuck
        (#{:working :queued} execution) :working
        (= :archived section) :archived
        (= :done execution) :done
        :else :quiet))

(defn ask-line
  "The open ask of THREAD to show on its row, or nil."
  [state thread-id]
  (->> (vals (:asks state))
       (filter #(and (= thread-id (:thread %)) (= :open (:state %))))
       (sort-by :seq) last))

(defn sections
  "Threads of STATE grouped in the doc's order, newest (highest number) first
  within a section, each with its newest open ask,
  all its open :asks and whether an archived one came back."
  [state]
  (let [rows (for [t (map #(thread-view state %) (sort-by (comp - id-order :id) (vals (:threads state))))
                   :let [s (section state t)
                         ask (ask-line state (:id t))]]
               (assoc t :section s :status (status s (:execution t)) :unread (unread? t) :ask ask :asks (vec (sort-by :seq (vals (open-asks state (:id t)))))
                      :line (or (:text ask)
                                (get-in state [:workers (:worker t) :health :problem])
                                (when (= :failed (:execution t)) (get-in state [:workers (peek (:history t)) :reason]))
                                (some (fn [w] (when (= (:id t) (:thread w))
                                                (if-let [{:keys [kind error]} (stuck-effect w)]
                                                  (format "%s finished, but its %s failed %d times: %s" (:id w) (name kind) effect-tries error)
                                                  (overdue w))))
                                      (sort-by (comp - id-order :id) (vals (:workers state))))
                                (:summary t))
                      :came-back (boolean (and (:resolved-at t) (> (:milestone-seq t) (:resolved-at t))))
                      :model (get-in state [:workers (or (:worker t) (peek (:history t))) :model])))
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
  (update-in state [:threads thread] assoc :resolved-at seq))

(defmethod fold* :thread-reopened [state {:keys [thread]}]
  (update-in state [:threads thread] assoc :resolved-at nil))

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

(defmethod fold* :outbox-sent [state {{:keys [id message]} :data}]
  (cond-> state
    (= :pending (get-in state [:outbox id :state])) (update-in [:outbox id] assoc :state :sent :message message)))

(defmethod fold* :outbox-dropped [state {{:keys [id]} :data}]
  (cond-> state
    (#{:pending :sent} (get-in state [:outbox id :state])) (assoc-in [:outbox id :state] :dropped)))

(defmethod fold* :outbox-acked [state {{:keys [id]} :data}]
  (cond-> state
    (#{:pending :sent} (get-in state [:outbox id :state])) (assoc-in [:outbox id :state] :done)))

(defmethod fold* :thread-read [state {{:keys [thread seq]} :data}]
  (assoc-in state [:threads thread :read-seq] seq))

(defmethod fold* :draft-saved [state {{:keys [thread draft]} :data}]
  (assoc-in state [:threads thread :draft] draft))

(defmethod fold* :draft-discarded [state {{:keys [thread]} :data}]
  (update-in state [:threads thread] dissoc :draft))

(defmethod fold* :reply-answered [state {{:keys [id]} :data}]
  (let [reply (get-in state [:replies id])]
    (-> state (assoc-in [:replies id :state] :answered)
        (assoc-in [:outbox (:outbox reply) :state] :done)
        (set-states :asks (concat (keys (:picks reply)) (keys (:answers reply)) (keys (:approved reply))) :answered))))
