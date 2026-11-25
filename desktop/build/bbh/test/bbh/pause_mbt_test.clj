(ns bbh.pause-mbt-test
  "The usage-limit pause of bbh.super.model, driven through super.qnt's
  limits actions (desktop/build/bbh/verify/quint). A driver commits each
  action as the command the controller, the person or a session would send,
  runs model/outbox-plan as dispatch! does and model/due on a clock, and
  keeps each conversation's mailbox files and transcript. Two uses:
  replay-trace follows a `quint run --mbt` trace of the pause module and
  compares the states (bb mbt bbh-pause-mbt), and check runs generated
  action sequences (bbh.gens.pause) against the spec's rules, then settles
  with the clock running and the sessions taking their mail."
  (:require [bbh.gens.pause :as gens]
            [bbh.props :as props]
            [bbh.super.model :as model]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.properties :as prop]))

(defn- commit
  "STATE after PRINCIPAL's command CMD, its events numbered as bbh.super.store
  does and timed at NOW, or [:refused error]."
  [state now principal cmd]
  (try (let [seq0 (:pbt/seq state 0)
             events (map-indexed (fn [i e] (assoc e :seq (+ seq0 i 1) :at now :v (model/version-of (:kind e))))
                                 (:events (model/decide state principal cmd)))]
         (assoc (reduce model/fold state events) :pbt/seq (+ seq0 (count events))))
       (catch clojure.lang.ExceptionInfo e [:refused (:error (ex-data e)) (ex-message e)])))

(defn- subject [s] (if (zero? s) "router" (str "W" s)))
(defn- conversation [s] (if (zero? s) "router" (str "c" s)))
(defn- row-no [id] (parse-long (subs id 1)))

(def t0 "The driver's clock at the start, epoch ms." 1795600000000)

