(ns bbh.models
  "Model ids are PROVIDER/MODEL. The provider decides which engine runs turns."
  (:require [bbh.claude]
            [bbh.llm :as llm]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def available
  ["claude/opus" "claude/sonnet" "claude/haiku"
   "openrouter/anthropic/claude-opus-5.5" "openrouter/deepseek/deepseek-v4.1-flash"])

(defn default-model []
  (or (System/getenv "BBH_MODEL") "claude/claude-opus-5-5"))

(defn- script
  "Replies read in order from an EDN file of steps: a string answers, {:code ...}
  calls the clojure tool, and {:text ...} answers too. A map step's :usage is
  the request's usage record. For tests and demos."
  [path]
  (let [steps (atom (edn/read-string (slurp path)))]
    (fn [_ {:keys [on-text]}]
      (let [step (or (first @steps) "(script exhausted)")
            text (if (string? step) step (:text step))]
        (swap! steps rest)
        (if text
          (do (on-text text) {:message {:role "assistant" :content text} :usage (:usage step)})
          {:message {:role "assistant" :content ""
                     :tool_calls [{:id (str (random-uuid)) :type "function"
                                   :function {:name "clojure" :arguments (json/generate-string (select-keys step [:code]))}}]}
           :usage (:usage step)})))))

(defn engine [id]
  (let [[provider model] (str/split id #"/" 2)]
    (case provider
      "claude" {:kind :claude :model model}
      "openrouter" {:kind :loop :sample (llm/openrouter model)}
      "script" {:kind :loop :sample (script model)}
      (throw (ex-info (str "Unknown provider in " id "; use claude/, openrouter/ or script/") {})))))
