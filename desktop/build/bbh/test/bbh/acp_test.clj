(ns bbh.acp-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defn- client [steps]
  (let [dir (fs/create-temp-dir {:prefix "bbh-acp-test"})
        script (str (fs/path dir "script.edn"))
        init (str (fs/path dir "init.clj"))
        _ (spit script (pr-str steps))
        _ (spit init "")
        proc (p/process {:extra-env {"XDG_DATA_HOME" (str dir) "BBH_INIT" init
                                     "BBH_MODEL" (str "script/" script)}
                         :err :string}
                        "bb" "-m" "bbh.main" "acp")
        queue (LinkedBlockingQueue.)]
    (future
      (try
        (doseq [line (line-seq (io/reader (:out proc)))]
          (.put queue (json/parse-string line true)))
        (finally (.put queue {:eof true}))))
    {:proc proc :in (io/writer (:in proc)) :queue queue :dir (str dir)}))

(defn- send! [{:keys [in]} id method params]
  (.write in (str (json/generate-string {:jsonrpc "2.0" :id id :method method :params params}) "\n"))
  (.flush in))

(defn- until! [{:keys [queue]} pred]
  (loop [seen []]
    (let [msg (.poll queue 15 TimeUnit/SECONDS)]
      (when (or (nil? msg) (:eof msg))
        (throw (ex-info "ACP ended or timed out" {:seen seen :last msg})))
      (let [seen (conj seen msg)]
        (if (pred msg) seen (recur seen))))))

(defn- response! [c id] (last (until! c #(= id (:id %)))))

(defn- start! [c]
  (send! c 1 "initialize" {:protocolVersion 1})
  (is (= 1 (get-in (response! c 1) [:result :protocolVersion])))
  (send! c 2 "session/new" {:cwd (:dir c)})
  (get-in (response! c 2) [:result :sessionId]))

(defn- stop! [{:keys [proc in]}]
  (try (.close in) (catch java.io.IOException _))
  (when (= ::timeout (deref proc 5000 ::timeout)) (p/destroy-tree proc)))

(deftest whitespace-chunks-reach-the-client
  (let [c (client [" "])]
    (try
      (let [sid (start! c)]
        (send! c 3 "session/prompt" {:sessionId sid :prompt [{:type "text" :text "space"}]})
        (let [events (until! c #(= 3 (:id %)))]
          (is (= [" "] (keep #(get-in % [:params :update :content :text]) events)))
          (is (= "end_turn" (get-in (last events) [:result :stopReason])))))
      (finally (stop! c)))))

(deftest resume-continues-a-saved-conversation-under-the-same-session
  (let [c (client ["continued"])
        root (fs/path (:dir c) "bbh" "conversations")
        old (fs/create-dirs (fs/path root "20200101-000000-000"))]
    (spit (str (fs/path old "messages.jsonl"))
          (str (json/generate-string {:role "user" :content "hello"}) "\n"
               (json/generate-string {:role "assistant" :content "Hi."}) "\n"))
    (try
      (let [sid (start! c)
            commands (until! c #(= "available_commands_update" (get-in % [:params :update :sessionUpdate])))]
        (is (some #{"resume"} (map :name (get-in (last commands) [:params :update :availableCommands]))))
        (send! c 3 "session/prompt" {:sessionId sid :prompt [{:type "text" :text "/resume last"}]})
        (is (= ["Resumed 20200101-000000-000 with 2 messages.\nLast reply: Hi."]
               (keep #(get-in % [:params :update :content :text]) (until! c #(= 3 (:id %))))))
        (send! c 4 "session/prompt" {:sessionId sid :prompt [{:type "text" :text "and?"}]})
        (is (= ["continued"] (keep #(get-in % [:params :update :content :text]) (until! c #(= 4 (:id %))))))
        (is (= 4 (count (fs/read-all-lines (str (fs/path old "messages.jsonl"))))))
        (is (fs/exists? (fs/path root sid "repl.port"))))
      (finally (stop! c)))))

(deftest bad-requests-do-not-kill-the-agent
  (let [c (client [])]
    (try
      (let [sid (start! c)]
        (send! c 3 "session/set_model" {:sessionId sid :modelId "missing/model"})
        (is (map? (:error (response! c 3))))
        (send! c 4 "session/prompt" {:sessionId "missing" :prompt [{:type "text" :text "/port"}]})
        (is (= -32602 (get-in (response! c 4) [:error :code])))
        (send! c 5 "session/prompt" {:sessionId sid :prompt [{:type "image" :data "not-an-image"}]})
        (is (= -32602 (get-in (response! c 5) [:error :code])))
        (send! c 6 "session/prompt" {:sessionId sid :prompt [{:type "text" :text "/port"}]})
        (is (= "end_turn" (get-in (response! c 6) [:result :stopReason]))))
      (finally (stop! c)))))

(deftest overlapping-turns-are-rejected-and-cancellation-recovers
  (let [c (client [{:code "(Thread/sleep 60000)"} "recovered"])]
    (try
      (let [sid (start! c)
            prompt {:sessionId sid :prompt [{:type "text" :text "sleep"}]}]
        (send! c 3 "session/prompt" prompt)
        (until! c #(= "tool_call" (get-in % [:params :update :sessionUpdate])))
        (send! c 4 "session/prompt" prompt)
        (is (= -32000 (get-in (response! c 4) [:error :code])))
        (send! c 5 "session/set_model" {:sessionId sid :modelId (str "script/" (fs/path (:dir c) "script.edn"))})
        (is (= -32000 (get-in (response! c 5) [:error :code])))
        (send! c nil "session/cancel" {:sessionId sid})
        (is (= "cancelled" (get-in (response! c 3) [:result :stopReason])))
        (send! c 6 "session/prompt" (assoc prompt :prompt [{:type "text" :text "again"}]))
        (let [events (until! c #(= 6 (:id %)))]
          (is (= ["recovered"] (keep #(get-in % [:params :update :content :text]) events)))
          (is (= "end_turn" (get-in (last events) [:result :stopReason])))))
      (finally (stop! c)))))
