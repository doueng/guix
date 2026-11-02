(ns bbh.super.triage
  "What a message to the Router is, judged by TypeSafe Jev before a Router turn
  is spent on it."
  (:require [babashka.http-client :as http]
            [bbh.auth :as auth]
            [cheshire.core :as json]))

(def criteria
  {:request "Asks for work to be done: a fix, a feature, a chore, an investigation or a change to running work."
   :question "Asks for information about threads, Workers or what is happening."
   :noise "Nothing to act on or answer: an acknowledgement, filler, a test message or an accidental fragment."})

(defn jev
  "{:choice :probability} for TEXT. Throws when TypeSafe does not answer."
  [text]
  (let [{:keys [status body]} (http/post "https://api.typesafe.ai/v1/systemone"
                                         {:headers {"Authorization" (str "Bearer " (auth/api-key "TYPESAFE_API_KEY" "typesafe"))
                                                    "Content-Type" "application/json"}
                                          :body (json/generate-string
                                                 {:model "jev-latest"
                                                  :state {:message text}
                                                  :questions {:kind {:type "choice"
                                                                     :instructions "The person sent this message to their coding agent's Router. What is it?"
                                                                     :criteria criteria}}})
                                          :throw false :timeout 20000})]
    (when (not= 200 status) (throw (ex-info (str "TypeSafe " status ": " body) {})))
    (let [{:keys [choice probabilities]} (get-in (json/parse-string body true) [:answers :kind])]
      {:choice (keyword choice) :probability (double (or (get probabilities (keyword choice)) 0.0))})))
