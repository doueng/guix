(ns rexg.connect-mbt-test
  "Model-based tests of the control connection against rexmux.qnt's connect
  module (desktop/build/rexmux/verify/quint). A driver performs each Quint
  action on the real rexg.model, with a simulated server and rexg.main's
  outs performed as main performs them, and ABSTRACT maps what results to
  Quint's ctrl record. Two uses: replay-trace follows a `quint run --mbt`
  trace and compares the two states after every step; check runs generated
  action sequences in the same vocabulary and checks the spec's invariants
  (neverStranded, upIsLive, noLeak) after each, and serverUpAttaches at the
  end."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [rexg.gens.connect-actions :as gens]
            [rexg.model :as m]))

;; The driver

(def max-gen "rexmux.qnt's connect instance: attempt ids 1..2." 2)
(def no-conn (inc max-gen))

(defn world
  "The client before anything happened: Quint's init with UP (the server
  answers) and LOCAL (the client may start it)."
  [up local]
  {:mod (m/new-model [{:label "local" :address (when-not local "far.example:7000")}] {:env {}})
   :up up :local local
   :next [:event]        ; what the client performs next: [:event] (a [:connect 0] it will handle), [:timer], or outs
   :outs []              ; outs rexg.main has not performed yet: [:start-local-server 0] then [:connect 0 g]
   :inflight #{} :open #{} :answers #{} :live nil :lates 0 :losses 0
   :ids {}               ; real attempt -> Quint id
   :waits []})

