(ns bbh.swe-report-test
  (:require [babashka.fs :as fs]
            [bbh.swe-report :as r]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- trial! [root job task reward cost error]
  (let [dir (fs/create-dirs (fs/path root job (str task "__abc")))]
    (spit (str (fs/path dir "result.json"))
          (json/generate-string
           (cond-> {:task_name task :trial_name (str task "__abc")
                    :config {:agent {:model_name "openrouter/openai/gpt-5.6-luna"}}
                    :agent_result {:cost_usd cost :n_input_tokens 10 :n_output_tokens 2}}
             reward (assoc :verifier_result {:rewards {:reward reward}})
             error (assoc :exception_info {:exception_type error}))))))

(deftest report-counts-missing-and-failed-trials-as-unresolved
  (let [root (str (fs/create-temp-dir {:prefix "swe-report-test"}))]
    (trial! root "shard-1" "a__a-1" 1.0 0.10 nil)
    (trial! root "shard-1" "b__b-2" 0.0 0.20 nil)
    (trial! root "shard-2" "c__c-3" nil nil "AgentTimeoutError")
    ;; A job-level result.json has no trial_name and must not count.
    (spit (str (fs/path root "shard-1" "result.json")) (json/generate-string {:n_total_trials 2}))
    (let [s (r/summary (r/trials [root]) 4)
          md (r/markdown s)]
      (is (= {:n 4 :ran 3 :resolved 1 :no-cost 1} (select-keys s [:n :ran :resolved :no-cost])))
      (is (< (Math/abs (- 0.30 (:cost s))) 1e-9))
      (is (= {"AgentTimeoutError" 1} (:errors s)))
      (is (str/includes? md "1 of 4 (25.0%"))
      (is (str/includes? md "GPT-5.6 Luna [medium]")))))
