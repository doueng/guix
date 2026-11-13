(ns bbh.super.store
  "The only SQL. super.db holds the append-only events and the commands with
  the result each returned. The state is nothing but `bbh.super.model/fold` over
  the events, from the newest snapshot on; `commit!` runs a command as one
  transaction."
  (:require [bbh.sqlite :as sq]
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

(defn open
  "Open or create the database at PATH (nil for memory), with the pragmas the
  design names, creating the current schema in a new one. Returns the connection."
  [path]
  (let [db (sq/open path)]
    (doseq [pragma ["pragma journal_mode=wal" "pragma synchronous=full" "pragma foreign_keys=on"]]
      (sq/query db pragma))
    (case (long (:user_version (first (sq/query db "pragma user_version"))))
      0 (sq/with-transaction db
          (doseq [ddl schema] (sq/execute! db ddl))
          (sq/execute! db (str "pragma user_version=" schema-version)))
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
