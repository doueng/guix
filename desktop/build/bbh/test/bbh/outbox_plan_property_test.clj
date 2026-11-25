(ns bbh.outbox-plan-property-test
  "model/outbox-plan over generated outboxes, Workers and mailboxes. Passes
  are simulated as super/dispatch! performs them, and a pass can crash after
  any of its effects, leaving the rest to the next pass. Sessions take
  mailbox files between passes, as session/take-mail! does: a message is
  recorded in the transcript, then its file deleted, and a file whose message
  the transcript already holds is deleted unread. The rules: a row of a finished Worker is
  never sent, the Router's batch goes as one, each send is recorded under
  the message it wrote, a row's message id never changes, a pass repeated
  changes nothing, each row's message is delivered at most once, and every
  row that someone will read ends done, after it was read."
  (:require [bbh.gens.outbox-plan :as gens]
            [bbh.props :as props]
            [bbh.super.model :as model]
            [clojure.test :refer [deftest]]
            [clojure.test.check.properties :as prop]))

(def ^:private conversations ["router" "c1" "c2" "c3"])

(defn- world-of
  "The controller's state, the mailbox files as [conversation message], and
  each conversation's transcript: the messages its session recorded."
  [{:keys [rows workers]}]
  (let [outbox (into {} (map-indexed (fn [i r] (let [id (str "o" (inc i))]
                                                 [id (cond-> (-> r (dissoc :file?) (assoc :id id :text (str "t" i)))
                                                       (= :sent (:state r)) (assoc :message (model/message-id id)))]))
                                     rows))]
    {:state {:router {:router {:conversation "router"}}
             :workers (into {} (map-indexed (fn [i [conv st]] [(str "W" (inc i)) {:id (str "W" (inc i)) :conversation conv :state st}])
                                            (sort workers)))
             :outbox outbox}
     :files (set (for [[i r] (map-indexed vector rows) :when (and (= :sent (:state r)) (:file? r))]
                   [(:conversation r) (model/message-id (str "o" (inc i)))]))
     :seen (zipmap conversations (repeat #{}))
     :delivered {}
     :ids {}}))

(defn- perform
  "WORLD after the effects of PLAN, as super/dispatch! performs them."
  [world plan]
  (reduce (fn [w [kind x y]]
            (case kind
              :mailbox/send (-> w (update :files conj [x (:message-id y)]))
              :mailbox/delete (update w :files disj [x y])
              :command (update w :state #(reduce model/fold % (:events (model/decide % :controller x))))))
          world plan))

(defn- scan
  "WORLD after the session of conversation CONV takes its mail, as
  session/take-mail! and record! do: a message is delivered and recorded,
  then its file deleted; a file whose message the transcript holds, as when
  a pass crashed between the send and its record and sent it again, is
  deleted unread."
  [world conv]
  (reduce (fn [w [c m :as f]]
            (cond (not= c conv) w
                  ((get-in w [:seen c]) m) (update w :files disj f)
                  :else (-> w (update-in [:seen c] conj m) (update-in [:delivered m] (fnil inc 0)) (update :files disj f))))
          world (sort (:files world))))

(defn- waiting [{:keys [state files]}]
  (set (keep (fn [o] (when (and (= :sent (:state o)) (files [(:conversation o) (:message o)])) (:id o))) (vals (:outbox state)))))

(defn- plan-of [world] (model/outbox-plan (:state world) (waiting world)))

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

(defn- note-ids
  "WORLD with the message id each send of PLAN wrote, by row."
  [world plan]
  (reduce (fn [w [kind _ y :as e]]
            (if (= :mailbox/send kind)
              (let [[_ c] (second (drop-while #(not= e %) plan))
                    row (get-in c [:args :outbox])]
                (update-in w [:ids row] (fnil conj #{}) (:message-id y)))
              w))
          world plan))

(defn- world-problems [world]
  (cond-> []
    (some #(< 1 (count %)) (vals (:ids world))) (conj [:message-id-changed (:ids world)])
    (some #(< 1 %) (vals (:delivered world))) (conj [:delivered-twice (:delivered world)])))

(defn- run-case
  "What case C breaks of the rules, round by round. NOTE!, when given, sees
  each round as [world-before plan crash world-after]."
  [{:keys [rounds] :as c} & [note!]]
  (loop [[{:keys [crash scans]} & more :as rs] rounds world (world-of c)]
    (if (seq rs)
      (let [before world
            plan (plan-of world)
            problems (plan-problems (:state world) plan)
            world (perform (note-ids world plan) (if crash (take crash plan) plan))
            again (when-not crash (plan-of world))
            problems (cond-> (into problems (world-problems world))
                       (some #(#{:mailbox/send :mailbox/delete} (first %)) again) (conj [:repeat-pass-acts again]))]
        (when note! (note! before plan crash world))
        (if (seq problems)
          problems
          (recur more (reduce scan world (keep-indexed (fn [i conv] (when (nth scans i) conv)) conversations)))))
      ;; The end: no more crashes; the sessions take everything between passes.
      (let [settle (fn [w] (reduce scan (perform w (plan-of w)) conversations))
            w (nth (iterate settle world) 3)
            left (remove #(or (#{:done :dropped} (:state %))
                              (and (= "router" (:conversation %)) (= :summary (:delivery %))))
                         (vals (:outbox (:state w))))
            ;; A row this run sent and saw done was read: none is lost.
            lost (for [[id {st :state}] (get-in (world-of c) [:state :outbox])
                       :let [o (get-in w [:state :outbox id])]
                       :when (and (= :pending st) (= :done (:state o)) (not= 1 (get-in w [:delivered (:message o)])))]
                   id)]
        (cond-> (world-problems w)
          (seq left) (conj [:rows-never-done left (:files w)])
          (seq lost) (conj [:done-but-never-read lost]))))))

(defn check
  "The rules case C breaks, [] when it keeps them. desktop/build/pbt runs this
  property with agent-written generators."
  [c]
  (vec (run-case c)))

(defn- abstract
  "WORLD as the row states each kind of conversation holds (the Router's, a
  live Worker's, a finished Worker's), and whether a mailbox file waits."
  [{:keys [state files]}]
  (let [finished (set (keep #(when (#{:done :failed :cancelled} (:state %)) (:conversation %)) (vals (:workers state))))
        who #(cond (= "router" %) :router (finished %) :finished :else :live)]
    [(into (sorted-map) (update-vals (group-by (comp who :conversation) (vals (:outbox state)))
                                     #(vec (sort (distinct (map :state %))))))
     (boolean (seq files))]))

(defn observe
  "Case C as the harness sees it: each round's abstract world, the kinds of
  effect its pass planned, whether it crashed, and labels for the paths that
  matter (a crash between a send and its record, a resend, a cancel row)."
  [c]
  (let [rounds (atom [])]
    (run-case c (fn [before plan crash after] (swap! rounds conj [before plan crash after])))
    (let [kinds (fn [plan] (vec (sort (distinct (map (fn [[k x]] (if (= :command k) (:command x) k)) plan)))))]
      {:steps (into (if-let [[w] (first @rounds)] [{:state (abstract w) :action :start}] [])
                    (for [[_ plan crash after] @rounds]
                      {:state (abstract after) :action [(kinds plan) (some? crash)]}))
       :labels (set (concat
                     (for [[_ plan crash] @rounds
                           :when (and crash (< 0 crash (count plan)) (= :mailbox/send (first (nth plan (dec crash)))))]
                       :crash-after-send)
                     (for [[before plan] @rounds :when (some #(= :mailbox/delete (first %)) plan)
                           :when (seq (:files before))]
                       :stale-file-deleted)
                     (for [[_ plan] @rounds [k _ y] plan :when (and (= :mailbox/send k) (= :cancel (:kind y)))]
                       :cancel-sent)
                     (for [[_ plan] @rounds :let [n (count (filter #(and (= :mailbox/send (first %)) (= "router" (second %))) plan))]
                           :when (> n 1)]
                       :router-batch)))})))

(deftest property-the-outbox-plan-sends-each-row-once-and-never-to-the-finished
  (props/check "outbox-plan" 300
               (prop/for-all [c gens/gen-case] (empty? (run-case c)))
               :explain run-case))
