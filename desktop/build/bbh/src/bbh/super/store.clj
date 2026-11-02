(ns bbh.super.store
  "The only SQL. super.db holds the append-only events, the commands with the
  result each returned, and one table per record kind that materializes what
  `bbh.super.model/fold` computes. `commit!` runs a command as one transaction."
  (:require [bbh.sqlite :as sq]
            [bbh.super.model :as model]
            [clojure.edn :as edn]))

(def ^:private schema-version 1)

(def ^:private schema
  ["create table if not exists events (seq integer primary key, at text not null, kind text not null, thread text, data text not null)"
   "create table if not exists commands (id text primary key, at text not null, result text not null)"
   "create index if not exists events_thread on events (thread, seq)"])

(defn- record-ddl [kind]
  (format "create table if not exists %s (id text primary key, thread text, state text, data text not null)"
          (name kind)))

(defn open
  "Open or create the database at PATH (nil for memory), with the pragmas the
  design names, migrated to the current schema. Returns the connection."
  [path]
  (let [db (sq/open path)]
    (doseq [pragma ["pragma journal_mode=wal" "pragma synchronous=full" "pragma foreign_keys=on"]]
      (sq/query db pragma))
    (let [version (:user_version (first (sq/query db "pragma user_version")))]
      (when (< version schema-version)
        (sq/with-transaction db
          (doseq [ddl (concat schema (map record-ddl model/entity-kinds))] (sq/execute! db ddl))
          (sq/execute! db (str "pragma user_version=" schema-version)))))
    db))

(def close! sq/close!)

(defn load
  "The state materialized in DB."
  [db]
  (reduce (fn [state kind]
            (assoc state kind
                   (into {} (map (fn [{:keys [id data]}] [(edn/read-string id) (edn/read-string data)])
                                 (sq/query db (str "select id, data from " (name kind)))))))
          model/empty-state model/entity-kinds))

(defn events
  "Events in DB after seq AFTER, oldest first, optionally those of THREAD."
  ([db] (events db 0 nil))
  ([db after thread]
   (mapv (fn [{:keys [seq at kind thread data]}]
           {:seq seq :at at :kind (keyword kind) :thread thread :data (edn/read-string data)})
         (if thread
           (sq/query db ["select * from events where seq > ? and thread = ? order by seq" after thread])
           (sq/query db ["select * from events where seq > ? order by seq" after])))))

(defn replay
  "The state folded from DB's events alone, for comparison with `load`."
  [db]
  (reduce model/fold model/empty-state (events db)))

(defn- upsert! [db [kind id record]]
  (sq/execute! db [(format "insert into %s (id, thread, state, data) values (?, ?, ?, ?) on conflict (id) do update set thread = excluded.thread, state = excluded.state, data = excluded.data"
                           (name kind))
                   (pr-str id)
                   (when (map? record) (:thread record))
                   (when (map? record) (some-> (:state record) name))
                   (pr-str record)]))

(defn commit!
  "Run COMMAND for PRINCIPAL against STATE in one transaction. A command id
  seen before returns its stored result unchanged. Otherwise the model decides,
  the events and the records they change are written, and the result is stored
  under the id. Returns {:state :result :repeat?}. A refusal rolls back and
  rethrows."
  [db state principal {:keys [id] :as command}]
  (sq/with-transaction db
    (if-let [row (first (sq/query db ["select result from commands where id = ?" id]))]
      {:state state :result (edn/read-string (:result row)) :repeat? true}
      (let [{:keys [events result]} (model/decide state principal command)
            now (str (java.time.Instant/now))
            last-seq (:seq (first (sq/query db "select coalesce(max(seq), 0) as seq from events")))
            events (map-indexed (fn [i e] (assoc e :seq (+ last-seq i 1) :at now)) events)
            state' (reduce model/fold state events)]
        (doseq [{:keys [seq at kind thread data]} events]
          (sq/execute! db ["insert into events (seq, at, kind, thread, data) values (?, ?, ?, ?, ?)"
                           seq at (name kind) thread (pr-str data)]))
        (doseq [change (model/changed state state')] (upsert! db change))
        (sq/execute! db ["insert into commands (id, at, result) values (?, ?, ?)" id now (pr-str result)])
        {:state state' :result result :repeat? false}))))
