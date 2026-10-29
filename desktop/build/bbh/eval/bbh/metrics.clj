(ns bbh.metrics
  "LangSmith-style dashboards from runs.jsonl: latency, tokens, cache share,
  cost and failure rates per day and model."
  (:require [babashka.fs :as fs]
            [bbh.runs :as runs]
            [clojure.string :as str]))

(def default-root (str (fs/expand-home "~/.local/share/bbh/conversations")))

(defn- percentile [sorted q]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted)) (long (* q (count sorted)))))))

(defn- latency [rs]
  (let [ms (sort (keep :ms rs))]
    {:n (count ms) :p50 (percentile ms 0.5) :p95 (percentile ms 0.95)}))

(defn- total [rs k]
  (reduce + 0 (keep #(get-in % [:usage k]) rs)))

(defn summarize
  "The numbers for one group of runs. Input counts every prompt token, so
  :cache-share is the part of it read back from the cache. :cost sums the roots,
  because a turn already holds its children's cost and Claude Code reports cost
  per turn only."
  [rs]
  (let [{:strs [turn llm tool]} (group-by :kind rs)
        input (total llm :input)]
    {:turns (count turn)
     :errors (count (filter #(= "error" (:status %)) turn))
     :turn (latency turn)
     :llm (latency llm)
     :tool (latency tool)
     :tool-failures (count (filter #(= "error" (:status %)) tool))
     :restarts (count (filter :restart tool))
     :input input
     :output (total llm :output)
     :cache-share (when (pos? input) (/ (double (total llm :cached)) input))
     :cost (total (remove :parent rs) :cost)}))

(defn report
  "RUNS as {[day model] numbers}, sorted."
  [rs]
  (into (sorted-map)
        (update-vals (group-by (juxt #(subs (str (:start %)) 0 (min 10 (count (str (:start %))))) :model) rs)
                     summarize)))

(defn- load-all [roots]
  (mapcat (fn [root]
            (->> (fs/glob root "**/runs.jsonl")
                 (mapcat #(runs/load-runs (fs/parent %)))))
          roots))

(defn- render [[[day model] {:keys [turns errors turn llm tool tool-failures restarts input output cache-share cost]}]]
  (let [pair (fn [{:keys [p50 p95]}] (str (or p50 "-") "/" (or p95 "-")))]
    (str/join "  " [day model (str turns " turns") (str errors " errors")
                    (str "turn " (pair turn) " ms")
                    (str "llm " (:n llm) " " (pair llm) " ms")
                    (str "tool " (:n tool) " " (pair tool) " ms")
                    (str tool-failures " failed") (str restarts " restarts")
                    (str input " in") (str output " out")
                    (str "cache " (if cache-share (format "%.0f%%" (* 100 cache-share)) "-"))
                    (format "$%.4f" (double cost))])))

(defn -main [& roots]
  (let [rows (report (load-all (or (seq roots) [default-root])))]
    (if (empty? rows)
      (println "No runs.jsonl found.")
      (run! (comp println render) rows))))
