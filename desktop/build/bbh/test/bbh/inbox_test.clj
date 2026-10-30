(ns bbh.inbox-test
  (:require [bbh.inbox :as inbox]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as server]))

(defn- wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 300) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

(defn- with-bridge
  "Run F with a fake bridge whose poll answers @PENDING, recording every request."
  [f]
  (let [pending (atom [])
        calls (atom [])
        handler (fn [{:keys [uri body]}]
                  (let [req (json/parse-string (slurp body) true)]
                    (swap! calls conj (assoc req :uri uri))
                    {:status 200 :headers {"Content-Type" "application/json"}
                     :body (json/generate-string (if (str/ends-with? uri "/poll")
                                                   {:commands @pending}
                                                   {:ok true}))}))
        stop (server/run-server handler {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        conn {:port (server/server-port stop) :token "t"}]
    (try
      (with-redefs [inbox/connection (constantly conn)]
        (f pending calls))
      (finally (server/server-stop! stop)))))

(defn- poller [delivered]
  (inbox/start! {:session "root" :project "/p" :wait-ms 0 :retry-ms 5
                 :deliver #(swap! delivered conj %)}))

(def ^:private command {:type "reply" :id "m1" :thread_id "t1" :subject "Queue" :body "Use the retries queue."})

(deftest a-wake-names-the-reply-and-how-to-answer-it
  (let [text (inbox/wake-text command)]
    (is (str/starts-with? text "Plannotator Inbox: Queue (m1)\n\nUse the retries queue.\n\n"))
    (is (str/includes? text "thread t1"))
    (is (str/includes? text "{:reply_to \"m1\""))))

(deftest a-command-is-delivered-once-until-it-is-retried
  (with-bridge
    (fn [pending calls]
      (let [delivered (atom [])
            p (poller delivered)]
        (reset! pending [command])
        (is (wait-for #(= 1 (count @delivered))))
        (is (= "m1" (:id (first @delivered))))
        (is (= (inbox/wake-text command) (:text (first @delivered))))
        (let [polls (count @calls)]
          (is (wait-for #(> (count @calls) (+ polls 3))))
          (is (= 1 (count @delivered))))
        (inbox/retry! p "m1")
        (is (wait-for #(= 2 (count @delivered))))
        (inbox/stop! p)))))

(deftest ack-and-busy-reach-the-bridge-as-this-session
  (with-bridge
    (fn [_ calls]
      (let [p (poller (atom []))]
        (is (wait-for #(some (fn [c] (str/ends-with? (:uri c) "/poll")) @calls)))
        (inbox/ack! p "m1")
        (inbox/busy! p true)
        (inbox/busy! p false)
        (inbox/stop! p)
        (let [events (filter #(str/ends-with? (:uri %) "/event") @calls)]
          (is (= [["delivered" nil] ["state" true] ["state" false]]
                 (map (juxt :type :busy) events)))
          (is (every? #(= ["root" "bbh"] [(:session %) (:host %)]) events)))
        (let [poll (first (filter #(str/ends-with? (:uri %) "/poll") @calls))]
          (is (= "/p" (:project_path poll)))
          (is (false? (:busy poll))))))))

(deftest a-stopped-inbox-does-not-end-the-poller
  (let [delivered (atom [])
        up (atom false)]
    (with-bridge
      (fn [pending _]
        (reset! pending [command])
        (let [real (inbox/connection)]
          (with-redefs [inbox/connection #(when @up real)]
            (let [p (poller delivered)]
              (Thread/sleep 50)
              (is (empty? @delivered))
              (reset! up true)
              (is (wait-for #(= 1 (count @delivered))))
              (inbox/stop! p))))))))

(deftest a-stopped-poller-delivers-nothing
  (with-bridge
    (fn [pending calls]
      (let [delivered (atom [])
            p (poller delivered)]
        (is (wait-for #(seq @calls)))
        (inbox/stop! p)
        (reset! pending [command])
        (Thread/sleep 50)
        (is (empty? @delivered))))))

(deftest a-tool-call-fills-in-who-is-asking
  (let [seen (atom nil)
        schema {:properties {:body {} :project_path {} :agent_session {} :agent_host {} :agent_name {}}}
        stop (server/run-server (fn [{:keys [body]}]
                                  (let [{:keys [method] :as req} (json/parse-string (slurp body) true)]
                                    (when (= "tools/call" method) (reset! seen req))
                                    {:status 200 :headers {"Content-Type" "text/event-stream"}
                                     :body (str "event: message\ndata: "
                                                (json/generate-string
                                                 (if (= "tools/list" method)
                                                   {:result {:tools [{:name "send_message" :inputSchema schema}
                                                                     {:name "resolve_message"
                                                                      :inputSchema {:properties {:message_id {}}}}]}}
                                                   {:result {:structuredContent {:message_id "m9"}}}))
                                                "\n\n")}))
                                {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try
      (with-redefs [inbox/connection (constantly {:port (server/server-port stop) :token "t"})]
        (is (= {:message_id "m9"}
               (inbox/tool! {:session "root" :project "/p"} "send_message"
                            {:body "hi" :agent_session "forged"}))))
      (is (= {:body "hi" :project_path "/p" :agent_session "root" :agent_host "bbh" :agent_name "bbh"}
             (get-in @seen [:params :arguments])))
      (with-redefs [inbox/connection (constantly {:port (server/server-port stop) :token "t"})]
        (inbox/tool! {:session "root" :project "/p"} "resolve_message" {:message_id "m9"}))
      (is (= {:message_id "m9"} (get-in @seen [:params :arguments]))
          "a tool that takes no identity gets none")
      (finally (server/server-stop! stop)))))

(deftest a-missing-inbox-is-a-clear-error
  (with-redefs [inbox/connection (constantly nil)]
    (is (thrown-with-msg? Exception #"not running"
                          (inbox/tool! {:session "root" :project "/p"} "read_thread" {})))))

(defn- call [code]
  {:id "c" :type "function" :function {:name "clojure" :arguments (json/generate-string {:code code})}})

(deftest a-turn-is-read-from-the-messages-after-the-last-user-message
  (let [wake (inbox/wake-text (assoc command :id "msg_01ABC"))
        messages [{:role "user" :content "older"}
                  {:role "assistant" :content "older answer"}
                  {:role "user" :content (str "[bbh] A note.\n\n" wake)}
                  {:role "assistant" :content "" :tool_calls [(call "(+ 1 2)")]}
                  {:role "tool" :content "=> 3"}
                  {:role "assistant" :content " Which queue? "}]]
    (is (= {:text "Which queue?" :wake "msg_01ABC" :posted? false} (inbox/last-turn messages)))
    (is (false? (:posted? (inbox/last-turn (conj messages {:role "assistant" :content ""
                                                           :tool_calls [(call "(grep \"send_message\")")]})))))
    (is (true? (:posted? (inbox/last-turn (conj messages {:role "assistant" :content ""
                                                          :tool_calls [(call "(inbox \"send_message\" {:body \"x\"})")]})))))
    (is (nil? (:wake (inbox/last-turn [{:role "user" :content "plain"}]))))))

(deftest what-reaches-the-inbox-depends-on-the-ask-and-the-wake
  (with-redefs [inbox/needs-input? #(str/includes? % "ASK")]
    (is (nil? (inbox/turn-message {:text "Done."})) "a status report stays in the chat")
    (is (nil? (inbox/turn-message {})) "a turn with no text has nothing to post")
    (is (nil? (inbox/turn-message {:text "ASK" :posted? true})) "the agent already posted")
    (let [m (inbox/turn-message {:text "**Plan** ready.\n\n- Should I ASK them?\n\nDetails."})]
      (is (= "Should I ASK them?" (:subject m)))
      (is (str/ends-with? (:body m) ":::question-text\nYour answer\n:::"))
      (is (nil? (:reply_to m))))
    (let [m (inbox/turn-message {:text "Done." :wake "m1"})]
      (is (= {:body "Done." :subject "Done." :reply_to "m1"} m) "a wake's answer goes back as plain text"))
    (is (= "m1" (:reply_to (inbox/turn-message {:text "ASK" :wake "m1"}))))))

(deftest without-jev-a-trailing-question-mark-decides
  (with-redefs [babashka.http-client/post (fn [& _] (throw (ex-info "offline" {})))]
    (is (inbox/needs-input? "Should I go ahead?\n"))
    (is (not (inbox/needs-input? "Done.")))))
