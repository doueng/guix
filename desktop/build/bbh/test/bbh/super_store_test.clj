(ns bbh.super-store-test
  "The store's own rules: the state is the log folded from its newest snapshot,
  and an event written in an old format is read in today's."
  (:require [bbh.sqlite :as sq]
            [bbh.super.model :as model]
            [bbh.super.store :as store]
            [clojure.test :refer [deftest is]]))

(def ^:private live-kinds
  "Every event kind the live log held when the tables were retired."
  [:ask-opened :asks-answered-in-chat :cancel-requested :draft-saved :health-observed :job-exited :lease-granted
   :machine-observed :milestone :note :outbox-acked :outbox-dropped :outbox-sent :reply-answered :reply-sent
   :restart-attempted :restart-failed :router-queued :router-registered :router-triaged :thread-created :thread-linked
   :thread-read :thread-resolved :token-issued :worker-attached :worker-finished :worker-prepared :worker-promoted
   :worker-restarted :worker-resumed :worker-running :worker-started :worker-steered])

(deftest every-event-kind-of-the-live-log-still-folds
  (let [folds (set (keys (methods @#'model/fold*)))]
    (doseq [kind live-kinds]
      (is (or (folds kind) (model/retired-events kind)) (str kind " has no fold and is not retired")))))

(deftest an-event-in-an-old-format-is-read-in-todays
  (let [db (store/open nil)]
    (try
      (let [state (:state (store/commit! db (store/replay db) :owner {:id "c1" :command :thread! :args {:title "old name"}}))]
        (with-redefs-fn {#'model/versions {:thread-created 3}
                         #'model/upcasts {[:thread-created 1] #(update-in % [:data :title] str " v2")
                                          [:thread-created 2] #(update-in % [:data :title] str " v3")}}
          (fn []
            (is (= "old name v2 v3" (get-in (store/replay db) [:threads "T1" :title])) "each step runs once, in order")
            (is (= [3] (map :v (store/events db))))
            (let [after (:state (store/commit! db state :owner {:id "c2" :command :thread! :args {:title "new name"}}))]
              (is (= "new name" (get-in after [:threads "T2" :title])))
              (is (= "new name" (get-in (store/replay db) [:threads "T2" :title])) "an event written today is not upcast")
              (is (= [1 3] (map :v (sq/query db "select v from events order by seq"))))))))
      (finally (store/close! db)))))

(deftest a-snapshot-from-before-waiting-was-derived-reads-the-same
  ;; The live database's snapshot was written while a Worker stored :waiting.
  (let [db (store/open nil)
        w {:id "W1" :thread "T1" :state :waiting :conversation "super-W1"}
        old (assoc model/empty-state
                   :threads {"T1" {:id "T1" :worker "W1"}}
                   :workers {"W1" w "W2" (assoc w :id "W2" :thread "T2" :state :cancelling)}
                   :asks {"a1" {:id "a1" :thread "T1" :worker "W1" :state :open :stopped true}})]
    (try
      (sq/execute! db ["insert into events (seq, at, kind, thread, data, v) values (1, '2026-11-12T00:00:00Z', 'snapshot', null, ?, 1)"
                       (pr-str {:state old})])
      (let [state (store/replay db)]
        (is (= [:working :cancelling] (map #(get-in state [:workers % :state]) ["W1" "W2"])) "no Worker holds :waiting")
        (is (= :waiting (:state (model/linked state :workers "W1"))) "and a read still shows it, from the ask")
        (is (= :waiting (model/execution state "T1")))
        (is (= (assoc-in old [:workers "W1" :state] :working) state) "nothing else moved")
        (store/snapshot! db state)
        (is (= [1 2] (map :v (sq/query db "select v from events order by seq"))) "a new snapshot is written in today's version")
        (is (= state (store/replay db))))
      (finally (store/close! db)))))
