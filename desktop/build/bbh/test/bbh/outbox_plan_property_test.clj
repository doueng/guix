(ns bbh.outbox-plan-property-test
  "model/outbox-plan over generated outboxes, Workers and mailboxes. Passes
  are simulated as super/dispatch! performs them, with sessions taking
  mailbox files between passes. The rules: a row of a finished Worker is
  never sent, the Router's batch goes as one, each send is recorded under
  the message it wrote, a pass repeated changes nothing, and every row that
  someone will read ends done."
  (:require [bbh.props :as props]
            [bbh.super.model :as model]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private gen-row
  (gen/hash-map :state (gen/elements [:pending :pending :sent :done :dropped])
                :conversation (gen/elements ["router" "c1" "c2" "c3"])
                :delivery (gen/elements [nil nil :summary :steer :cancel])))

(def ^:private gen-case
  (gen/hash-map
   :rows (gen/vector gen-row 0 8)
   :workers (gen/hash-map "c1" (gen/elements [:working :done :failed])
                          "c2" (gen/elements [:working :cancelled])
                          "c3" (gen/elements [:working :working :done]))
   :takes (gen/vector (gen/vector gen/boolean 8) 1 4)))

(defn- state-of [{:keys [rows workers]}]
  {:router {:router {:conversation "router"}}
   :workers (into {} (map-indexed (fn [i [conv st]] [(str "W" (inc i)) {:id (str "W" (inc i)) :conversation conv :state st}]) (sort workers)))
   :outbox (into {} (map-indexed (fn [i r] (let [id (str "o" (inc i))]
                                             [id (cond-> (assoc r :id id :text (str "t" i))
                                                   (= :sent (:state r)) (assoc :message (model/message-id id)))]))
                                 rows))})

(defn- perform
  "[state files] after PLAN: files is the set of [conversation message] in mailboxes."
  [[state files] plan]
  (reduce (fn [[s fs] [kind x y]]
            (case kind
              :mailbox/send [s (conj fs [x (:message-id y)])]
              :mailbox/delete [s (disj fs [x y])]
              :command [(reduce model/fold s (:events (model/decide s :controller x))) fs]))
          [state files] plan))

(defn- waiting [state files]
  (set (keep (fn [o] (when (and (= :sent (:state o)) (files [(:conversation o) (:message o)])) (:id o))) (vals (:outbox state)))))

(defn- plan-problems [state plan]
  (let [sends (filter #(= :mailbox/send (first %)) plan)
        router-pending (filter #(and (= :pending (:state %)) (= "router" (:conversation %))) (vals (:outbox state)))
        sent-router (filter #(= "router" (second %)) sends)]
    (cond-> []
      (some (fn [[_ conv]] (let [ws (filter #(= conv (:conversation %)) (vals (:workers state)))]
                             (and (seq ws) (every? #(#{:done :failed :cancelled} (:state %)) ws))))
            sends)
      (conj [:sent-to-a-finished-worker sends])
      (not (or (empty? sent-router) (= (count sent-router) (count router-pending))))
      (conj [:router-batch-split sent-router router-pending])
      (and (seq router-pending) (empty? sent-router) (some #(not= :summary (:delivery %)) router-pending))
      (conj [:router-batch-held router-pending])
      (some (fn [[[k1 _ y] [k2 cmd]]] (and (= :mailbox/send k1)
                                          (not (and (= :command k2) (= :outbox-sent! (:command cmd)) (= (:message-id y) (get-in cmd [:args :message]))))))
            (partition 2 1 (concat plan [nil])))
      (conj [:send-not-recorded plan]))))

(defn- run-case [{:keys [takes] :as c}]
  (loop [[take & more] takes world [(state-of c) #{}]]
    (let [[state files] world
          plan (model/outbox-plan state (waiting state files))
          problems (plan-problems state plan)
          world (perform world plan)
          again (model/outbox-plan (first world) (waiting (first world) (second world)))
          problems (cond-> problems
                     (some #(#{:mailbox/send :mailbox/delete} (first %)) again) (conj [:repeat-pass-acts again]))]
      (cond
        (seq problems) problems
        take (let [[s fs] world
                   taken (set (keep-indexed (fn [i f] (when (nth take i false) f)) (sort fs)))]
               (recur more [s (apply disj fs taken)]))
        :else
        ;; the end: sessions take everything, two more passes
        (let [[s] (perform (perform [(first world) #{}] (model/outbox-plan (first world) #{}))
                           (model/outbox-plan (first (perform [(first world) #{}] (model/outbox-plan (first world) #{}))) #{}))
              left (remove #(or (#{:done :dropped} (:state %))
                                (and (= "router" (:conversation %)) (= :summary (:delivery %))))
                           (vals (:outbox s)))]
          (when (seq left) [[:rows-never-done left]]))))))

(deftest property-the-outbox-plan-sends-each-row-once-and-never-to-the-finished
  (props/check "outbox-plan" 300
               (prop/for-all [c gen-case] (empty? (run-case c)))
               :explain run-case))
