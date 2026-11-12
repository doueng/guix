(ns bbh.super-property-test
  "Random command sequences on the controller's state machine, committed through
  the store as the controller does. After every step the rules of Super.tla and
  the per-command postconditions below must hold, and at the end the event log
  must replay to the stored state. It stands in for the socket tests that only
  checked these rules; super_test.clj keeps those that need files, processes,
  jj or Emacs."
  (:require [babashka.fs :as fs]
            [bbh.super.model :as model]
            [bbh.super.store :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; ---- commands from random choices -------------------------------------------

(def ^:private finished #{:done :failed :cancelled})

(defn- pick
  "An element of COLL chosen by the random number N, or nil when COLL is empty."
  [coll n]
  (when (seq coll) (nth (vec coll) (mod n (count coll)))))

(defn- ids [state kind] (sort-by model/id-order (keys (kind state))))

(defn- thread-of
  "The thread of Worker WORKER, or T9, a thread nothing has, for a Worker nothing has."
  [state worker]
  (or (get-in state [:workers worker :thread]) "T9"))

(defn- live-workers
  "Workers that still hold a token, so their session can call the controller."
  [state]
  (keep #(when-not (#{:done :failed :cancelled} (:state %)) (:id %)) (sort-by (comp model/id-order :id) (vals (:workers state)))))

(defn- open-asks [state thread]
  (filter #(and (= thread (:thread %)) (= :open (:state %))) (sort-by :seq (vals (:asks state)))))

(defn- router-rows [state]
  (keep #(when (:origin %) (:id %)) (vals (:outbox state))))

(def ^:private kinds
  "Each command kind, as often as it should come up."
  (vec (mapcat (fn [[k n]] (repeat n k))
               {:thread! 3 :start-worker! 4 :router-start! 1 :promote-worker! 2 :worker-running! 4 :finish-worker! 2
                :cancel! 1 :cancel-delivered! 1 :health! 3 :resume! 2 :steer! 1 :ask! 5 :foreign-ask! 1 :reply! 5
                :approve! 2 :draft! 1 :read! 1 :dismiss! 1 :archive! 1 :say! 2 :register-router! 1 :note! 1 :answered! 2
                :outbox! 3 :job-exited! 1 :effect! 3 :repeat 2})))

(defn- pick-id
  "Mostly one of IDS, chosen by N; one time in ten, or when there are none,
  MISSING, an id nothing has."
  [ids n missing]
  (if (and (seq ids) (pos? (mod n 10))) (pick ids n) missing))

(defn- command
  "The [principal command] that random numbers [K A B C] make on STATE at step I.
  Ids come from what STATE holds, plus one that does not exist, so commands
  collide and some are refused."
  [state cap i [k a b c]]
  (let [id (str "c" i)
        thread (pick-id (ids state :threads) a "T9")
        live (pick (live-workers state) a)
        worker (pick-id (if (odd? (quot a 10)) (live-workers state) (ids state :workers)) a "W9")
        policy {:workers cap}
        start (fn [principal args]
                [principal {:id id :command :start-worker! :token-hash (str "h" i) :policy policy :requires (if (zero? (mod c 5)) [:check] [])
                            :args (merge {:thread thread :workflow :change :brief "b" :repo "/r"} args)}])]
    (case (pick kinds k)
      :thread! [:owner {:id id :command :thread! :args {:title (if (zero? (mod b 10)) " " "a")}}]
      :start-worker! (start :owner (when (odd? b) {:check "make t"}))
      :router-start! (start :router {:for (pick [nil "o99" (pick (router-rows state) b)] c) :check "make t"})
      :promote-worker! [:controller {:id id :command :promote-worker! :token-hash (str "h" i) :policy policy}]
      :worker-running! [:controller {:id id :command :worker-running!
                                     :args {:worker (or (pick (keep #(when (= :starting (:state %)) (:id %)) (vals (:workers state))) b) worker)
                                            :conversation (str "conv-" i) :wf-run "run" :workspace "/w" :buffer "b" :started-at 0}}]
      :effect! (let [owing (keep #(when (seq (model/obligations state %)) (:id %)) (vals (:workers state)))
                     w (pick-id owing a worker)
                     kind (or (pick (if (odd? c) (model/obligations state (get-in state [:workers w])) (get-in state [:workers w :owes])) b)
                              (pick model/effect-order b))]
                 [:controller (if (zero? (mod b 3))
                                {:id id :command :effect-failed! :args {:worker w :kind kind :error "boom"}}
                                {:id id :command :effect-done! :args {:worker w :kind kind}})])
      :finish-worker! (let [args (cond-> {:state-after (pick [:done :failed :cancelled] b) :reason "r"} (zero? (mod a 3)) (assoc :landed "tip"))]
                        (if (and live (even? c))
                          [[:worker live] {:id id :command :finish-worker! :args args}]
                          [:controller {:id id :command :finish-worker! :args (assoc args :worker worker)}]))
      :cancel! (let [archived (keep #(when (and (get-in state [:threads (:thread %) :resolved-at]) (model/active-states (:state %))) (:id %)) (vals (:workers state)))]
                 [(pick [:owner :router] c) {:id id :command :cancel!
                                             :args {:thread (thread-of state (if (and (seq archived) (odd? b)) (pick archived a) worker)) :reason (pick ["stop" "" "stop"] b)}}])
      :cancel-delivered! [:controller {:id id :command :cancel-delivered!
                                       :args {:worker (pick-id (keep #(when (= :cancelling (:state %)) (:id %)) (vals (:workers state))) b worker)}}]
      ;; A session that ended stays ended until it is resumed, as in Super.tla.
      :health! (let [w (get-in state [:workers worker])
                     alive? (and (odd? b) (not (and w (model/session-ended? w))))]
                 [:controller {:id id :command :health!
                               :args {:worker worker :at i
                                      :health {:classification (if alive? :running :session-ended)
                                               :process (if alive? {:pid 7 :start "s"} {})
                                               ;; In a turn, and one the person typed, as agent.json says.
                                               :busy (boolean (and alive? (zero? (mod b 3))))
                                               :prompted-at (when (and alive? (zero? (mod c 2))) (System/currentTimeMillis))
                                               :jobs (if (zero? (mod c 3)) [{:id "j1" :state :rebooted}] [])}}}])
      :resume! (let [ended (keep #(when (model/session-ended? %) (:thread %)) (vals (:workers state)))]
                 [:owner {:id id :command :resume! :token-hash (str "h" i) :policy policy
                          :args {:thread (if (and (seq ended) (odd? b)) (pick ended c) thread)}}])
      :steer! [(pick [:owner :router (when live [:worker live])] (if live c (mod c 2)))
               {:id id :command :steer! :token-hash (str "h" i) :policy policy :args {:thread (thread-of state worker) :text (pick ["go on" " "] b)}}]
      :ask! (when live
              (let [t (get-in state [:workers live :thread])
                    kind (pick [:question :question :action :review] b)]
                [[:worker live] {:id id :command :ask!
                                 :args (cond-> {:kind kind :text "q?" :stopped (odd? c)
                                                :options [{:id "y" :label "Yes"} {:id "n" :label "No"}]
                                                :supersedes (if (zero? (mod c 3)) (mapv :id (take 1 (open-asks state t))) [])}
                                         (= :review kind) (assoc :revision (pick ["r1" "r2" nil] c) :evidence-sha (pick ["e1" "e2"] b))
                                         (zero? (mod c 4)) (assoc :holds-up true))}]))
      :foreign-ask! (when live
                      [[:worker live] {:id id :command :ask! :args {:thread thread :kind :question :text "q?" :options []}}])
      :reply! (let [open (filter #(= :open (:state %)) (vals (:asks state)))
                    thread (if (and (seq open) (pos? (mod a 5))) (:thread (pick open a)) thread)
                    t (get-in state [:threads thread])
                    target (if (pos? (mod b 4)) (pick (open-asks state thread) b) (pick (vals (:asks state)) b))]
                [(if (and live (zero? (mod c 7))) [:worker live] :owner)
                 {:id id :command :reply!
                  :args (cond-> {:thread thread :asks-version (if (zero? (mod c 4)) (dec (:asks-version t 0)) (:asks-version t 0))}
                          (and target (odd? c)) (assoc :picks {(:id target) (pick ["y" "n" "x"] a)})
                          (and target (even? c)) (assoc :dismiss [(:id target)])
                          (or (nil? target) (zero? (mod b 3))) (assoc :note "why"))}])
      :approve! (let [reviews (filter #(= :review (:kind %)) (vals (:asks state)))
                      a' (pick (if (pos? (mod a 4)) (filter #(= :open (:state %)) reviews) reviews) b)]
                  [:owner {:id id :command :approve!
                           :args {:thread (or (:thread a') thread) :ask (:id a')
                                  :revision (if (zero? (mod c 5)) "r9" (:revision a'))
                                  :evidence-sha (if (= 1 (mod c 5)) "e9" (:evidence-sha a'))
                                  :current-sha (if (= 2 (mod c 5)) "e9" (:evidence-sha a'))}}])
      :draft! (let [a' (first (open-asks state thread))]
                [:owner {:id id :command :draft! :args (cond-> {:thread thread}
                                                         (and a' (odd? b)) (assoc :pick [(:id a') (pick ["y" "n"] c)])
                                                         (even? b) (assoc :note (pick ["" "later"] c)))}])
      :read! [:owner {:id id :command :read! :args {:threads [thread] :read (odd? b)}}]
      :dismiss! [:owner {:id id :command :dismiss! :args {:thread thread}}]
      :archive! [:owner {:id id :command :archive! :args {:thread thread}}]
      :say! [:owner {:id id :command :say!
                     :args {:origin (pick [:phone :emacs :herdr :voice] c)
                            :text (str (when (odd? b) (str thread " ")) (pick ["use plan B" "what runs"] c))}}]
      :register-router! [:controller {:id id :command :register-router! :token-hash (str "h" i) :args {:conversation "router"}}]
      :note! (when live [[:worker live] {:id id :command (pick [:note! :milestone!] b) :args {:text "looking"}}])
      :answered! (when live
                   [[:worker live] {:id id :command :answered!
                                    :args {:reply (pick-id (keep #(when (= live (:worker %)) (:id %)) (vals (:replies state))) b "r99")}}])
      :outbox! (let [done-for (set (keep #(when (finished (:state %)) (:conversation %)) (vals (:workers state))))
                     dead (filter #(and (done-for (:conversation %)) (not= :done (:state %))) (vals (:outbox state)))
                     row (when (and (seq dead) (odd? a)) (pick dead c))]
                 ;; A row nobody will read is dropped, and a dropped one is then
                 ;; acked or sent late, which must not revive it.
                 [:controller {:id id :command (cond (nil? row) (pick [:outbox-sent! :outbox-acked! :outbox-dropped!] b)
                                                     (= :dropped (:state row)) (pick [:outbox-sent! :outbox-acked!] b)
                                                     :else :outbox-dropped!)
                               :args {:outbox (or (:id row) (pick-id (ids state :outbox) c "o99")) :message "m"}}])
      :job-exited! [:controller {:id id :command :job-exited!
                                 :args {:worker worker :job (str "j" (mod b 2)) :exit (mod c 3) :log "l" :seen (odd? c)}}]
      :repeat nil)))

;; ---- the rules ----------------------------------------------------------------

(defn- violations
  "What STATE breaks of Super.tla's invariants and the record rules. LAUNCHED
  holds the Workers that ever started; as in Super.tla, a queued Worker that is
  cancelled never launches, so it counts against no cap."
  [state cap launched]
  (let [workers (vals (:workers state))
        open (filter #(= :open (:state %)) (vals (:asks state)))]
    (concat
     (when (> (count (filter #(and (launched (:id %)) (model/holds-slot? state %)) workers)) cap) [[:cap-exceeded cap]])
     (for [[t ws] (group-by :thread workers)
           :when (> (count (filter #(or (= :queued (:state %)) (model/active-states (:state %))) ws)) 1)]
       [:two-workers-on-a-thread t])
     (for [a open :when (not (model/active-states (get-in state [:workers (:worker a) :state])))]
       [:open-ask-without-a-live-worker (:id a)])
     ;; A Worker seen mid-turn is never shown as waiting.
     (for [t (vals (:threads state))
           :let [w (->> workers (filter #(= (:id t) (:thread %))) (sort-by (comp model/id-order :id)) last)]
           :when (and (get-in w [:health :busy]) (= :waiting (model/execution state (:id t))))]
       [:mid-turn-shown-waiting (:id t)])
     ;; Waiting is read from the asks, so no Worker stores it, and a read shows
     ;; it exactly while a working Worker outside a turn has a stopped ask open.
     (for [w workers :when (= :waiting (:state w))] [:waiting-is-stored (:id w)])
     ;; Only a Worker that ended owes effects, each at most once, and what it
     ;; still owes is among them, without those done or failed too often.
     (for [w workers :when (and (:owes w) (not (finished (:state w))))] [:live-worker-owes (:id w)])
     (for [w workers :when (not= (:owes w) (seq (filter (set (:owes w)) model/effect-order)))] [:owes-out-of-order (:id w) (:owes w)])
     (for [w workers kind (model/obligations state w)
           :let [e (get-in w [:effects kind])]
           :when (or (not (some #{kind} (:owes w))) (:done e) (>= (:tries e 0) model/effect-tries))]
       [:owes-what-it-should-not (:id w) kind])
     (for [w workers kind (keys (:effects w)) :when (not (some #{kind} (:owes w)))] [:effect-nobody-owed (:id w) kind])
     (for [w workers
           :let [stopped (boolean (some #(and (:stopped %) (= (:id w) (:worker %))) open))
                 shown (:state (model/linked state :workers (:id w)))]
           :when (not= (= :waiting shown) (and stopped (= :working (:state w)) (not (get-in w [:health :busy]))))]
       [:waiting-shown-disagrees-with-the-asks (:id w) shown])
     (for [w workers :when (not= (:state w) (:state (model/linked state :workers (:id w))))
           :when (not= :waiting (:state (model/linked state :workers (:id w))))]
       [:read-shows-another-state (:id w)])
     (for [w workers :when (and (= :queued (:state w)) (get-in state [:threads (:thread w) :resolved-at]))]
       [:queued-on-an-archived-thread (:id w)])
     (for [t (vals (:threads state)) :when (:worker t) :when (not= (:id t) (get-in state [:workers (:worker t) :thread]))]
       [:thread-names-a-foreign-worker (:id t)])
     (for [kind [:threads :workers :asks :outbox] id (keys (kind state))
           :when (> (model/id-order id) (get-in state [:counters kind] 0))]
       [:id-past-its-counter id])
     ;; The Inbox: every thread once, newest first within a section, and
     ;; the first matching rule decides the section.
     (let [sections (model/sections state)
           rows (for [{:keys [section threads]} sections t threads] [section t])]
       (concat
        (when (not= (sort (keys (:threads state))) (sort (map (comp :id second) rows))) [[:sections-lose-a-thread]])
        (for [{:keys [section threads]} sections
              :when (not= (map :id threads) (sort-by (comp - model/id-order) (map :id threads)))]
          [:section-out-of-order section])
        (for [[section t] rows
              :let [stopped? (some #(and (:stopped %) (= (:id t) (:thread %))) open)
                    any-open? (some #(= (:id t) (:thread %)) open)]
              :when (or (and stopped? (not= :stopped section))
                        (and (not any-open?) (:resolved-at t) (not= :archived section))
                        (not= (:came-back t) (boolean (and (:resolved-at t) (> (:milestone-seq t) (:resolved-at t))))))]
          [:wrong-section (:id t) section])))
     ;; Reads hop between linked records: an ask names the reply that took it.
     (for [r (vals (:replies state))
           a (concat (keys (:picks r)) (keys (:approved r)) (:dismiss r))
           :when (or (not= (:id r) (:reply (model/linked state :asks a)))
                     (not (some #{a} (:asks (model/linked state :replies (:id r))))))]
       [:unlinked-reply (:id r) a])
     (for [a (vals (:asks state))
           :when (not (some #{(:id a)} (:asks (model/linked state :workers (:worker a)))))]
       [:worker-does-not-list-its-ask (:id a)]))))

(def ^:private ask-rank {:open 0 :sent 1 :dismissed 1 :superseded 1 :answered 2})
(def ^:private outbox-rank {:pending 0 :sent 1 :done 2 :dropped 2})

(defn- regressions
  "Records that moved backwards from BEFORE to AFTER: a finished Worker that
  changed, an ask or outbox row that went back, or a Worker that started on an
  archived thread."
  [before after]
  (concat
   (for [[id w] (:workers before) :when (finished (:state w))
         :when (not= (:state w) (get-in after [:workers id :state]))]
     [:finished-worker-moved id])
   (for [[id w] (:workers after)
         :when (and (= :starting (:state w)) (not= :starting (get-in before [:workers id :state]))
                    (get-in after [:threads (:thread w) :resolved-at]))]
     [:started-on-an-archived-thread id])
   (for [[id w] (:workers before) [kind e] (:effects w) :when (and (:done e) (not (:done (get-in after [:workers id :effects kind]))))]
     [:effect-undone id kind])
   (for [[id a] (:asks before) :let [s (get-in after [:asks id :state])]
         :when (or (< (ask-rank s) (ask-rank (:state a))) (and (not= :open (:state a)) (not= s (:state a)) (not= [:sent :answered] [(:state a) s])))]
     [:ask-moved-back id (:state a) s])
   (for [[id r] (:replies before) :when (and (= :answered (:state r)) (not= :answered (get-in after [:replies id :state])))]
     [:reply-moved-back id])
   (for [[id o] (:outbox before) :let [s (get-in after [:outbox id :state])]
         :when (or (< (outbox-rank s) (outbox-rank (:state o))) (and (= :dropped (:state o)) (not= :dropped s)))]
     [:outbox-moved-back id (:state o) s])))

(defn- refusal [e] (assoc (ex-data e) :message (ex-message e)))

(defn- new-rows [before after] (map #(get-in after [:outbox %]) (remove (set (keys (:outbox before))) (keys (:outbox after)))))

(defn- postconditions
  "What one step broke of the command's own rules. OUT is {:result} or
  {:refused ex-data}; BEFORE and AFTER are the states around it."
  [[principal {:keys [command args]}] before after {:keys [result refused]} cap]
  (let [t (get-in before [:threads (:thread args)])
        open (open-asks before (:thread args))
        bad (fn [why & more] [(into [why command] more)])]
    (concat
     (when (and refused (not= before after)) (bad :refusal-changed-state))
     ;; A reply or a new ask changes what is open, so a send against the old list is stale.
     (when (and (not refused) (#{:reply! :approve! :ask!} command)
                (let [t' (get-in after [:threads (:thread (or (get-in after [:replies result]) (get-in after [:asks result])))])]
                  (not (> (:asks-version t' 0) (get-in before [:threads (:id t') :asks-version] 0)))))
       (bad :asks-version-not-moved))
     (when (and (vector? principal) (= :worker (first principal)))
       (let [own (get-in before [:workers (second principal) :thread])]
         (cond (not (#{:ask! :note! :milestone! :answered! :finish-worker!} command))
               (when (not= :forbidden (:error refused)) (bad :worker-not-forbidden))
               (and (:thread args) (not= own (:thread args)))
               (when (not= :forbidden (:error refused)) (bad :foreign-thread-not-forbidden))
               :else (when (= :forbidden (:error refused)) (bad :own-command-forbidden)))))
     (case (when-not (and (vector? principal) (= :forbidden (:error refused))) command)
       :health!
       ;; A prompt the person typed answers every ask the Worker opened before it.
       (when-let [p (and (not refused) (get-in args [:health :prompted-at]))]
         (let [w (get-in after [:workers (:worker args)])]
           (when (and (= :working (get-in before [:workers (:worker args) :state]))
                      (some #(and (= (:id w) (:worker %)) (= :open (:state %)) (< (:opened-at %) p)) (vals (:asks after))))
             (bad :typed-answer-left-asks-open))))

       :start-worker!
       (cond (and t (= :router principal) (not (get-in before [:outbox (:for args) :origin])))
             (when (not= :invalid (:error refused)) (bad :router-start-without-a-message refused))
             (and (:resolved-at t) (not refused) (get-in after [:threads (:thread args) :resolved-at])) (bad :archived-not-reopened)
             refused nil
             :else (let [w (get-in after [:workers (:id result)])
                         full? (>= (count (filter #(model/holds-slot? before %) (vals (:workers before)))) cap)]
                     (concat
                      (when (not= (:state w) (if full? :queued :starting)) (bad :cap-decision (:state w)))
                      (when (and full? (not= (str cap " Workers already running") (:reason result))) (bad :queued-reason (:reason result)))
                      ;; A Worker it replaced has joined the history.
                      (when (not= (:generation w) (inc (count (get-in after [:threads (:thread args) :history])))) (bad :generation))
                      (when (not= (:origin w) (if (= :owner principal) :owner (get-in before [:outbox (:for args) :origin])))
                        (bad :origin (:origin w))))))
       :resume!
       (let [others (filter #(model/holds-slot? before %) (vals (:workers before)))]
         (when (and (not refused) (>= (count others) cap)) (bad :resumed-past-the-cap)))
       :promote-worker!
       (let [queued (filter #(= :queued (:state %)) (vals (:workers before)))
             room? (< (count (filter #(model/holds-slot? before %) (vals (:workers before)))) cap)]
         (when (and (seq queued) room? (nil? result)) (bad :queued-left-with-a-free-slot)))
       :reply!
       (cond (and t (not= (:asks-version args) (:asks-version t)))
             (when-not (and (= :stale (:error refused)) (= (:asks-version t) (:current refused)) (= (map :id open) (map :id (:asks refused))))
               (bad :stale-not-refused refused))
             (and t (some #(not (some #{%} (map :id open))) (concat (keys (:picks args)) (:dismiss args))))
             (when (not= :no-such-ask (:error refused)) (bad :closed-ask-answered refused)))
       :approve!
       (let [a (some #(when (= (:ask args) (:id %)) %) open)]
         (when (and t (not (and a (= :review (:kind a)) (= (:revision args) (:revision a))
                               (= (:evidence-sha args) (:evidence-sha a) (:current-sha args)))))
           (when (not= :stale (:error refused)) (bad :approval-not-bound refused))))
       :ask!
       (if refused
         (when (and (= :review (:kind args)) (str/blank? (:revision args)) (not= :invalid (:error refused)))
           (bad :review-without-revision refused))
         (let [a (get-in after [:asks result])
               w (get-in after [:workers (:worker a)])]
           (concat (when (not= [(boolean (:stopped args)) :open] [(:stopped a) (:state a)]) (bad :ask-not-recorded a))
                   (when (and (:stopped args) (not (model/waiting? after w))) (bad :stopped-ask-does-not-wait (:state w)))
                   (when-not (every? #(= :superseded (get-in after [:asks % :state])) (:supersedes args)) (bad :not-superseded)))))
       :dismiss!
       (when-not refused
         (concat (when (seq (open-asks after (:thread args))) (bad :dismiss-left-open))
                 (when (and (empty? open) (seq (new-rows before after))) (bad :dismiss-of-nothing-sent))
                 (when (and (seq open) (:worker t)
                            (not-any? #(str/includes? (:text %) (str "Not answered, do not wait: ")) (new-rows before after)))
                   (bad :dismiss-not-told))))
       :archive!
       (when-not refused
         (when-not (get-in after [:threads (:thread args) :resolved-at]) (bad :not-archived)))
       :read!
       (when-not refused
         (let [[id] (:threads args)
               t (get-in before [:threads id])
               t' (get-in after [:threads id])]
           (concat (when (= (:read args) (model/unread? t')) (bad :read-not-recorded t'))
                   (when (not= (dissoc t :read-seq) (dissoc t' :read-seq)) (bad :read-moved-the-thread)))))
       :draft!
       (when (not= (get-in before [:threads (:thread args) :updated-at]) (get-in after [:threads (:thread args) :updated-at]))
         (bad :draft-moved-the-thread))
       ;; Steering a Worker whose session ended resumes it first, so it is delivered too.
       :steer!
       (let [w (get-in before [:workers (get-in before [:threads (:thread args) :worker])])
             deliverable? (and (= :working (:state w)) (:conversation w) (not (model/session-ended? w)))]
         (cond refused (when (and deliverable? (not (vector? principal)))
                         (when (not= (if (str/blank? (:text args)) :invalid ::accepted) (:error refused))
                           (bad :steer-refused refused)))
               (not (some #(= (str "[Steering] " (:text args)) (:text %)) (new-rows before after)))
               (bad :steer-not-delivered)))
       ;; There is no lease: the owner and the Router cancel any Worker that runs
       ;; or waits to, on an archived thread too.
       :cancel!
       (let [w (get-in before [:workers (get-in before [:threads (:thread args) :worker])])]
         (when (and refused (#{:queued :starting :working} (:state w)) (not (str/blank? (:reason args))))
           (bad :cancel-refused refused)))
       :say!
       (let [[handle message] (model/parse-handle (:text args))
             w (get-in before [:workers (get-in before [:threads handle :worker])])
             router (get-in before [:router :router :conversation])]
         (cond (and handle (not (get-in before [:threads handle]))) (when (not= :no-such-thread (:error refused)) (bad :unknown-handle))
               (and w (= :working (:state w)) (:conversation w))
               (when-not (and (= {:thread handle :to (:id w)} result)
                              (some #(and (= (:conversation w) (:conversation %))
                                          (= (format "[%s from %s%s] %s" handle (name (:origin args)) (if (= :voice (:origin args)) ", dictated" "") message) (:text %)))
                                    (new-rows before after)))
                 (bad :handle-not-routed result))
               refused (when-not (contains? (cond-> #{} (nil? router) (conj :no-router) (get-in before [:threads handle :worker]) (conj :not-running))
                                             (:error refused))
                         (bad :say-refused refused))
               :else (when-not (and (= :router (:to result)) (some #(= router (:conversation %)) (new-rows before after)))
                       (bad :not-for-the-router result))))
       nil))))

;; ---- the property -------------------------------------------------------------

(def ^:private preamble
  "Random numbers that open with three threads, a Router and two running
  Workers, so short sequences reach asks and replies. Each is [kind a b c] with
  kind an index into kinds."
  (let [k #(.indexOf kinds %)]
    [[(k :thread!) 0 1 0] [(k :thread!) 0 1 0] [(k :thread!) 0 1 0] [(k :register-router!) 0 0 0]
     [(k :start-worker!) 1 1 1] [(k :worker-running!) 1 0 0] [(k :start-worker!) 2 1 1] [(k :worker-running!) 1 0 0]]))

(defn- history-problems
  "What breaks 'the state is a function of the event log' for the store at
  PATH, whose controller held STATE: the log folded from its newest snapshot,
  folded twice from its first event, and folded by a store opened anew, as
  after a restart, must each give STATE."
  [db path state]
  (let [whole #(reduce model/fold model/empty-state (store/events db))
        reopened (let [db2 (store/open path)] (try (store/replay db2) (finally (store/close! db2))))]
    (seq (concat (when (not= state (store/replay db)) [[:replay-differs]])
                 (when (not= state (whole)) [[:whole-log-differs-from-snapshot-and-tail]])
                 (when (not= (whole) (whole)) [[:fold-is-not-deterministic]])
                 (when (not= state reopened) [[:restart-differs]])))))

(defn- run-steps
  "Commit the commands random CHOICES make, one at a time, on a store with a
  Worker cap of CAP, taking a snapshot before step SNAP-AT. A choice can repeat
  an earlier accepted command with its id, which must return the first result
  and change nothing. Returns the first problem found, or nil."
  [cap snap-at choices]
  (let [dir (fs/create-temp-dir {:prefix "bbh-super-property"})
        path (str (fs/path dir "super.db"))
        db (store/open path)]
    (try
      (loop [state (store/replay db) [ch & more] (concat preamble choices) i 0 accepted [] launched #{}]
        (when (= i snap-at) (store/snapshot! db state))
        (if (nil? ch)
          (when-let [problems (history-problems db path state)] {:problems (vec problems)})
          (let [[step first-result] (if (= :repeat (pick kinds (first ch)))
                                      (pick accepted (second ch))
                                      [(command state cap i ch)])]
            (if (nil? step)
              (recur state more (inc i) accepted launched)
              (let [[principal cmd] step
                    events (count (store/events db))
                    out (try (store/commit! db state principal cmd)
                             (catch clojure.lang.ExceptionInfo e {:refused (refusal e) :state state}))
                    after (:state out)
                    launched (into launched (keep #(when (#{:starting :working} (:state %)) (:id %))) (vals (:workers after)))
                    problems (concat (if (:repeat? out)
                                       (concat (when (not= first-result (:result out)) [[:repeat-gave-another-result]])
                                               (when (not= state after) [[:repeat-changed-state]])
                                               (when (not= events (count (store/events db))) [[:repeat-added-events]]))
                                       (postconditions step state after out cap))
                                     (violations after cap launched)
                                     (regressions state after))]
                (if (seq problems)
                  {:step i :command step :out (dissoc out :state) :problems (vec problems)}
                  (recur after more (inc i)
                         (cond-> accepted (not (or (:refused out) (:repeat? out))) (conj [step (:result out)]))
                         launched)))))))
      (finally (store/close! db) (fs/delete-tree dir)))))

(def ^:private choice (gen/vector (gen/choose 0 1000) 4))

(deftest property-random-command-sequences-keep-the-controllers-rules
  (let [result (tc/quick-check 150 (prop/for-all [cap (gen/choose 1 2)
                                                  snap-at (gen/choose 0 70)
                                                  choices (gen/vector choice 1 60)]
                                     (nil? (run-steps cap snap-at choices))))]
    (is (:pass? result) (pr-str (when-let [[cap snap-at choices] (get-in result [:shrunk :smallest])] (run-steps cap snap-at choices))))))