(defn- close-socks
  "W with sockets SOCKS closed; each open one reports its close."
  [w socks]
  (let [closing (set/intersection (set socks) (:open w))]
    (-> w (update :open set/difference closing) (update :answers into (map #(vector :Closed %) closing)))))

(defn- perform
  "W after rexg.main performs OUTS. A start of the local server and the
  attempt that follows it wait for startLocal and tryConnect, as Quint
  splits them; the rest happen at once."
  [w outs]
  (reduce (fn [w [kind a x]]
            (case kind
              :start-local-server (update w :outs conj [:start-local-server 0])
              :connect (if (seq (:outs w)) (update w :outs conj [:connect 0 x]) (update w :inflight conj x))
              :conn/adopt (assoc w :live x)
              :conn/close (close-socks w [x])
              :conn/drop (-> w (close-socks [x]) (assoc :live nil))
              :after (-> w (assoc :next [:timer]) (update :waits conj a))
              w))
          w outs))

(defn- call [w f & args]
  (let [[mod outs] (apply f (:mod w) 0 args)]
    (perform (assoc w :mod mod) outs)))

(defn- server [w] (get-in w [:mod :servers 0]))

(defn try-of
  "Quint's try: the out the client performs next."
  [w]
  (cond (= :start-local-server (ffirst (:outs w))) :ToStart
        (seq (:outs w)) :ToConnect
        (= [:event] (:next w)) :ToConnect
        (= [:timer] (:next w)) :ToAfter
        :else :Nothing))

(defn conn-of
  "Quint's conn: down while an attempt waits to be performed."
  [w]
  (if (seq (:outs w)) :Down ({:down :Down :connecting :Connecting :up :Up nil :Down} (:conn (server w)) :Down)))

(defn enabled?
  "Whether Quint's action [NAME ARG] can happen in W (ARG an attempt for
  answer and the deliveries, a flag for startLocal and hangup)."
  [w [name arg]]
  (case name
    :tryConnect (and (= :ToConnect (try-of w)) (= :Down (conn-of w)))
    :answer (contains? (:inflight w) arg)
    :late (and (:up w) (< (:lates w) 1) (not (contains? (:open w) 0)))
    :connected (contains? (:answers w) [:Connected arg])
    :connectFailed (contains? (:answers w) [:Failed arg])
    :closed (contains? (:answers w) [:Closed arg])
    :startLocal (= :ToStart (try-of w))
    :timer (= :ToAfter (try-of w))
    :hangup (and (contains? (:open w) (:live w)) (< (:losses w) 3))
    :serverStops (and (:up w) (< (:losses w) 3))
    :serverStarts (not (:up w))))

(defn step
  "W after Quint action [NAME ARG], which must be enabled. ARG names a real
  attempt; 0 stands for an earlier one, as in the spec."
  [w [name arg]]
  (case name
    :tryConnect (if (seq (:outs w))
                  (let [[_ _ g] (first (:outs w))] (-> w (assoc :outs []) (update :inflight conj g) (assoc :next nil)))
                  (call (assoc w :next nil) m/connect))
    :answer (-> w (update :inflight disj arg)
                (cond-> (:up w) (-> (update :open conj arg) (update :answers conj [:Connected arg]))
                        (not (:up w)) (update :answers conj [:Failed arg])))
    :late (-> w (update :open conj 0) (update :answers conj [:Connected 0]) (update :lates inc))
    :connected (call (update w :answers disj [:Connected arg]) m/connected arg (str "client:" arg))
    :connectFailed (call (update w :answers disj [:Failed arg]) m/connect-failed arg "no server")
    :closed (call (update w :answers disj [:Closed arg]) m/closed arg "closed")
    :startLocal (-> w (update :outs (comp vec rest)) (update :up #(or % arg)))
    :timer (assoc w :next [:event])
    :hangup (-> (close-socks w (if arg (:open w) [(:live w)])) (assoc :up (not arg)) (update :losses inc))
    :serverStops (-> (close-socks w (:open w)) (assoc :up false) (update :losses inc))
    :serverStarts (assoc w :up true)))

(defn abstract
  "W as rexmux.qnt's ctrl record, attempts renamed through IDS (real ->
  Quint; identity when nil, with :none for no connection)."
  [w & [ids]]
  (let [q (fn [g] (cond (nil? g) (if ids no-conn :none) (zero? g) 0 ids (get ids g [:unmapped g]) :else g))
        conn (conn-of w)]
    {:srvUp (:up w) :local (:local w) :conn conn :try (try-of w)
     :started (boolean (:started (server w))) :losses (:losses w) :lates (:lates w)
     :gen (if-let [g (:gen (server w))] (q g) :no-attempt)
     :live (q (:live w)) :inflight (set (map q (:inflight w))) :open (set (map q (:open w)))
     :answers (set (map (fn [[k g]] [k (q g)]) (:answers w)))}))

;; The spec's rules, on the abstraction

(defn- named [c g] (some #(= g (second %)) (:answers c)))

(defn rule-problems
  "Which of rexmux.qnt's invariants ctrl record C breaks."
  [c]
  (cond-> []
    (not (and (or (not= :Down (:conn c)) (not= :Nothing (:try c)))
              (or (not= :Connecting (:conn c)) (contains? (:inflight c) (:gen c)) (named c (:gen c)))))
    (conj [:neverStranded c])
    (not (and (= (= :Up (:conn c)) (= (:live c) (:gen c)))
              (or (not= :Up (:conn c)) (contains? (:open c) (:live c)) (contains? (:answers c) [:Closed (:live c)]))))
    (conj [:upIsLive c])
    (not (every? #(or (= % (:live c)) (contains? (:answers c) [:Connected %])) (:open c)))
    (conj [:noLeak c])))

(defn- settle
  "W after the server starts and stays up and the client runs every step
  the spec's fairness promises, until nothing is left to do."
  [w]
  (loop [w (cond-> w (not (:up w)) (step [:serverStarts])) n 0]
    (let [moves (concat [[:tryConnect] [:startLocal true] [:timer]]
                        (map #(vector :answer %) (sort (:inflight w)))
                        (map (fn [[k g]] [({:Connected :connected :Failed :connectFailed :Closed :closed} k) g]) (sort (:answers w))))
          mv (first (filter #(enabled? w %) moves))]
      (if (or (nil? mv) (> n 200)) w (recur (step w mv) (inc n))))))

(defn- resolve-action
  "Generated action [NAME I] made concrete in W: I picks among the attempts
  in flight or the answers queued."
  [w [name i]]
  (let [pick #(when (seq %) (nth (vec (sort %)) (mod (or i 0) (count %))))]
    (case name
      :answer [:answer (pick (:inflight w))]
      :deliver (when-let [[k g] (pick (:answers w))] [({:Connected :connected :Failed :connectFailed :Closed :closed} k) g])
      [name i])))

(defn run-actions
  "[world-after-each action-taken] for generated input {:up :local :actions},
  skipping actions the state does not enable."
  [{:keys [up local actions]}]
  (reductions (fn [[w] a]
                (let [a (resolve-action w a)]
                  (if (and a (enabled? w a)) [(step w a) a] [w nil])))
              [(world up local) :init]
              actions))

(defn check
  "What a generated action sequence breaks: the spec's invariants after
  every step, then, with the server up for good, the client must attach on
  exactly one socket (serverUpAttaches, noLeak)."
  [input]
  (let [ws (map first (run-actions input))
        end (settle (last ws))
        c (abstract end)]
    (vec (concat (some #(not-empty (rule-problems (abstract %))) ws)
                 (when (not= :Up (:conn c)) [[:not-attached c]])
                 (when (not= #{(:live c)} (:open c)) [[:leaked c]])))))

(defn observe
  "The abstract ctrl after each action taken, and labels for paths the spec
  calls out: a late answer, a close before connected, a retry after a wait,
  starting the local server, the id of an attempt reused."
  [input]
  (let [steps (remove (comp nil? second) (run-actions input))
        shape (fn [w] (let [c (abstract w)]
                        [(:conn c) (:try c) (:srvUp c) (count (:open c)) (count (:inflight c)) (count (:answers c))]))]
    {:steps (map (fn [[w a]] {:state (shape w) :action (if (keyword? a) a (first a))}) steps)
     :labels (set (keep (fn [[[w0] [w1 a]]]
                          (when (vector? a)
                            (case (first a)
                              :connected (cond (zero? (second a)) :late-delivered
                                               (not= :Up (conn-of w1)) :stale-connected-closed)
                              :closed (when (= :Connecting (conn-of w0)) :closed-before-connected)
                              :startLocal (if (second a) :local-start-works :local-start-fails)
                              :timer :retry-after-wait
                              :hangup (when (second a) :hangup-kills-server)
                              nil)))
                        (partition 2 1 steps)))}))

;; Replaying Quint traces

(defn- quint-action
  "The driver action for trace state ST, whose action Quint took from PREV.
  Quint ids are mapped to real attempts through IDS."
  [ids prev st]
  (let [name (keyword (:action st))
        g (get-in st [:picks :g])
        real (fn [q] (if (zero? q) 0 (get (set/map-invert ids) q)))]
    (case name
      (:answer :connected :connectFailed :closed) [name (real g)]
      :hangup [name (get-in st [:picks :dies])]
      ;; startLocal(ok) has no pick: ok shows in whether the server came up.
      :startLocal [name (and (not (get-in prev [:ctrl :srvUp])) (get-in st [:ctrl :srvUp]))]
      [name])))

(defn replay-trace
  "Follow STATES, a quint run --mbt trace of the connect module as
  [{:action :picks :ctrl}], on the real code. Returns nil when every state
  agrees, else where they first differ."
  [states]
  (let [{:keys [ctrl]} (first states)]
    (loop [w (world (:srvUp ctrl) (:local ctrl)) ids {} [st & more] (rest states) prev (first states) i 1]
      (let [want (fn [c] (cond-> (select-keys c [:srvUp :local :conn :try :started :losses :lates :live :inflight :open :answers])
                           (not= :Down (:conn c)) (assoc :gen (:gen c))))
            got (fn [w ids] (let [a (abstract w ids)] (cond-> (dissoc a :gen) (not= :Down (:conn a)) (assoc :gen (:gen a)))))]
        (if-not st
          nil
          (let [a (quint-action ids prev st)]
            (if-not (enabled? w a)
              {:step i :action a :problem :not-enabled :quint (want (:ctrl st)) :real (got w ids)}
              (let [w2 (step w a)
                    ids (if (= :tryConnect (first a)) (assoc ids (:gen (server w2)) (get-in st [:ctrl :gen])) ids)]
                (if (not= (want (:ctrl st)) (got w2 ids))
                  {:step i :action a :problem :states-differ :quint (want (:ctrl st)) :real (got w2 ids)}
                  (recur w2 ids more st (inc i)))))))))))

(deftest property-generated-connect-actions-keep-the-spec
  (let [r (tc/quick-check 300 (prop/for-all [input gens/gen-input] (empty? (check input))))]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))
