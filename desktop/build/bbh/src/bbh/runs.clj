(ns bbh.runs
  "A conversation's run tree in runs.jsonl, after LangSmith. A trace is one
  user turn. Its turn run's id is the trace id, and the turn's llm and tool
  runs are its children. Runs point into messages.jsonl by index
  and tool_call_id instead of copying content."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- runs-file [dir] (str (fs/path dir "runs.jsonl")))

(def ^:private lock
  "Claude Code's bridge runs tools on other threads, so appends take turns."
  (Object.))

(defn append!
  "Append RUN, which started at START-MS, with its :start instant and :ms."
  [dir start-ms run]
  (let [now (System/currentTimeMillis)
        run (assoc run :start (str (java.time.Instant/ofEpochMilli start-ms)) :ms (- now start-ms))]
    (locking lock
      (fs/create-dirs dir)
      (spit (runs-file dir) (str (json/generate-string run) "\n") :append true))
    run))

(defn load-runs [dir]
  (let [f (runs-file dir)]
    (if (fs/exists? f)
      (->> (fs/read-all-lines f) (remove str/blank?) (mapv #(json/parse-string % true)))
      [])))
