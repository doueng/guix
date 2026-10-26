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
          :usage {:input 3 :cached 0 :written 0 :output 0 :thinking 0 :cost nil}}
         (llm/accumulate [{:choices [{:delta {:content "Hel"}}]}
                          {:choices [{:delta {:content "lo"
                                              :tool_calls [{:index 0 :id "c1"
                                                            :function {:name "clojure" :arguments "{\"co"}}]}}]}
                          {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "de\":\"1\"}"}}]}}]}
                          {:usage {:prompt_tokens 3}}]))))

(deftest a-reply-stopped-at-the-output-limit-is-truncated
  (is (:truncated (llm/accumulate [{:choices [{:delta {:content "Hel"}}]}
                                   {:choices [{:delta {} :finish_reason "length"}]}])))
  (is (:truncated (llm/accumulate [{:choices [{:delta {} :finish_reason "tool_calls" :native_finish_reason "length"}]}])))
  (is (nil? (:truncated (llm/accumulate [{:choices [{:delta {:content "Hi"} :finish_reason "stop"}]}])))))

(deftest broken-streams-are-errors-unless-the-user-cancelled
  (let [path (str (fs/create-temp-file {:prefix "bbh-stream-test"}))
        stream (java.io.FileInputStream. path)]
    (.close stream)
    (with-redefs [http/post (fn [& _] {:status 200 :body stream})
                  auth/api-key (fn [& _] "test-key")
                  llm/model-info (constantly nil)]
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
                  llm/model-info (constantly nil)
                  llm/retry-delays-ms [0 0 0]]
      (is (= "ok" (get-in ((llm/openrouter "test") {} {:on-text identity :on-open identity
                                                         :on-retry #(swap! notes conj %)})
                           [:message :content]))))
    (is (= ["OpenRouter 429, retrying in 0 s." "OpenRouter 503, retrying in 0 s."] @notes))))

(deftest errors-say-whether-the-context-overflowed
  (letfn [(failure [status body]
            (with-redefs [http/post (fn [& _] (response status body))
                          auth/api-key (fn [& _] "test-key")
                  llm/model-info (constantly nil)
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
                  auth/api-key (fn [& _] "test-key")
                  llm/model-info (constantly nil)]
      (is (= "hello world" (get-in ((llm/openrouter "test") {}
                                     {:on-text #(swap! text conj %) :on-open identity})
                                    [:message :content])))
      (is (= ["hello world"] @text))
      (is (thrown? java.io.IOException (.read stream))))))

(deftest usage-reports-cache-reads-writes-and-reasoning
  (is (= {:input 10339 :cached 10318 :written 0 :output 60 :thinking 12 :cost 0.002}
         (:usage (llm/accumulate [{:usage {:prompt_tokens 10339 :completion_tokens 60 :cost 0.002
                                           :prompt_tokens_details {:cached_tokens 10318 :cache_write_tokens 0}
                                           :completion_tokens_details {:reasoning_tokens 12}}}])))))

(deftest streamed-reasoning-details-are-joined-by-index-for-the-next-request
  (is (= [{:type "reasoning.text" :text "Let me think" :signature "sig" :format "anthropic-claude-v1" :index 0}
          {:type "reasoning.encrypted" :data "abc" :index 1}]
         (get-in (llm/accumulate
                  [{:choices [{:delta {:reasoning_details [{:type "reasoning.text" :text "Let me " :signature nil
                                                            :format "anthropic-claude-v1" :index 0}]}}]}
                   {:choices [{:delta {:reasoning_details [{:type "reasoning.text" :text "think" :index 0}]}}]}
                   {:choices [{:delta {:reasoning_details [{:type "reasoning.text" :text "" :signature "sig" :index 0}
                                                           {:type "reasoning.encrypted" :data "abc" :index 1}]}}]}])
                 [:message :reasoning_details]))))

(deftest effort-falls-back-to-the-nearest-supported-level
  (is (= "medium" (llm/pick-effort "medium" ["max" "xhigh" "high" "medium" "low"])))
  (is (= "high" (llm/pick-effort "medium" ["max" "high" "low" "none"])))
  (is (= "low" (llm/pick-effort "minimal" ["max" "high" "low" "none"])))
  (is (= "medium" (llm/pick-effort "medium" nil))))

(deftest requests-carry-effort-output-room-session-and-caching
  (let [req {:messages [] :effort "medium" :session-id "20261024-1"}
        opus {:size 1000000 :max-output 128000 :efforts ["max" "xhigh" "high" "medium" "low"]}
        deepseek {:size 1048576 :max-output 943718 :efforts ["max" "high" "low" "none"]}]
    (is (= {:model "anthropic/claude-opus-5.5" :messages [] :stream true :usage {:include true}
            :reasoning {:effort "medium"} :max_tokens 128000 :session_id "20261024-1"
            :cache_control {:type "ephemeral"}}
           (llm/request-body "anthropic/claude-opus-5.5" opus req)))
    (is (= {:model "deepseek/deepseek-v4.1-flash" :messages [] :stream true :usage {:include true}
            :reasoning {:effort "high"} :max_tokens 128000 :session_id "20261024-1"}
           (llm/request-body "deepseek/deepseek-v4.1-flash" deepseek req)))
    (is (= 4000 (:max_tokens (llm/request-body "x/y" opus (assoc req :max-tokens 4000)))))
    (is (not (contains? (llm/request-body "x/y" {:efforts nil} req) :reasoning)))
    (is (= {:effort "medium"} (:reasoning (llm/request-body "x/y" {:efforts :any} req))))))

(deftest reasoning-text-streams-as-thoughts
  (let [thoughts (atom [])]
    (with-redefs [http/post (fn [& _] (response 200 (str "data: {\"choices\":[{\"delta\":{\"reasoning\":\"hmm\"}}]}\n\n" done)))
                  auth/api-key (fn [& _] "test-key")
                  llm/model-info (constantly {:size 1000})]
      (is (= {:message {:role "assistant" :content "ok"} :usage nil}
             ((llm/openrouter "test") {} {:on-text identity :on-open identity :on-thought #(swap! thoughts conj %)}))))
    (is (= ["hmm"] @thoughts))))
