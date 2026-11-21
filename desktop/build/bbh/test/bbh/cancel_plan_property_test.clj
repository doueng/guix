(ns bbh.cancel-plan-property-test
  "model/cancel-plan over generated observations. A cancelling Worker's jobs,
  its session and the person's answers are drawn at random, and reconcile!'s
  passes are simulated: each pass plans, then performs the plan the way
  super/perform-plan! does, with stops that may fail. The rules: a cancel never
  completes while a job nobody can identify holds it, every plan's commands are
  accepted, a pass that does nothing names why, and once the session took the
  cancel, every stop works and the person abandoned the blocking jobs, the
  Worker ends cancelled. Super.tla's CancelEnds is the same rule."
  (:require [bbh.props :as props]
            [bbh.super.model :as model]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- commit
  "STATE after PRINCIPAL's command CMD, or throws its refusal."
  [state principal cmd]
  (reduce model/fold state (:events (model/decide state principal (merge {:id "c"} cmd)))))

(defn- cancelling-state
  "A state with thread T1 whose Worker W1 runs in conversation conv-1 and was
  then cancelled, with its cancel row in STATE-OF-ROW."
  [row]
  (let [s (-> model/empty-state
              (commit :owner {:command :thread! :args {:title "t"}})
              (commit :owner {:command :start-worker! :token-hash "h" :policy {:workers 2}
                              :args {:thread "T1" :workflow :change :brief "b" :repo "/r"}})
              (commit :controller {:command :worker-running!
                                   :args {:worker "W1" :conversation "conv-1" :wf-run "run" :workspace "/w" :buffer "b" :started-at 0}})
              (commit :owner {:command :cancel! :args {:thread "T1" :reason "stop"}}))
        o (some #(when (= :cancel (:delivery %)) (:id %)) (vals (:outbox s)))]
    (assoc-in s [:outbox o :state] row)))

(def ^:private job-states [:running :exited :unknown :lost])

(def ^:private gen-case
  (gen/hash-map
   :row (gen/elements [:pending :sent :done])
   :agent? gen/boolean
   :jobs (gen/vector (gen/elements job-states) 0 4)
   ;; per pass: does each stop work, does the session take the cancel, does the person abandon
   :passes (gen/vector (gen/hash-map :stops (gen/vector gen/boolean 4)
                                     :take? gen/boolean
                                     :abandon (gen/elements [nil 0 1 2 3]))
                       1 8)))

(defn- blocking [w jobs]
  (set (keep #(when (and (#{:unknown :lost} (:state %)) (not (some #{(:id %)} (:abandoned-jobs w)))) (:id %)) jobs)))

(defn- pass
  "One reconcile! pass on STATE and JOBS for W1 with world choices P. Returns
  [state jobs problems]."
  [state jobs agent {:keys [stops]}]
  (let [w (get-in state [:workers "W1"])
        plan (model/cancel-plan state w {:agent agent :jobs jobs})
        held (blocking w jobs)
        delivered? (some #(= :cancel-delivered! (:command (second %))) plan)
        problems (cond-> []
                   (and delivered? (seq held)) (conj [:delivered-while-blocked held plan])
                   (and (empty? plan) (not (or (and agent (model/cancel-owed? state w))
                                               (and (seq held) (= (set (:cancel-blocked w)) held)))))
                   (conj [:idle-without-a-reason plan])
                   (and (seq held) (not (some #(= :job/stop (first %)) plan))
                        (some #(= :running (:state %)) jobs))
                   (conj [:running-job-left-alone plan]))]
    (loop [[[kind x :as e] & more] plan state state jobs jobs problems problems i 0]
      (cond
        (nil? e) [state jobs problems]
        (= :command kind)
        (let [[state problems] (try [(commit state :controller x) problems]
                                    (catch clojure.lang.ExceptionInfo ex [state (conj problems [:refused x (ex-data ex)])]))]
          (recur more state jobs problems i))
        (= :job/stop kind)
        (if (nth stops (mod i (count stops)) true)
          (recur more state (mapv #(if (= (:id %) (:id x)) (assoc % :state :exited) %) jobs) problems (inc i))
          [state jobs problems])
        :else (recur more state jobs problems i)))))

(defn- run-case
  "Problems of one generated case, or nil."
  [{:keys [row agent? jobs passes]}]
  (let [jobs (vec (map-indexed (fn [i s] {:id (str "j" i) :state s :base (str "/tmp/j" i)}) jobs))
        ;; the last passes are a calm world: stops work, the session took the cancel, the person abandons everything
        calm (repeat (+ 2 (count jobs)) {:stops [true] :take? true :abandon :all})]
    (loop [[p & more] (concat passes calm) state (cancelling-state row) jobs jobs agent agent?]
      (let [w (get-in state [:workers "W1"])]
        (cond
          (not= :cancelling (:state w))
          (when-not (= :cancelled (:state w)) [[:ended-as (:state w)]])

          (nil? p) [[:never-cancelled (select-keys w [:cancel-blocked :abandoned-jobs]) jobs
                     (model/cancel-plan state w {:agent agent :jobs jobs})]]

          :else
          (let [[state jobs problems] (pass state jobs agent p)
                w (get-in state [:workers "W1"])
                problems (cond-> problems
                           (and (seq (:cancel-blocked w)) (= :cancelling (:state w)) (not (model/cancel-blocked w)))
                           (conj [:blocked-not-shown w]))
                ;; the world moves: the session may take the cancel, the person may abandon a blocked job
                state (if (:take? p)
                        (update state :outbox update-vals #(cond-> % (= :cancel (:delivery %)) (assoc :state :done)))
                        state)
                targets (if (= :all (:abandon p)) (:cancel-blocked w) (some-> (:abandon p) (#(nth (:cancel-blocked w) % nil)) vector))
                state (reduce (fn [s job] (if (= :cancelling (get-in s [:workers "W1" :state]))
                                            (commit s :owner {:command :abandon-job! :args {:thread "T1" :job job}})
                                            s))
                              state targets)]
            (if (seq problems)
              problems
              (recur more state jobs (and agent (not (:take? p)))))))))))

(deftest property-a-cancel-ends-or-names-what-holds-it
  (props/check "cancel-plan" 300
               (prop/for-all [c gen-case] (nil? (run-case c)))
               :explain run-case))

(deftest an-unknown-job-blocks-the-cancel-until-abandoned
  (let [s (cancelling-state :done)
        w (get-in s [:workers "W1"])
        jobs [{:id "j1" :state :unknown} {:id "j2" :state :running :base "/tmp/j2"}]
        plan (model/cancel-plan s w {:agent nil :jobs jobs})]
    (is (= [[:command {:command :cancel-blocked! :args {:worker "W1" :jobs ["j1"]}}] [:job/stop (second jobs)]] plan))
    (let [s (commit s :controller (second (first plan)))
          w (get-in s [:workers "W1"])]
      (is (re-find #"bbh inbox abandon T1 j1" (model/cancel-blocked w)))
      (is (= :needs-attention (:section (first (filter #(= "T1" (:id %)) (mapcat :threads (model/sections s)))))))
      (is (= [] (model/cancel-plan s w {:agent nil :jobs [(first jobs)]})) "a recorded block is not committed again")
      (is (= :invalid (try (commit s :owner {:command :abandon-job! :args {:thread "T1" :job "j9"}}) nil
                                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
          "only a blocking job can be abandoned")
      (let [s (commit s :router {:command :abandon-job! :args {:thread "T1" :job "j1"}})
            w (get-in s [:workers "W1"])]
        (is (= [[:command {:id "cancelled-W1" :command :cancel-delivered! :args {:worker "W1"}}] [:promote]]
               (model/cancel-plan s w {:agent nil :jobs [(first jobs)]})))))))
