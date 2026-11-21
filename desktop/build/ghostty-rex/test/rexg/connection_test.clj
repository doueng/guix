(ns rexg.connection-test
  "The control connection as rexg.model's state machine, against a simulated
  Rex server that dies and comes back. Rexmux.tla's NeverStranded and
  ServerUpAttaches say the same: a client is never left disconnected with nothing that will try again,
  and once the server answers it attaches again."
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
  [mod [kind srv x]]
  (case kind
    :connect (m/connect mod srv)
    :connected (m/connected mod srv x)
    :connect-failed (m/connect-failed mod srv x)
    :closed (m/closed mod srv x)))

(defn- perform
  "WORLD after the outs of one step, as rexg.main's perform! does them: a
  connect answers at once from whether the server is up, a local start brings
  it up when starts work, and a timer waits."
  [world outs]
  (reduce (fn [w [kind & xs]]
            (case kind
              :connect (update w :queue conj (if (:up? w) [:connected (first xs) "client:1"] [:connect-failed (first xs) "no server"]))
              :start-local-server (cond-> (update w :starts inc) (:starts-work? w) (assoc :up? true))
              :after (let [[ms ev] xs] (-> w (update :timers conj ev) (update :waits conj ms)))
              w))
          world outs))

(defn- step [{:keys [mod queue timers] :as w} move]
  (case move
    :process (if-let [ev (first queue)]
               (let [[mod outs] (handle mod ev)]
                 (perform (cond-> (assoc w :mod mod :queue (vec (rest queue)))
                            (= :connected (first ev)) (assoc :live? true))
                          outs))
               w)
    :fire (if-let [ev (first timers)] (assoc w :timers (vec (rest timers)) :queue (conj queue ev)) w)
    :die (cond-> (assoc w :up? false :live? false)
           (:live? w) (update :queue conj [:closed 0 "server closed the connection"]))
    :revive (assoc w :up? true)))

(defn- stranded?
  "Not connected, and nothing queued or waiting will try again."
  [{:keys [live? queue timers]}]
  (and (not live?) (empty? queue) (empty? timers)))

(defn- run [kind starts-work? moves]
  (let [w0 {:mod (model kind) :queue [[:connect 0]] :timers [] :waits [] :up? false :starts 0
            :starts-work? (and (= :local kind) starts-work?)}
        w (reduce step w0 moves)
        ;; calm: the server comes back and stays, the client runs its queue and timers
        calm (loop [w (step w :revive) n 0]
               (if (or (> n 50) (and (empty? (:queue w)) (empty? (:timers w)))) w
                   (recur (step (step w :process) :fire) (inc n))))]
    {:during w :after calm}))

(def ^:private gen-moves (gen/vector (gen/frequency [[6 (gen/return :process)] [2 (gen/return :fire)] [1 (gen/return :die)] [1 (gen/return :revive)]]) 0 60))

(deftest property-the-client-always-reattaches
  (let [r (tc/quick-check 300
                          (prop/for-all [kind (gen/elements [:local :rex-server :remote])
                                         starts-work? gen/boolean
                                         moves gen-moves]
                            (let [ws (reductions step {:mod (model kind) :queue [[:connect 0]] :timers [] :waits [] :up? false :starts 0
                                                       :starts-work? (and (= :local kind) starts-work?)}
                                                 moves)
                                  {:keys [after]} (run kind starts-work? moves)]
                              (and (not-any? stranded? ws)
                                   (every? #(<= 250 % 4000) (:waits after))
                                   (:live? after) (= :up (get-in after [:mod :servers 0 :conn]))
                                   (or (= :local kind) (zero? (:starts after)))))))]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))

(deftest closed-failed-three-times-then-connected
  (testing "a remote server: backoff grows, then the session it showed comes back"
    (let [mod (-> (model :remote) (m/connected 0 "client:a") first
                  (assoc-in [:servers 0 :view] {:session "session:p" :windows []}))
          [mod outs] (m/closed mod 0 "server closed the connection")
          _ (is (= [[:after 250 [:connect 0]]] outs))
          _ (is (= :down (get-in mod [:servers 0 :conn])))
          _ (is (str/includes? (:echo mod) "far not connected: server closed the connection; retrying in 0.3 s"))
          [mod outs] (m/connect mod 0)
          _ (is (= [[:connect 0]] outs))
          [mod outs] (m/connect-failed mod 0 "refused")
          _ (is (= [[:after 500 [:connect 0]]] outs))
          [mod outs] (m/connect-failed mod 0 "refused")
          _ (is (= [[:after 1000 [:connect 0]]] outs))
          [mod outs] (m/connected mod 0 "client:b")]
      (is (= :up (get-in mod [:servers 0 :conn])))
      (is (= "far: connected again" (:echo mod)))
      (is (= "session:p" (get-in mod [:servers 0 :reattach])))
      (is (= "session.list" (nth (first outs) 2)))))
  (testing "the local server is started once per try, then the try waits"
    (let [[mod outs] (m/connect-failed (model :local) 0 "no socket")
          _ (is (= [[:start-local-server 0] [:connect 0]] outs))
          [mod outs] (m/connect-failed mod 0 "no server")
          _ (is (= [[:after 250 [:connect 0]]] outs))
          [_ outs] (m/connect-failed mod 0 "no server")]
      (is (= [[:start-local-server 0] [:connect 0]] outs))))
  (testing "REX_SERVER names another server, which the client does not start"
    (is (= [[:after 250 [:connect 0]]] (second (m/connect-failed (model :rex-server) 0 "no server")))))
  (testing "a try that lost to one that connected changes nothing"
    (let [mod (first (m/connected (model :local) 0 "client:a"))]
      (is (= [mod []] (m/connect-failed mod 0 "late")))
      (is (= [] (second (m/connect mod 0)))))))
