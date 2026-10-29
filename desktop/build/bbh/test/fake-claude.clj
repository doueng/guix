#!/usr/bin/env bb
(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(def config (json/parse-string (second (drop-while #(not= "--mcp-config" %) *command-line-args*)) true))
(def mcp (get-in config [:mcpServers :bbh]))
(def child (apply p/process {:extra-env (:env mcp)} (:command mcp) (:args mcp)))
(def input (io/writer (:in child)))
(def output (io/reader (:out child)))
(def pending (atom nil))
(def turns (atom 0))
(def streaming (atom false))

(defn emit [event]
  (locking *out*
    (println (json/generate-string event))
    (flush)))

(defn rpc [id method params]
  (.write input (str (json/generate-string {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
  (.flush input)
  (json/parse-string (.readLine output) true))

(rpc 1 "initialize" {:protocolVersion "2024-11-05"})
(rpc 2 "tools/list" {})
(emit {:type "system" :subtype "init" :session_id "fake-session"})

(try
  (doseq [line (line-seq (io/reader System/in))]
    (let [{:keys [type message]} (json/parse-string line true)]
      (case type
        "user"
        (case (:content message)
          "crash"
          (do (println "panic: stray stdout before dying") (flush)
              (binding [*out* *err*] (println "fatal: synthetic crash") (flush))
              (p/destroy-tree child)
              (System/exit 3))
          "stream"
          (do (reset! streaming true)
              (emit {:type "stream_event" :event {:type "content_block_delta" :delta {:type "text_delta" :text "partial"}}}))
          "fail"
          (emit {:type "result" :is_error true :subtype "error_during_execution" :errors ["synthetic provider failure"]})
          (reset! pending
                  (future
                    (let [id (str (random-uuid))
                          code (if (= "sleep" (:content message)) "(Thread/sleep 60000)" "(+ 40 2)")]
                      (emit {:type "assistant" :message {:content [{:type "tool_use" :id id :name "mcp__bbh__clojure" :input {:code code}}]}})
                      (let [result (rpc 3 "tools/call" {:name "clojure" :arguments {:code code}
                                                       :_meta {:claudecode/toolUseId id}})]
                        (emit {:type "user" :message {:content [{:type "tool_result" :tool_use_id id
                                                                 :content (get-in result [:result :content])}]}}))
                      (emit {:type "stream_event" :event {:type "content_block_delta" :delta {:type "text_delta" :text "done"}}})
                      (emit {:type "stream_event" :event {:type "message_delta"
                                                           :usage {:input_tokens 2 :cache_read_input_tokens 900
                                                                   :cache_creation_input_tokens 98 :output_tokens 7}}})
                      (emit {:type "assistant" :message {:content [{:type "text" :text "done"}]}})
                      ;; Like Claude Code, the cost covers the whole process so far.
                      (emit {:type "result" :total_cost_usd (* 0.001 (swap! turns inc))
                             :modelUsage {:fake {:contextWindow 200000}}})))))
        "control_request"
        ;; Like Claude Code, an interrupted stream reports a user event without a
        ;; tool result, then its usage so far.
        (when (compare-and-set! streaming true false)
          (emit {:type "user" :message {:content [{:type "text" :text "[Request interrupted by user]"}]}})
          (emit {:type "stream_event" :event {:type "message_delta" :usage {:input_tokens 5 :output_tokens 3}}})
          (emit {:type "result" :total_cost_usd 0}))
        nil)))
  (finally
    (when @pending (deref @pending 5000 nil))
    (p/destroy-tree child)
    (shutdown-agents)))
