(ns bbh.workers-mbt-test
  "Model-based test of the Worker lifecycle against super.qnt's workers
  module (desktop/build/bbh/verify/quint). A driver commits each Quint action
  as the command bbh.super.model decides and folds, and ABSTRACT reads the
  real state back as Quint's record: each Worker's state, each thread's
  Worker and archived flag, whose session ended, and the cancel row each
  Worker owes (its outbox row with :delivery :cancel). replay-trace follows
  a `quint run --mbt` trace and compares the two after every step;
  cancelDelivered is taken only when model/cancel-plan, as the controller
  runs it, asks for it."
  (:require [bbh.super.model :as model]
            [clojure.test :refer [deftest is]]))

(def workers-module
  "super.qnt's workers instance."
  {:nw 3 :threads ["T1" "T2" "T3"] :cap 2})

(defn- commit
  "STATE after PRINCIPAL's command CMD, its events numbered and timed as
  bbh.super.store does, or [:refused error]."
  [state principal cmd]
  (try (let [seq0 (:pbt/seq state 0)
             events (map-indexed (fn [i e] (assoc e :seq (+ seq0 i 1) :at (+ seq0 i 1) :v (model/version-of (:kind e))))
                                 (:events (model/decide state principal cmd)))]
         (assoc (reduce model/fold state events) :pbt/seq (+ seq0 (count events))))
       (catch clojure.lang.ExceptionInfo e [:refused (:error (ex-data e)) (ex-message e)])))

(defn world
  "The controller with the module's threads and no Worker."
  [{:keys [threads cap]}]
  {:cap cap :i 0
   :state (reduce (fn [s _] (commit s :owner {:id (str "t" (count (:threads s))) :command :thread! :args {:title "t"}}))
                  model/empty-state threads)})

(defn- wid [w] (str "W" w))

