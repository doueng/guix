(ns bbh.stats
  "Harness health from real conversations and workflow runs: how often evaluations time out, restart the REPL or
  throw, which runs still need attention, and how long reviews take."
  (:require [babashka.fs :as fs]
            [bbh.runs :as runs]
            [bbh.traces :as traces]
            [bbh.workflow :as wf]
            [clojure.string :as str]))

(def default-root (str (fs/expand-home "~/.local/share/bbh/conversations")))

(defn- turn-roots [dir]
  (seq (filter #(and (= "turn" (:kind %)) (nil? (:parent %))) (runs/load-runs dir))))

(defn conversation-stats
  "One conversation's health. :cost and :wall-ms come from its turn runs, and stay nil for a conversation
  without any, so missing data does not read as free."
  [{:keys [dir steps turns]}]
  {:id (str (fs/file-name dir))
   :cost (some->> (turn-roots dir) (keep #(get-in % [:usage :cost])) seq (reduce +))
   :wall-ms (some->> (turn-roots dir) (keep :ms) seq (reduce +))
   :prompts (count turns)
   :calls (count steps)
   :timeouts (count (filter #(re-find #"\ATimed out after \d+ ms\. " (str (:result %))) steps))
   :restarts (count (filter #(= :restart (:error %)) steps))
   :errors (count (filter #(string? (:error %)) steps))})

(defn- in-range? [id since until]
  (and (or (nil? since) (not (neg? (compare id since))))
       (or (nil? until) (not (pos? (compare (subs id 0 (min (count id) (count until))) until))))))

(defn- review-seconds [run-dir]
  (for [d (when (fs/exists? (fs/path run-dir "reviews")) (fs/list-dir (fs/path run-dir "reviews")))
        :let [started (some-> (re-find #"\d+$" (str (fs/file-name d))) parse-long)
              messages (fs/path d "messages.jsonl")]
        :when (and started (fs/exists? messages))]
    (quot (- (.toMillis (fs/last-modified-time messages)) started) 1000)))

(defn run-stats [state-root]
  (vec (for [d (when (fs/exists? state-root) (sort (fs/list-dir state-root)))
             :let [id (str (fs/file-name d))
                   st (try (wf/status id) (catch Exception _ nil))]
             :when st]
         {:id id :workflow (name (:workflow st)) :status (:status st) :phase (:phase st)
          :reviews (vec (review-seconds d))})))

(defn report
  "Stats for the conversations under ROOTS whose ids fall in [SINCE, UNTIL], and for every workflow run."
  [{:keys [roots since until state-root]}]
  (let [convs (->> (traces/find-traces roots)
                   (filter #(in-range? (str (fs/file-name (:dir %))) since until))
                   (mapv conversation-stats))
        runs (run-stats state-root)
        reviews (sort (mapcat :reviews runs))]
    {:conversations convs
     :totals (assoc (reduce (fn [acc c] (merge-with + acc (select-keys c [:prompts :calls :timeouts :restarts :errors])))
                            {:conversations (count convs) :prompts 0 :calls 0 :timeouts 0 :restarts 0 :errors 0}
                            (map #(assoc % :conversations 0) convs))
                     :cost (some->> (seq (keep :cost convs)) (reduce +))
                     :wall-ms (some->> (seq (keep :wall-ms convs)) (reduce +)))
     :runs runs
     :run-statuses (frequencies (map :status runs))
     :review-seconds (when (seq reviews)
                       {:n (count reviews) :median (nth reviews (quot (count reviews) 2)) :max (last reviews)})}))

(defn- money [cost] (if cost (format "$%.2f" (double cost)) "-"))

(defn- minutes [ms] (if ms (format "%.1fm" (/ ms 60000.0)) "-"))

(defn print-report [{:keys [conversations totals runs run-statuses review-seconds]}]
  (println (format "%-20s %7s %5s %8s %8s %6s %8s %8s" "conversation" "prompts" "calls" "timeouts" "restarts" "errors" "cost" "wall"))
  (doseq [{:keys [id prompts calls timeouts restarts errors] :as c} conversations]
    (println (format "%-20s %7d %5d %8d %8d %6d %8s %8s" id prompts calls timeouts restarts errors
                     (money (:cost c)) (minutes (:wall-ms c)))))
  (let [{:keys [prompts calls timeouts restarts errors cost wall-ms] n :conversations} totals]
    (println (format "%-20s %7d %5d %8d %8d %6d %8s %8s" (str "total (" n ")") prompts calls timeouts restarts errors
                     (money cost) (minutes wall-ms))))
  (println)
  (println (str "workflow runs: " (str/join ", " (for [[k v] (sort-by key run-statuses)] (str (name k) " " v)))))
  (doseq [{:keys [id status phase]} runs :when (#{:active :blocked} status)]
    (println (str "  " (name status) "  " id "  at " (name phase))))
  (when-let [{:keys [n median max]} review-seconds]
    (println (str "reviews: " n ", median " median " s, max " max " s"))))

(defn- parse-args [args]
  (loop [[a & more :as xs] args opts {:roots []}]
    (cond (empty? xs) opts
          (= "--since" a) (recur (rest more) (assoc opts :since (first more)))
          (= "--until" a) (recur (rest more) (assoc opts :until (first more)))
          :else (recur more (update opts :roots conj a)))))

(defn -main [& args]
  (let [opts (parse-args args)]
    (print-report (report (assoc opts
                                 :roots (if (seq (:roots opts)) (:roots opts) [default-root])
                                 :state-root (wf/state-root))))))
