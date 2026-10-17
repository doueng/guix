(ns bbh.llm
  "Streaming OpenAI-compatible chat completions."
  (:require [babashka.http-client :as http]
            [bbh.auth :as auth]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- openrouter-key []
  (or (auth/api-key "OPENROUTER_API_KEY" "openrouter")
      (throw (ex-info "Set OPENROUTER_API_KEY or log in to OpenRouter in Pi" {}))))

(defn- step [msg chunk]
  (let [delta (get-in chunk [:choices 0 :delta])]
    (cond-> (reduce (fn [m {:keys [index id function]}]
                      (update-in m [:tool_calls index]
                                 (fn [call]
                                   (cond-> (or call {:id nil :type "function" :function {:name "" :arguments ""}})
                                     id (assoc :id id)
                                     (:name function) (update-in [:function :name] str (:name function))
                                     (:arguments function) (update-in [:function :arguments] str (:arguments function))))))
                    (update msg :content str (:content delta))
                    (:tool_calls delta))
      (:usage chunk) (assoc :usage (:usage chunk)))))

(defn- finish [msg]
  (let [calls (->> (:tool_calls msg) (sort-by key) (mapv val))]
    {:message (cond-> {:role "assistant" :content (:content msg)}
                (seq calls) (assoc :tool_calls calls))
     :usage (:usage msg)}))

(defn accumulate
  "Fold streamed chunks into {:message :usage}."
  [chunks]
  (finish (reduce step {:content ""} chunks)))

(defn- chunks [body]
  (->> (line-seq (io/reader body))
       (keep #(when (str/starts-with? % "data: ") (subs % 6)))
       (take-while #(not= % "[DONE]"))
       (map #(json/parse-string % true))))

(defn openrouter
  "A sample function for MODEL on OpenRouter. Calls ON-OPEN with a function
  that aborts the stream."
  [model]
  (fn [{:keys [messages tools]} {:keys [on-text on-open]}]
    (let [resp (http/post "https://openrouter.ai/api/v1/chat/completions"
                          {:headers {"Authorization" (str "Bearer " (openrouter-key))
                                     "Content-Type" "application/json"}
                           :body (json/generate-string {:model model :messages messages :tools tools
                                                        :stream true :usage {:include true}})
                           :as :stream
                           :throw false})
          cancelled (atom false)
          acc (volatile! {:content ""})]
      (with-open [body (:body resp)]
        (when (not= 200 (:status resp))
          (throw (ex-info (str "OpenRouter " (:status resp) ": " (slurp body)) {})))
        (on-open #(do (reset! cancelled true) (.close ^java.io.InputStream body)))
        (try
          (doseq [c (chunks body)]
            (when-let [e (:error c)]
              (throw (ex-info (str "OpenRouter: " (:message e)) c)))
            (some-> (get-in c [:choices 0 :delta :content]) on-text)
            (vswap! acc step c))
          (finish @acc)
          (catch java.io.IOException e
            (if @cancelled
              (-> (finish @acc) (update :message dissoc :tool_calls) (assoc :interrupted true))
              (throw e))))))))
