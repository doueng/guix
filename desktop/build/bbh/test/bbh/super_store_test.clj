(ns bbh.super-store-test
  "The store's own rules: the state is the log folded from its newest snapshot,
  a version 1 database migrates to that, and an event written in an old format
  is read in today's."
  (:require [babashka.fs :as fs]
            [bbh.sqlite :as sq]
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

(defn- v1-db!
  "A version 1 database at PATH as the retired store wrote it: events without
  a version, and the state in one table per record kind."
  [path rows]
  (let [db (sq/open path)]
    (sq/execute! db "create table events (seq integer primary key, at text not null, kind text not null, thread text, data text not null)")
    (sq/execute! db "create table commands (id text primary key, at text not null, result text not null)")
    (doseq [kind [:counters :threads :workers :asks :replies :outbox :tokens :router]]
      (sq/execute! db (str "create table " (name kind) " (id text primary key, thread text, state text, data text not null)")))
    (sq/execute! db ["insert into events (seq, at, kind, thread, data) values (1, '2026-11-01T00:00:00Z', 'thread-created', 'T1', ?)"
                     (pr-str {:id "T1" :title "old"})])
    ;; A retired kind, which today's fold ignores although the tables hold what it wrote.
    (sq/execute! db "insert into events (seq, at, kind, thread, data) values (2, '2026-11-01T00:00:01Z', 'lease-granted', 'T1', '{}')")
    (sq/execute! db "insert into commands (id, at, result) values ('c1', '2026-11-01T00:00:00Z', '\"T1\"')")
    (doseq [[kind id record] rows]
      (sq/execute! db [(str "insert into " (name kind) " (id, thread, state, data) values (?, null, null, ?)") (pr-str id) (pr-str record)]))
    (sq/execute! db "pragma user_version=1")
    (sq/close! db)))

(deftest a-version-1-database-boots-from-its-tables-once-and-from-the-log-after
  (let [dir (fs/create-temp-dir {:prefix "bbh-super-store"})
        path (str (fs/path dir "super.db"))
        thread {:id "T1" :title "old" :worker nil :history ["W1"] :resources #{} :summary "only the table knew this"}]
    (try
      (v1-db! path [[:counters :threads 1]
                    [:threads "T1" (assoc thread :execution :done :watchdog-of nil)]
                    [:workers "W1" {:id "W1" :thread "T1" :state :done :lease 3 :restarts 2}]
                    [:asks "a1" {:id "a1" :thread "T1" :state :delivered}]
                    [:outbox 1 {:id 1 :state :acked :sent-at "x"}]
                    [:outbox 2 {:id 2 :state :processed}]
                    [:outbox 3 {:id 3 :state :pending}]
                    [:router :heartbeat {:at "2026-11-01T00:00:00Z"}]])
      (let [db (store/open path)
            state (store/replay db)]
        (is (fs/exists? (str path ".v1.bak")) "the database as it was is kept beside it")
        (is (= {:counters {:threads 1}
                :threads {"T1" thread}
                :workers {"W1" {:id "W1" :thread "T1" :state :done}}
                :asks {"a1" {:id "a1" :thread "T1" :state :sent}}
                :replies {}
                :outbox {1 {:id 1 :state :done} 2 {:id 2 :state :done} 3 {:id 3 :state :pending}}
                :tokens {} :router {}}
               state)
            "the tables' state in today's shape: retired states mapped, retired fields gone")
        (is (= [:thread-created :lease-granted] (map :kind (store/events db))) "the old events stay, and no reader sees the snapshot")
        (is (= {:result "T1" :repeat? true} (dissoc (store/commit! db state :owner {:id "c1" :command :thread! :args {:title "x"}}) :state))
            "a command from before the migration is still a repeat")
        (let [after (:state (store/commit! db state :owner {:id "c2" :command :thread! :args {:title "new"}}))]
          (is (= "new" (get-in after [:threads "T2" :title])))
          (store/close! db)
          (let [db (store/open path)]
            (is (= after (store/replay db)) "a restart folds the snapshot and what came after it")
            (is (= 1 (count (sq/query db "select seq from events where kind = 'snapshot'"))) "and does not migrate again")
            (is (= [1 1 1] (map :v (sq/query db "select v from events where kind != 'snapshot' order by seq"))))
            (store/close! db))))
      (finally (fs/delete-tree dir)))))

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