(defn- cancel-row [state w]
  (let [conv (get-in state [:workers (wid w) :conversation])]
    (when conv (first (filter #(and (= :cancel (:delivery %)) (= conv (:conversation %))) (vals (:outbox state)))))))

(defn- ended? [w] (and (some? (:health w)) (empty? (get-in w [:health :process]))))

(defn- command
  "The [principal command] Quint action [NAME ARG] is, in WORLD."
  [{:keys [state cap i]} [name x y]]
  (let [id (str "c" i)
        policy {:workers cap}
        thread-of #(get-in state [:workers (wid %) :thread])]
    (case name
      :startWorker [:owner {:id id :command :start-worker! :token-hash (str "h" i) :policy policy :requires []
                            :args {:thread y :workflow :change :brief "b" :repo "/r"}}]
      :promoteWorker [:controller {:id id :command :promote-worker! :token-hash (str "h" i) :policy policy}]
      :workerRunning [:controller {:id id :command :worker-running!
                                   :args {:worker (wid x) :conversation (str "conv-" x) :wf-run "run" :workspace "/w"
                                          :buffer "b" :started-at 0}}]
      :cancel [:owner {:id id :command :cancel! :args {:thread (thread-of x) :reason "stop"}}]
      :cancelDelivered [:controller {:id id :command :cancel-delivered! :args {:worker (wid x)}}]
      :finishWorker [:controller {:id id :command :finish-worker! :args {:worker (wid x) :state-after y :reason "r"}}]
      :archive [:owner {:id id :command :archive! :args {:thread x}}]
      :sessionEnds [:controller {:id id :command :health!
                                 :args {:worker (wid x) :at i :health {:classification :session-ended :process {} :busy false :jobs []}}}]
      :resumeWorker [:owner {:id id :command :resume! :token-hash (str "h" i) :policy policy :args {:thread (thread-of x)}}])))

(defn- take-cancel
  "STATE after Worker W's session takes its cancel: the outbox plan sends
  the row, the session deletes the file, the plan marks it done."
  [state w]
  (let [{:keys [id] :as row} (cancel-row state w)
        sent (if (= :pending (:state row))
               (commit state :controller {:id (str "sent-" id) :command :outbox-sent! :args {:outbox id :message (model/message-id id)}})
               state)]
    (if (vector? sent) sent (commit sent :controller {:id (str "acked-" id) :command :outbox-acked! :args {:outbox id}}))))

(defn plan-delivers?
  "Whether model/cancel-plan, as the controller runs it on Worker W with no
  jobs, asks for :cancel-delivered!."
  [state w]
  (let [wk (get-in state [:workers (wid w)])]
    (boolean (some #(= :cancel-delivered! (get-in % [1 :command]))
                   (model/cancel-plan state wk {:agent (when-not (ended? wk) {:live true}) :jobs []})))))

(defn- prepare
  "STATE with every :starting Worker that has no conversation prepared, as
  the controller's launch does with :worker-prepared!."
  [state]
  (reduce (fn [s {:keys [id]}]
            (let [n (subs id 1)
                  s2 (commit s :controller {:id (str "prepared-" id) :command :worker-prepared!
                                            :args {:worker id :conversation (str "conv-" n) :workspace "/w" :wf-run "run"}})]
              (if (vector? s2) s s2)))
          state
          (filter #(and (= :starting (:state %)) (nil? (:conversation %))) (vals (:workers state)))))

(defn step
  "WORLD after Quint action A, or [:refused ...] when the real code refuses
  it. With :prepare in WORLD, a Worker that starts is prepared at once."
  [world [name x :as a]]
  (let [after (cond (= :takeCancel name) (take-cancel (:state world) x)
                    (and (= :cancelDelivered name) (not (plan-delivers? (:state world) x))) [:refused :not-planned]
                    :else (let [[p cmd] (command world a)] (commit (:state world) p cmd)))]
    (if (vector? after)
      after
      (-> world (assoc :state (cond-> after (:prepare world) prepare)) (update :i inc)))))

(def ^:private states
  {:queued :Queued :starting :Starting :working :Working :cancelling :Cancelling :done :Done :failed :Failed :cancelled :Cancelled})

(defn abstract
  "The real STATE as super.qnt's s, for module M: wstate, wthread, tworker,
  resolved, ended and csig."
  [{:keys [nw threads]} state]
  (let [ws (range 1 (inc nw))
        w-of #(get-in state [:workers (wid %)])]
    {:wstate (into {} (for [w ws] [w (if-let [x (w-of w)] (states (:state x)) :Absent)]))
     :wthread (into {} (for [w ws] [w (or (:thread (w-of w)) "none")]))
     :tworker (into {} (for [t threads :let [x (get-in state [:threads t :worker])]]
                         [t (if (and x (#{:queued :starting :working :cancelling} (get-in state [:workers x :state]))) (parse-long (subs x 1)) 0)]))
     :resolved (into {} (for [t threads] [t (some? (get-in state [:threads t :resolved-at]))]))
     :ended (into {} (for [w ws] [w (boolean (some-> (w-of w) ended?))]))
     :csig (into {} (for [w ws] [w (case (:state (cancel-row state w)) nil :NoSig (:pending :sent) :Owed :Taken)]))}))

(defn- action-of
  "The driver action for trace state ST: Quint's action, its picks, and for
  finishWorker the outcome, which shows in the next state."
  [st]
  (let [{:keys [w t]} (:picks st)
        name (keyword (:action st))]
    (case name
      :startWorker [name w t]
      :archive [name t]
      :finishWorker [name w ({:Done :done :Failed :failed} (get-in st [:s :wstate w]))]
      :promoteWorker [name]
      [name w])))

(defn replay-trace
  "Follow STATES, a quint run --mbt trace of the workers module, on the real
  model. Returns nil when every state agrees, else where they first differ.
  With PREPARE?, each Worker is prepared as it starts, closing the window
  super.qnt does not model: a cancel before :worker-prepared! has no
  conversation to tell."
  [states & [prepare?]]
  (let [m workers-module
        want #(select-keys (:s %) [:wstate :wthread :tworker :resolved :ended :csig])]
    (loop [w (assoc (world m) :prepare prepare?) [st & more] (rest states) i 1]
      (cond (nil? st) nil
            :else (let [a (action-of st)
                        w2 (step w a)]
                    (cond (and (vector? w2) (= :refused (first w2)))
                          {:step i :action a :problem :refused :why (vec (rest w2)) :quint (want st) :real (abstract m (:state w))}
                          (not= (want st) (abstract m (:state w2)))
                          {:step i :action a :problem :states-differ :quint (want st) :real (abstract m (:state w2))}
                          :else (recur w2 more (inc i))))))))

(defn replay-trace-prepared
  "replay-trace with each Worker prepared as it starts."
  [states]
  (replay-trace states true))

(deftest the-cap-queues-and-archive-frees-a-slot
  ;; super.qnt's capQueuesTest, on the real model.
  (let [m workers-module
        w (reduce step (world m) [[:startWorker 1 "T1"] [:startWorker 2 "T2"] [:startWorker 3 "T3"]])
        _ (is (= :Queued (get-in (abstract m (:state w)) [:wstate 3])))
        w (reduce step w [[:archive "T1"] [:promoteWorker]])]
    (is (= :Starting (get-in (abstract m (:state w)) [:wstate 3])))))
