(ns rexg.connection-test
  "The control connection as rexg.model's state machine, against a simulated
  Rex server that dies, comes back, answers attempts late and drops
  connections. Each attempt has an identity, as in rexg.main: every answer
  names its attempt, so a late answer or a close from an old connection must
  change nothing. rexmux.qnt's neverStranded, serverUpAttaches and
  noLeak say the same: a client is never left disconnected with
  nothing that will try again, once the server answers it attaches again,
  and it leaks no connection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [rexg.model :as m]))

(defn- model [kind]
  (case kind
    :local (m/new-model [{:label "local" :address nil}] {:env {}})
    :rex-server (m/new-model [{:label "local" :address nil}] {:env {"REX_SERVER" "/tmp/other.sock"}})
    :remote (m/new-model [{:label "far" :address "far.example:7000"}] {:env {}})))

(defn- handle
  "The model's answer to the client event EV, as rexg.main's handle! routes it."
  [mod [kind srv gen x]]
  (case kind
    :connect (m/connect mod srv)
    :connected (m/connected mod srv gen x)
    :connect-failed (m/connect-failed mod srv gen x)
    :closed (m/closed mod srv gen x)))

(defn- close-conn
  "The client closes connection GEN: its reader reports the close too."
  [w gen]
  (if (contains? (:open w) gen)
    (-> w (update :open disj gen) (update :queue conj [:closed 0 gen "closed locally"]))
    w))

(defn- perform
  "WORLD after the outs of one step, as rexg.main's perform! does them. A
  connect is in flight until the server answers it (:answer); a local start
  brings the server up when starts work; a timer waits."
  [world outs]
  (reduce (fn [w [kind & xs]]
            (case kind
              :connect (update w :inflight conj (second xs))
              :conn/adopt (assoc w :live (second xs))
              :conn/close (close-conn w (second xs))
              :conn/drop (-> w (close-conn (second xs)) (assoc :live nil))
              :start-local-server (cond-> (update w :starts inc) (:starts-work? w) (assoc :up? true))
              :after (let [[ms ev] xs] (-> w (update :timers conj ev) (update :waits conj ms)))
              w))
          world outs))

