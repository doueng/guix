(ns bbh.inbox-contract
  "bbh against the real Plannotator Inbox, because the bridge is not a public
  API. Run it with `bb test:inbox` after bumping the package pin. It needs
  plannotator on PATH, or PLANNOTATOR_BIN."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [bbh.inbox :as inbox]
            [bbh.models :as models]
            [bbh.repl :as repl]
            [bbh.session :as session]
            [bbh.transcript :as transcript]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures run-tests]]))

(def ^:private project (str (fs/create-temp-dir {:prefix "bbh-inbox-project"})))

(defn- wait-for [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) end)
            (Thread/sleep 50)
            (recur))))))

(defn- start-inbox []
  (let [bin (or (System/getenv "PLANNOTATOR_BIN") (some-> (fs/which "plannotator") str)
                (throw (ex-info "Set PLANNOTATOR_BIN or put plannotator on PATH" {})))
        data (System/getenv "PLANNOTATOR_DATA_DIR")
        proc (p/process {:out :discard :err :discard :dir data
                         :extra-env {"HOME" data "PLANNOTATOR_INBOX_CONNECT" "0"
                                     "PLANNOTATOR_UPDATE_CHECK" "0" "BROWSER" "/bin/false"}}
                        bin "inbox" "--no-open")]
    (when-not (wait-for inbox/connection 15000)
      (throw (ex-info "The Inbox did not start" {})))
    proc))

(use-fixtures :once (fn [t] (let [proc (start-inbox)]
                              (try (t) (finally (p/destroy-tree proc))))))

(defn- window [method path & [body]]
  (let [{:keys [port serverSession]} (inbox/connection)
        r (http/request {:method method :throw false :uri (str "http://127.0.0.1:" port path)
                         :headers {"Content-Type" "application/json"}
                         :body (when body (json/generate-string (assoc body :serverSession serverSession)))})]
    (is (= 200 (:status r)) (str path " " (:body r)))
    (json/parse-string (:body r) true)))

(defn- reply! [message-id words]
  (window :post (str "/api/inbox/messages/" message-id "/reply")
          {:words words :idempotency_key (str (random-uuid))}))

(defn- thread [id]
  (get-in (window :get (str "/api/inbox/threads/" id)) [:thread :messages]))

(defn- session-map [id] {:session id :project project})

