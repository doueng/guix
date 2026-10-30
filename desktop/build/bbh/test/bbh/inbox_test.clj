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
        stop (server/run-server (fn [{:keys [body]}]
                                  (reset! seen (json/parse-string (slurp body) true))
                                  {:status 200 :headers {"Content-Type" "text/event-stream"}
                                   :body (str "event: message\ndata: "
                                              (json/generate-string {:result {:structuredContent {:message_id "m9"}}})
                                              "\n\n")})
                                {:port 0 :ip "127.0.0.1" :legacy-return-value? false})]
    (try
      (with-redefs [inbox/connection (constantly {:port (server/server-port stop) :token "t"})]
        (is (= {:message_id "m9"}
               (inbox/tool! {:session "root" :project "/p"} "send_message"
                            {:body "hi" :agent_session "forged"}))))
      (is (= {:body "hi" :project_path "/p" :agent_session "root" :agent_host "bbh" :agent_name "bbh"}
             (get-in @seen [:params :arguments])))
      (finally (server/server-stop! stop)))))

(deftest a-missing-inbox-is-a-clear-error
  (with-redefs [inbox/connection (constantly nil)]
    (is (thrown-with-msg? Exception #"not running"
                          (inbox/tool! {:session "root" :project "/p"} "read_thread" {})))))
