(ns bbh.super.store
  "The only SQL. super.db holds the append-only events and the commands with
  the result each returned. The state is nothing but `bbh.super.model/fold` over
  the events, from the newest snapshot on; `commit!` runs a command as one
  transaction."
  (:require [babashka.fs :as fs]
            [bbh.sqlite :as sq]
            [bbh.super.model :as model]
            [clojure.edn :as edn]))

(def ^:private schema-version 2)

(def ^:private schema
  ["create table if not exists events (seq integer primary key, at text not null, kind text not null, thread text, data text not null, v integer not null default 1)"
   "create table if not exists commands (id text primary key, at text not null, result text not null)"
   "create index if not exists events_thread on events (thread, seq)"])

(defn- append!
  "Write EVENTS, which have no :seq or :at yet, after DB's last event. Returns
  them as written."
  [db events]
  (let [now (str (java.time.Instant/now))
        last-seq (:seq (first (sq/query db "select coalesce(max(seq), 0) as seq from events")))
        events (vec (map-indexed (fn [i e] (assoc e :seq (+ last-seq i 1) :at now :v (model/version-of (:kind e)))) events))]
    (doseq [{:keys [seq at kind thread data v]} events]
      (sq/execute! db ["insert into events (seq, at, kind, thread, data, v) values (?, ?, ?, ?, ?, ?)"
                       seq at (name kind) thread (pr-str data) v]))
    events))

(defn snapshot!
  "Append STATE to DB as a snapshot event, so `replay` starts from it and
  needs no event before it."
  [db state]
  (sq/with-transaction db (append! db [{:kind :snapshot :data {:state state}}])))

;; ---- version 1: the state lived in one table per record kind ----------------

(def ^:private v1-tables [:counters :threads :workers :asks :replies :outbox :tokens :router])

(def ^:private v1-states
  "States that version 1 rows still hold and no fold writes any more."
  {:outbox {:acked :done :processed :done} :asks {:delivered :sent} :replies {:delivered :sent}})

(def ^:private v1-fields
  "Fields that version 1 rows still hold and nothing reads any more."
  {:threads [:execution :watchdog-of] :workers [:lease :capabilities :locks :restarts :restart-at :restart-error]
   :outbox [:sent-at :triage :granted]})

(defn- v1-state
  "The state a version 1 DB materialized in its tables, in today's shape. Its
  log cannot stand in for it: folds changed since, so a replay disagrees."
  [db]
  (reduce (fn [state kind]
            (assoc state kind
                   (into {} (for [{:keys [id data]} (sq/query db (str "select id, data from " (name kind)))
                                  :let [record (edn/read-string data)]]
                              [(edn/read-string id)
                               (if (map? record)
                                 (cond-> (apply dissoc record (v1-fields kind))
                                   (:state record) (update :state #(get-in v1-states [kind %] %)))
                                 record)]))))
          model/empty-state v1-tables))

(defn- migrate-v1!
  "Bring a version 1 DB at PATH to version 2: a backup beside it, the version
  column, and its tables' state as a snapshot event. The tables stay as they
  were, unused."
  [db path]
  (when path
    (let [backup (str path ".v1.bak")]
      (fs/delete-if-exists backup)
      (sq/execute! db ["vacuum into ?" backup])))
  (sq/with-transaction db
    (sq/execute! db "alter table events add column v integer not null default 1")
    (append! db [{:kind :snapshot :data {:state (update (v1-state db) :router dissoc :heartbeat)}}])
    (sq/execute! db (str "pragma user_version=" schema-version))))

;; ---- the store ---------------------------------------------------------------

(defn open
  "Open or create the database at PATH (nil for memory), with the pragmas the
  design names, migrated to the current schema. Returns the connection."
  [path]
  (let [db (sq/open path)]
    (doseq [pragma ["pragma journal_mode=wal" "pragma synchronous=full" "pragma foreign_keys=on"]]
      (sq/query db pragma))
    (case (long (:user_version (first (sq/query db "pragma user_version"))))
      0 (sq/with-transaction db
          (doseq [ddl schema] (sq/execute! db ddl))
          (sq/execute! db (str "pragma user_version=" schema-version)))
      1 (migrate-v1! db path)
      nil)
    db))

(def close! sq/close!)

(defn- read-event [{:keys [seq at kind thread data v]}]
  (model/upcast {:seq seq :at at :kind (keyword kind) :thread thread :data (edn/read-string data) :v v}))

(defn events
  "Events in DB after seq AFTER, oldest first, optionally those of THREAD, each
  in today's format. Snapshots are the store's own and are left out."
  ([db] (events db 0 nil))
  ([db after thread]
   (mapv read-event
         (if thread
           (sq/query db ["select * from events where seq > ? and thread = ? and kind != 'snapshot' order by seq" after thread])
           (sq/query db ["select * from events where seq > ? and kind != 'snapshot' order by seq" after])))))

(defn replay
  "The state of DB: its newest snapshot, if any, with every later event folded in."
  [db]
  (let [from (:seq (first (sq/query db "select coalesce(max(seq), 0) as seq from events where kind = 'snapshot'")))]
    (transduce (map read-event) (completing model/fold) model/empty-state
               (sq/query db ["select * from events where seq >= ? order by seq" from]))))

(defn commit!
  "Run COMMAND for PRINCIPAL against STATE in one transaction. A command id
  seen before returns its stored result unchanged. Otherwise the model decides,
  the events are written, and the result is stored under the id. Returns
  {:state :result :repeat?}. A refusal rolls back and rethrows."
  [db state principal {:keys [id] :as command}]
  (sq/with-transaction db
    (if-let [row (first (sq/query db ["select result from commands where id = ?" id]))]
      {:state state :result (edn/read-string (:result row)) :repeat? true}
      (let [{:keys [events result]} (model/decide state principal command)
            events (append! db events)]
        (sq/execute! db ["insert into commands (id, at, result) values (?, ?, ?)" id (str (java.time.Instant/now)) (pr-str result)])
        {:state (reduce model/fold state events) :result result :repeat? false}))))
