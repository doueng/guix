(ns bbh.contracts
  (:require [babashka.fs :as fs]
            [bbh.compare :as c]
            [bbh.tasks :as tasks]
            [clojure.string :as str]))

(def profiles [:pi :pi-sci :bbh])

(defn call [profile shell code sci]
  (case profile
    :pi {:name "bash" :arguments {:command shell}}
    :pi-sci {:name "codemode" :arguments {:code sci}}
    :bbh {:code code}))

(defn text [profile events]
  (apply str
         (if (= profile :bbh)
           (keep #(when (= "agent_message_chunk" (get-in % [:params :update :sessionUpdate]))
                    (get-in % [:params :update :content :text])) events)
           (keep #(when (= "text_delta" (get-in % [:assistantMessageEvent :type]))
                    (get-in % [:assistantMessageEvent :delta])) events))))

(defn check! [predicate label]
  (when-not predicate (throw (ex-info label {}))))

(defn workspace! [ws path]
  (loop [n 500]
    (cond (fs/exists? (fs/path ws path)) true
          (zero? n) (throw (ex-info "Tool did not start" {:ws ws :path path}))
          :else (do (Thread/sleep 10) (recur (dec n))))))

(def cases
  [{:id "whitespace-unicode"
    :script (fn [_] ["alpha \n\nλ 🥔 omega\n"])
    :run (fn [client sid profile]
           (c/prompt! client sid "stream the fixture" 30)
           (check! (= "alpha \n\nλ 🥔 omega\n" (text profile @(:events client)))
                   "Streaming text changed whitespace or Unicode"))}
   {:id "tool-error-recovery"
    :script (fn [profile]
              [(call profile "exit 1" "(throw (ex-info \"synthetic failure\" {}))" "(throw (ex-info \"synthetic failure\" {}))")
               (call profile "printf recovered > recovered.txt" "(spit \"recovered.txt\" \"recovered\")"
                     "(await (tools/write {:path \"recovered.txt\" :content \"recovered\"}))")
               "RECOVERED"])
    :run (fn [client sid profile]
           (c/prompt! client sid "recover" 30)
           (check! (= "recovered" (tasks/read-file (:ws client) "recovered.txt")) "Recovery file missing")
           (check! (= "RECOVERED" (text profile @(:events client))) "Recovery text missing")
           (check! (= 1 (:tool-errors (c/metrics profile @(:events client)))) "Failed tool was not reported"))}
   {:id "bad-model-recovery"
    :script (fn [_] ["ALIVE"])
    :run (fn [client sid profile]
           (let [id (c/send! client (if (= :bbh profile)
                                     {:jsonrpc "2.0" :method "session/set_model"
                                      :params {:sessionId sid :modelId "missing/model"}}
                                     {:type "set_model" :provider "missing" :modelId "model"}))
                 response (c/wait! client #(= id (:id %)) 15)]
             (check! (or (:error response) (= false (:success response))) "Invalid model accepted"))
           (c/prompt! client sid "still alive" 30)
           (check! (= "ALIVE" (text profile @(:events client))) "Agent failed after invalid model"))}
   {:id "overlap-cancel-recover"
    :script (fn [profile]
              [(call profile "printf yes > started.txt; while :; do printf tick >> heartbeat.txt; sleep 0.1; done"
                     "(spit \"started.txt\" \"yes\") (loop [] (spit \"heartbeat.txt\" \"tick\" :append true) (Thread/sleep 100) (recur))"
                     "(await (tools/bash {:command \"printf yes > started.txt; while :; do printf tick >> heartbeat.txt; sleep 0.1; done\"}))")
               "NEXT-TURN"])
    :run (fn [client sid profile]
           (let [prompt (if (= :bbh profile)
                          {:jsonrpc "2.0" :method "session/prompt" :params {:sessionId sid :prompt [{:type "text" :text "sleep"}]}}
                          {:type "prompt" :message "sleep"})
                 first-id (c/send! client prompt)]
             (workspace! (:ws client) "started.txt")
             (let [id (c/send! client prompt)
                   response (c/wait! client #(= id (:id %)) 15)]
               (check! (or (:error response) (= false (:success response))) "Overlapping prompt was accepted"))
             (if (= :bbh profile)
               (do (c/send! client {:jsonrpc "2.0" :method "session/cancel" :params {:sessionId sid}})
                   (check! (= "cancelled" (get-in (c/wait! client #(= first-id (:id %)) 15) [:result :stopReason]))
                           "Cancellation not acknowledged"))
               (do (c/rpc! client {:type "abort"})
                   (check! (some #(and (= "agent_settled" (:type %)) (:aborted %)) @(:events client))
                           "Pi did not settle as aborted")))
             (let [before (tasks/read-file (:ws client) "heartbeat.txt")]
               (Thread/sleep 350)
               (check! (= before (tasks/read-file (:ws client) "heartbeat.txt"))
                       "Cancelled tool kept writing its heartbeat"))
             (c/prompt! client sid "next turn" 30)
             (check! (str/ends-with? (text profile @(:events client)) "NEXT-TURN") "Next turn did not recover")))}])

(defn run! [out case profile]
  (let [dir (str (fs/path out (str (:id case) "__" (name profile))))
        ws (str (fs/path dir "ws"))]
    (fs/create-dirs ws)
    (let [client (c/start! profile dir ws "script" ((:script case) profile))
          result (try
                   ((:run case) client (c/initialize! client) profile)
                   {:passed true}
                   (catch Exception e {:passed false :error (ex-message e) :data (ex-data e)})
                   (finally (c/stop! client)))
          record (merge {:case (:id case) :profile profile :dir dir} result)]
      (c/json! (fs/path dir "result.json") record)
      (println (if (:passed record) "PASS" "FAIL") (:id case) (name profile) (:error record))
      record)))

(defn -main [& [out repetitions]]
  (let [out (str (fs/absolutize (or out (str (fs/create-temp-dir {:prefix "bbh-contracts"})))))
        _ (fs/create-dirs out)
        _ (when (fs/exists? (fs/path out "results.json"))
            (throw (ex-info "Refusing to overwrite contract results" {:out out})))
        repetitions (parse-long (or repetitions "3"))
        _ (when-not (pos-int? repetitions)
            (throw (ex-info "Repetitions must be a positive integer" {:repetitions repetitions})))
        results (vec (for [repetition (range 1 (inc repetitions)) case cases profile profiles]
                       (assoc (run! (str (fs/path out (str "round-" repetition))) case profile)
                              :repetition repetition)))]
    (c/json! (fs/path out "results.json") results)
    (println "Artifacts:" out)
    (shutdown-agents)
    (System/exit (if (every? :passed results) 0 1))))