(defn- step
  "WORLD after MOVE: the client handles its next event, a timer fires, the
  oldest attempt in flight is answered, the server dies (closing every
  connection it has) or comes back, the network drops the live connection,
  or an answer already handled arrives again, late."
  [{:keys [mod queue timers inflight] :as w} move]
  (case move
    :process (if-let [ev (first queue)]
               (let [[mod outs] (handle mod ev)]
                 (perform (-> w (assoc :mod mod :queue (vec (rest queue))) (update :seen conj ev)) outs))
               w)
    :fire (if-let [ev (first timers)] (assoc w :timers (vec (rest timers)) :queue (conj queue ev)) w)
    :answer (if-let [g (first inflight)]
              (cond-> (assoc w :inflight (vec (rest inflight)))
                (:up? w) (-> (update :open conj g) (update :queue conj [:connected 0 g (str "client:" g)]))
                (not (:up? w)) (update :queue conj [:connect-failed 0 g "no server"]))
              w)
    :die (-> w
             (assoc :up? false :open #{})
             (update :queue into (for [g (sort (:open w))] [:closed 0 g "server closed the connection"])))
    ;; The server answers, then dies before rexg.main queues [:connected]:
    ;; the connection's reader reports the close first.
    :answer-die (if-let [g (and (:up? w) (first inflight))]
                  (-> w
                      (assoc :inflight (vec (rest inflight)) :up? false :open #{})
                      (update :queue into (for [o (sort (conj (:open w) g))] [:closed 0 o "server closed the connection"]))
                      (update :queue conj [:connected 0 g (str "client:" g)]))
                  w)
    :revive (assoc w :up? true)
    :hangup (if-let [g (:live w)] (close-conn w g) w)
    :replay (if-let [ev (last (filter #(not= :connect (first %)) (:seen w)))] (update w :queue conj ev) w)))

(defn- stranded?
  "Not connected, and nothing queued, in flight or waiting will try again."
  [{:keys [mod queue timers inflight]}]
  (and (not= :up (get-in mod [:servers 0 :conn])) (empty? queue) (empty? timers) (empty? inflight)))

(defn- agrees?
  "The model is up exactly on the connection the client adopted."
  [{:keys [mod live]}]
  (let [s (get-in mod [:servers 0])]
    (= (when (= :up (:conn s)) (:gen s)) live)))

(defn- world [kind starts-work?]
  {:mod (model kind) :queue [[:connect 0]] :timers [] :inflight [] :waits [] :seen []
   :up? false :open #{} :live nil :starts 0 :starts-work? (and (= :local kind) starts-work?)})

(defn- calm
  "W after the server comes back and stays, and the client runs its queue,
  attempts and timers out."
  [w]
  (loop [w (step w :revive) n 0]
    (if (or (> n 80) (every? empty? ((juxt :queue :timers :inflight) w))) w
        (recur (-> w (step :process) (step :answer) (step :fire)) (inc n)))))

(def ^:private gen-moves
  (gen/vector (gen/frequency [[6 (gen/return :process)] [3 (gen/return :answer)] [2 (gen/return :fire)]
                              [1 (gen/return :die)] [1 (gen/return :answer-die)] [1 (gen/return :revive)] [1 (gen/return :hangup)]
                              [1 (gen/return :replay)]])
              0 80))

(deftest property-the-client-always-reattaches-and-leaks-no-connection
  (let [r (tc/quick-check 400
                          (prop/for-all [kind (gen/elements [:local :rex-server :remote])
                                         starts-work? gen/boolean
                                         moves gen-moves]
                            (let [ws (reductions step (world kind starts-work?) moves)
                                  after (calm (last ws))
                                  s (get-in after [:mod :servers 0])]
                              (and (not-any? stranded? ws)
                                   (every? agrees? ws)
                                   (every? #(<= 250 % 4000) (:waits after))
                                   (= :up (:conn s))
                                   (= #{(:gen s)} (:open after))
                                   (or (= :local kind) (zero? (:starts after)))))))]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))

(deftest a-duplicate-is-closed-and-its-late-close-changes-nothing
  (let [mod (model :remote)
        [mod [[_ _ g1]]] (m/connect mod 0)
        _ (is (= :connecting (get-in mod [:servers 0 :conn])))
        _ (is (= [mod []] (m/connect mod 0)) "one try at a time")
        ;; An attempt the model no longer waits for also connected.
        [mod outs] (m/connected mod 0 (dec g1) "client:old")
        _ (is (= [[:conn/close 0 (dec g1)]] outs) "the duplicate is closed")
        [mod outs] (m/connected mod 0 g1 "client:new")
        _ (is (= [:conn/adopt 0 g1] (first outs)))
        up mod]
    (testing "the duplicate's close, after the other is live, changes nothing"
      (is (= [up []] (m/closed up 0 (dec g1) "closed locally"))))
    (testing "so does its late connect-failed or connected"
      (is (= [up []] (m/connect-failed up 0 (dec g1) "late")))
      (is (= [up [[:conn/close 0 g1]]] (m/connected up 0 g1 "client:again"))))
    (testing "a close before its own [:connected] fails the try, and that [:connected] is closed"
      (let [[mod [[_ _ g]]] (m/connect (model :remote) 0)
            [mod outs] (m/closed mod 0 g "server closed the connection")]
        (is (= [[:after 250 [:connect 0]]] outs))
        (is (= [[:conn/close 0 g]] (second (m/connected mod 0 g "client:x"))))))
    (testing "after a reconnect, the old connection's close changes nothing"
      (let [[mod outs] (m/closed up 0 g1 "server closed the connection")
            _ (is (= [:conn/drop 0 g1] (first outs)))
            [mod [[_ _ g2]]] (m/connect mod 0)
            [mod] (m/connected mod 0 g2 "client:b")]
        (is (= [mod []] (m/closed mod 0 g1 "late")))))))

(deftest closed-failed-three-times-then-connected
  (testing "a remote server: backoff grows, then the session it showed comes back"
    (let [[mod [[_ _ g]]] (m/connect (model :remote) 0)
          mod (-> mod (m/connected 0 g "client:a") first
                  (assoc-in [:servers 0 :view] {:session "session:p" :windows []}))
          [mod outs] (m/closed mod 0 g "server closed the connection")
          _ (is (= [[:conn/drop 0 g] [:after 250 [:connect 0]]] outs))
          _ (is (= :down (get-in mod [:servers 0 :conn])))
          _ (is (str/includes? (:echo mod) "far not connected: server closed the connection; retrying in 0.3 s"))
          [mod [[_ _ g]]] (m/connect mod 0)
          [mod outs] (m/connect-failed mod 0 g "refused")
          _ (is (= [[:after 500 [:connect 0]]] outs))
          [mod [[_ _ g]]] (m/connect mod 0)
          [mod outs] (m/connect-failed mod 0 g "refused")
          _ (is (= [[:after 1000 [:connect 0]]] outs))
          [mod [[_ _ g]]] (m/connect mod 0)
          [mod outs] (m/connected mod 0 g "client:b")]
      (is (= :up (get-in mod [:servers 0 :conn])))
      (is (= "far: connected again" (:echo mod)))
      (is (= "session:p" (get-in mod [:servers 0 :reattach])))
      (is (= "session.list" (nth (second outs) 2)))))
  (testing "the local server is started once per try, then the try waits, and the status line says so"
    (let [[mod [[_ _ g]]] (m/connect (model :local) 0)
          [mod outs] (m/connect-failed mod 0 g "no socket")
          _ (is (= [[:start-local-server 0] [:connect 0 (inc g)]] outs))
          _ (is (= "local not connected: no socket; starting the server" (:echo mod)))
          [mod outs] (m/connect-failed mod 0 (inc g) "no server")
          _ (is (= [[:after 250 [:connect 0]]] outs))
          [mod [[_ _ g]]] (m/connect mod 0)
          [_ outs] (m/connect-failed mod 0 g "no server")]
      (is (= [[:start-local-server 0] [:connect 0 (inc g)]] outs))))
  (testing "a lost local connection says so while it starts the server"
    (let [[mod [[_ _ g]]] (m/connect (model :local) 0)
          [mod] (m/connected mod 0 g "client:a")
          [mod outs] (m/closed mod 0 g "server closed the connection")]
      (is (= [[:conn/drop 0 g] [:start-local-server 0] [:connect 0 (inc g)]] outs))
      (is (str/includes? (:echo mod) "local not connected: server closed the connection"))
      (is (get-in mod [:servers 0 :lost]) "the status line says not connected while it reconnects")))
  (testing "REX_SERVER names another server, which the client does not start"
    (let [[mod [[_ _ g]]] (m/connect (model :rex-server) 0)]
      (is (= [[:after 250 [:connect 0]]] (second (m/connect-failed mod 0 g "no server")))))))