(defn- with-poller [id f]
  (let [delivered (atom [])
        poller (inbox/start! {:session id :project project :retry-ms 50 :deliver #(swap! delivered conj %)})]
    (try (f poller delivered) (finally (inbox/stop! poller)))))

(deftest a-reply-reaches-the-session-that-asked-and-records-delivery
  (let [{:keys [message_id thread_id]} (inbox/tool! (session-map "root-1") "send_message"
                                                    {:body "Which queue?" :subject "Queue"})]
    (with-poller "root-1"
      (fn [poller delivered]
        (Thread/sleep 300)
        (reply! message_id "The retries queue")
        (is (wait-for #(seq @delivered) 5000) "a held poll returns the reply")
        (let [{:keys [id text]} (first @delivered)]
          (is (str/includes? text "The retries queue"))
          (is (str/includes? text thread_id))
          (Thread/sleep 300)
          (is (= 1 (count @delivered)) "an unacked reply repeats on the wire only")
          (inbox/retry! poller id)
          (is (wait-for #(= 2 (count @delivered)) 5000) "retry delivers it again")
          (is (nil? (:delivery (last (thread thread_id)))))
          (inbox/ack! poller id)
          (is (= "delivered" (get-in (last (thread thread_id)) [:delivery :state]))))))))

(deftest a-reply-is-kept-until-a-session-polls
  (let [{:keys [message_id]} (inbox/tool! (session-map "root-2") "send_message" {:body "Later?"})]
    (reply! message_id "Yes")
    (with-poller "root-2"
      (fn [_ delivered]
        (is (wait-for #(seq @delivered) 5000))))))

(deftest a-polling-session-is-live-and-takes-new-messages
  (let [{:keys [thread_id]} (inbox/tool! (session-map "root-3") "send_message" {:body "Note"})]
    (with-poller "root-3"
      (fn [poller delivered]
        (let [live (fn [] (->> (:projects (window :get "/api/inbox/sessions"))
                               (mapcat :sessions)
                               (filter #(= "root-3" (:session %)))
                               first))]
          (is (wait-for live 3000))
          (is (= "bbh" (:host (live))))
          (inbox/busy! poller true)
          (is (true? (:busy (live))))
          (inbox/busy! poller false)
          (is (false? (:busy (live))))
          (window :post (str "/api/inbox/threads/" thread_id "/message")
                  {:session "root-3" :body "Also this" :idempotency_key "new-message"})
          (is (wait-for #(seq @delivered) 5000))
          (is (str/includes? (:text (first @delivered)) "Also this")))))))

(defn- section-of [thread-id]
  (->> (:sections (window :get "/api/inbox/threads?archived=exclude"))
       (filter #(some (fn [t] (= thread-id (:thread_id t))) (:threads %)))
       first
       :id))

(defn- conversation [id & messages]
  (let [dir (str (fs/path (fs/create-temp-dir {:prefix "bbh-inbox-turn"}) id))]
    (doseq [m messages] (transcript/append! dir m))
    dir))

(deftest a-turn-waiting-on-the-person-opens-a-thread-they-can-answer
  (with-redefs [inbox/needs-input? (constantly true)]
    (let [poller {:session "root-4" :project project :state (atom {})}
          dir (conversation "root-4" {:role "user" :content "go"}
                            {:role "assistant" :content "I found two options.\n\nShould I use the queue?"})]
      (inbox/turn-ended! poller dir)
      (let [id (:asked @(:state poller))
            [message] (thread id)]
        (is (= "waiting" (section-of id)))
        (is (= 1 (count (:questions message))))
        (is (= "root-4" (get-in message [:author :session])))
        (inbox/prompted! poller)
        (is (nil? (:asked @(:state poller))))
        (is (nil? (section-of id)) "answering in the chat closes the thread")))))

(deftest a-wake-is-answered-in-its-thread
  (with-redefs [inbox/needs-input? (constantly false)]
    (let [{:keys [message_id thread_id]} (inbox/tool! (session-map "root-5") "send_message" {:body "Which?"})
          _ (reply! message_id "The first")
          reply (:id (last (thread thread_id)))
          dir (conversation "root-5"
                            {:role "user" :content (inbox/wake-text {:id reply :subject "s" :body "The first" :thread_id thread_id})}
                            {:role "assistant" :content "Using the first."})]
      (inbox/turn-ended! {:session "root-5" :project project :state (atom {})} dir)
      (is (= "Using the first." (:body (last (thread thread_id))))))))

(deftest every-tool-an-agent-uses-accepts-what-the-helper-sends
  (let [s (session-map "root-6")
        {:keys [thread_id message_id]} (inbox/tool! s "send_message" {:body "Needle in the haystack"})]
    (is (some? (inbox/tool! s "read_thread" {:thread_id thread_id})))
    (is (some? (inbox/tool! s "read_thread" {})))
    (is (some? (inbox/tool! s "search" {:query "Needle"})))
    (is (some? (inbox/tool! s "resolve_message" {:message_id message_id})))))

(deftest the-repl-helper-posts-as-the-session
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-inbox-repl"}))
        s (session/new-session {:dir dir :init (str (fs/absolutize "../../configs/bbh/init.clj"))
                                :cwd project :model (models/default-model)
                                :make-engine models/engine :emit (fn [_])})]
    (try
      (is (nil? (session/start-repl! s)))
      (let [{:keys [value error?]} (repl/eval! (:repl @s)
                                               "(:thread_id (inbox \"send_message\" {:body \"From the REPL\"}))"
                                               30000)
            id (read-string value)
            author (:author (first (thread id)))]
        (is (not error?))
        (is (= {:kind "agent" :host "bbh" :session (str (fs/file-name dir)) :name "bbh"} author)))
      (finally (session/close! s)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'bbh.inbox-contract)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
