(ns bbh.codex-test
  (:require [bbh.hermetic :as hermetic]
            [bbh.auth :as auth]
            [bbh.codex :as codex]
            [bbh.llm :as llm]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(def ^:private tool
  {:type "function"
   :function {:name "clojure" :description "Evaluate Clojure."
              :parameters {:type "object" :properties {:code {:type "string"}}
                           :required ["code"]}}})

(def ^:private transcript
  [{:role "system" :content "You are bbh."}
   {:role "user" :content "What is 2+2?"}
   {:role "assistant" :content "" :tool_calls [{:id "c1" :type "function"
                                                  :function {:name "clojure" :arguments "{}"}}]}
   {:role "tool" :tool_call_id "c1" :content "4"}])

(deftest a-chat-request-becomes-instructions-and-input-items
  (is (= {:model "gpt-6.1-sol"
          :instructions "You are bbh."
          :input [{:type "message" :role "user" :content [{:type "input_text" :text "What is 2+2?"}]}
                  {:type "function_call" :call_id "c1" :name "clojure" :arguments "{}"}
                  {:type "function_call_output" :call_id "c1" :output "4"}]
          :stream true
          :store false
          :tools [{:name "clojure" :description "Evaluate Clojure."
                   :parameters {:type "object" :properties {:code {:type "string"}}
                                :required ["code"]}
                   :type "function"}]
          :reasoning {:effort "medium"}
          :max_output_tokens 4000}
         (codex/request "gpt-6.1-sol" {:messages transcript :tools [tool] :effort "medium"
                                         :max-tokens 4000 :session-id "20261102-1"})))
  (is (= {:model "m" :instructions "" :input [] :stream true :store false}
         (codex/request "m" {:messages []}))))

(deftest streamed-events-become-a-reply
  (is (= {:message {:role "assistant" :content "Hello"
                    :tool_calls [{:id "c1" :type "function"
                                  :function {:name "clojure" :arguments "{}"}}]}
          :usage {:input 10 :cached 4 :written 0 :output 5 :thinking 2 :cost nil}}
         (codex/accumulate
          [{:type "response.output_item.added" :item {:type "message"}}
           {:type "response.output_text.delta" :delta "Hel"}
           {:type "response.output_text.delta" :delta "lo"}
           {:type "response.output_item.done"
            :item {:type "function_call" :call_id "c1" :name "clojure" :arguments "{}"}}
           {:type "response.completed"
            :response {:incomplete_details nil
                       :usage {:input_tokens 10 :output_tokens 5
                               :input_tokens_details {:cached_tokens 4 :cache_write_tokens 0}
                               :output_tokens_details {:reasoning_tokens 2}}}}]))))

(deftest a-reply-cut-off-at-the-output-limit-is-truncated
  (is (:truncated (codex/accumulate
                   [{:type "response.output_text.delta" :delta "partial"}
                    {:type "response.completed"
                     :response {:incomplete_details {:reason "max_output_tokens"}}}])))
  (is (nil? (:truncated (codex/accumulate
                         [{:type "response.completed" :response {:incomplete_details nil}}]))))
  (let [r (codex/accumulate [{:type "response.output_text.delta" :delta "partial"}
                             {:type "response.incomplete"
                              :response {:status "incomplete" :incomplete_details {:reason "max_output_tokens"}
                                         :usage {:input_tokens 10 :output_tokens 5}}}])]
    (is (:truncated r) "the Responses API ends a cut-off reply with response.incomplete")
    (is (= 10 (get-in r [:usage :input])))))

(defn- stream [body]
  {:status 200 :headers {}
   :body (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))})

(def ^:private reply (str "event: response.created\ndata: {\"type\":\"response.created\"}\n\n"
                          "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n"
                          "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}\n"))

(defn- subscription []
  (with-redefs [auth/pi-entry (fn [_] {:access "test-token" :accountId "acct"
                                       :expires (+ (System/currentTimeMillis) 60000)})]
    ((codex/sample "gpt-6.1-sol") {:messages [{:role "user" :content "hi"}]}
     {:on-text identity :on-open identity})))

(deftest a-subscription-turn-streams-its-text
  (let [text (atom [])]
    (with-redefs [llm/stream! (fn [label url headers body cancelled on-retry]
                                (is (= ["Codex" "https://chatgpt.com/backend-api/codex/responses"] [label url]))
                                (is (= "Bearer test-token" (get headers "Authorization")))
                                (is (= "acct" (get headers "chatgpt-account-id")))
                                (is (= "20261102-cache" (get headers "session_id")))
                                (stream reply))]
      (with-redefs [auth/pi-entry (fn [_] {:access "test-token" :accountId "acct"
                                           :expires (+ (System/currentTimeMillis) 60000)})]
        (is (= {:message {:role "assistant" :content "Hi"}
                :usage {:input 7 :cached 0 :written 0 :output 1 :thinking 0 :cost nil}}
               ((codex/sample "gpt-6.1-sol") {:messages [{:role "user" :content "hi"}]
                                                :session-id "20261102-cache"}
                {:on-text #(swap! text conj %) :on-open identity})))))
    (is (= ["Hi"] @text))))

(deftest an-error-event-fails-the-turn
  (with-redefs [llm/stream! (fn [& _] (stream "data: {\"type\":\"error\",\"error\":{\"message\":\"quota\"}}\n"))
                auth/pi-entry (fn [_] {:access "t" :accountId "a" :expires (+ (System/currentTimeMillis) 60000)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Codex: quota"
                          ((codex/sample "gpt-6.1-sol") {:messages []}
                           {:on-text identity :on-open identity})))))

(defn- breaking-stream
  "A response body that delivers PREFIX and then fails, as closing the connection
  under a read does."
  [prefix]
  (let [sent (atom false)
        buf (.getBytes ^String prefix "UTF-8")]
    (proxy [java.io.InputStream] []
      (read
        ([]
         (throw (java.io.IOException. "cancelled")))
        ([b off len]
         (if @sent
           (throw (java.io.IOException. "cancelled"))
           (do (reset! sent true)
               (let [n (min len (alength buf))]
                 (System/arraycopy buf 0 b off n)
                 n)))))
      (close [] nil)
      (available [] 0))))

(deftest a-cancelled-turn-keeps-its-partial-reply
  (with-redefs [llm/stream! (fn [& _] {:status 200 :headers {}
                                       :body (breaking-stream "data: {\"type\":\"response.output_text.delta\",\"delta\":\"par\"}\n")})
                auth/pi-entry (fn [_] {:access "t" :accountId "a" :expires (+ (System/currentTimeMillis) 60000)})]
    (is (= {:message {:role "assistant" :content "par"} :usage nil :interrupted true}
           ((codex/sample "gpt-6.1-sol") {:messages []}
            {:on-text identity :on-open (fn [cancel] (cancel))})))))
