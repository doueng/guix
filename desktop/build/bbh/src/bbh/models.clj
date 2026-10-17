(ns bbh.models
  "Model ids are PROVIDER/MODEL. The provider decides which engine runs turns."
  (:require [bbh.llm :as llm]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def available
  ["claude/opus" "claude/sonnet" "claude/haiku"
   "openrouter/anthropic/claude-opus-5.5" "openrouter/deepseek/deepseek-v4.1-flash"])

(defn default-model []
  (or (System/getenv "BBH_MODEL") "claude/haiku"))

(defn- script
  "Replies read in order from an EDN file of steps: a string answers, and
  {:code ...} calls the clojure tool. For tests and demos."
  [path]
  (let [steps (atom (edn/read-string (slurp path)))]
    (fn [_ {:keys [on-text]}]
      (let [step (or (first @steps) "(script exhausted)")]
        (swap! steps rest)
        (if (string? step)
          (do (on-text step) {:message {:role "assistant" :content step}})
          {:message {:role "assistant" :content ""
                     :tool_calls [{:id (str (random-uuid)) :type "function"
                                   :function {:name "clojure" :arguments (cheshire.core/generate-string step)}}]}})))))

(defn engine [id]
  (let [[provider model] (str/split id #"/" 2)]
    (case provider
      "claude" {:kind :claude :model model}
      "openrouter" {:kind :loop :sample (llm/openrouter model)}
      "script" {:kind :loop :sample (script model)}
      (throw (ex-info (str "Unknown provider in " id "; use claude/, openrouter/ or script/") {})))))