(defn world
  "The Router registered and NW Workers working, each with its brief in the outbox."
  [nw]
  (let [run (fn [st principal cmd] (let [r (commit st t0 principal cmd)] (assert (map? r) (pr-str r)) r))
        st (run model/empty-state :controller {:id "reg" :command :register-router! :token-hash "rh" :args {:conversation "router"}})
        st (reduce (fn [st w]
                     (-> st
                         (run :owner {:id (str "t" w) :command :thread! :args {:title "t"}})
                         (run :owner {:id (str "s" w) :command :start-worker! :token-hash (str "h" w) :policy {:workers 10} :requires []
                                      :args {:thread (str "T" w) :workflow :change :brief "b" :repo "/r"}})
                         (run :controller {:id (str "r" w) :command :worker-running!
                                           :args {:worker (str "W" w) :conversation (conversation w) :wf-run "run" :workspace "/w"
                                                  :buffer "b" :started-at t0}})))
                   st (range 1 (inc nw)))]
    {:nw nw :state st :now t0 :i 0 :files #{} :cancelled #{}
     :transcript (into {} (for [s (range (inc nw))] [s []]))
     :interrupted #{} :resumes []}))

(defn- wstate [w s] (if (zero? s) :working (get-in w [:state :workers (subject s) :state])))
(defn- paused? [w s] (some? (if (zero? s) (get-in w [:state :router :router :paused]) (get-in w [:state :workers (subject s) :paused]))))
(defn- live? [w s] (#{:working :cancelling} (wstate w s)))
(defn- rows [w] (sort-by (comp row-no :id) (vals (get-in w [:state :outbox]))))
(defn- kind-of [o]
  (cond (= :cancel (:delivery o)) :Cancel
        (str/starts-with? (:text o) "[Resumed]") :Continue
        (str/includes? (:text o) " brief for ") :Brief
        :else :Msg))

(defn- run-cmd
  "W after PRINCIPAL commits CMD, or [:refused ...]."
  [w principal cmd]
  (let [st (commit (:state w) (:now w) principal (assoc cmd :id (or (:id cmd) (str "c" (:i w)))))]
    (if (vector? st) st (assoc w :state st))))

(defn- dispatch
  "W after one dispatch! pass: model/outbox-plan on the files still waiting."
  [w]
  (let [waiting (set (keep #(when (and (= :sent (:state %)) ((:files w) [(:conversation %) (:message %)])) (:id %)) (rows w)))]
    (reduce (fn [w [kind x y]]
              (case kind
                :mailbox/send (update w :files conj [x (:message-id y)])
                :mailbox/delete (update w :files disj [x y])
                :command (let [w2 (run-cmd w :controller x)] (if (vector? w2) (throw (ex-info "plan refused" {:cmd x :why w2})) w2))))
            w (model/outbox-plan (:state w) waiting))))

(defn- box
  "The rows whose files wait in subject S's mailbox, oldest first."
  [w s]
  (filter #(and (= :sent (:state %)) ((:files w) [(conversation s) (:message %)])) (rows w)))

(defn- take-plan
  "The rows subject S's session takes now: a cancel first, then, unless it
  took one, all its mail."
  [w s]
  (let [b (box w s) cancels (filter #(= :Cancel (kind-of %)) b)]
    (cond (seq cancels) cancels
          ((:cancelled w) s) []
          :else b)))

(defn- take-mail
  "W after S's session takes its files (session/take-mail!, record!, a
  cancel by receive-control!) and the plan acks their rows."
  [w s]
  (let [taken (take-plan w s)
        mail (remove #(= :Cancel (kind-of %)) taken)
        w (reduce (fn [w o] (-> (run-cmd w :controller {:id (str "acked-" (:id o)) :command :outbox-acked! :args {:outbox (:id o)}})
                                (update :files disj [(conversation s) (:message o)])))
                  w taken)]
    (cond-> (update-in w [:transcript s] into (map (comp row-no :id) mail))
      (some #(= :Cancel (kind-of %)) taken) (update :cancelled conj s)
      (some #(= :Continue (kind-of %)) mail) (update :interrupted disj s))))

(defn enabled?
  "Whether action A, as super.qnt guards it, may run in W."
  [w [name x]]
  (case name
    :msg (live? w x)
    :limitHit true
    (:timerFires :resumeNow :cancelPause) (paused? w x)
    :take (and (live? w x) (seq (take-plan w x)))
    :cancel (= :working (wstate w x))
    :cancelDelivered (and (= :cancelling (wstate w x))
                          (some #(= :cancel-delivered! (get-in % [1 :command]))
                                (model/cancel-plan (:state w) (get-in w [:state :workers (subject x)]) {:agent {:pid 1} :jobs []})))
    :finish (= :working (wstate w x))
    (:dispatch :tick :clock :timers) true))

(defn step
  "W after action A, or [:refused ...] when the real code refuses it."
  [w [name x]]
  (let [i (:i w)
        w (update w :i inc)
        hit-at (+ (:now w) i)]
    (case name
      :msg (if (zero? x)
             (run-cmd w :owner {:command :say! :args {:text (str "m" i) :origin :emacs}})
             (run-cmd w :controller {:command :job-exited! :args {:worker (subject x) :job (str "j" i) :exit 0 :log "/l"}}))
      :limitHit (let [w2 (run-cmd w :controller {:command :limit-hit!
                                                 :args {:subject (subject x) :at hit-at :until (when (odd? i) (+ (:now w) (* 60000 (mod i 7))))
                                                        :window "5-hour" :text "You've hit your 5-hour limit"}})]
                  (cond-> w2 (and (map? w2) (= :working (wstate w x))) (update :interrupted conj x)))
      :timerFires (let [p (if (zero? x) (get-in w [:state :router :router :paused]) (get-in w [:state :workers (subject x) :paused]))]
                    (run-cmd w :controller {:id (str "resume-" (subject x) "-" (:at p)) :command :pause-ended!
                                            :args {:subject (subject x) :how :timer :at (:at p)}}))
      :resumeNow (run-cmd w :owner {:command :pause-ended! :args {:subject (subject x) :how :now}})
      :cancelPause (some-> (run-cmd w :owner {:command :pause-ended! :args {:subject (subject x) :how :cancel}})
                           (#(if (vector? %) % (update % :interrupted disj x))))
      :dispatch (dispatch w)
      :take (take-mail w x)
      :cancel (run-cmd w :owner {:command :cancel! :args {:thread (str "T" x) :reason "stop"}})
      :cancelDelivered (run-cmd w :controller {:id (str "cancelled-" (subject x)) :command :cancel-delivered! :args {:worker (subject x)}})
      :finish (run-cmd w :controller {:command :finish-worker! :args {:worker (subject x) :state-after :done :reason "r"}})
      :tick w
      :clock (update w :now + (* 15000 x))
      :timers (reduce (fn [w cmd]
                        (let [w2 (run-cmd w :controller cmd)]
                          (cond-> w2 (= :pause-ended! (:command cmd)) (update :resumes conj [(:now w) (get-in cmd [:args :subject])]))))
                      w (model/due (:state w) (:now w))))))

(def ^:private wstates {:working :Working :cancelling :Cancelling :done :Done :cancelled :Cancelled})

(defn abstract
  "The real world W as super.qnt's l: wstate, paused, rows, nrows, the newest
  row each session took (last) and whether one came out of order. Rows past
  MAX-ROWS are out of the spec's bound, which ends a pause without its
  [Resumed] row once every row id is used."
  [w max-rows]
  (let [ss (range (inc (:nw w)))
        by-no (into {} (map (juxt (comp row-no :id) identity)) (rows w))]
    {:wstate (into {} (for [s ss] [s (wstates (wstate w s))]))
     :paused (into {} (for [s ss] [s (paused? w s)]))
     :rows (into {} (for [r (range 1 (inc max-rows)) :let [o (by-no r)]]
                      [r (if o
                           {:st ({:pending :Pending :sent :InMailbox :done :Taken :dropped :Dropped} (:state o))
                            :to (if (= "router" (:conversation o)) 0 (parse-long (subs (:conversation o) 1)))
                            :kind (kind-of o)}
                           {:st :NoRow :to 0 :kind :Msg})]))
     :nrows (min max-rows (count by-no))
     :last (into {} (for [[s t] (:transcript w)] [s (reduce max 0 (filter #(<= % max-rows) t))]))
     :disorder (boolean (some (fn [[_ t]] (not (apply < -1 t))) (:transcript w)))}))

;; Rules

(defn- step-problems
  "What the step from W0 to W1 breaks: mail sent to a paused session, a
  [Resumed] row for a Worker not working, a pause step that moves a Worker,
  or two timer resumes closer than the stagger."
  [w0 w1]
  (let [before (into {} (map (juxt :id identity)) (rows w0))
        fresh (remove #(before (:id %)) (rows w1))
        paused-conv (fn [w c] (paused? w (if (= "router" c) 0 (parse-long (subs c 1)))))]
    (cond-> []
      (some #(and (= :pending (:state (before (:id %)))) (= :sent (:state %)) (not= :Cancel (kind-of %))
                  (paused-conv w0 (:conversation %)) (paused-conv w1 (:conversation %)))
            (rows w1))
      (conj [:sent-while-paused])
      (some #(and (= :Continue (kind-of %)) (not= "router" (:conversation %))
                  (not= :working (wstate w0 (parse-long (subs (:conversation %) 1)))))
            fresh)
      (conj [:continue-to-finished fresh])
      (and (not= (model/pauses (:state w0)) (model/pauses (:state w1)))
           (not= (map #(get-in % [1 :state]) (sort (:workers (:state w0))))
                 (map #(get-in % [1 :state]) (sort (:workers (:state w1)))))
           (= (count (:resumes w0)) (count (:resumes w1)))
           (not (some #(#{:done :cancelled} (get-in % [1 :state])) (:workers (:state w1)))))
      (conj [:pause-moved-a-worker])
      (some (fn [[[a _] [b _]]] (< (- b a) (:stagger-ms model/pause-timing))) (partition 2 1 (:resumes w1)))
      (conj [:resumes-not-staggered (:resumes w1)]))))

(defn- world-problems [w]
  (cond-> []
    (some (fn [[_ t]] (not (apply < -1 t))) (:transcript w)) (conj [:out-of-order (:transcript w)])))

(defn run-actions
  "[world action] after each generated action, skipping those not enabled."
  [{:keys [actions]}]
  (reductions (fn [[w] a] (if (enabled? w a) (let [w2 (step w a)] (if (vector? w2) [w [:refused a w2]] [w2 a])) [w nil]))
              [(world 2) :init] actions))

(defn- settle
  "W after the account recovers: the clock runs, timers fire, the
  controller dispatches and ends the cancels its sessions took, and every
  live session takes its mail, until nothing moves."
  [w]
  (loop [w w n 0]
    (let [ss (range (inc (:nw w)))
          w2 (reduce (fn [w a] (if (enabled? w a) (step w a) w))
                     (-> w (step [:clock 4]) (step [:timers nil]) dispatch)
                     (concat (map #(vector :take %) ss) (map #(vector :cancelDelivered %) (rest ss))))]
      (if (or (> n 300)
              (and (empty? (model/pauses (:state w2))) (= (dissoc (:state w) :pbt/seq) (dissoc (:state w2) :pbt/seq)) (= (:files w) (:files w2))))
        w2
        (recur w2 (inc n))))))

(defn check
  "What a generated action sequence breaks: a refused action, the rules
  after every step, then once settled: a pause left, a row neither taken nor
  dropped, or a session whose turn a limit stopped and nothing continued."
  [input]
  (let [steps (run-actions input)
        refused (some (fn [[_ a]] (when (and (vector? a) (= :refused (first a))) [[:refused a]])) steps)
        ws (map first steps)
        end (settle (last ws))]
    (vec (concat refused
                 (some (fn [[a b]] (not-empty (step-problems a b))) (partition 2 1 ws))
                 (some #(not-empty (world-problems %)) ws)
                 (world-problems end)
                 (when-let [p (seq (model/pauses (:state end)))] [[:still-paused p]])
                 (when-let [r (seq (remove #(#{:done :dropped} (:state %)) (rows end)))] [[:undelivered (map :id r)]])
                 (when-let [s (seq (filter #(live? end %) (:interrupted end)))] [[:not-continued s]])))))

(defn observe
  "The pauses, held rows and Worker states after each action taken, and
  labels for the paths the brief names."
  [input]
  (let [steps (remove (comp nil? second) (run-actions input))
        shape (fn [w] [(count (model/pauses (:state w))) (count (filter #(= :pending (:state %)) (rows w)))
                       (sort (map :state (vals (get-in w [:state :workers]))))])]
    {:steps (map (fn [[w a]] {:state (shape w) :action (if (keyword? a) a (first a))}) steps)
     :labels (set (keep (fn [[[w0] [w1 a]]]
                          (when (vector? a)
                            (case (first a)
                              :msg (when (paused? w0 (second a)) :mail-while-paused)
                              :limitHit (cond (= :cancelling (wstate w0 (second a))) :limit-during-cancel
                                              (some #(= [(:now w0) (subject (second a))] %) (:resumes w0)) :limit-right-after-resume
                                              (paused? w0 (second a)) :limit-while-paused)
                              :cancel (when (paused? w0 (second a)) :cancel-while-paused)
                              :timers (when (> (count (:resumes w1)) (count (:resumes w0))) :timer-resumed)
                              nil)))
                        (partition 2 1 steps)))}))

(deftest property-generated-pauses-keep-the-spec
  (props/check "pause actions" 150 (prop/for-all [input gens/gen-input] (empty? (check input)))
               :explain check))

;; Replaying Quint traces

(def pause-module "super.qnt's pause instance." {:nw 1 :max-rows 4})

(defn replay-trace
  "Follow STATES, a quint run --mbt trace of the pause module as
  [{:action :picks :l}], on the real model. Returns nil when every state
  agrees, else where they first differ."
  [states]
  (let [{:keys [nw max-rows]} pause-module
        want #(select-keys (:l %) [:wstate :paused :rows :nrows :last :disorder])]
    (loop [w (world nw) [st & more] (rest states) i 1]
      (if-not st
        nil
        (let [{:keys [s] :as picks} (:picks st)
              a [(keyword (:action st)) (or s (:w picks))]
              w2 (step w a)]
          (cond (vector? w2) {:step i :action a :problem :refused :why w2 :quint (want st) :real (abstract w max-rows)}
                (not= (want st) (abstract w2 max-rows)) {:step i :action a :problem :states-differ :quint (want st) :real (abstract w2 max-rows)}
                :else (recur w2 more (inc i))))))))

(deftest the-named-cases-agree-with-the-spec
  ;; super.qnt's pause runs on the real model: a limit during a tool call,
  ;; during a cancel, and a second limit right after resuming.
  (let [m (:max-rows pause-module)
        run (fn [actions] (reduce (fn [w a] (let [w2 (step w a)] (is (map? w2) (pr-str a w2)) w2)) (world 1) actions))
        tool (run [[:dispatch] [:take 1] [:limitHit 1] [:msg 1] [:msg 0] [:dispatch] [:timerFires 1] [:dispatch] [:take 1]])
        cancel (run [[:limitHit 1] [:cancel 1] [:limitHit 1] [:dispatch] [:take 1] [:cancelDelivered 1] [:dispatch]])
        second (run [[:limitHit 0] [:msg 0] [:timerFires 0] [:limitHit 0] [:dispatch]])]
    (is (= [1 2 4] (get-in tool [:transcript 1])))
    (is (= :Cancelled (get-in (abstract cancel m) [:wstate 1])))
    (is (= :Dropped (get-in (abstract cancel m) [:rows 1 :st])))
    (is (= [:Pending :Pending] (map #(get-in (abstract second m) [:rows % :st]) [2 3])))
    (is (= [2 3 4] (get-in (-> second (step [:timerFires 0]) dispatch (step [:take 0])) [:transcript 0])))))
