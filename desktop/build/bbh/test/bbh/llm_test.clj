(ns bbh.llm-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bbh.auth :as auth]
            [bbh.llm :as llm]
            [clojure.test :refer [deftest is]]))

(deftest accumulate-joins-text-and-split-tool-arguments
  (is (= {:message {:role "assistant"
                    :content "Hello"
                    :tool_calls [{:id "c1" :type "function"
                                  :function {:name "clojure" :arguments "{\"code\":\"1\"}"}}]}
          :usage {:prompt_tokens 3}}
         (llm/accumulate [{:choices [{:delta {:content "Hel"}}]}
                          {:choices [{:delta {:content "lo"
                                              :tool_calls [{:index 0 :id "c1"
                                                            :function {:name "clojure" :arguments "{\"co"}}]}}]}
                          {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "de\":\"1\"}"}}]}}]}
                          {:usage {:prompt_tokens 3}}]))))

(deftest broken-streams-are-errors-unless-the-user-cancelled
  (let [path (str (fs/create-temp-file {:prefix "bbh-stream-test"}))
        stream (java.io.FileInputStream. path)]
    (.close stream)
    (with-redefs [http/post (fn [& _] {:status 200 :body stream})
                  auth/api-key (fn [& _] "test-key")]
      (is (thrown? java.io.IOException
                   ((llm/openrouter "test") {} {:on-text identity :on-open identity})))
      (is (= {:message {:role "assistant" :content ""} :usage nil :interrupted true}
             ((llm/openrouter "test") {} {:on-text identity :on-open (fn [cancel] (cancel))}))))))

(defn- response [status body]
  {:status status :headers {} :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))})

(def ^:private done "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n")

(deftest rate-limits-and-server-errors-are-retried
  (let [responses (atom [(response 429 "slow down") (response 503 "busy") (response 200 done)])
        notes (atom [])]
    (with-redefs [http/post (fn [& _] (let [r (first @responses)] (swap! responses rest) r))
                  auth/api-key (fn [& _] "test-key")
                  llm/retry-delays-ms [0 0 0]]
      (is (= "ok" (get-in ((llm/openrouter "test") {} {:on-text identity :on-open identity
                                                         :on-retry #(swap! notes conj %)})
                           [:message :content]))))
    (is (= ["OpenRouter 429, retrying in 0 s." "OpenRouter 503, retrying in 0 s."] @notes))))

(deftest errors-say-whether-the-context-overflowed
  (letfn [(failure [status body]
            (with-redefs [http/post (fn [& _] (response status body))
                          auth/api-key (fn [& _] "test-key")
                          llm/retry-delays-ms [0]]
              (try ((llm/openrouter "test") {} {:on-text identity :on-open identity})
                   (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) :message (ex-message e))))))]
    (is (= {:status 400 :overflow true :message "OpenRouter 400: This endpoint's maximum context length is 1000 tokens"}
           (failure 400 "This endpoint's maximum context length is 1000 tokens")))
    (is (= {:status 400 :overflow false :message "OpenRouter 400: bad tool schema"} (failure 400 "bad tool schema")))
    (is (= {:status 429 :overflow false :message "OpenRouter 429: still slow"} (failure 429 "still slow")))))

(deftest successful-streams-release-the-response-body
  (let [path (str (fs/create-temp-file {:prefix "bbh-stream-test"}))
        _ (spit path "data: {\"choices\":[{\"delta\":{\"content\":\"hello world\"}}]}\n\ndata: [DONE]\n")
        stream (java.io.FileInputStream. path)
        text (atom [])]
    (with-redefs [http/post (fn [& _] {:status 200 :body stream})
                  auth/api-key (fn [& _] "test-key")]
      (is (= "hello world" (get-in ((llm/openrouter "test") {}
                                     {:on-text #(swap! text conj %) :on-open identity})
                                    [:message :content])))
      (is (= ["hello world"] @text))
      (is (thrown? java.io.IOException (.read stream))))))
