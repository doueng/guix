(ns bbh.swe-report
  "Summarize Harbor trial results from a SWE-rebench run as Markdown.
  Args: [--expected N] ROOT... Every result.json with a trial_name under ROOT counts.
  Expected tasks that produced no trial count as unresolved, as on the leaderboard."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def reference
  "swe-rebench.com rows for the 15/05/2026-01/07/2026 window (111 problems), read 2026-10-27.
  Each is a mean over several runs of SWE-rebench's own scaffold."
  {"openai/gpt-5.6-luna" {:row "GPT-5.6 Luna [medium]" :resolved 43.6 :ci 1.47 :cost 0.11}
   "deepseek/deepseek-v4-pro" {:row "DeepSeek-V4 Pro [high]" :resolved 40.2 :ci 1.29 :cost 0.15}
   "xiaomi/mimo-v2.5-pro" {:row "MiMo V2.5 Pro" :resolved 46.5 :ci 0.54 :cost 0.10}
   "z-ai/glm-5.2" {:row "GLM-5.2 [high]" :resolved 62.9 :ci 1.19 :cost 1.40}})

(defn trials [roots]
  (for [root roots
        path (fs/glob root "**result.json")
        :let [r (json/parse-string (slurp (str path)) true)]
        :when (:trial_name r)]
    (let [rewards (get-in r [:verifier_result :rewards])]
      {:task (:task_name r)
       :resolved (boolean (some-> (or (:reward rewards) (first (vals rewards))) (>= 1)))
       :cost (get-in r [:agent_result :cost_usd])
       :input (get-in r [:agent_result :n_input_tokens])
       :output (get-in r [:agent_result :n_output_tokens])
       :error (get-in r [:exception_info :exception_type])
       :model (get-in r [:config :agent :model_name])})))

(defn summary [ts expected]
  (let [n (max (or expected 0) (count ts))
        resolved (count (filter :resolved ts))
        p (if (pos? n) (/ resolved (double n)) 0.0)
        cost (reduce + 0.0 (keep :cost ts))]
    {:n n :ran (count ts) :resolved resolved :rate (* 100 p)
     :ci (if (pos? n) (* 100 1.96 (Math/sqrt (/ (* p (- 1 p)) n))) 0.0)
     :cost cost :cost-per (if (pos? (count ts)) (/ cost (count ts)) 0.0)
     :no-cost (count (remove :cost ts))
     :errors (frequencies (keep :error ts))
     :models (set (keep :model ts))}))

(defn markdown [{:keys [n ran resolved rate ci cost cost-per no-cost errors models]}]
  (let [model (first models)
        ref (some->> model (re-find #"^openrouter/(.*)$") second reference)]
    (str/join "\n"
              (concat
               ["## SWE-rebench: bbh" ""
                (str "Model: `" (str/join ", " (sort models)) "`") ""
                "| | bbh, this run | Leaderboard |" "|---|---|---|"
                (format "| Resolved | %d of %d (%.1f%% ± %.1f) | %s |" resolved n rate ci
                        (if ref (format "%.1f%% ± %.2f (%s)" (:resolved ref) (:ci ref) (:row ref)) "no row recorded"))
                (format "| Cost per problem | $%.3f | %s |" cost-per (if ref (format "$%.2f" (:cost ref)) ""))
                (format "| Total cost | $%.2f | |" cost)
                (format "| Trials that ran | %d of %d | |" ran n)
                (format "| Trials with no recorded cost | %d | |" no-cost)
                ""]
               (when (seq errors)
                 (cons "Trial exceptions:" (for [[e k] (sort-by val > errors)] (str "- " e ": " k))))
               ["" "The ± is a 95% binomial interval for one run. The leaderboard averages several runs of its own scaffold."]))))

(defn -main [& args]
  (let [[expected roots] (if (= "--expected" (first args))
                           [(parse-long (second args)) (drop 2 args)]
                           [nil args])
        ts (trials roots)]
    (when (empty? ts) (binding [*out* *err*] (println "No Harbor trial results under" (str/join " " roots))))
    (println (markdown (summary ts expected)))))
