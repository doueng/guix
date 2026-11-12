(ns bbh.super-test
  (:require [bbh.hermetic :as hermetic]
            [babashka.fs :as fs]
            [bbh.super :as super]
            [bbh.super.client :as client]
            [bbh.super.pane :as pane]
            [bbh.super.process :as process]
            [bbh.super.sandbox :as sandbox]
            [bbh.super.voice :as voice]
            [babashka.process]
            [cheshire.core]
            [bbh.super.model :as model]
            [bbh.super.notify :as notify]
            [bbh.super.store :as store]
            [bbh.super.workspace :as workspace]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 300) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

(def ^:private opened
  "Controller dir to the open! specs its stub launch saw, each with the token."
  (atom {}))

(def ^:private controllers "Controller dir to the controller." (atom {}))

(defn- stub-launch
  "A launch that makes plain directories under WORKSPACES and records each
  open! for DIR, so a Worker runs on a real wf run with no jj or Emacs."
  [dir workspaces]
  {:workspace! (fn [_ w] (str (fs/create-dirs (fs/path workspaces (str (:thread w) "-" (:generation w))))))
   :open! (fn [spec] (swap! opened update dir (fnil conj []) spec) (str "buf-" (:conversation spec)))
   :land! (fn [_] {:landed nil}) :retire! (constantly nil) :release! (constantly nil)})

(defn- with-controller [f]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-conv"}))
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :launch (stub-launch dir (str (fs/create-temp-dir {:prefix "bbh-super-ws"})))})]
    (swap! controllers assoc dir c)
    (try (f c dir conversations)
         (finally (super/stop! c) (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(defn- call [dir request] (client/call! dir request))

(defn- run-worker
  "Start a Worker on THREAD under a controller from with-controller and take
  its brief from the mailbox, as its session would. Returns its token."
  [dir thread]
  (let [c (get @controllers dir)
        w (:id (:ok (call dir {:command :start-worker! :args {:thread thread :workflow :investigation :brief "look" :repo (str (fs/home))}})))
        mailbox (fs/path (:conversations c) (str "super-" w) "mailbox")]
    (wait-for #(seq (fs/glob mailbox "*.json")))
    (doseq [f (fs/glob mailbox "*.json")] (fs/delete f))
    (wait-for #(every? (fn [o] (= :done (:state o))) (vals (:outbox @(:state c)))))
    (:token (last (get @opened dir)))))

(defn- thread-with-ask
  "A thread, a Worker on it and one open ask. Returns the Worker's token and
  the thread's asks-version."
  [dir]
  (call dir {:command :thread! :args {:title "Alt+W closes windows everywhere"}})
  (let [token (run-worker dir "T1")]
    (call dir {:token token :command :ask! :args {:kind :question :text "Keep Ctrl+Alt+W as an alias?"
                                                     :options [{:id "no" :label "No"} {:id "week" :label "Yes, for a week"}]
                                                     :stopped true}})
    {:token token :version (:asks-version (:ok (call dir {:command :thread :args {:id "T1"}})))}))

(deftest reads-show-a-worker-waiting-while-its-stopped-ask-is-open
  (with-controller
    (fn [c dir _]
      (let [{:keys [version]} (thread-with-ask dir)
            read (fn [] [(:state (:ok (call dir {:command :worker :args {:id "W1"}})))
                         (:state (last (:workers (:ok (call dir {:command :thread :args {:id "T1"}})))))
                         (:execution (first (:ok (call dir {:command :threads}))))])]
        (is (= [:waiting :waiting :waiting] (read)) "the Worker, its thread's list and the thread's execution agree")
        (is (= :working (get-in @(:state c) [:workers "W1" :state])) "from the ask, not from a stored state")
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}})
        (is (= [:working :working :working] (read)))))))

(deftest a-command-that-fails-after-its-events-leaves-no-row
  (let [db (store/open nil)
        state (store/replay db)]
    (is (thrown? Exception
                 ;; The second event cannot be written, after the first was.
                 (with-redefs [model/decide (fn [& _] {:events [{:kind :thread-created :thread "T1" :data {:id "T1" :title "x"}} {:kind nil}]
                                                       :result "T1"})]
                   (store/commit! db state :owner {:id "c1" :command :thread! :args {:title "x"}}))))
    (is (empty? (store/events db)))
    (is (= model/empty-state (store/replay db)))
    (is (= "T1" (:result (store/commit! db state :owner {:id "c1" :command :thread! :args {:title "x"}})))
        "the id is free again, so a retry runs")
    (store/close! db)))

(deftest an-ask-notifies-once-and-its-answer-closes-the-notification
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super"}))
        log (atom [])
        c (super/start! {:dir dir :conversations (str (fs/create-temp-dir {:prefix "bbh-super-conv"})) :scan-ms 10
                         :policy {:notify-delay-seconds 0}
                         :notify {:show! (fn [n _] (swap! log conj [:show (:summary n) (:body n) (:urgency n)]) {})
                                  :close! (fn [shown] (swap! log conj [:close (:key shown)]))}
                         :launch (stub-launch dir (str (fs/create-temp-dir {:prefix "bbh-super-ws"})))})]
    (swap! controllers assoc dir c)
    (try
      (let [{:keys [version]} (thread-with-ask dir)]
        (is (wait-for #(seq @log)))
        (is (= [[:show "T1 needs you: Alt+W closes windows everywhere" "Keep Ctrl+Alt+W as an alias?  [No | Yes, for a week]" :normal]]
               @log))
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}})
        (is (wait-for #(= [:close "T1"] (last @log))))
        (is (= 2 (count @log)) "the reply's other commits notify nothing"))
      (finally (super/stop! c) (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest a-retired-event-kind-folds-to-nothing-and-an-unknown-one-throws
  (let [state (model/fold model/empty-state {:seq 1 :at "t" :kind :thread-created :thread "T1" :data {:id "T1" :title "x"}})]
    (is (= state (model/fold state {:seq 2 :at "t" :kind :message-routed :thread nil :data {:outbox "o1"}})))
    (is (= :unknown-event (:error (ex-data (try (model/fold state {:seq 3 :at "t" :kind :no-such-kind :data {}})
                                                (catch Exception e e))))))))

(defn- restartable
  "Options for a controller on a new directory that a test stops and starts again."
  []
  (let [root (fs/create-temp-dir {:prefix "super-signals"})
        dir (str (fs/path root "controller"))]
    {:dir dir :conversations (str (fs/path root "conversations")) :scan-ms 10
     :launch (stub-launch dir (str (fs/path root "workspaces")))}))

(deftest a-restarted-controller-does-not-notify-what-it-replays
  (let [{:keys [dir] :as opts} (restartable)
        log (atom [])
        c (super/start! opts)]
    (swap! controllers assoc dir c)
    (try
      ;; The first controller has notifications off, so the ask is never recorded as notified.
      (let [{:keys [token]} (thread-with-ask dir)]
        (Thread/sleep 20)
        (super/stop! c)
        (let [c2 (with-redefs [notify/replay-grace-ms 0]
                   (super/start! (assoc opts :policy {:notify-delay-seconds 0}
                                        ;; The stub Worker's session is gone, which is news of its own; count the asks.
                                        :notify {:show! (fn [n _] (swap! log into (filter #(= :ask (first %)) (:keys n))) {}) :close! (fn [_])})))]
          (swap! controllers assoc dir c2)
          (try
            (is (wait-for #(contains? (:notified @(:state c2)) [:ask "a1"])) "the replayed ask is recorded")
            (Thread/sleep 100)
            (is (= [] @log) "but not shown")
            (testing "a live ask is shown once"
              (call dir {:token token :command :ask! :args {:kind :question :text "And Cmd+W?" :options [{:id "y" :label "Yes"}]}})
              (is (wait-for #(seq @log)))
              (Thread/sleep 100)
              (is (= [[:ask "a2"]] @log)))
            (finally (super/stop! c2)))))
      (finally (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest a-deadline-fires-once-across-a-controller-restart
  (let [{:keys [dir conversations] :as opts} (restartable)
        clock (atom (System/currentTimeMillis))
        log (atom [])
        opts (-> opts (assoc :now #(deref clock) :policy {:notify-delay-seconds 5}
                             :notify {:show! (fn [n _] (swap! log conj (:summary n)) {}) :close! (fn [_])})
                 ;; Emacs is down, so no session opens and W1 stays :starting.
                 (update :launch assoc :emacs-up? (constantly false)
                         :open! (fn [_] (throw (ex-info "no socket" {:error :emacs})))))
        c (super/start! opts)
        fired (fn [c] (mapv (comp (juxt :kind :subject) :data) (filter #(= :timer-fired (:kind %)) (store/events (:db c)))))
        row (fn [c] (select-keys (first (mapcat :threads (:sections (:ok (call dir {:command :sections}))))) [:section :line]))]
    (swap! controllers assoc dir c)
    (try
      (call dir {:command :thread! :args {:title "slow start"}})
      (call dir {:command :start-worker! :args {:thread "T1" :workflow :investigation :brief "look" :repo (str (fs/home))}})
      (is (= :starting (get-in @(:state c) [:workers "W1" :state])))
      (swap! clock + (* 9 60000))
      (Thread/sleep 50)
      (is (= [] (fired c)) "not before the deadline")
      (super/stop! c)
      (swap! clock + 61000)
      (let [c2 (super/start! opts)]
        (try
          (is (wait-for #(= 2 (count (fired c2)))) "the restarted controller finds it due from the log")
          (swap! clock + (* 60 60000))
          (Thread/sleep 50)
          (is (= [[:starting "W1"] [:notify [:needs-human "W1"]]] (fired c2)) "once, then the person is told once")
          (is (= {:section :needs-attention :line "W1 has been starting for 10 minutes and its session has not opened."} (row c2)))
          (is (= ["T1 needs you: slow start"] @log))
          (is (= :starting (get-in @(:state c2) [:workers "W1" :state])) "nothing is killed")
          (testing "a cancel that the Worker does not obey is told the same way"
            ;; A session that lives and never takes the cancellation.
            (with-redefs [model/cancel-owed? (constantly true) bbh.inbox/agent (constantly {:pid 1 :start 1})]
              (call dir {:command :cancel! :args {:thread "T1" :reason "stop"}})
              (swap! clock + (* 10 60000) 1000)
              (is (wait-for #(some #{[:cancelling "W1"]} (fired c2))))
              (Thread/sleep 50)
              (is (= 1 (count (filter #{[:cancelling "W1"]} (fired c2)))))
              (is (= :cancelling (get-in @(:state c2) [:workers "W1" :state])))
              (is (= "W1 was told to stop 10 minutes ago and has not." (:line (row c2))))))
          (locking (:db c2) (is (= (store/replay (:db c2)) @(:state c2))))
          (finally (super/stop! c2))))
      (finally (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest a-send-the-controller-died-before-recording-is-one-message
  (let [{:keys [dir conversations] :as opts} (restartable)
        c (super/start! opts)
        root (str (fs/path conversations "super-W1"))]
    (swap! controllers assoc dir c)
    (try
      (call dir {:command :thread! :args {:title "plan"}})
      (run-worker dir "T1")
      (super/stop! c)
      ;; The steer commits, dispatch! writes its file, and the controller dies
      ;; before :outbox-sent! commits.
      (let [db (store/open (str (fs/path dir "super.db")))
            st (try (:state (store/commit! db (store/replay db) :owner {:id "s1" :command :steer! :args {:thread "T1" :text "use plan B"}}))
                    (finally (store/close! db)))
            row (some #(when (= :steer (:delivery %)) %) (vals (:outbox st)))
            message (model/message-id (:id row))]
        (is (= :pending (:state row)))
        (bbh.inbox/send! root (:text row) nil {:kind :steer :message-id message})
        (let [c2 (super/start! opts)]
          (try
            (is (wait-for #(= :sent (get-in @(:state c2) [:outbox (:id row) :state]))) "the next pass sends the row again")
            (is (= [{:id message :kind "steer" :text "[Steering] use plan B"}] (bbh.inbox/pending root))
                "and the mailbox holds one message, the row's")
            (bbh.inbox/ack! {:root root} message)
            (is (wait-for #(= :done (get-in @(:state c2) [:outbox (:id row) :state]))))
            (Thread/sleep 50)
            (is (= [] (bbh.inbox/pending root)) "a taken message is not sent again")
            (locking (:db c2) (is (= (store/replay (:db c2)) @(:state c2))))
            (finally (super/stop! c2)))))
      (finally (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest a-cancel-is-an-outbox-row-taken-once-across-a-controller-restart
  (let [{:keys [dir conversations] :as opts} (restartable)
        c (super/start! opts)
        root (str (fs/path conversations "super-W1"))]
    (swap! controllers assoc dir c)
    (try
      (call dir {:command :thread! :args {:title "stop me"}})
      (run-worker dir "T1")
      (let [session (bbh.inbox/start! {:root root :project "p" :scan-ms 5 :deliver (fn [_])})
            files #(sort (map fs/file-name (fs/glob root "**")))]
        (try
          (super/reconcile! c)
          (is (= {:ok "W1"} (call dir {:command :cancel! :args {:thread "T1" :reason "stop now"}})))
          (let [row (some #(when (= :cancel (:delivery %)) %) (vals (:outbox @(:state c))))
                message (model/message-id (:id row))]
            (is (wait-for #(= :sent (get-in @(:state c) [:outbox (:id row) :state]))))
            (is (= [{:id message :kind "cancel" :text "[Cancelled] stop now"}] (bbh.inbox/pending root)))
            (is (not-any? #(re-find #"received|0-cancel" %) (files)) "no file of its own and no receipt")
            (super/reconcile! c)
            (is (= :cancelling (get-in @(:state c) [:workers "W1" :state])) "it waits for the session")
            (super/stop! c)
            (let [c2 (super/start! opts)]
              (try
                (super/reconcile! c2)
                (Thread/sleep 50)
                (is (= :cancelling (get-in @(:state c2) [:workers "W1" :state])) "and still does after a restart")
                (is (= [message] (map :id (bbh.inbox/pending root))) "the restart sends no second cancel")
                ;; The session interrupts and deletes the file.
                (bbh.inbox/ack! {:root root} message)
                (is (wait-for #(= :cancelled (get-in @(:state c2) [:workers "W1" :state]))))
                (is (= :done (get-in @(:state c2) [:outbox (:id row) :state])))
                (is (= {:cancel-requested 1 :worker-finished 1}
                       (select-keys (frequencies (map :kind (store/events (:db c2)))) [:cancel-requested :worker-finished])))
                (is (= [] (bbh.inbox/pending root)))
                (locking (:db c2) (is (= (store/replay (:db c2)) @(:state c2))))
                (finally (super/stop! c2)))))
          (finally (bbh.inbox/stop! session))))
      (finally (when-not @(:stopped c) (super/stop! c)) (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest a-reply-stops-waiting-when-its-worker-ends
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [version token]} (thread-with-ask dir)
            section #(model/section @(:state c) (get-in @(:state c) [:threads "T1"]))]
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}})
        (is (= [:sent] (map :state (vals (:replies @(:state c))))))
        (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "gave up"}})))
        (is (= [:closed] (map :state (vals (:replies @(:state c))))) "closed, a state of its own: nobody answered it")
        (is (not= :sent (section)))
        (is (wait-for #(= [] (bbh.inbox/pending (str (fs/path conversations "super-W1"))))) "its message is deleted unread")
        (let [reply (ffirst (:replies @(:state c)))
]
          (call dir {:command :start-worker! :args {:thread "T1" :workflow :investigation :brief "again" :repo (str (fs/home))}})
          (is (= :ok (ffirst (call dir {:command :answered! :args {:thread "T1" :reply reply}}))))
          (is (= [:closed] (map :state (vals (:replies @(:state c))))) "a later Worker cannot answer what it never took")
          (is (not-any? #(= :reply-answered (:kind %)) (store/events (:db c)))))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest an-outbox-row-is-done-when-its-mailbox-file-is-removed
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [version token]} (thread-with-ask dir)]
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}})
        (is (wait-for #(seq (fs/glob conversations "super-W1/mailbox/*.json"))))
        (let [files (fs/glob conversations "super-W1/mailbox/*.json")
              text (slurp (str (first files)))]
          (is (= 1 (count files)))
          (is (re-find #"Keep Ctrl\+Alt\+W as an alias\?: No" text))
          (is (wait-for #(= :sent (get-in @(:state c) [:outbox "o2" :state]))) "the send is recorded after the file lands")
          (doseq [f files] (fs/delete f))
          (is (wait-for #(= :done (get-in @(:state c) [:outbox "o2" :state]))))
          (is (not-any? #{:acked :processed} (map :state (vals (:outbox @(:state c))))))
          (is (= [:sent] (map :state (vals (:asks @(:state c))))) "an ack moves only the row")
          (is (= [:sent] (map :state (vals (:replies @(:state c))))))
          (call dir {:token token :command :answered! :args {:reply (ffirst (:replies @(:state c)))}})
          (is (= [:answered] (map :state (vals (:replies @(:state c))))) "the Worker's answered! does")
          (call dir {:command :steer! :args {:thread "T1" :text "use the small build"}})
          (is (wait-for #(seq (bbh.inbox/pending (str (fs/path conversations "super-W1"))))))
          (is (= [{:kind "steer" :text "[Steering] use the small build"}]
                 (mapv #(select-keys % [:kind :text]) (bbh.inbox/pending (str (fs/path conversations "super-W1")))))
              "a steer lands as its own kind"))))))

(deftest the-controller-serves-the-website-and-leaves-a-taken-port-alone
  (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
        dir (str (fs/create-temp-dir {:prefix "bbh-super"}))
        c (super/start! {:dir dir :conversations (str (fs/create-temp-dir {:prefix "bbh-super-conv"})) :scan-ms 10
                         :launch (stub-launch dir (str (fs/create-temp-dir {:prefix "bbh-super-ws"})))
                         :web {:port port}})]
    (try
      (is (wait-for #(some? @(:web c))))
      (let [r (babashka.http-client/get (str "http://127.0.0.1:" port "/api/super/threads") {:throw false})]
        (is (= 403 (:status r)) "the controller answers the site's requests")
        (is (re-find #"Tailnet owner only" (:body r))))
      (is (nil? ((requiring-resolve 'bbh.web/serve!) {:port port :conversations dir :super-dir dir})) "a taken port gives nil")
      (finally (super/stop! c)))
    (is (thrown? java.net.ConnectException
                 (with-open [_ (java.net.Socket. "127.0.0.1" (int port))])) "stop! closes it")))

(deftest a-second-controller-on-the-same-store-is-refused
  (with-controller
    (fn [_ dir _]
      (is (thrown-with-msg? Exception #"already running" (super/start! {:dir dir}))))))

(deftest threads-list-in-number-order
  (with-controller
    (fn [_ dir _]
      (dotimes [n 10] (call dir {:command :thread! :args {:title (str "t" (inc n))}}))
      (is (= "T10" (:id (last (:ok (call dir {:command :threads}))))))
      (is (= (map #(str "T" %) (range 10 0 -1))
             (map :id (mapcat :threads (:sections (:ok (call dir {:command :sections}))))))))))

(deftest sections-derive-from-a-fixed-event-list-as-the-table-says
  (let [base [{:seq 1 :at "2026-11-02T10:00:00Z" :kind :thread-created :thread "T1" :data {:id "T1" :title "t"}}
              {:seq 2 :at "2026-11-02T10:00:01Z" :kind :worker-attached :thread "T1" :data {:id "W1" :conversation "c" :generation 1}}]
        ask (fn [n & {:as more}] {:seq n :kind :ask-opened :thread "T1" :data (merge {:id "a1" :worker "W1" :kind :question :text "?" :options [{:id "y" :label "Y"}] :stopped false} more)})
        reply {:seq 4 :kind :reply-sent :thread "T1" :data {:id "r1" :thread "T1" :worker "W1" :picks {"a1" "y"} :note "" :dismiss #{} :outbox "o1" :conversation "c" :text "m"}}
        section (fn [events cursor]
                  (let [state (reduce model/fold model/empty-state (conj events {:seq 99 :kind :thread-read :data {:thread "T1" :seq cursor}}))]
                    (model/section state (get-in state [:threads "T1"]))))]
    (is (= :working (section base 2)))
    (is (= :new (section base 0)) "the thread's creation is a milestone the client has not seen")
    (is (= :stopped (section (conj base (ask 3 :stopped true)) 3)))
    (is (= :holding-up (section (conj base (ask 3 :holds-up "T2")) 3)))
    (is (= :waiting (section (conj base (ask 3)) 3)))
    (is (= :sent (section (conj base (ask 3) reply) 4)))
    (is (= :working (section (conj base (ask 3) reply {:seq 5 :kind :outbox-sent :thread "T1" :data {:id "o1" :message "m"}}
                                   {:seq 6 :kind :outbox-acked :thread "T1" :data {:id "o1"}}
                                   {:seq 7 :kind :reply-answered :thread "T1" :data {:id "r1"}}) 7)))
    (is (= :archived (section (conj base {:seq 3 :kind :thread-resolved :thread "T1" :data {}}) 3)))
    (is (= 2 (model/badge [{:section :stopped :threads [1]} {:section :waiting :threads [2]} {:section :working :threads [3 4]}])))))

(deftest the-inbox-outline-heads-each-thread-and-ask-with-its-id
  (let [sections {:badge 1
                  :sections [{:section :stopped :title "Stopped on you"
                              :threads [{:id "T1" :title "Alt+W closes windows" :updated-at (str (java.time.Instant/now))
                                         :execution :waiting :line "Keep?"
                                         :asks [{:id "a1" :kind :question :text "Keep?" :stopped true :recommended "no"
                                                 :options [{:id "no" :label "No"} {:id "yes" :label "Yes"}]
                                                 :details "Because\nof reasons" :evidence "/tmp/e.md"}
                                                {:id "a2" :kind :review :text "Approve?" :revision "abc" :evidence-sha "0123456789" :options []}]
                                         :draft {:picks {"a1" "yes"} :answers {"a2" "looks good\nship it"} :note "and why" :asks-version 3}}]}
                             {:section :archived :title "Archived" :threads [{:id "T3" :title "old" :came-back true}]}]}]
    (is (= ["* STOPPED T1 Alt+W closes windows  waiting · now"
            "** a1 Keep?"
            "   1. No  (recommended)"
            "   2. Yes  <- picked"
            "*** Details"
            "    Because"
            "    of reasons"
            "    [[file:/tmp/e.md][/tmp/e.md]]"
            "*** Answer"
            ""
            "** a2 Approve?  [review]"
            "   revision abc  evidence 01234567  A approve"
            "*** Answer"
            "    looks good"
            "    ship it"
            "  note: and why"
            "* ARCHIVED T3 old (came back)"]
           (clojure.string/split-lines (pane/org-view sections))))
    (is (clojure.string/starts-with? (pane/header sections []) "bbh inbox · 1 need you   1-9 pick  . recommended  i answer  s/C-n send"))
    (is (clojure.string/starts-with? (pane/header sections ["controller" "web"]) "bbh inbox · 1 need you · old code: controller, web   1-9 pick"))))

(deftest the-split-view-lists-a-line-per-thread-and-the-selected-threads-progress
  (let [sections {:sections [{:section :stopped :threads [{:id "T1" :title "Alt+W\tcloses\nwindows" :status :on-you
                                                         :updated-at (str (java.time.Instant/now)) :milestone-seq 7 :read-seq 5 :worker "W1"
                                                         :asks [{:id "a1"}]}]}
                             {:section :quiet :threads [{:id "T2" :title "done one" :status :done :milestone-seq 3 :read-seq 3 :asks []}]}]}]
    (is (= ["#+thread: id=T1\tsection=stopped\tstatus=on-you\tasks=1\tage=now\tunseen=1\tworker=W1\ttitle=Alt+W closes windows"
            "#+thread: id=T2\tsection=quiet\tstatus=done\tasks=0\tage=\tunseen=0\tworker=\ttitle=done one"]
           (pane/split-rows sections))
        "unseen once the thread moved past its read cursor; tabs and newlines never split a field"))
  (let [at "2026-11-08T10:00:00Z"
        events [{:kind :thread-created :at at :data {}}
                {:kind :milestone :at at :data {:text "half way"}}
                {:kind :outbox-sent :at at :data {}}
                {:kind :ask-opened :at at :data {:id "a1" :text "Keep?"}}
                {:kind :reply-sent :at at :data {:id "r1" :picks {"a1" "no"} :note "why"}}
                {:kind :worker-finished :at at :data {:id "W1" :state :done :landed "abcdef0123"}}]
        lines (pane/split-progress "T1" events 3)]
    (is (= ["asked" "replied" "finished"] (map #(nth (clojure.string/split % #"\t") 2) lines)) "the newest that tell how the work went")
    (is (= ["a1 Keep?" "r1 a1 note: why" "W1 done, landed abcdef01"] (map #(nth (clojure.string/split % #"\t") 3) lines)))
    (is (every? #(re-matches #"#\+progress: T1\t\d\d-\d\d \d\d:\d\d\t.*" %) lines))))

(deftest an-ask-is-one-line-and-its-details-ride-along
  (with-controller
    (fn [c dir _]
      (let [{:keys [token]} (thread-with-ask dir)
            ask (fn [args] (call dir {:token token :command :ask! :args (merge {:kind :question :options [{:id "y" :label "Y"}]} args)}))]
        (is (= :invalid (:error (ask {:text "Keep it?\nBecause the old one..."}))))
        (is (= :invalid (:error (ask {:text (apply str (repeat 161 "x"))}))))
        (let [id (:ok (ask {:text "Keep it?" :details "Because." :evidence "/tmp/e.md"}))]
          (is (= ["Keep it?" "Because." "/tmp/e.md"] ((juxt :text :details :evidence) (get-in @(:state c) [:asks id])))))))))

(deftest marking-a-thread-read-or-unread-is-shared-and-moves-nothing-else
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (call dir {:command :thread! :args {:title "one"}})
            (call dir {:command :thread! :args {:title "two"}})
            (let [section (fn [id] (some (fn [{:keys [section threads]}] (when (some #(= id (:id %)) threads) section))
                                         (:sections (:ok (call dir {:command :sections})))))
                  ;; The dispatcher's first machine observation can land at any moment.
                  events #(count (remove (comp #{:machine-observed} :kind) (store/events (:db c))))
                  at (get-in @(:state c) [:threads "T1" :updated-at])]
              (is (= [:new :new] (map section ["T1" "T2"])) "a new thread is unread")
              (pane/read! ["T1"] true)
              (is (= [:quiet :new] (map section ["T1" "T2"])) "read leaves the thread's section for :new")
              (is (= at (get-in @(:state c) [:threads "T1" :updated-at])) "reading does not change the row's age")
              (let [n (events)]
                (pane/read! ["T1"] true)
                (is (= n (events)) "reading a read thread records nothing"))
              (pane/read! ["T1"] false)
              (is (= :new (section "T1")) "unread brings it back")
              (pane/read! ["all"] true)
              (is (= [:quiet :quiet] (map section ["T1" "T2"])) "all marks every unread thread")
              (call dir {:command :milestone! :args {:thread "T2" :text "half way"}})
              (is (= :new (section "T2")) "a later milestone is unread again")
              (is (= :no-such-thread (:error (call dir {:command :read! :args {:threads ["T9"] :read true}}))))
              (locking (:db c) (is (= (store/replay (:db c)) @(:state c)))))))))))

(deftest a-typed-answer-closes-its-ask-and-reaches-the-worker
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)
                  a2 (:ok (call dir {:token token :command :ask! :args {:kind :question :text "Which name?" :options [{:id "x" :label "X"} {:id "y" :label "Y"}]}}))]
              (testing "a pick lands on the ask you name, not only the newest"
                (pane/pick! "T1" 2 :ask "a1")
                (is (= {"a1" "week"} (get-in @(:state c) [:threads "T1" :draft :picks]))))
              (testing "an answer saved to the draft shows in the outline"
                (pane/answer! "T1" a2 "call it Zed")
                (is (re-find #"\*\*\* Answer\n    call it Zed" (pane/org-view (:ok (call dir {:command :sections :args {}}))))))
              (pane/send! "T1" :answers {a2 "call it Zed, please"})
              (let [reply (first (vals (:replies @(:state c))))
                    outbox (get-in @(:state c) [:outbox (:outbox reply)])]
                (is (= {a2 "call it Zed, please"} (:answers reply)))
                (is (= #{:sent} (set (map #(get-in @(:state c) [:asks % :state]) ["a1" a2]))) "the pick and the answer both close their asks")
                (is (clojure.string/includes? (:text outbox) "- Which name?: call it Zed, please"))
                (is (clojure.string/includes? (:text outbox) (str "(super-answered! \"" (:id reply) "\")"))))
              (is (nil? (pane/send! "T1")) "nothing left to send sends nothing")
              (locking (:db c) (is (= (store/replay (:db c)) @(:state c)))))))))))

(deftest a-finished-worker-s-row-shows-how-it-ended-not-its-last-note
  (let [base [{:seq 1 :kind :thread-created :thread "T1" :data {:id "T1" :title "Fix it"}}
              {:seq 2 :kind :worker-started :thread "T1" :data {:id "W1" :state :starting :generation 1}}
              {:seq 3 :kind :worker-running :thread "T1" :data {:id "W1" :conversation "super-W1" :outbox "o1" :text "brief"}}
              {:seq 4 :kind :milestone :thread "T1" :data {:text "Writing failing test"}}]
        finish (fn [data] {:seq 5 :kind :worker-finished :thread "T1" :data (assoc data :id "W1")})
        row (fn [events]
              (let [state (reduce model/fold model/empty-state events)]
                [(get-in state [:threads "T1" :summary])
                 (pane/org-view {:sections (model/sections (assoc-in state [:threads "T1" :read-seq] 5))})]))]
    (testing "a running thread shows its execution and its latest note"
      (is (re-find #"^\* WORKING T1 Fix it  working\n  Writing failing test$" (second (row base)))))
    (testing "finishing replaces the summary, and the row shows the outcome"
      (let [[summary r] (row (conj base (finish {:state :done :landed "abcdef1234567890"})))]
        (is (= "W1 done, landed abcdef12. Last note: Writing failing test" summary))
        (is (re-find #"^\* QUIET T1 Fix it  done\n  W1 done, landed abcdef12\. Last note: Writing failing test$" r)))
      (is (= "W1 failed: REPL died. Last note: Writing failing test" (first (row (conj base (finish {:state :failed :reason "REPL died"}))))))
      (is (= "W1 cancelled: Cancelled from the inbox. Last note: Writing failing test"
             (first (row (conj base (finish {:state :cancelled :reason "Cancelled from the inbox"})))))))
    (testing "a Worker that never wrote a note still gets a summary"
      (is (= "W1 done" (first (row (conj (pop base) (finish {:state :done})))))))
    (testing "the next generation does not show the last one's note as its own"
      (let [events (conj base (finish {:state :done})
                         {:seq 6 :kind :worker-started :thread "T1" :data {:id "W2" :state :starting :generation 2}}
                         {:seq 7 :kind :worker-running :thread "T1" :data {:id "W2" :conversation "super-W2" :outbox "o2" :text "brief"}}
                         {:seq 8 :kind :worker-finished :thread "T1" :data {:id "W2" :state :done}})]
        (is (= "W2 done" (first (row events))))))))

(deftest jjui-opens-in-the-worker-workspace-until-it-is-gone-then-the-repo
  (let [dir (fs/create-temp-dir {:prefix "bbh-inbox-dir"})
        repo (str (fs/create-dirs (fs/path dir "repo")))
        workspace (str (fs/create-dirs (fs/path dir "T1-2")))
        thread {:id "T1" :workers [{:id "W1" :repo repo :workspace (str (fs/path dir "T1-1"))}
                                     {:id "W2" :repo repo :workspace workspace}]}]
    (is (= workspace (bbh.super.pane/workdir thread)))
    (fs/delete workspace)
    (is (= repo (bbh.super.pane/workdir thread)))
    (is (thrown-with-msg? Exception #"T2 has no Worker" (bbh.super.pane/workdir {:id "T2" :workers []})))))

;; ---- Workers ---------------------------------------------------------------

(deftest a-worker-on-a-missing-repository-starts-a-new-project
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-new"}))
        repo (str (fs/path dir "projects" "game"))
        path (workspace/workspace! dir {:repo repo :thread "T1" :generation 1})]
    (is (fs/exists? (fs/path repo ".jj")))
    (is (fs/exists? (fs/path path ".jj")))))

(defn- jj-repo
  "A temporary jj repository with one committed file."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-repo"}))]
    (spit (str (fs/path dir "README")) "hello\n")
    (doseq [args [["git" "init"] ["commit" "-m" "start"]]]
      (let [{:keys [exit err]} (apply babashka.process/shell {:dir dir :out :string :err :string :continue true} "jj" args)]
        (when-not (zero? exit) (throw (ex-info (str "jj " args ": " err) {})))))
    dir))

(defn- with-workers
  "A controller whose Emacs buffer and wf start are recorded instead of run, on a
  temporary jj repo, with a policy of two Workers. Calls F with the controller,
  its dir, the repo and the atom of launches. POLICY adds to the policy."
  ([f] (with-workers {} f))
  ([policy f]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-w"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-wconv"}))
        repo (jj-repo)
        launches (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :policy (merge {:workers 2} policy)
                         :launch {:wf-start! (fn [workspace w] (str "run-" (:id w)))
                                  :open! (fn [spec] (swap! launches conj spec) (str "wC:p" (count @launches)))
                                  :release! (constantly nil)}})]
    (try (f c dir repo launches)
         (finally (super/stop! c))))))

(defn- start-worker [dir thread & {:as more}]
  (call dir {:command :start-worker! :args (merge {:thread thread :workflow :change :brief (str "work on " thread) :repo (:repo more) :check "true"} more)}))

(deftest a-project-name-brings-its-repo-and-check
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-p"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-pconv"}))
        repo (jj-repo)
        other (jj-repo)
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :policy {:workers 3 :projects {"demo" {:repo repo :check "make t" :about "a demo"}}}
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w))) :open! (fn [_] "buf")}})]
    (try
      (dotimes [_ 3] (call dir {:command :thread! :args {:title "p"}}))
      (is (= {"demo" {:repo repo :check "make t" :about "a demo"}} (:ok (call dir {:command :projects}))))
      (call dir {:command :start-worker! :args {:thread "T1" :workflow :change :brief "b" :project "demo"}})
      (is (= [repo "make t"] ((juxt :repo :check) (get-in @(:state c) [:workers "W1"]))))
      (call dir {:command :start-worker! :args {:thread "T2" :workflow :change :brief "b" :project "demo" :check "make u"}})
      (is (= "make u" (get-in @(:state c) [:workers "W2" :check])) "a named check wins")
      (is (= :no-such-project (:error (call dir {:command :start-worker! :args {:thread "T3" :workflow :change :brief "b" :project "nope"}}))))
      (call dir {:command :start-worker! :args {:thread "T3" :workflow :change :brief "b" :repo other :check "make v"}})
      (is (= {:repo other :check "make v" :used true} (get (:ok (call dir {:command :projects})) (str (fs/file-name other))))
          "a repository a Worker used joins the list under its folder name")
      (finally (super/stop! c)))))

(deftest two-workers-get-their-own-jj-workspaces-and-the-main-checkout-is-untouched
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "first"}})
      (call dir {:command :thread! :args {:title "second"}})
      (let [w1 (:ok (start-worker dir "T1" :repo repo))
            w2 (:ok (start-worker dir "T2" :repo repo))]
        (is (= [:working :working] (map :state [w1 w2])))
        (is (= [(str (fs/path dir "workspaces" "T1-1")) (str (fs/path dir "workspaces" "T2-1"))] (map :workspace [w1 w2])))
        (is (fs/exists? (fs/path (:workspace w1) "README")) "the workspace holds the repository's files")
        ;; A missing workspace must not make the write land in the checkout.
        (is (fs/starts-with? (str (:workspace w1)) dir))
        (when (fs/starts-with? (str (:workspace w1)) dir)
          (spit (str (fs/path (:workspace w1) "only-here")) "x"))
        (is (not (fs/exists? (fs/path repo "only-here"))))
        (is (not (fs/exists? (fs/path (:workspace w2) "only-here"))))
        (is (= #{"default" "T1-1" "T2-1"}
               ;; jj 0.47 marks the current workspace: "default (current): ...".
               (set (map #(first (clojure.string/split % #"[: ]")) (clojure.string/split-lines (:out (babashka.process/shell {:dir repo :out :string} "jj" "workspace" "list")))))))
        (is (= ["T1 first" "T2 second"] (map #(str (:thread %) " " (:title %)) @launches)))
        (is (every? #(re-matches #"[0-9a-f]{48}" (:token %)) @launches) "each buffer gets its Worker's token")
        (is (= "super-W1" (:conversation (first @launches))))
        (is (fs/exists? (fs/path (:conversations c) "super-W1")))
        (let [t (:ok (call dir {:command :thread :args {:id "T1"}}))]
          (is (= "W1" (:worker t)))
          (is (= :working (:execution t))))))))

(deftest a-worker-past-the-cap-is-queued-and-starts-when-one-finishes
  (with-workers
    (fn [c dir repo launches]
      (doseq [t ["a" "b" "c"]] (call dir {:command :thread! :args {:title t}}))
      (start-worker dir "T1" :repo repo)
      (start-worker dir "T2" :repo repo)
      (let [w3 (:ok (start-worker dir "T3" :repo repo))]
        (is (= :queued (:state w3)))
        (is (= "2 Workers already running" (:reason w3)))
        (is (= 2 (count @launches)))
        (is (= :queued (:execution (:ok (call dir {:command :thread :args {:id "T3"}})))))
        (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})))
        (is (= 3 (count @launches)) "the queued Worker was launched")
        (is (= :working (:state (get-in @(:state c) [:workers "W3"]))))
        (let [t (:ok (call dir {:command :thread :args {:id "T1"}}))]
          (is (nil? (:worker t)))
          (is (= ["W1"] (:history t)))
          (is (= :done (:execution t))))))))

(deftest the-brief-is-the-workers-first-mailbox-message
  (with-workers
    (fn [c dir repo _]
      (call dir {:command :thread! :args {:title "a"}})
      (start-worker dir "T1" :repo repo :brief "Make Alt+W close windows everywhere.")
      (let [root (fs/path (:conversations c) "super-W1")]
        (is (wait-for #(seq (fs/glob root "mailbox/*.json"))))
        (let [text (slurp (str (first (fs/glob root "mailbox/*.json"))))]
          (is (re-find #"\[T1 brief for W1\]" text))
          (is (re-find #"Make Alt\+W close windows everywhere" text))
          (is (re-find #"run-W1" text))
          (is (clojure.string/includes? text (str "Work only in your jj workspace " (fs/path dir "workspaces" "T1-1"))) "it says where to work")
          (is (clojure.string/includes? text (str repo " is the main checkout"))))
        (doseq [f (fs/glob root "mailbox/*.json")] (fs/delete f))
        (is (wait-for #(= [:done] (map :state (vals (:outbox @(:state c)))))))
        (is (= {} (:replies @(:state c))) "a brief is not a reply")
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

;; ---- the Router ------------------------------------------------------------

(deftest a-handle-is-read-typed-or-spoken
  (is (= ["T12" "also handle the phone layout"] (model/parse-handle "T12 also handle the phone layout")))
  (is (= ["T12" "use plan B"] (model/parse-handle "t12: use plan B")))
  (is (= ["T12" "option two"] (model/parse-handle "tee twelve, option two")))
  (is (= ["T22" "go"] (model/parse-handle "Tee twenty two go")))
  (is (= ["T7" ""] (model/parse-handle "T seven")))
  (is (nil? (model/parse-handle "Make the page show cost per session")))
  (is (nil? (model/parse-handle "tea for two")) "a word that is not a number is not a handle"))

(deftest unresolved-messages-reach-the-router-together-one-file-a-row
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-r"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-rconv"}))
        ;; a slow dispatcher, so two messages are pending at once
        c (super/start! {:dir dir :conversations conversations :scan-ms 1500})]
    (try
      (let [token (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))
            root (fs/path conversations "router")]
        (is (re-matches #"[0-9a-f]{48}" token))
        (is (= {:ok {:to :router}} (call dir {:command :say! :args {:text "Make the page show cost per session" :origin :herdr}})))
        (is (= {:ok {:to :router}} (call dir {:command :say! :args {:text "and what is T3 doing" :origin :voice}})))
        (is (wait-for #(seq (fs/glob root "mailbox/*.json"))))
        (Thread/sleep 100)
        (is (= [{:id "0000000001-o1" :text "o1 [herdr] Make the page show cost per session"}
                {:id "0000000002-o2" :text "o2 [voice] (spoken) and what is T3 doing"}]
               (bbh.inbox/pending (str root)))
            "each row is its own message, named after the row, and one turn takes both")
        (is (= #{:sent} (set (map :state (vals (:outbox @(:state c)))))))
        (testing "the Router's token starts work but does not answer asks"
          (is (= {:ok "T1"} (call dir {:token token :command :thread! :args {:title "web cost per session"}})))
          (is (= :forbidden (:error (call dir {:token token :command :reply! :args {:thread "T1" :asks-version 0 :picks {}}})))))
        (testing "a handle for a thread without a Worker goes to the Router with the thread named"
          (is (= {:ok {:to :router :thread "T1"}} (call dir {:command :say! :args {:text "T1 go ahead" :origin :phone}}))))
        (testing "registering again revokes the old token"
          (call dir {:command :register-router! :args {:conversation "router"}})
          (is (= :unauthorized (:error (call dir {:token token :command :threads}))))))
      (finally (super/stop! c)))))

(deftest job-exits-wake-the-worker-once-after-controller-restart
  (let [root (fs/create-temp-dir {:prefix "super-job-recovery"})
        dir (str (fs/path root "controller"))
        conversations (str (fs/path root "conversations"))
        opts {:dir dir :conversations conversations :scan-ms 10
              :launch (stub-launch dir (str (fs/path root "workspaces")))}
        c (super/start! opts)
        jobs (fs/create-dirs (fs/path conversations "super-W1" "jobs"))
        base (str (fs/path jobs "build-1"))
        job-rows (fn [c] (filter #(clojure.string/starts-with? (:text %) "[Job") (vals (:outbox @(:state c)))))]
    (swap! controllers assoc dir c)
    (try
      (thread-with-ask dir)
      (spit (str base ".cmd") "build command")
      ;; A real detached process outlives the first controller. A file gate
      ;; avoids racing the restart against a short sleep.
      (babashka.process/shell
        {:out :discard :err :discard}
        "setsid" "-f" "bash" "-c"
        (str "echo $$ > " (pr-str (str base ".pid"))
             "; while [ ! -f " (pr-str (str base ".release")) " ]; do sleep 0.02; done; "
             "echo compiler-failed >> " (pr-str (str base ".log"))
             "; echo 17 > " (pr-str (str base ".exit"))))
      (super/observe-jobs! c)
      (is (empty? (job-rows c)) "a running job does not wake the Worker")
      (super/stop! c)
      (let [c2 (super/start! opts)]
        (try
          (super/observe-jobs! c2)
          (is (empty? (job-rows c2)) "restart does not manufacture an exit")
          (spit (str base ".release") "go")
          (is (wait-for #(seq (fs/glob conversations "super-W1/mailbox/*.json"))))
          (dotimes [_ 3] (super/observe-jobs! c2))
          (is (= [{:id "build-1" :exit 17 :log (str base ".log")}]
                 (vec (vals (get-in @(:state c2) [:workers "W1" :jobs])))))
          (is (= 1 (count (filter #(= :job-exited (:kind %)) (store/events (:db c2))))))
          (is (= 1 (count (job-rows c2))))
          (is (re-find #"Job build-1 exited with status 17"
                       (:text (cheshire.core/parse-string
                                (slurp (str (first (fs/glob conversations "super-W1/mailbox/*.json")))) true))))
          (locking (:db c2) (is (= (store/replay (:db c2)) @(:state c2))))
          (finally (super/stop! c2))))
      (let [c3 (super/start! opts)]
        (try
          (super/observe-jobs! c3)
          (is (= 1 (count (job-rows c3))))
          (is (= 1 (count (fs/glob conversations "super-W1/mailbox/*.json"))))
          (is (= "compiler-failed\n" (slurp (str base ".log"))) "the command executed once")
          (finally (super/stop! c3))))
      (finally
        (spit (str base ".release") "go")
        (when-not @(:stopped c) (super/stop! c))
        (fs/delete-tree root)))))

(deftest incomplete-exit-markers-and-unowned-jobs-do-not-wake-workers
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [token]} (thread-with-ask dir)
            jobs (fs/create-dirs (fs/path conversations "super-W1" "jobs"))]
        (spit (str (fs/path jobs "partial.cmd")) "test")
        (spit (str (fs/path jobs "partial.exit")) "")
        (spit (str (fs/path jobs "orphan.exit")) "0")
        (super/observe-jobs! c)
        (is (= ["o1"] (keys (:outbox @(:state c)))) "only the brief")
        (is (= :forbidden (:error (call dir {:token token :command :job-exited!
                                            :args {:job "fake" :exit 0 :log "fake.log"}}))))
        (spit (str (fs/path jobs "partial.exit")) "0")
        (is (wait-for #(= 0 (get-in @(:state c) [:workers "W1" :jobs "partial" :exit]))))))))

(deftest a-job-exit-the-worker-already-read-sends-no-message
  (with-controller
    (fn [c dir conversations]
      (thread-with-ask dir)
      (let [jobs (fs/create-dirs (fs/path conversations "super-W1" "jobs"))
            base #(str (fs/path jobs %))
            job-rows #(filter (fn [o] (clojure.string/starts-with? (:text o) "[Job")) (vals (:outbox @(:state c))))]
        ;; job-status read the exit, so it left a .seen marker.
        (spit (str (base "seen") ".cmd") "test")
        (spit (str (base "seen") ".seen") "")
        (spit (str (base "seen") ".exit") "0")
        ;; A job-wait still running holds the exit back; it reports it itself.
        (spit (str (base "waited") ".cmd") "test")
        (spit (str (base "waited") ".waiting") (str (+ (System/currentTimeMillis) 60000)))
        (spit (str (base "waited") ".exit") "3")
        (super/observe-jobs! c)
        (is (= 0 (get-in @(:state c) [:workers "W1" :jobs "seen" :exit])) "the exit is recorded")
        (is (nil? (get-in @(:state c) [:workers "W1" :jobs "waited"])))
        (is (empty? (job-rows)) "neither sends a message")
        (fs/delete (str (base "waited") ".waiting"))
        (super/observe-jobs! c)
        (is (= 1 (count (job-rows))) "an exit nobody read once the wait ended sends one")
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest mail-to-a-finished-worker-is-dropped-and-its-file-deleted
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [version]} (thread-with-ask dir)]
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}})
        (is (wait-for #(= :sent (get-in @(:state c) [:outbox "o2" :state]))))
        (is (= 1 (count (fs/glob conversations "super-W1/mailbox/*.json"))))
        (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "x"}})))
        (is (wait-for #(= :dropped (get-in @(:state c) [:outbox "o2" :state]))))
        (is (empty? (fs/glob conversations "super-W1/mailbox/*.json")) "the unread file is deleted")
        (is (empty? (filter #(#{:pending :sent} (:state %)) (vals (:outbox @(:state c))))))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest cancellation-kills-only-identity-verified-detached-jobs
  (with-controller
    (fn [c dir conversations]
      (thread-with-ask dir)
      (let [root (fs/path conversations "super-W1")
            jobs (fs/create-dirs (fs/path root "jobs"))
            base (str (fs/path jobs "cancel-job"))]
        (spit (str base ".cmd") "sleep 60")
        (babashka.process/shell {:out :discard :err :discard} "setsid" "-f" "bash" "-c"
                               (str "echo $$ > " (pr-str (str base ".pid")) "; sleep 60"))
        (is (wait-for #(fs/exists? (str base ".pid"))))
        (let [pid (parse-long (clojure.string/trim (slurp (str base ".pid"))))
              identity (bbh.super.process/identity-of pid)]
          (try
            (is (string? (:start identity)))
            (spit (str base ".start") "wrong-start")
            (is (false? (bbh.super.process/stop-job! {:pid pid :start "wrong-start"})))
            (is (= identity (bbh.super.process/identity-of pid)))
            (spit (str base ".start") (:start identity))
            (is (= {:ok "W1"} (call dir {:id "cancel-once" :command :cancel! :args {:thread "T1" :reason "stop the build"}})))
            (is (= {:ok "W1"} (call dir {:id "cancel-once" :command :cancel! :args {:thread "T1" :reason "stop the build"}})))
            (is (wait-for #(nil? (bbh.super.process/identity-of pid))))
            (is (= "137\n" (slurp (str base ".exit"))))
            (is (= :cancelled (get-in @(:state c) [:workers "W1" :state])))
            (is (= [] (filter #(= :open (:state %)) (vals (:asks @(:state c))))))
            (finally (when (bbh.super.process/matches? identity) (bbh.super.process/stop-job! identity)))))))))

(defn- running-job!
  "A detached job in conversation ROOT that sleeps, as `job` starts one, with
  its identity recorded. Returns its identity."
  [root id]
  (let [base (str (fs/path root "jobs" id))]
    (fs/create-dirs (fs/parent base))
    (spit (str base ".cmd") "sleep 60")
    (babashka.process/shell {:out :discard :err :discard} "setsid" "-f" "bash" "-c"
                            (str "echo $$ > " (pr-str (str base ".pid")) "; sleep 60"))
    (wait-for #(fs/exists? (str base ".pid")))
    (let [identity (bbh.super.process/identity-of (parse-long (clojure.string/trim (slurp (str base ".pid")))))]
      (spit (str base ".start") (:start identity))
      identity)))

(deftest an-ended-worker-leaves-no-job-running
  (with-workers
    (fn [c dir repo _]
      (call dir {:command :thread! :args {:title "t"}})
      (start-worker dir "T1" :repo repo)
      (let [root (fs/path (:conversations c) "super-W1")
            during (running-job! root "during")]
        (try
          (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "gave up"}})))
          (is (nil? (bbh.super.process/identity-of (:pid during))) "its job is stopped at the finish")
          (is (= "137\n" (slurp (str (fs/path root "jobs" "during.exit")))))
          (let [after (running-job! root "after")]
            (try
              (super/reconcile! c)
              (is (nil? (bbh.super.process/identity-of (:pid after))) "a health pass stops one an ended Worker still has")
              (finally (when (bbh.super.process/matches? after) (bbh.super.process/stop-job! after)))))
          (finally (when (bbh.super.process/matches? during) (bbh.super.process/stop-job! during))))))))

(defn- effect-events [c]
  (locking (:db c)
    (mapv (juxt :kind (comp :kind :data)) (filter #(#{:effect-done :effect-failed} (:kind %)) (store/events (:db c))))))

(deftest a-finish-the-controller-died-after-is-settled-once-at-restart
  (let [root (fs/create-temp-dir {:prefix "super-effects"})
        dir (str (fs/path root "controller"))
        ran (atom [])
        opts {:dir dir :conversations (str (fs/path root "conversations")) :scan-ms 10
              :launch (assoc (stub-launch dir (str (fs/path root "workspaces")))
                             :retire! (fn [w] (swap! ran conj [:retire (:id w)]) nil)
                             :advance! (fn [{:keys [tip]}] (swap! ran conj [:advance tip]) :moved))}
        c (super/start! opts)]
    (swap! controllers assoc dir c)
    (try
      (doseq [t ["lands" "fails"]] (call dir {:command :thread! :args {:title t}}))
      (run-worker dir "T1")
      (run-worker dir "T2")
      (let [run-of #(get-in @(:state c) [:workers % :wf-run])
            runs [(run-of "W1") (run-of "W2")]]
        (is (= [:active :active] (map #(:status (bbh.workflow/status %)) runs)))
        (super/stop! c)
        ;; The finish commits and the controller dies before any effect ran.
        (let [db (store/open (str (fs/path dir "super.db")))]
          (try (reduce (fn [st cmd] (:state (store/commit! db st :controller cmd)))
                       (store/replay db)
                       [{:id "f1" :command :finish-worker! :args {:worker "W1" :state-after :done :landed "tip1"}}
                        {:id "f2" :command :finish-worker! :args {:worker "W2" :state-after :failed :reason "gave up"}}])
               (finally (store/close! db))))
        (is (= [] @ran))
        (let [c2 (super/start! opts)
              owed #(mapcat (fn [w] (model/obligations @(:state c2) w)) (vals (:workers @(:state c2))))]
          (try
            (is (= [:stop-jobs :close-wf :advance :retire] (get-in @(:state c2) [:workers "W1" :owes])))
            (is (= [:stop-jobs :close-wf :retire] (get-in @(:state c2) [:workers "W2" :owes])))
            (is (wait-for #(= 6 (count (effect-events c2)))) "the first pass after the restart runs them")
            (is (= [[:advance "tip1"] [:retire "W1"]] @ran) "a failed Worker's workspace stays until its thread is archived")
            (is (= [:abandoned :abandoned] (map #(:status (bbh.workflow/status %)) runs)))
            (is (re-find #"ended failed: gave up" (pr-str (bbh.workflow/status (second runs)))))
            (is (= {:ok "T2"} (call dir {:command :archive! :args {:thread "T2"}})))
            (is (= [[:advance "tip1"] [:retire "W1"] [:retire "W2"]] @ran))
            (is (empty? (owed)))
            (is (= 7 (count (effect-events c2))))
            (locking (:db c2) (is (= (store/replay (:db c2)) @(:state c2))))
            (finally (super/stop! c2))))
        (let [c3 (super/start! opts)]
          (try
            (super/reconcile! c3)
            (Thread/sleep 100)
            (is (= 3 (count @ran)) "a second restart runs nothing again")
            (is (= 7 (count (effect-events c3))))
            (finally (super/stop! c3)))))
      (finally (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest an-effect-that-keeps-failing-needs-the-person-after-three-tries
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-stuck"}))
        tries (atom 0)
        c (super/start! {:dir dir :conversations (str (fs/create-temp-dir {:prefix "bbh-super-stuck-conv"})) :scan-ms 10 :health-ms 20
                         :launch (assoc (stub-launch dir (str (fs/create-temp-dir {:prefix "bbh-super-stuck-ws"})))
                                        :retire! (fn [_] (swap! tries inc) (throw (ex-info "jj is locked" {}))))})]
    (swap! controllers assoc dir c)
    (try
      (call dir {:command :thread! :args {:title "t"}})
      (run-worker dir "T1")
      (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})) "the finish stands")
      (is (wait-for #(model/stuck-effect (get-in @(:state c) [:workers "W1"]))))
      (Thread/sleep 100)
      (is (= 3 @tries) "it is not tried a fourth time")
      (is (= {:kind :retire :error "jj is locked"} (model/stuck-effect (get-in @(:state c) [:workers "W1"]))))
      (let [row (first (mapcat :threads (model/sections @(:state c))))]
        (is (= :needs-attention (:section row)))
        (is (= "W1 finished, but its retire failed 3 times: jj is locked" (:line row))))
      (is (= [[:effect-done :stop-jobs] [:effect-done :close-wf] [:effect-failed :retire] [:effect-failed :retire] [:effect-failed :retire]]
             (effect-events c)))
      (finally (super/stop! c) (swap! opened dissoc dir) (swap! controllers dissoc dir)))))

(deftest a-dead-session-waits-for-resume-holding-no-slot
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "recover me"}})
      (call dir {:command :thread! :args {:title "b"}})
      (call dir {:command :thread! :args {:title "c"}})
      (call dir {:command :thread! :args {:title "d"}})
      (start-worker dir "T1" :repo repo)
      (start-worker dir "T2" :repo repo)
      (let [c (assoc c :restart-grace-ms -1)
            health #(get-in @(:state c) [:workers % :health :classification])]
        (with-redefs [bbh.workflow/status (fn [_] {:status :active :phase :implement})]
          ;; Both sessions are dead: no agent.json was ever written.
          (dotimes [_ 2] (super/reconcile! c))
          (is (= 2 (count @launches)) "nothing is relaunched")
          (is (= [:session-ended :working] [(health "W1") (get-in @(:state c) [:workers "W1" :state])]))
          (is (= :needs-attention (model/section @(:state c) (get-in @(:state c) [:threads "T1"]))))
          (is (re-find #"Resume it" (:line (first (:threads (first (model/sections @(:state c))))))))
          (is (not (model/holds-slot? @(:state c) (get-in @(:state c) [:workers "W1"]))) "an ended session holds no slot")
          (is (= :working (:state (:ok (start-worker dir "T3" :repo repo)))) "so a third Worker starts under a cap of two")
          (is (= :working (:state (:ok (start-worker dir "T4" :repo repo)))) "and a fourth")
          (is (= :cap-full (:error (call dir {:command :resume! :args {:thread "T1"}}))) "a resume waits for a free slot")
          (call dir {:command :finish-worker! :args {:worker "W3" :state-after :failed :reason "x"}})
          (let [old-token (:token (first @launches))]
            (is (= {:ok "W1"} (call dir {:command :resume! :args {:thread "T1"}})))
            (is (= 5 (count @launches)))
            (is (= ["super-W1" "run-W1"] ((juxt :conversation :wf-run) (last @launches))) "the same conversation and wf run")
            (is (not= old-token (:token (last @launches))) "with a fresh token")
            (is (= :unauthorized (:error (call dir {:token old-token :command :note! :args {:text "x"}}))) "the old token is revoked"))
          (is (some #(re-find #"^\[Recovery\]" (:text %)) (vals (:outbox @(:state c)))))
          (is (model/holds-slot? @(:state c) (get-in @(:state c) [:workers "W1"])) "it holds its slot again")
          (is (= :not-ended (:error (call dir {:command :resume! :args {:thread "T1"}})))))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest cancelling-an-ended-session-keeps-its-slot-free
  ;; Found by Super.tla CapRespected: an ended Worker frees its slot, another
  ;; takes it, and cancelling the first must not count it again.
  (let [w {:id "W1" :thread "T1" :state :cancelling :health {:classification :session-ended}}
        state {:threads {"T1" {:id "T1" :worker "W1"}} :workers {"W1" w}}]
    (is (model/session-ended? w))
    (is (not (model/holds-slot? state w)))
    (is (thrown-with-msg? Exception #"has not ended"
                          (model/decide* state {:command :resume! :args {:thread "T1"}})))))

(deftest an-ended-session-stays-ended-when-health-names-another-problem
  ;; Found by Super.tla CapRespected: an ended Worker frees its slot, another
  ;; takes it, and a later pass that sees a lost job must not count it again.
  (let [w {:id "W1" :thread "T1" :state :working :wf-run "r1"}
        state {:threads {"T1" {:id "T1" :worker "W1"}} :workers {"W1" w}}
        observe (fn [obs] (assoc w :health (model/health state w (merge {:agent nil :jobs [] :wf {:status :active} :repl? false :waits {}} obs) 0 60000)))
        resume (fn [w] (try (:result (model/decide* (assoc-in state [:workers "W1"] w) {:command :resume! :args {:thread "T1"}}))
                            (catch Exception e (:error (ex-data e)))))]
    (is (model/session-ended? (observe {})))
    (let [lost (observe {:jobs [{:id "j1" :state :lost}]})]
      (is (= :needs-human (get-in lost [:health :classification])))
      (is (model/session-ended? lost))
      (is (not (model/holds-slot? state lost)))
      (is (= "W1" (resume lost))))
    (let [missing (observe {:wf {:status :missing}})]
      (is (not (model/holds-slot? state missing)))
      (is (= :wf-missing (resume missing))))
    (is (not (model/session-ended? (observe {:agent {:pid 1 :start "s"} :repl? true}))) "a live session has not ended")))

(deftest a-launch-that-meets-emacs-down-waits-instead-of-failing
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-ed"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-edconv"}))
        up (atom false)
        opened (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :restart-grace-ms -1
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w)))
                                  :emacs-up? #(deref up)
                                  :open! (fn [spec] (if @up (do (swap! opened conj (:conversation spec)) "buf")
                                                        (throw (ex-info "Emacs daemon bbh-super: no socket" {:error :emacs}))))}})]
    (try
      (call dir {:command :thread! :args {:title "t"}})
      (is (= :starting (:state (:ok (start-worker dir "T1" :repo (jj-repo))))))
      (is (= :starting (get-in @(:state c) [:workers "W1" :state])))
      (reset! up true)
      (with-redefs [bbh.workflow/status (fn [_] {:status :active :phase :implement})]
        (super/reconcile! c))
      (is (= ["super-W1"] @opened))
      (is (= :working (get-in @(:state c) [:workers "W1" :state])))
      (finally (super/stop! c)))))

(deftest the-controller-keeps-a-router-running-once-emacs-is-up
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-rt"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-rtconv"}))
        up (atom false)
        opened (atom [])
        session (atom nil)
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :restart-grace-ms -1 :manage-router true
                         :launch {:emacs-up? #(deref up)
                                  ;; The Router session announces itself in its conversation, as bbh acp does.
                                  :open-router! (fn [{:keys [conversation token]}]
                                                  (swap! opened conj [conversation token])
                                                  (reset! session (bbh.inbox/start! {:root (str (fs/path conversations conversation)) :deliver (fn [_])})))}})]
    (try
      (is (wait-for #(pos? @(:last-health c))) "the dispatcher's first pass is done")
      (super/reconcile! c)
      (is (= [] @opened) "Emacs down: no Router yet")
      (reset! up true)
      (super/reconcile! c)
      (super/reconcile! c)
      (is (= ["router"] (map first @opened)) "started once, kept while its session lives")
      (is (= "router" (get-in @(:state c) [:router :router :conversation])))
      (is (= {:ok []} (call dir {:token (second (first @opened)) :command :threads})) "its token is the Router's")
      (bbh.inbox/stop! @session)
      (super/reconcile! c)
      (is (= 2 (count @opened)) "a Router whose session ended is started again")
      (is (= :unauthorized (:error (call dir {:token (second (first @opened)) :command :threads}))) "and the old token is revoked")
      (finally (some-> @session bbh.inbox/stop!) (super/stop! c)))))

(deftest surviving-workers-are-observed-not-restarted-and-cancel-waits-for-the-session
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "survive sleep"}})
      (start-worker dir "T1" :repo repo)
      (let [root (str (fs/path (:conversations c) "super-W1"))
            poller (bbh.inbox/start! {:root root :project repo :scan-ms 5 :deliver (fn [_])})]
        (try
          (dotimes [_ 2] (super/reconcile! (assoc c :restart-grace-ms -1)))
          (is (= 1 (count @launches)))
          (is (= (.pid (java.lang.ProcessHandle/current)) (get-in @(:state c) [:workers "W1" :health :process :pid])))
          (is (= {:ok "W1"} (call dir {:command :cancel! :args {:thread "T1" :reason "stop now"}})))
          (is (= :cancelling (get-in @(:state c) [:workers "W1" :state])))
          (is (= "W1" (get-in @(:state c) [:threads "T1" :worker])))
          (is (wait-for #(some (fn [m] (= "cancel" (:kind m))) (bbh.inbox/pending root))))
          (let [[m :as cancels] (filter #(= "cancel" (:kind %)) (bbh.inbox/pending root))
                row (some #(when (= :cancel (:delivery %)) %) (vals (:outbox @(:state c))))]
            (is (= [{:id (model/message-id (:id row)) :kind "cancel" :text "[Cancelled] stop now"}] cancels)
                "the cancellation is an outbox row's message")
            (super/reconcile! c)
            (is (= :cancelling (get-in @(:state c) [:workers "W1" :state])) "it waits for the session to take it")
            (bbh.inbox/ack! {:root root} (:id m))
            (is (wait-for #(= :done (get-in @(:state c) [:outbox (:id row) :state])))))
          (super/reconcile! c)
          (is (= :cancelled (get-in @(:state c) [:workers "W1" :state])))
          (is (nil? (get-in @(:state c) [:threads "T1" :worker])))
          (finally (bbh.inbox/stop! poller)))))))

(deftest sigkill-controller-rediscovers-a-running-job-without-relaunch
  (let [root (fs/create-temp-dir {:prefix "super-sigkill"})
        dir (str (fs/path root "controller"))
        conversations (str (fs/path root "conversations"))
        opts {:dir dir :conversations conversations :scan-ms 10}
        proc (babashka.process/process
               {:out :string :err :string}
               "bb" "-cp" "src" "-e"
                (str "(require '[bbh.super :as s] '[babashka.fs :as fs]) (s/start! (assoc " (pr-str opts)
                     " :launch {:workspace! (fn [_ w] (str (fs/create-dirs (fs/path " (pr-str (str root)) " (:thread w)))))"
                     " :open! (fn [{:keys [token]}] (spit " (pr-str (str root "/token")) " token) \"buf\")})) @(promise)"))
        base (str (fs/path conversations "super-W1" "jobs" "survivor"))]
    (try
      (is (wait-for #(fs/exists? (fs/path dir "port"))))
      (call dir {:command :thread! :args {:title "survivor"}})
      (call dir {:command :start-worker! :args {:thread "T1" :workflow :investigation :brief "look" :repo (str (fs/home))}})
      (is (wait-for #(fs/exists? (fs/path root "token"))))
      (fs/create-dirs (fs/parent base))
      (spit (str base ".cmd") "one execution")
      (babashka.process/shell {:out :discard :err :discard} "setsid" "-f" "bash" "-c"
        (str "echo $$ > " (pr-str (str base ".pid"))
             "; stat=$(cat /proc/$$/stat); rest=${stat##*) }; set -- $rest; shift 19; echo $1 > " (pr-str (str base ".start"))
             "; while [ ! -f " (pr-str (str base ".release")) " ]; do sleep 0.02; done; echo ran >> "
             (pr-str (str base ".log")) "; echo 0 > " (pr-str (str base ".exit"))))
      (is (wait-for #(fs/exists? (str base ".start"))))
      (.destroyForcibly (:proc proc))
      (deref proc 10000 nil)
      (let [c (super/start! opts)]
        (try
          (super/reconcile! c)
          (is (= :running (get-in @(:state c) [:workers "W1" :health :jobs 0 :state])))
          (is (= ["o1"] (keys (:outbox @(:state c)))) "only the brief")
          (spit (str base ".release") "go")
          (is (wait-for #(some (fn [m] (clojure.string/starts-with? (:text m) "[Job")) (bbh.inbox/pending (str (fs/path conversations "super-W1"))))))
          (is (= "ran\n" (slurp (str base ".log"))))
          (is (= 1 (count (filter #(= :job-exited (:kind %)) (store/events (:db c))))))
          (finally (super/stop! c))))
      (finally
        (spit (str base ".release") "go")
        (when (babashka.process/alive? proc) (.destroyForcibly (:proc proc)))
        (fs/delete-tree root)))))

(deftest a-draft-lives-in-the-controller-until-a-reply-takes-it
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!
              draft #(get-in @(:state c) [:threads "T1" :draft])]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token version]} (thread-with-ask dir)
                  updated (get-in @(:state c) [:threads "T1" :updated-at])]
              (pane/pick! "T1" 2)
              (pane/note! "T1" "only for a week")
              (is (= {:picks {"a1" "week"} :note "only for a week" :asks-version version} (draft)))
              (is (= updated (get-in @(:state c) [:threads "T1" :updated-at])) "a draft does not move the thread in the Inbox")
              (fs/delete-if-exists pane/*state-file*)
              (is (re-find #"2\. Yes, for a week  <- picked" (pane/org-view (:ok (call dir {:command :sections :args {}}))))
                  "the pick outlives this client's state, as across an Emacs restart")
              (pane/send! "T1")
              (is (= [[{"a1" "week"} "only for a week"]] (mapv (juxt :picks :note) (vals (:replies @(:state c))))))
              (is (nil? (draft)) "the reply takes the draft")
              (call dir {:token token :command :ask! :args {:kind :question :text "Again?" :options [{:id "y" :label "Y"}]}})
              (pane/note! "T1" "hm")
              (call dir {:token token :command :ask! :args {:kind :question :text "And?" :options [{:id "z" :label "Z"}]}})
              (is (thrown-with-msg? Exception #"stale" (pane/pick! "T1" 1)) "a draft from before a new ask refuses a pick")
              (pane/-main "clear" "T1")
              (is (nil? (draft)))
              (pane/pick! "T1" 1)
              (is (= {"a3" "z"} (:picks (draft))))
              (locking (:db c) (is (= (store/replay (:db c)) @(:state c)))))))))))

(deftest voice-drafts-need-send-and-action-asks-require-keyboard
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)]
              (pane/select! "T1")
              (is (= {:thread "T1" :draft true :option 2} (voice/submit! "option two" :target :selected)))
              (is (= {"a1" "week"} (get-in @(:state c) [:threads "T1" :draft :picks])) "the pick is the thread's draft in the controller")
              (is (= {} (:replies @(:state c))))
              (is (= {:thread "T1" :sent true} (voice/submit! "send" :target :selected)))
              (is (= [{"a1" "week"}] (mapv :picks (vals (:replies @(:state c))))))
              (call dir {:token token :command :ask! :args {:kind :action :text "Switch system?" :options [{:id "yes" :label "Yes"}]}})
              (is (thrown-with-msg? Exception #"Voice cannot approve" (voice/submit! "option one" :target :selected)))
              (pane/pick! "T1" 1)
              (is (thrown-with-msg? Exception #"keyboard" (voice/submit! "send" :target :selected))))))))))

(deftest voice-expiry-handle-precedence-and-stale-drafts
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)]
              (call dir {:command :register-router! :args {:conversation "router"}})
              (call dir {:command :thread! :args {:title "second"}})
              (run-worker dir "T2")
              (pane/select! "T2")
              (is (= {:thread "T1" :draft true :option 2} (voice/submit! "tee one, option two")))
              (call dir {:token token :command :ask! :args {:kind :question :text "A new question" :options []}})
              (is (thrown? Exception (voice/submit! "tee one send")))
              (spit pane/*state-file* (pr-str {:selected {:thread "T2" :at 1}}))
              (is (= {:to :router} (voice/submit! "investigate my build" :target :selected)))
              (is (= :voice (:origin (:data (last (filter #(= :router-queued (:kind %)) (store/events (:db c)))))))))))))))

(deftest the-agent-voice-binding-goes-to-the-router-whatever-the-inbox-selected
  ;; Cmd+Shift+M runs `bbh say start TAKE` and `bbh say stop TAKE`, which end in
  ;; stop!. A thread the Inbox cursor selected must not become a "T1:" prefix.
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))
                        voice/hex! (fn [& _] {:exit 0 :out "Hey, look at my commit history"})]
            (thread-with-ask dir)
            (call dir {:command :register-router! :args {:conversation "router"}})
            (pane/select! "T1")
            (is (= {:to :router} (voice/stop!)))
            (is (= ["[voice] (spoken) Hey, look at my commit history"] (mapv :text (filter #(= "router" (:conversation %)) (vals (:outbox @(:state c)))))))
            (is (nil? (get-in @(:state c) [:threads "T1" :draft])) "no draft was made on the selected thread")
            (testing "a spoken handle still names its thread"
              (is (= {:thread "T1" :draft true :option 2} (voice/submit! "tee one, option two"))))
            (testing "the selected-thread mode keeps acting on the selection"
              (is (= {:thread "T1" :sent true} (voice/submit! "send" :target :selected))))))))))

(deftest hex-stop-does-not-paste-or-route-silence
  (let [calls (atom [])]
    (with-redefs [voice/hex! (fn [& args] (swap! calls conj args) {:exit 4 :out ""})]
      (is (= {:silence true} (voice/stop!)))
      (is (= [["dictation" "stop" "--print"]] (mapv vec @calls))))))

(deftest handle-input-does-not-target-undeliverable-workers
  (doseq [[worker-state conversation] [[:queued nil] [:starting nil] [:cancelling "super-W1"]]]
    (let [state (-> model/empty-state
                    (assoc-in [:threads "T1"] {:id "T1" :worker "W1"})
                    (assoc-in [:workers "W1"] {:id "W1" :thread "T1" :state worker-state :conversation conversation}))]
      (is (= :not-running
             (try (model/decide state :owner {:id "voice-probe" :command :say!
                                              :args {:text "tee one use plan B" :origin :voice}})
                  :accepted
                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))

(deftest the-router-sandbox-never-falls-back-to-the-owner-token
  (is (= [:unauthorized "bbh super: no Router token"]
         (try (sandbox/eval-in (sandbox/context sandbox/bindings) "(super-threads)")
              (catch clojure.lang.ExceptionInfo e [(:error (ex-data e)) (ex-message e)])))))

(defn- fake-nrepl!
  "A loopback socket that answers each request with one bencode map, as
  `describe` does, with its port in ROOT/repl.port. Returns the server."
  [root]
  (let [server (java.net.ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress))]
    (spit (str (fs/path root "repl.port")) (str (.getLocalPort server)))
    (doto (Thread. #(try (loop []
                           (with-open [s (.accept server)]
                             (bencode.core/read-bencode (java.io.PushbackInputStream. (.getInputStream s)))
                             (bencode.core/write-bencode (.getOutputStream s) {"status" ["done"]})
                             (.flush (.getOutputStream s)))
                           (recur))
                         (catch java.net.SocketException _ nil)))
      (.setDaemon true) (.start))
    server))

(deftest health-is-decided-from-what-the-controller-observed
  (let [w {:id "W1" :thread "T1" :state :working :conversation "super-W1" :wf-run "run1"}
        state {:outbox {"o1" {:id "o1" :thread "T1" :state :sent :conversation "super-W1"}}}
        asked (assoc state :asks {"a1" {:id "a1" :thread "T1" :worker "W1" :state :open :stopped true}})
        alive {:agent {:pid 7 :start "9"} :jobs [] :wf {:status :active} :repl? true :waits {}}
        judge (fn [w obs] ((juxt :classification :problem) (model/health state w obs 1000 60000)))
        judge-asked (fn [w obs] ((juxt :classification :problem) (model/health asked w obs 1000 60000)))]
    (is (= [:running nil] (judge w alive)))
    (is (= [:waiting nil] (judge-asked w alive)) "a stopped ask is open")
    (is (= [:running nil] (judge-asked w (assoc-in alive [:agent :busy] true))) "a turn wins over a stopped ask")
    (is (= [:running nil] (judge-asked (assoc w :id "W2") alive)) "another Worker's ask")
    (is (= [:waiting nil] (judge (assoc w :state :queued) {})))
    (is (= [:needs-human "Its session (pid 7) is alive but its REPL does not answer."] (judge w (assoc alive :repl? false))))
    (is (= [:needs-human "Delivery o1 has waited 2 min in its mailbox while the Worker runs; it is not taking messages."]
           (judge w (assoc alive :waits {"o1" 120000}))))
    (is (= [:running nil] (judge w (assoc alive :waits {"o1" 59999}))) "younger than the limit")
    (is (= [:needs-human "Job j1 can no longer be identified as the process that started it; check it before anything kills or reruns it."]
           (judge w (assoc alive :jobs [{:id "j1" :state :lost}]))))
    (is (= [:needs-human "Its wf run run1 is missing, so it cannot be resumed."] (judge w (assoc alive :wf {:status :missing}))))
    (is (= [:session-ended "Its session ended. Resume it with u in the Inbox or bbh inbox resume T1."] (judge w {:jobs [] :waits {}})))
    (is (= [:starting nil] (judge (assoc w :state :starting) {:jobs [] :waits {}})))
    (is (= "o1" (:oldest-unacked (model/health state w alive 1000 60000))))
    (is (= [500 1000] (mapv #(:since (model/health state (assoc w :health {:classification % :since 500}) alive 1000 60000)) [:running :waiting]))
        "since holds while the classification holds")))

(deftest a-steady-worker-writes-its-health-once
  (with-controller
    (fn [c dir _]
      (call dir {:command :thread! :args {:title "steady"}})
      (run-worker dir "T1")
      (let [before (count (store/events (:db c)))]
        (dotimes [_ 10] (super/reconcile! c))
        (is (= [:health-observed] (map :kind (drop before (store/events (:db c))))) "one observation, then nothing new")
        (is (= :session-ended (get-in @(:state c) [:workers "W1" :health :classification])))))))

(deftest a-delivery-left-in-a-live-workers-mailbox-past-the-policy-age-needs-you
  (with-controller
    (fn [c dir conversations]
      (call dir {:command :thread! :args {:title "stuck mailbox"}})
      (run-worker dir "T1")
      (let [root (str (fs/path conversations "super-W1"))
            poller (bbh.inbox/start! {:root root :project root :scan-ms 5 :deliver (fn [_])})
            server (fake-nrepl! root)
            health #(get-in @(:state c) [:workers "W1" :health])
            row #(first (:threads (first (filter (fn [s] (= :needs-attention (:section s))) (model/sections @(:state c))))))]
        (try
          (call dir {:command :steer! :args {:thread "T1" :text "use plan B"}})
          (is (wait-for #(= :sent (get-in @(:state c) [:outbox "o2" :state]))))
          (super/reconcile! c)
          (is (= [:running nil] [(:classification (health)) (:problem (health))]) "a fresh delivery is not an anomaly")
          (super/reconcile! (assoc c :policy {:stale-delivery-minutes 0}))
          (let [problem (str "Delivery o2" " has waited 0 min in its mailbox while the Worker runs; it is not taking messages.")]
            (is (= [:needs-human problem] [(:classification (health)) (:problem (health))]))
            (is (= ["T1" problem] [(:id (row)) (:line (row))]) "the row says why it needs you"))
          (doseq [m (bbh.inbox/pending root)] (bbh.inbox/ack! {:root root} (:id m)))
          (is (wait-for #(= :done (get-in @(:state c) [:outbox "o2" :state]))))
          (super/reconcile! (assoc c :policy {:stale-delivery-minutes 0}))
          (is (= [:running nil] [(:classification (health)) (:problem (health))]) "it clears once the Worker takes it")
          (bbh.inbox/stop! poller)
          (super/reconcile! c)
          (is (= :session-ended (:classification (health))) "a session gone with its wf run kept waits to be resumed")
          (finally (bbh.inbox/stop! poller) (.close server)))))))

(deftest a-continued-workers-repl-is-probed-where-it-runs
  (with-controller
    (fn [c dir conversations]
      (call dir {:command :thread! :args {:title "continued"}})
      (run-worker dir "T1")
      (let [root (str (fs/path conversations "super-W1"))
            next-root (str (fs/create-dirs (fs/path conversations "conv-2")))
            poller (bbh.inbox/start! {:root root :project root :scan-ms 5 :deliver (fn [_])})
            dead (doto (java.net.ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress)) .close)
            _ (spit (str (fs/path root "repl.port")) (str (.getLocalPort dead)))
            _ (spit (str (fs/path root "continued-in")) "conv-2")
            _ (fs/create-dirs (fs/path conversations "conv-3"))
            _ (spit (str (fs/path next-root "continued-in")) "conv-3")
            server (fake-nrepl! next-root)
            jobs (str (fs/create-dirs (fs/path next-root "jobs")))]
        (spit (str (fs/path jobs "j1.cmd")) "true")
        (spit (str (fs/path jobs "j1.exit")) "3\n")
        (try
          (is (wait-for #(bbh.inbox/agent root)))
          (super/observe-jobs! c)
          (is (= 3 (get-in @(:state c) [:workers "W1" :jobs "j1" :exit])) "a job the REPL started in conv-2 is seen")
          (super/reconcile! c)
          (is (= [true nil] ((juxt :repl :problem) (get-in @(:state c) [:workers "W1" :health])))
              "the REPL started in conv-2 keeps running after conv-3 continues it")
          (finally (bbh.inbox/stop! poller) (.close server)))))))

(deftest a-worker-whose-launch-fails-is-failed-with-the-reason-and-frees-its-slot
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-lf"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-lfconv"}))
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :policy {:workers 1}
                         :launch {:workspace! (fn [_ _] (throw (ex-info "jj workspace add: no repo" {})))}})]
    (try
      (call dir {:command :thread! :args {:title "bad repo"}})
      (is (= :failed (:error (start-worker dir "T1" :repo "/nonexistent"))))
      (is (= :failed (get-in @(:state c) [:workers "W1" :state])))
      (let [row (first (:threads (first (model/sections @(:state c)))))]
        (is (= [:needs-attention "Launch failed: jj workspace add: no repo"] [(:section row) (:line row)])))
      (call dir {:command :thread! :args {:title "next"}})
      (start-worker dir "T2" :repo "/nonexistent")
      (is (= :failed (get-in @(:state c) [:workers "W2" :state])) "W2 was not queued behind the dead W1")
      (finally (super/stop! c)))))

(deftest start-worker-refuses-a-missing-required-flag-before-any-workspace
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "no check"}})
      (is (= [:invalid :check] ((juxt :error (comp :field :data)) (start-worker dir "T1" :repo repo :check nil))))
      (is (= :unknown-workflow (:error (start-worker dir "T1" :repo repo :workflow :nope))))
      (is (empty? (:workers @(:state c))))
      (is (not (fs/exists? (fs/path dir "workspaces"))))
      (is (= :working (:state (:ok (start-worker dir "T1" :repo repo))))))))

(deftest routed-messages-ride-along-in-the-routers-next-batch-but-never-wake-it
  (with-controller
    (fn [c dir conversations]
      (call dir {:command :register-router! :args {:conversation "router"}})
      (call dir {:command :thread! :args {:title "Alt+W"}})
      (run-worker dir "T1")
      (let [router (fs/path conversations "router")
            prompts #(mapv (fn [f] (:text (cheshire.core/parse-string (slurp (str f)) true))) (fs/glob router "mailbox/*.json"))]
        (call dir {:command :say! :args {:text "T1 keep the alias" :origin :herdr}})
        (is (wait-for #(seq (fs/glob (fs/path conversations "super-W1") "mailbox/*.json"))) "the Worker got it")
        (Thread/sleep 100)
        (is (= [] (prompts)) "a routed message alone wakes no Router")
        (call dir {:command :say! :args {:text "what is running" :origin :phone}})
        (is (wait-for #(seq (prompts))))
        (is (wait-for #(= 2 (count (prompts)))))
        (is (= ["[routed to T1 W1 from herdr] keep the alias" "o4 [phone] what is running"] (sort (prompts))))))))

(deftest approval-binds-to-the-reviewed-revision-and-its-evidence
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-ap"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-apconv"}))
        wf-root (str (fs/create-temp-dir {:prefix "bbh-super-wf"}))
        evidence (fs/path wf-root "run-W1" "evidence")
        repo (jj-repo)
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :wf-root wf-root
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w))) :open! (fn [_] "wC:p1")}})]
    (try
      (fs/create-dirs evidence)
      (spit (str (fs/path evidence "verify.md")) "checks pass")
      (call dir {:command :thread! :args {:title "review me"}})
      (call dir {:command :start-worker! :args {:thread "T1" :workflow :investigation :brief "b" :repo repo}})
      (let [ask! (fn [m] (call dir {:command :ask! :args (merge {:thread "T1" :worker "W1" :kind :review :text "Ship it?"} m)}))]
        (is (= :invalid (:error (ask! {}))) "a review names its revision")
        (let [a1 (:ok (ask! {:revision "c068de11"}))
              sha (:evidence-sha (get-in @(:state c) [:asks a1]))
              approve #(call dir {:command :approve! :args (merge {:thread "T1" :ask a1 :revision "c068de11" :evidence-sha sha} %)})]
          (is (= 64 (count sha)))
          (is (= :stale (:error (approve {:revision "deadbeef"}))))
          (is (= :stale (:error (approve {:evidence-sha "0000"}))))
          (spit (str (fs/path evidence "verify.md")) "checks pass, then one more edit")
          (is (= [:stale "the evidence changed since the review was asked"] ((juxt :error :message) (approve {}))))
          (let [a2 (:ok (ask! {:revision "c068de12" :supersedes [a1]}))
                sha2 (:evidence-sha (get-in @(:state c) [:asks a2]))]
            (is (= :stale (:error (approve {}))) "a superseded review cannot be approved")
            (let [r (:ok (call dir {:command :approve! :args {:thread "T1" :ask a2 :revision "c068de12" :evidence-sha sha2}}))]
              (is (= :sent (get-in @(:state c) [:asks a2 :state])))
              (is (clojure.string/includes? (:text (get-in @(:state c) [:outbox (get-in @(:state c) [:replies r :outbox])]))
                                 (str "Approved " a2 " at revision c068de12 (evidence " (subs sha2 0 8) ")")))))))
      (finally (super/stop! c)))))

(deftest the-inbox-approves-a-review-at-what-it-shows-and-voice-cannot
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (call dir {:command :thread! :args {:title "review"}})
            (let [token (run-worker dir "T1")]
              (call dir {:token token :command :ask! :args {:kind :review :text "Ship it?" :revision "c068de11" :stopped true}})
              (is (clojure.string/includes? (pane/org-view (:ok (call dir {:command :sections :args {}})))
                                            "revision c068de11  evidence e3b0c442  A approve")
                  "the outline shows what A approves")
              (is (thrown-with-msg? Exception #"Voice cannot approve" (voice/submit! "tee one approve")))
              (is (thrown-with-msg? Exception #"Voice cannot approve" (voice/submit! "tee one option one")))
              (is (= :open (get-in @(:state c) [:asks "a1" :state])))
              (let [r (pane/approve! "T1")]
                (is (= {"a1" "c068de11"} (get-in @(:state c) [:replies r :approved])))
                (is (= :sent (get-in @(:state c) [:asks "a1" :state])))))))))))

(deftest the-list-holds-its-order-while-you-look-and-new-threads-wait
  (let [row (fn [id & {:as m}] (merge {:id id :title id} m))
        before [{:section :stopped :threads [(row "T1")]} {:section :working :threads [(row "T2") (row "T3")]}]
        shown (model/order before)
        now [{:section :stopped :threads [(row "T1") (row "T3" :line "Can I push?")]}
             {:section :working :threads [(row "T2") (row "T4")]}]
        {:keys [sections fresh]} (model/hold shown now)]
    (is (= [["T1" :stopped] ["T2" :working] ["T3" :working]] shown))
    (is (= [["T1" :stopped nil] ["T2" :working nil] ["T3" :working :stopped]]
           (for [{:keys [section threads]} sections t threads] [(:id t) section (:now t)])))
    (is (= "Can I push?" (:line (second (:threads (second sections))))) "a held row shows current data")
    (is (= 1 fresh) "T4 waits")
    (is (= (model/order now) (model/order (:sections (model/hold (model/order now) now)))) "revealing shows the current list")))

(deftest a-worker-stuck-needing-you-starts-no-other-thread
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-wd"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-wdconv"}))
        repo (jj-repo)
        launches (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :restart-grace-ms 3600000
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w))) :open! (fn [spec] (swap! launches conj spec) "wC:p1")}})]
    (try
      (call dir {:command :thread! :args {:title "Alt+W"}})
      (start-worker dir "T1" :repo repo :workflow :investigation)
      (dotimes [_ 3] (super/reconcile! c))
      (let [st @(:state c)]
        (is (= :needs-human (get-in st [:workers "W1" :health :classification])))
        (is (= ["T1"] (keys (:threads st))) "it shows under Needs attention and starts nothing else")
        (is (= 1 (count @launches))))
      (finally (super/stop! c)))))


;; ---- review regressions: the pure state machine -------------------------

(defn- run-commands
  "Commit each [principal command] in turn on an in-memory store. Returns the
  db, the final state and each result, a refusal as {:error kw}."
  [steps]
  (let [db (store/open nil)]
    (reduce (fn [{:keys [state] :as acc} [principal cmd]]
              (let [r (try (store/commit! db state principal cmd)
                           (catch clojure.lang.ExceptionInfo e {:state state :result {:error (:error (ex-data e))}}))]
                (-> acc (assoc :state (:state r)) (update :results conj (:result r)))))
            {:db db :state (store/replay db) :results []}
            steps)))

(defn- running
  "The controller's step that has Worker W running, as launch does."
  [w]
  [:controller {:id (str "run-" w) :command :worker-running!
                :args {:worker w :conversation (str "super-" w) :wf-run "run" :workspace "/w" :buffer "b" :started-at 0}}])

(defn- start-cmd [id thread & {:as args}]
  [:owner {:id id :command :start-worker! :token-hash (str "h-" id) :requires []
           :policy {:workers 1}
           :args (merge {:thread thread :workflow :change :brief "b" :repo "/r"} args)}])

(deftest a-worker-that-supersedes-its-stopped-ask-with-an-open-one-is-working
  (let [{:keys [state]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                       (start-cmd "at" "T1") (running "W1")
                                       [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "Which?" :stopped true}}]
                                       [[:worker "W1"] {:id "k2" :command :ask! :args {:kind :question :text "FYI" :supersedes ["a1"]}}]])]
    (is (= :working (get-in state [:workers "W1" :state])))
    (is (= :working (model/execution state "T1")))))

(deftest a-worker-answered-in-its-chat-is-working-and-its-asks-close
  ;; T72: the person typed the answer into W69's buffer, so no reply closed its
  ;; stopped asks and the Inbox showed it waiting while it worked.
  (let [health (fn [id h] [:controller {:id id :command :health!
                                         :args {:worker "W1" :at 0 :health (merge {:classification :running :process {:pid 7 :start "s"}} h)}}])
        asking [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                (start-cmd "at" "T1") (running "W1")
                [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "Which?" :stopped true}}]]
        busy (:state (run-commands (conj asking (health "h1" {:busy true :prompted-at 0}))))
        typed (:state (run-commands (conj asking (health "h1" {:busy true :prompted-at (+ (System/currentTimeMillis) 1000)}))))]
    (is (model/waiting? busy (get-in busy [:workers "W1"])) "a prompt older than the ask answers nothing")
    (is (= :working (get-in busy [:workers "W1" :state])) "waiting is read from the ask, not stored")
    (is (= :open (get-in busy [:asks "a1" :state])))
    (is (= :working (model/execution busy "T1")) "a session in a turn shows working")
    (is (= :superseded (get-in typed [:asks "a1" :state])))
    (is (= :working (get-in typed [:workers "W1" :state])))
    (is (not= :stopped (model/section typed (get-in typed [:threads "T1"]))))))

(deftest a-worker-on-an-archived-thread-resumes-when-its-stopped-ask-is-answered
  (let [asking [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                (start-cmd "at" "T1") (running "W1")
                [:owner {:id "ar" :command :archive! :args {:thread "T1"}}]
                [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "Which?" :options [{:id "y" :label "Y"}] :stopped true}}]]
        version (get-in (run-commands asking) [:state :threads "T1" :asks-version])
        {:keys [state]} (run-commands (conj asking [:owner {:id "r1" :command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "y"}}}]))]
    (is (= :sent (get-in state [:asks "a1" :state])))
    (is (= :working (get-in state [:workers "W1" :state])))
    (is (some? (get-in state [:threads "T1" :resolved-at])) "the thread stays archived")))

(deftest archiving-a-thread-cancels-its-queued-worker-so-it-never-launches
  (let [{:keys [state results]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                               [:owner {:id "t2" :command :thread! :args {:title "b"}}]
                                               (start-cmd "s1" "T1")
                                               (start-cmd "s2" "T2")
                                               [:owner {:id "ar" :command :archive! :args {:thread "T2"}}]
                                               [:controller {:id "f1" :command :finish-worker! :args {:worker "W1" :state-after :done}}]
                                               [:controller {:id "p1" :command :promote-worker! :token-hash "h-p" :policy {:workers 2}}]])]
    (is (= :queued (:state (nth results 3))))
    (is (nil? (last results)) "nothing to promote")
    (is (= [:cancelled "thread archived"] ((juxt :state :reason) (get-in state [:workers "W2"]))))))

(deftest a-starting-worker-holds-the-only-slot-so-a-second-one-queues
  (let [{:keys [state results]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                               [:owner {:id "t2" :command :thread! :args {:title "b"}}]
                                               (start-cmd "s1" "T1")
                                               (start-cmd "s2" "T2")])]
    (is (= :starting (:state (nth results 2))))
    (is (= [:queued "1 Workers already running"] ((juxt :state :reason) (nth results 3))))
    (is (= #{:counters :threads :workers :asks :replies :outbox :tokens :router} (set (keys state))) "no lock records")))

(deftest a-thread-with-a-starting-worker-takes-no-second-one
  (let [{:keys [results]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                         (start-cmd "s1" "T1")
                                         (start-cmd "s2" "T1")])]
    (is (= [:thread-has-worker] (map :error (drop 2 results))))))

(deftest removed-commands-are-unknown
  (let [removed [:attach-worker! :link! :takeover! :handback! :restart-worker! :restart-failed! :worker-restarted!]
        error #(:error (first (:results (run-commands [[:owner {:id "c1" :command % :args {:thread "T1" :worker "W1"}}]]))))]
    (is (= (zipmap removed (repeat :unknown-command)) (zipmap removed (map error removed))))))

(deftest a-stopped-ask-does-not-undo-a-cancel
  (let [{:keys [state results]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                               (start-cmd "a1" "T1") (running "W1")
                                               [:owner {:id "c1" :command :cancel! :args {:thread "T1" :reason "stop"}}]
                                               [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "q?" :stopped true}}]
                                               [:controller {:id "cd" :command :cancel-delivered! :args {:worker "W1"}}]])]
    (is (= :cancelling (:error (nth results 4))))
    (is (= :cancelled (get-in state [:workers "W1" :state])))))

(deftest thread-execution-is-derived-not-stored
  (let [{:keys [state]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                       (start-cmd "s1" "T1") (running "W1")
                                       [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "ok?" :stopped true}}]])]
    (is (not (contains? (get-in state [:threads "T1"]) :execution)) "the record holds no copy")
    (is (= :waiting (model/execution state "T1")))
    (is (= :waiting (:execution (model/thread-view state (get-in state [:threads "T1"])))))
    (is (= :working (get-in state [:workers "W1" :state])) "nor does the Worker hold a copy")
    (is (= :waiting (:state (model/linked state :workers "W1"))) "a read of the Worker shows it")))

(deftest a-worker-waits-exactly-while-its-stopped-ask-is-open
  ;; T79: `resume` copied the asks into the Worker's state at four folds, and 23
  ;; of 68 stopped asks ended without one, so the Worker worked on as :waiting.
  (let [base [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
              (start-cmd "s1" "T1") (running "W1")
              [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "ok?" :stopped true}}]]
        shown (fn [more] (let [state (:state (run-commands (into base more)))]
                           [(get-in state [:workers "W1" :state]) (:state (model/linked state :workers "W1")) (model/execution state "T1")]))]
    (is (= [:working :waiting :waiting] (shown [])))
    (is (= [:working :working :working] (shown [[:owner {:id "d1" :command :dismiss! :args {:thread "T1"}}]]))
        "a dismissed ask")
    (is (= [:working :working :working] (shown [[[:worker "W1"] {:id "k2" :command :ask! :args {:kind :question :text "FYI" :supersedes ["a1"]}}]]))
        "superseded by an ask that does not stop")
    (is (= [:working :waiting :waiting] (shown [[[:worker "W1"] {:id "k2" :command :ask! :args {:kind :question :text "and?" :stopped true :supersedes ["a1"]}}]]))
        "superseded by one that does")
    (is (= [:cancelling :cancelling :working] (shown [[:owner {:id "c1" :command :cancel! :args {:thread "T1" :reason "stop"}}]]))
        "a cancel wins")
    (is (= [:done :done :done] (shown [[[:worker "W1"] {:id "f1" :command :finish-worker! :args {:state-after :done}}]])))))

(deftest a-finished-workers-open-asks-are-superseded
  (let [{:keys [state]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                               (start-cmd "a1" "T1") (running "W1")
                                               [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :review :text "ok?" :revision "abc" :evidence-sha "none"}}]
                                               [[:worker "W1"] {:id "f1" :command :finish-worker! :args {:state-after :failed}}]])]
    (is (= :superseded (get-in state [:asks "a1" :state])))
    (is (empty? (filter #(= :open (:state %)) (vals (:asks state)))))))

(deftest a-late-ack-does-not-move-an-answered-reply-back
  (let [{:keys [state]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                       (start-cmd "a1" "T1") (running "W1")
                                       [[:worker "W1"] {:id "k1" :command :ask! :args {:kind :question :text "q?" :options [{:id "y" :label "Y"}]}}]
                                       [:owner {:id "r1" :command :reply! :args {:thread "T1" :asks-version 5 :picks {"a1" "y"}}}]
                                       [:controller {:id "s1" :command :outbox-sent! :args {:outbox "o2" :message "m1"}}]
                                       [[:worker "W1"] {:id "n1" :command :answered! :args {:reply "r1"}}]
                                       [:controller {:id "ack" :command :outbox-acked! :args {:outbox "o2"}}]
                                       [:controller {:id "s2" :command :outbox-sent! :args {:outbox "o2" :message "m1"}}]])]
    (is (= :answered (get-in state [:replies "r1" :state])))
    (is (= :answered (get-in state [:asks "a1" :state])))
    (is (= :done (get-in state [:outbox "o2" :state])))))

(deftest controller-commands-refuse-a-missing-or-ended-worker
  (let [{:keys [results]} (run-commands [[:controller {:id "p" :command :worker-prepared! :args {:worker "W9"}}]
                                         [:controller {:id "h" :command :health! :args {:worker "W9" :health {} :at 1}}]
                                         [:controller {:id "h0" :command :health! :args {:at 1}}]])]
    (is (= [:no-such-worker :no-such-worker :no-such-worker] (map :error results)) "health names its Worker")))

(deftest promotion-is-one-commit-so-a-queued-worker-starts-once
  (let [{:keys [state results]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                               [:owner {:id "t2" :command :thread! :args {:title "b"}}]
                                               (start-cmd "s1" "T1")
                                               (start-cmd "s2" "T2")
                                               [:controller {:id "f1" :command :finish-worker! :args {:worker "W1" :state-after :done}}]
                                               [:controller {:id "p1" :command :promote-worker! :token-hash "hp1" :policy {:workers 1}}]
                                               [:controller {:id "p2" :command :promote-worker! :token-hash "hp2" :policy {:workers 1}}]])]
    (is (= "W2" (nth results 5)))
    (is (nil? (nth results 6)) "nothing left to promote")
    (is (= :starting (get-in state [:workers "W2" :state])))
    (is (= [:worker "W2"] (get-in state [:tokens "hp1" :principal])))))


(deftest a-worker-on-an-archived-thread-holds-no-slot
  (let [policy {:workers 1}
        {:keys [results state]}
        (run-commands [[:owner {:id "t1" :command :thread! :args {:title "archived"}}]
                       [:owner {:id "t2" :command :thread! :args {:title "next"}}]
                       [:owner {:id "t3" :command :thread! :args {:title "after"}}]
                       (start-cmd "a1" "T1") (running "W1")
                       [:owner {:id "r1" :command :archive! :args {:thread "T1"}}]
                       [:owner {:id "s2" :command :start-worker! :token-hash "h2" :requires [] :policy policy
                                :args {:thread "T2" :workflow :change :brief "b" :repo "/r"}}]
                       [:owner {:id "s3" :command :start-worker! :token-hash "h3" :requires [] :policy policy
                                :args {:thread "T3" :workflow :change :brief "b" :repo "/r"}}]])]
    (is (= :starting (:state (nth results 6))) "W1 on the archived thread holds no slot")
    (is (= [:queued "1 Workers already running"] ((juxt :state :reason) (nth results 7))) "W2 does hold it")
    (is (= :working (get-in state [:workers "W1" :state])) "archiving leaves the Worker itself alone")))

;; ---- review regressions: the controller ---------------------------------

(deftest a-silent-client-does-not-block-the-controller
  (with-controller
    (fn [_ dir _]
      (with-open [_ (java.net.Socket. "127.0.0.1" (int (parse-long (clojure.string/trim (slurp (str (fs/path dir "port")))))))]
        (is (= {:ok []} (deref (future (call dir {:command :threads})) 3000 :blocked)))))))

(deftest the-owner-token-file-is-never-readable-by-others
  (with-controller
    (fn [_ dir _]
      (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString (fs/posix-file-permissions (fs/path dir "token"))))))))


;; ---- review regressions: voice and the Inbox pane -------------------------

(deftest voice-takes-a-pick-and-send-in-one-breath
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)
                  replies #(mapv :picks (sort-by :seq (vals (:replies @(:state c)))))]
              (pane/select! "T1")
              (is (= {:thread "T1" :draft true :option 2 :sent true} (voice/submit! "Yes, for a week, send." :target :selected)))
              (is (= [{"a1" "week"}] (replies)))
              (call dir {:token token :command :ask! :args {:kind :question :text "Which build?" :recommended "small"
                                                           :options [{:id "big" :label "Big"} {:id "small" :label "Small"}]}})
              (is (= {:thread "T1" :draft true :option 2 :sent true} (voice/submit! "the recommended one, send" :target :selected)))
              (call dir {:token token :command :ask! :args {:kind :question :text "Again?" :options [{:id "y" :label "Yes"} {:id "n" :label "No"}]}})
              (is (= {:thread "T1" :draft true :option 1 :sent true} (voice/submit! "Option 1 and send" :target :selected)))
              (is (= [{"a1" "week"} {"a2" "small"} {"a3" "y"}] (replies)))
              (is (thrown-with-msg? Exception #"Voice cannot approve" (voice/submit! "approve" :target :selected)))
              (is (= "T1" (:thread (voice/submit! "don't approve until CI passes" :target :selected)))
                  "a message that mentions approval is a message, not an approval"))))))))

(deftest a-pick-lands-only-on-the-ask-it-was-checked-against
  (with-controller
    (fn [_ dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)
                  draft #(:draft (:ok (call dir {:command :thread :args {:id "T1"}})))]
              (call dir {:token token :command :ask! :args {:kind :action :text "Switch?" :options [{:id "yes" :label "Yes"}]}})
              (pane/pick! "T1" 1 :ask "a1")
              (is (= {"a1" "no"} (:picks (draft))) "an older ask still open takes the pick")
              (pane/send! "T1")
              (is (thrown-with-msg? Exception #"changed" (pane/pick! "T1" 1 :ask "a1")) "a closed one refuses it")
              (is (nil? (draft))))))))))


(deftest a-quick-tap-stops-hex-only-after-it-started
  (let [calls (atom [])
        dir (str (fs/create-temp-dir {:prefix "bbh-say"}))]
    (with-redefs [voice/hex! (fn [& args] (Thread/sleep 50) (swap! calls conj (vec args))
                              (if (= "start" (second args)) {:exit 0 :out ""} {:exit 4 :out ""}))]
      ;; The release's stop process wins the race and runs before the press's start.
      (voice/request-stop! "take-1" :dir dir)
      (is (= {:silence true} (deref (future (voice/take! "take-1" :dir dir :poll-ms 5)) 3000 :hung)))
      (is (= [["dictation" "start"] ["dictation" "stop" "--print"]] @calls))
      (is (empty? (fs/list-dir dir)) "the stop request is used up"))))


;; ---- the super-agent's Emacs ---------------------------------------------

(defn- fake-emacsclient
  "A script that prints OUT and exits with EXIT, recording its arguments in a file."
  [exit out]
  (let [dir (fs/create-temp-dir {:prefix "bbh-emacsclient"})
        script (fs/path dir "emacsclient")]
    (spit (str (fs/path dir "out")) (str out "\n"))
    (spit (str script) (str "#!/bin/sh\nprintf '%s\\n' \"$@\" > " (fs/path dir "args") "\n"
                            "cat " (fs/path dir "out") (when-not (zero? exit) " >&2") "\nexit " exit "\n"))
    (fs/set-posix-file-permissions script "rwx------")
    {:bin (str script) :args #(clojure.string/split-lines (slurp (str (fs/path dir "args"))))}))

(deftest a-worker-opens-as-an-emacs-buffer-with-its-token-and-run
  (let [{:keys [bin args]} (fake-emacsclient 0 "\"T1 a\"")]
    (binding [bbh.super.emacs/*emacsclient* bin]
      (is (= "T1 a" (super/open! {:workspace "/w" :thread "T1" :title "a" :conversation "super-W1" :token "tok" :wf-run "run-1"})))
      (is (= ["-s" (bbh.super.emacs/server) "--timeout=30" "--eval"
              "(eng/bbh-super-open \"super-W1\" \"/w\" \"T1 a\" '((\"BBH_SUPER_TOKEN\" . \"tok\") (\"WF_RUN\" . \"run-1\") (\"BBH_ROLE\" . \"worker\")))"]
             (args))))))

(deftest a-worker-on-another-model-opens-with-bbh-model-set
  (let [{:keys [bin args]} (fake-emacsclient 0 "\"T1 a\"")]
    (binding [bbh.super.emacs/*emacsclient* bin]
      (super/open! {:workspace "/w" :thread "T1" :title "a" :conversation "super-W1" :token "tok" :wf-run "run-1" :model "claude/claude-fable-5-2"})
      (is (str/includes? (last (args)) "(\"BBH_MODEL\" . \"claude/claude-fable-5-2\")")))))

(deftest a-fable-worker-launches-and-resumes-on-its-model
  (with-workers {:worker-models ["claude/claude-fable-5-2"]}
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "fable"}})
      (call dir {:command :thread! :args {:title "plain"}})
      (is (= :invalid (:error (start-worker dir "T1" :repo repo :model "claude/claude-fabel-5-2"))) "a typo is refused")
      (start-worker dir "T1" :repo repo :model "claude/claude-fable-5-2")
      (start-worker dir "T2" :repo repo)
      (is (= ["claude/claude-fable-5-2" nil] (map :model @launches)) "only the Worker that asked runs on Fable")
      (is (= {"T1" "claude/claude-fable-5-2" "T2" nil}
             (into {} (for [s (:sections (:ok (call dir {:command :sections}))) t (:threads s)] [(:id t) (:model t)])))
          "bbh super list, the Inbox and the web show it")
      (with-redefs [bbh.workflow/status (fn [_] {:status :active :phase :implement})]
        (super/reconcile! (assoc c :restart-grace-ms -1))
        (is (= {:ok "W1"} (call dir {:command :resume! :args {:thread "T1"}})))
        (is (= ["super-W1" "claude/claude-fable-5-2"] ((juxt :conversation :model) (last @launches))) "a resume keeps it"))
      (locking (:db c) (is (= (store/replay (:db c)) @(:state c))) "the model survives a restart"))))

(deftest no-emacs-daemon-is-an-error-that-says-how-to-start-it
  (let [{:keys [bin]} (fake-emacsclient 1 "emacsclient: can't find socket; have you started the server?")]
    (binding [bbh.super.emacs/*emacsclient* bin]
      (let [e (try (super/open! {:workspace "/w" :thread "T1" :title "a" :conversation "c" :token "t" :wf-run "r"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :emacs (:error (ex-data e))))
        (is (clojure.string/includes? (ex-message e) "herd start bbh-emacs"))))))

(deftest a-launch-waits-for-a-down-daemon-and-fails-on-one-that-refuses
  (let [down (:bin (fake-emacsclient 1 "emacsclient: can't find socket"))
        ;; Answers the liveness check, refuses to open the buffer.
        refusing (str (fs/create-temp-file {:prefix "fake-emacsclient"}))
        dir (str (fs/create-temp-dir {:prefix "bbh-super-em"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-emconv"}))
        repo (jj-repo)
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :policy {:workers 2}
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w)))}})]
    (spit refusing "#!/bin/sh\nfor a; do last=$a; done\n[ \"$last\" = t ] && { echo t; exit 0; }\necho 'No such directory: /w' >&2\nexit 1\n")
    (fs/set-posix-file-permissions refusing "rwx------")
    (try
      ;; with-redefs, not binding: the controller launches on its own thread.
      (with-redefs [bbh.super.emacs/*emacsclient* down]
        (call dir {:command :thread! :args {:title "no daemon"}})
        (is (= :starting (:state (:ok (start-worker dir "T1" :repo repo)))))
        (is (= :starting (get-in @(:state c) [:workers "W1" :state])) "it waits for Emacs"))
      (with-redefs [bbh.super.emacs/*emacsclient* refusing]
        (call dir {:command :thread! :args {:title "refused"}})
        (is (= :emacs (:error (start-worker dir "T2" :repo repo))))
        (is (= :failed (get-in @(:state c) [:workers "W2" :state])))
        (is (clojure.string/includes? (get-in @(:state c) [:workers "W2" :reason]) "No such directory: /w")))
      (finally (super/stop! c)))))

(deftest the-inbox-finds-a-threads-worker-buffer
  (with-workers
    (fn [_ dir repo _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (call dir {:command :thread! :args {:title "a"}})
            (call dir {:command :thread! :args {:title "b"}})
            (start-worker dir "T1" :repo repo)
            (is (= "wC:p1\n" (with-out-str (pane/-main "buffer" "T1"))))
            ;; The error path exits the process, so it runs as the real command, pointed at this controller.
            (let [data (fs/create-temp-dir {:prefix "bbh-super-data"})
                  _ (fs/create-sym-link (fs/path (fs/create-dirs (fs/path data "bbh")) "super") dir)
                  r (babashka.process/shell {:out :string :err :string :continue true
                                             :env (assoc (hermetic/env) "XDG_DATA_HOME" (str data) "XDG_STATE_HOME" (str data))}
                                            "bb" "-e" "(require 'bbh.super.pane) (bbh.super.pane/-main \"buffer\" \"T2\")")]
              (is (= 1 (:exit r)))
              (is (clojure.string/includes? (:err r) "T2 has no Worker") (:err r)))))))))


(defn- jj-in [dir & args]
  ;; A nil DIR would run jj in the checkout the tests run from.
  (when-not (and dir (fs/starts-with? dir (System/getProperty "java.io.tmpdir")))
    (throw (ex-info (str "jj-in outside the temporary directory: " dir) {})))
  (let [{:keys [exit out err]} (apply babashka.process/shell {:dir dir :out :string :err :string :continue true} "jj" args)]
    (when-not (zero? exit) (throw (ex-info (str "jj " args ": " err) {})))
    (clojure.string/trim out)))

(defn- line-of
  "The descriptions from REV down to the root, skipping empty undescribed commits."
  [repo rev]
  (clojure.string/split-lines
   (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" (str "::" rev " ~ root() ~ (empty() & description(exact:\"\"))")
          "-T" "description.first_line() ++ \"\\n\"")))

(defn- landed [c worker] (get-in @(:state c) [:workers worker :landed]))

(defn- workspaces [repo]
  (set (clojure.string/split-lines (jj-in repo "--ignore-working-copy" "workspace" "list" "-T" "name ++ \"\\n\""))))

(defn- commit-file [workspace file text message]
  (assert workspace "a Worker without a workspace")
  (spit (str (fs/path workspace file)) text)
  (jj-in workspace "commit" "-m" message))

(deftest finished-workers-land-in-one-line-and-the-users-change-stays-put
  (with-workers
    (fn [c dir repo _]
      (doseq [t ["one" "two" "three"]] (call dir {:command :thread! :args {:title t}}))
      (let [[w1 w2] (map #(:ok (start-worker dir % :repo repo)) ["T1" "T2"])]
        (assert (every? :workspace [w1 w2]) "both Workers started")
        (spit (str (fs/path repo "mine")) "in progress\n")
        (let [users (jj-in repo "log" "--no-graph" "-r" "@" "-T" "commit_id")]
          (commit-file (:workspace w1) "one" "1\n" "one")
          (commit-file (:workspace w2) "two" "2\n" "[T2] two")
          (is (= {:ok "W2"} (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
          (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})))
          (is (= ["[T1] one" "[T2] two" "start"] (line-of repo (landed c "W1")))
              "the later finish lands on the earlier one, each change prefixed with its thread once")
          (is (= #{"default"} (workspaces repo)) "a done Worker's workspace is forgotten")
          (is (not (fs/exists? (:workspace w1))) "and its directory removed")
          (is (= "[T1] one" (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "heads(all()) ~ default@" "-T" "description.first_line()"))
              "no empty change dangles beside the line")
          (is (= users (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "default@" "-T" "commit_id"))
              "the user's working-copy change is not rewritten")
          (let [w3 (:ok (start-worker dir "T3" :repo repo))]
            (assert (:workspace w3) "the third Worker started")
            (spit (str (fs/path (:workspace w3) "one")) "clash\n")
            (is (= :in-progress (:error (call dir {:command :finish-worker! :args {:worker "W3" :state-after :done}}))))
            (jj-in (:workspace w3) "commit" "-m" "three")
            (let [r (call dir {:command :finish-worker! :args {:worker "W3" :state-after :done}})]
              (is (= 1 (count (:conflicts (:ok r)))) (pr-str r))
              (is (re-find #"Resolve them now in your workspace" (:todo (:ok r))) "it tells the Worker to resolve them"))
            (is (= :working (:state (get-in @(:state c) [:workers "W3"]))) "a conflicted landing leaves the Worker running")
            (is (fs/exists? (:workspace w3)) "and its workspace in place")
            (is (= ["[T3] three" "[T1] one" "[T2] two" "start"] (line-of repo "T3-1@")) "its commits moved, conflicted, onto the tip")
            (spit (str (fs/path (:workspace w3) "one")) "clash\n")
            (jj-in (:workspace w3) "squash")
            (is (= {:ok "W3"} (call dir {:command :finish-worker! :args {:worker "W3" :state-after :done}})) "once resolved it lands")
            (is (= ["[T3] three" "[T1] one" "[T2] two" "start"] (line-of repo (landed c "W3"))))
            (is (= #{"default"} (workspaces repo)))))))))

(defn- wip-changes [repo]
  (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "description(regex:\"^(.T[0-9]+. )?wip\")" "-T" "description.first_line() ++ \"\\n\""))

(deftest a-workers-change-is-described-wip-until-it-commits-and-wip-never-lands
  (with-workers
    (fn [c dir repo _]
      (doseq [t ["idle" "busy"]] (call dir {:command :thread! :args {:title t}}))
      (let [[w1 w2] (map #(:ok (start-worker dir % :repo repo)) ["T1" "T2"])]
        (assert (every? :workspace [w1 w2]) "both Workers started")
        (is (= "[T1] wip(W1): idle" (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "T1-1@" "-T" "description.first_line()"))
            "the user's jj log names the Worker and its thread")
        (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})) "an idle Worker finishes")
        (is (nil? (landed c "W1")) "landing nothing")
        (is (= "[T2] wip(W2): busy" (wip-changes repo)) "and its empty wip change is abandoned with its workspace")
        (spit (str (fs/path (:workspace w2) "two")) "2\n")
        (is (= :in-progress (:error (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
            "edits under the wip description are uncommitted work")
        (jj-in (:workspace w2) "new")
        (is (= :wip (:error (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
            "a commit still described wip does not land")
        (jj-in (:workspace w2) "describe" "-r" "@-" "-m" "wip(T2 W2): busy")
        (is (= :wip (:error (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
            "nor one in the older wip form without the prefix")
        (jj-in (:workspace w2) "describe" "-r" "@-" "-m" "two")
        (is (= {:ok "W2"} (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
        (is (= ["[T2] two" "start"] (line-of repo (landed c "W2"))))
        (is (= "" (wip-changes repo)) "no wip change is left anywhere")))))

(deftest retiring-unlanded-work-keeps-it-but-not-an-empty-wip-change
  (let [repo (jj-repo)
        dir (str (fs/create-temp-dir {:prefix "bbh-super-ws"}))
        idle (workspace/workspace! dir {:repo repo :thread "T1" :id "W1" :title "idle" :generation 1})
        busy (workspace/workspace! dir {:repo repo :thread "T2" :id "W2" :title "busy" :generation 1})]
    (is (nil? (workspace/retire! {:repo repo :workspace idle :keep? true})) "an empty wip change is no work to keep")
    (spit (str (fs/path busy "x")) "x\n")
    (is (= "super/T2-1" (workspace/retire! {:repo repo :workspace busy :keep? true})))
    (is (= "[T2] wip(W2): busy" (wip-changes repo)) "the kept work stays, under its bookmark, still marked wip")))

(defn- parent-of-default [repo]
  (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "default@-" "-T" "change_id"))

(deftest a-landing-moves-the-users-empty-working-copy-and-leaves-one-with-changes
  (with-workers
    (fn [c dir repo _]
      (doseq [t ["one" "two"]] (call dir {:command :thread! :args {:title t}}))
      (let [w1 (:ok (start-worker dir "T1" :repo repo))]
        (commit-file (:workspace w1) "one" "1\n" "one")
        (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})))
        (is (= (landed c "W1") (parent-of-default repo)) "an empty working copy moves onto the new tip")
        (is (= "1\n" (slurp (str (fs/path repo "one")))) "and the checkout has the new work")
        (let [w2 (:ok (start-worker dir "T2" :repo repo))]
          (commit-file (:workspace w2) "two" "2\n" "two")
          ;; Edited but not yet seen by jj: advance! must snapshot before it judges.
          (spit (str (fs/path repo "mine")) "in progress\n")
          (is (= {:ok "W2"} (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
          (is (= (landed c "W1") (parent-of-default repo)) "a working copy with changes stays")
          (is (= "in progress\n" (slurp (str (fs/path repo "mine")))) "with its edit")
          (is (not (fs/exists? (fs/path repo "two"))))
          (is (re-find #"^W2 landed \w{8}\. Your working copy has changes, so it was left where it is\. To take it: jj rebase -r @ -d \w{8} in "
                       (get-in @(:state c) [:threads "T2" :summary]))
              "the thread says so and how to take the work"))))))

(deftest a-user-who-opts-in-has-a-working-copy-with-changes-moved
  (with-workers {:move-working-copy :always}
    (fn [c dir repo _]
      (call dir {:command :thread! :args {:title "one"}})
      (let [w1 (:ok (start-worker dir "T1" :repo repo))]
        (commit-file (:workspace w1) "one" "1\n" "one")
        (spit (str (fs/path repo "mine")) "in progress\n")
        (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})))
        (is (= (landed c "W1") (parent-of-default repo)))
        (is (= ["1\n" "in progress\n"] (map #(slurp (str (fs/path repo %))) ["one" "mine"])))))))

(deftest advance-moves-nothing-it-cannot-judge
  (let [repo (jj-repo)
        ws (str (fs/create-temp-dir {:prefix "bbh-super-adv"}) "/w")]
    (jj-in repo "workspace" "add" "--name" "w" ws)
    (commit-file ws "x" "x\n" "x")
    (let [tip (jj-in ws "log" "--no-graph" "-r" "@-" "-T" "commit_id")
          before (jj-in repo "log" "--no-graph" "-r" "default@" "-T" "commit_id")]
      (is (= :skipped (workspace/advance! {:repo ws :tip tip})) "a repo dir that is not the default workspace")
      (is (= :failed (:kept (workspace/advance! {:repo repo :tip "nosuchrev"}))))
      (is (= before (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "default@" "-T" "commit_id")))
      (is (= :moved (workspace/advance! {:repo repo :tip tip})))
      (is (= :current (workspace/advance! {:repo repo :tip tip})) "a repeat is a no-op")
      (jj-in repo "commit" "-m" "mine")
      (is (= :current (workspace/advance! {:repo repo :tip tip})) "a working copy above the tip stays above it")
      (is (= "mine" (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "default@-" "-T" "description.first_line()")))
      ;; jj edit of a landed commit, then a landing on top of it: nothing to move.
      (jj-in repo "edit" tip)
      (jj-in ws "new" tip)
      (commit-file ws "y" "y\n" "y")
      (let [tip2 (jj-in ws "log" "--no-graph" "-r" "@-" "-T" "commit_id")
            r (workspace/advance! {:repo repo :tip tip2 :move :always})]
        (is (= {:kept :editing :at tip} r) "an edited ancestor of the tip is reported as such")
        (is (re-find #"on top of \w{8}, the commit your working copy edits \(jj edit\), so it stays there\. To work on the tip: jj new \w{8} in "
                     (workspace/kept-milestone {:worker "W9" :repo repo :tip tip2} r)))))))

(deftest an-unclear-tip-is-refused-until-the-worker-names-one
  (with-workers
    (fn [c dir repo _]
      (doseq [t ["one" "two"]] (call dir {:command :thread! :args {:title t}}))
      (let [[w1 w2] (map #(:ok (start-worker dir % :repo repo)) ["T1" "T2"])]
        (assert (every? :workspace [w1 w2]) "both Workers started")
        (commit-file (:workspace w1) "one" "1\n" "one")
        (commit-file (:workspace w2) "two" "2\n" "two")
        ;; Work in progress keeps the user's change where it is, so theirs forks from start.
        (spit (str (fs/path repo "yours")) "y\n")
        (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})
        (jj-in repo "commit" "-m" "yours")
        (let [r (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})]
          (is (= :unclear-tip (:error r)))
          (is (re-find #"2 heads.*one|2 heads.*yours" (:message r)) (:message r)))
        (is (= {:ok "W2"} (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done :onto (landed c "W1")}})))
        (is (= ["[T2] two" "[T1] one" "start"] (line-of repo (landed c "W2"))))))))

(deftest inbox-views-reload-on-a-change-but-not-on-a-health-pass-that-found-nothing-new
  (with-controller
    (fn [c dir _]
      (let [server (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
            pokes (atom 0)
            settled #(let [n @pokes] (Thread/sleep 200) (= n @pokes))]
        (future (while true (with-open [_ (.accept server)] (swap! pokes inc))))
        (try
          (client/register-view! dir (.getLocalPort server))
          (let [{:keys [token]} (thread-with-ask dir)]
            (super/reconcile! c)
            (is (wait-for settled))
            (let [before @pokes]
              (super/reconcile! c)
              (is (wait-for settled))
              (is (= before @pokes) "a health pass that saw the same health pokes no view")
              (call dir {:token token :command :note! :args {:text "looking at kanata"}})
              (is (wait-for #(< before @pokes)) "a Worker's note pokes the views")))
          (finally (.close server)))))))

(deftest failed-work-stays-until-its-thread-is-archived-then-is-kept-under-a-bookmark
  (with-workers
    (fn [c dir repo _]
      (call dir {:command :thread! :args {:title "one"}})
      (let [w (:ok (start-worker dir "T1" :repo repo))]
        (assert (:workspace w) "the Worker started")
        (commit-file (:workspace w) "one" "1\n" "half done")
        (spit (str (fs/path (:workspace w) "two")) "uncommitted\n")
        (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "stuck"}})
        (is (= #{"default" "T1-1"} (workspaces repo)) "a failed Worker's workspace stays for inspection")
        (is (fs/exists? (:workspace w)))
        (call dir {:command :archive! :args {:thread "T1"}})
        (is (= #{"default"} (workspaces repo)) "archiving the thread forgets it")
        (is (not (fs/exists? (:workspace w))) "and deletes its directory")
        (is (= ["half done" "start"] (line-of repo "super/T1-1")) "its work is kept under a bookmark")
        (is (= "uncommitted" (jj-in repo "--ignore-working-copy" "file" "show" "-r" "super/T1-1" "two"))
            "including what it had not committed")))))

(deftest retire-is-idempotent-and-survives-a-forgotten-workspace-or-a-missing-repo
  (let [repo (jj-repo)
        ws (str (fs/path (fs/create-temp-dir) "T9-1"))]
    (jj-in repo "workspace" "add" "--name" "T9-1" ws)
    (is (nil? (workspace/retire! {:repo repo :workspace ws})))
    (is (= #{"default"} (workspaces repo)))
    (fs/create-dirs ws)
    (is (nil? (workspace/retire! {:repo repo :workspace ws :keep? true})) "a forgotten workspace's directory still goes")
    (is (not (fs/exists? ws)))
    (is (nil? (workspace/retire! {:repo repo :workspace ws})) "a second retire is a no-op")
    (fs/create-dirs ws)
    (is (nil? (workspace/retire! {:repo (str repo "-gone") :workspace ws})) "a missing repo is skipped")
    (is (not (fs/exists? ws)))))

(deftest sweep-lists-orphaned-workspaces-and-retires-them-only-when-confirmed
  (with-workers
    (fn [c dir repo _]
      (doseq [t ["one" "two"]] (call dir {:command :thread! :args {:title t}}))
      (let [[w1 w2] (map #(:ok (start-worker dir % :repo repo)) ["T1" "T2"])
            stray (str (fs/path dir "workspaces" "T7-1"))]
        (assert (every? :workspace [w1 w2]) "both Workers started")
        (jj-in repo "workspace" "add" "--name" "T7-1" stray)
        (call dir {:command :finish-worker! :args {:worker "W2" :state-after :failed :reason "x"}})
        (is (= [{:workspace stray :worker nil :state nil :repo repo}]
               (:ok (call dir {:command :sweep! :args {}})))
            "a running Worker and a failed one on an open thread are not orphans; a directory nothing owns is")
        (is (fs/exists? stray) "a dry run deletes nothing")
        (is (= [{:workspace stray :worker nil :state nil :repo repo :bookmark nil}]
               (:ok (call dir {:command :sweep! :args {:confirm true}}))))
        (is (= #{"default" "T1-1" "T2-1"} (workspaces repo)))
        (is (not (fs/exists? stray)))))))

(deftest a-workers-session-leaves-its-workspace-before-the-workspace-goes
  ;; Its Emacs shell outlives the Worker. Left in a deleted directory it stays
  ;; busy forever, and every message to it waits in its queue.
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-r"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-rconv"}))
        repo (jj-repo)
        released (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10 :policy {:workers 2}
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w))) :open! (fn [_] "buf")
                                  :release! (fn [spec] (swap! released conj (assoc spec :workspaces (set (map (comp str fs/file-name) (fs/list-dir (fs/path dir "workspaces"))))))
                                              "buf")}})]
    (try
      (doseq [t ["done" "failed"]] (call dir {:command :thread! :args {:title t}}))
      (let [[w1 w2] (map #(:ok (start-worker dir % :repo repo)) ["T1" "T2"])]
        (assert (every? :workspace [w1 w2]) "both Workers started")
        (commit-file (:workspace w1) "one" "1\n" "one")
        (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})
        (is (= [{:conversation "super-W1" :dir repo :workspaces #{"T1-1" "T2-1"}}] @released)
            "a done Worker's session moves to the repo while its workspace still exists")
        (is (not (fs/exists? (:workspace w1))))
        (call dir {:command :finish-worker! :args {:worker "W2" :state-after :failed :reason "x"}})
        (is (= 1 (count @released)) "a failed Worker's workspace stays, and so does its session")
        (call dir {:command :archive! :args {:thread "T2"}})
        (is (= {:conversation "super-W2" :dir repo :workspaces #{"T2-1"}} (last @released))
            "archiving moves it out before the workspace goes")
        (is (not (fs/exists? (:workspace w2)))))
      (finally (super/stop! c)))))

(deftest release-asks-emacs-to-move-the-session
  (let [{:keys [bin args]} (fake-emacsclient 0 "\"T1 a\"")]
    (binding [bbh.super.emacs/*emacsclient* bin]
      (is (= "T1 a" (super/release! {:conversation "super-W1" :dir "/repo"})))
      (is (= ["-s" (bbh.super.emacs/server) "--timeout=30" "--eval" "(eng/bbh-super-release \"super-W1\" \"/repo\")"]
             (args))))))

;; ---- the person's message a Router command names, and jobs a reboot lost ----

(defn- apply-cmd
  "[state error events] after PRINCIPAL's CMD on STATE; a refusal leaves STATE."
  [state principal cmd]
  (try (let [{:keys [events result]} (model/decide state principal cmd)]
         [(reduce model/fold state (map-indexed #(assoc %2 :seq (+ 1000 %1) :at "t") events)) nil events result])
       (catch clojure.lang.ExceptionInfo e [state (:error (ex-data e)) nil])))

(defn- router-world
  "A Router, thread T1 with Worker W1 at work, and o1, a message from emacs to the Router."
  []
  (reduce (fn [st [p cmd]] (first (apply-cmd st p cmd)))
          model/empty-state
          [[:controller {:command :register-router! :args {:conversation "router"} :token-hash "r"}]
           [:owner {:command :thread! :args {:title "a"}}]
           [:owner {:id "m1" :command :say! :args {:text "restart it" :origin :emacs}}]
           [:owner {:command :start-worker! :token-hash "h1" :args {:thread "T1" :workflow :change :brief "b" :repo "/r"}}]
           [:controller {:command :worker-running! :args {:worker "W1" :conversation "super-W1" :wf-run "run1" :workspace "/ws/T1-1"}}]]))

(deftest a-router-command-logs-the-persons-message-it-names
  (let [steer #(apply-cmd (router-world) :router {:command :steer! :args (merge {:thread "T1" :text "go on"} %)})
        logged #(select-keys (:data (first (nth % 2))) [:for :origin])]
    (is (= {:for "o1" :origin :emacs} (logged (steer {:for "o1"}))))
    (is (= [nil {}] [(second (steer {})) (logged (steer {}))]) "naming no message is allowed and logs none")
    (is (= :invalid (second (steer {:for "o404"}))) "a message the controller never recorded for the Router")))

(deftest starting-on-a-thread-whose-worker-session-ended-replaces-it-and-the-log-names-it
  (let [st (first (apply-cmd (router-world) :controller {:command :health! :args {:worker "W1" :health {:classification :session-ended :process {} :jobs []}}}))
        replace #(apply-cmd st :router {:command :start-worker! :token-hash "h2"
                                        :args (merge {:thread "T1" :workflow :change :brief "resume" :repo "/r"} %)})
        [st' err events] (replace {:for "o1"})]
    (is (= [nil [:worker-finished :worker-started :token-issued]] [err (mapv :kind events)]))
    (is (= {:for "o1" :origin :emacs} (select-keys (:data (first events)) [:for :origin])))
    (is (= [:failed "Replaced by W2 as o1 asked"] ((juxt :state :reason) (get-in st' [:workers "W1"]))))
    (is (= ["T1" :starting 2 :emacs] ((juxt :thread :state :generation :origin) (get-in st' [:workers "W2"])))
        "the new Worker is the thread's next generation, so its workspace is new")
    (let [alive (first (apply-cmd st :controller {:command :health! :args {:worker "W1" :health {:classification :needs-human :process {:pid 7 :start "9"}}}}))]
      (is (= :thread-has-worker (second (apply-cmd alive :router {:command :start-worker! :token-hash "h2"
                                                                  :args {:thread "T1" :for "o1" :workflow :change :brief "r" :repo "/r"}})))
          "a Worker whose session still runs is cancelled, not replaced"))))

(deftest starting-on-an-archived-thread-reopens-it
  (let [st (reduce (fn [st [p cmd]] (first (apply-cmd st p cmd)))
                   model/empty-state
                   [[:owner {:command :thread! :args {:title "a"}}]
                    [:owner {:command :start-worker! :token-hash "h1" :args {:thread "T1" :workflow :change :brief "b" :repo "/r"}}]
                    [:owner {:command :cancel! :args {:thread "T1" :reason "later"}}]
                    [:controller {:command :cancel-delivered! :args {:worker "W1"}}]
                    [:owner {:command :archive! :args {:thread "T1"}}]])
        [st' err events] (apply-cmd st :owner {:command :start-worker! :token-hash "h2" :args {:thread "T1" :workflow :change :brief "again" :repo "/r"}})]
    (is (some? (get-in st [:threads "T1" :resolved-at])))
    (is (= [nil [:thread-reopened :worker-started :token-issued]] [err (mapv :kind events)]))
    (is (nil? (get-in st' [:threads "T1" :resolved-at])))
    (is (= :starting (get-in st' [:workers "W2" :state])))))

(deftest steering-a-worker-whose-session-ended-resumes-it-first
  (let [st (first (apply-cmd (router-world) :controller {:command :health! :args {:worker "W1" :health {:classification :session-ended :process {} :jobs []}}}))
        [st' err events result] (apply-cmd st :router {:command :steer! :token-hash "h2" :args {:thread "T1" :text "go on" :for "o1"}})]
    (is (= [nil [:worker-resumed :token-issued :worker-steered]] [err (mapv :kind events)]))
    (is (= {:worker "W1" :resumed true} result))
    (is (not (model/session-ended? (get-in st' [:workers "W1"]))))
    (is (= ["[Steering] go on"] (keep #(when (str/starts-with? (:text %) "[Steering]") (:text %)) (vals (:outbox st')))))
    (is (= :no-such-thread (second (apply-cmd model/empty-state :owner {:command :steer! :args {:thread "T9" :text "x"}})))
        "a thread that does not exist")))

(defn- job-files!
  "A job ID under ROOT that never wrote an exit, its pid long gone, with the boot id BOOT when given."
  [root id boot]
  (let [base (str (fs/path root "jobs" id))]
    (fs/create-dirs (fs/path root "jobs"))
    (spit (str base ".cmd") "sleep 1000")
    (spit (str base ".pid") "999999")
    (spit (str base ".start") "1")
    (when boot (spit (str base ".boot") boot))
    base))

(deftest a-job-from-before-the-last-boot-is-rebooted-not-lost
  (let [root (str (fs/create-temp-dir {:prefix "bbh-super-boot"}))
        boot {:id "this-boot" :at-ms (- (System/currentTimeMillis) 60000)}
        state #(into {} (map (juxt :id :state)) (process/jobs root boot))]
    (job-files! root "other-boot" "an-earlier-boot")
    (job-files! root "this-boot" "this-boot")
    (job-files! root "no-boot-id" nil)
    (is (= {"other-boot" :rebooted "this-boot" :lost "no-boot-id" :lost} (state)))
    (fs/set-last-modified-time (fs/path root "jobs" "no-boot-id.cmd") (fs/millis->file-time (- (:at-ms boot) 1000)))
    (is (= :rebooted (get (state) "no-boot-id")) "a job from before .boot was written, launched before the boot")))

(deftest a-worker-whose-jobs-died-in-a-reboot-is-told-when-resumed
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "recover me"}})
      (start-worker dir "T1" :repo repo)
      (let [c (assoc c :restart-grace-ms -1)
            root (#'super/conversation-root c "super-W1")
            base (job-files! root "build" nil)]
        (with-redefs [bbh.workflow/status (fn [_] {:status :active :phase :implement})]
          (super/reconcile! c)
          (is (= [1 :needs-human] [(count @launches) (get-in @(:state c) [:workers "W1" :health :classification])])
              "a job that may still run elsewhere needs you")
          (spit (str base ".boot") "an-earlier-boot")
          (super/reconcile! c)
          (is (= [1 :session-ended] [(count @launches) (get-in @(:state c) [:workers "W1" :health :classification])])
              "nothing restarts it behind the person's back")
          (is (= "W1" (:ok (call dir {:command :resume! :args {:thread "T1"}}))))
          (is (some #(re-find #"jobs build died unfinished" (str (:text %))) (vals (:outbox @(:state c)))))
          (locking (:db c) (is (= (store/replay (:db c)) @(:state c)))))))))

(deftest this-boots-time-is-read-so-an-old-job-without-a-boot-id-is-rebooted
  (let [root (str (fs/create-temp-dir {:prefix "bbh-super-boot"}))]
    (job-files! root "ancient" nil)
    (fs/set-last-modified-time (fs/path root "jobs" "ancient.cmd") (fs/millis->file-time 1000))
    (is (= [:rebooted] (mapv :state (process/jobs root))))))

(deftest the-routers-sandbox-cancels-a-worker-under-your-message-and-logs-it
  (with-controller
    (fn [c dir _]
      (thread-with-ask dir)
      (let [router (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))
            _ (call dir {:command :say! :args {:text "stop W1, it is stuck" :origin :emacs}})
            for (some (fn [[id o]] (when (= "router" (:conversation o)) id)) (:outbox @(:state c)))]
        (with-redefs [bbh.sysenv/getenv (let [getenv bbh.sysenv/getenv] (fn [k] (if (= "BBH_SUPER_TOKEN" k) router (getenv k))))
                      client/call! (let [call! client/call!] (fn [req] (call! dir req)))]
          (is (= "W1" (sandbox/eval-in (sandbox/context sandbox/bindings) (format "(super-cancel! \"T1\" \"stuck\" :for %s)" (pr-str for))))))
        (is (= [{:id "W1" :reason "stuck" :for for :origin :emacs}]
               (mapv #(select-keys (:data %) [:id :reason :for :origin]) (filter #(= :cancel-requested (:kind %)) (store/events (:db c))))))
        (is (#{:cancelling :cancelled} (get-in @(:state c) [:workers "W1" :state])))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(defn- refuse-or-nil [refused code] (or (refused code) ""))

(deftest the-routers-call-shapes-reach-a-live-worker
  ;; Each shape the Router has used, new and old, against a scratch super: a
  ;; Router REPL loaded before a controller change keeps sending the old one.
  (with-controller
    (fn [c dir _]
      (thread-with-ask dir)
      (let [router (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))
            _ (call dir {:command :say! :args {:text "tell W1 to hurry" :origin :emacs}})
            for (some (fn [[id o]] (when (= "router" (:conversation o)) id)) (:outbox @(:state c)))
            ctx (sandbox/context sandbox/bindings)
            run #(sandbox/eval-in ctx %)
            refused #(try (run %) nil (catch Exception e (ex-message e)))
            wire #(client/call! {:token router :command %1 :args %2})]
        (call dir {:command :thread! :args {:title "second"}})
        (call dir {:command :start-worker! :args {:thread "T2" :workflow :investigation :brief "look" :repo (str (fs/home))}})
        (with-redefs [bbh.sysenv/getenv (let [getenv bbh.sysenv/getenv] (fn [k] (if (= "BBH_SUPER_TOKEN" k) router (getenv k))))
                      client/call! (let [call! client/call!] (fn [req] (call! dir req)))]
          (testing "steer by thread id, by Worker id, and in the pre-T104 wire shape"
            (is (= {:worker "W1"} (run (format "(super-steer! \"T1\" \"a\" :for %s)" (pr-str for)))))
            (is (= {:worker "W1"} (run (format "(super-steer! \"W1\" \"b\" {:for %s})" (pr-str for)))))
            (is (= {:worker "W1"} (:ok (wire :steer! {:worker "W1" :text "c" :for for}))))
            (is (= [for for for] (mapv (comp :for :data) (filter #(= :worker-steered (:kind %)) (store/events (:db c)))))
                "{:for} names the person's message each time")
            (is (re-find #"no thread or Worker \"T9\"" (refuse-or-nil refused "(super-steer! \"T9\" \"x\")"))))
          (testing "start with :replaces names only the thread's Worker"
            (is (re-find #"W9 is not T1's Worker" (refuse-or-nil refused (format "(super-start! \"T1\" \"b\" :for %s :replaces \"W9\")" (pr-str for)))))
            (is (re-find #"thread-has-worker" (refuse-or-nil refused (format "(super-start! \"T1\" \"b\" :for %s :replaces \"W1\")" (pr-str for))))
                "a live Worker is cancelled, not replaced"))
          (testing "cancel by Worker id"
            (is (= "W1" (run (format "(super-cancel! \"W1\" \"stuck\" :for %s)" (pr-str for)))))
            (is (#{:cancelling :cancelled} (get-in @(:state c) [:workers "W1" :state]))))
          (testing "start with :replaces replaces a Worker whose session ended"
            (#'super/run! c :controller {:command :health! :args {:worker "W2" :health {:classification :session-ended :process {} :jobs []}}})
            (is (= "W3" (:id (run (format "(super-start! \"T2\" \"again\" :for %s :replaces \"W2\")" (pr-str for))))))
            (is (= :failed (get-in @(:state c) [:workers "W2" :state])))))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest the-routers-sandbox-continues-a-thread-with-only-a-brief
  (with-workers
    (fn [c dir repo _]
      (call dir {:command :thread! :args {:title "t"}})
      (start-worker dir "T1" :repo repo :check "make x" :bench "make b")
      (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "stuck"}})
      (let [router (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))
            _ (call dir {:command :say! :args {:text "T1: try again" :origin :emacs}})
            for (some (fn [[id o]] (when (= "router" (:conversation o)) id)) (:outbox @(:state c)))
            ctx (sandbox/context sandbox/bindings)
            refused #(try (sandbox/eval-in ctx %) nil (catch Exception e (ex-message e)))]
        (with-redefs [bbh.sysenv/getenv (let [getenv bbh.sysenv/getenv] (fn [k] (if (= "BBH_SUPER_TOKEN" k) router (getenv k))))
                      client/call! (let [call! client/call!] (fn [req] (call! dir req)))]
          (is (= "W2" (:id (sandbox/eval-in ctx (format "(super-start! \"T1\" \"try again\" :for %s)" (pr-str for))))))
          (is (= [:change repo "make x" "make b" 2] ((juxt :workflow :repo :check :bench :generation) (get-in @(:state c) [:workers "W2"])))
              "what the start leaves out comes from the thread's last Worker")
          (is (re-find #"has no option :forr" (refuse-or-nil refused "(super-steer! \"T1\" \"x\" :forr \"o1\")")))
          (is (re-find #"resolve symbol: super-start-worker!" (refuse-or-nil refused "(super-start-worker! {:thread \"T1\" :brief \"b\"})"))
              "the old spec shape is gone"))))))

(deftest a-thread-rows-status-is-decided-once-by-the-controller
  (is (= [:on-you :on-you :stuck :stuck :working :working :archived :done :quiet]
         (map #(apply model/status %) [[:stopped :working] [:new :waiting] [:needs-attention :none] [:quiet :failed]
                                       [:new :working] [:working :queued] [:archived :none] [:quiet :done] [:quiet :none]]))))

(deftest a-worker-model-is-checked-against-the-policy-and-a-replacement-keeps-it
  (let [fable "claude/claude-fable-5-2"
        start (fn [st args] (apply-cmd st :owner {:command :start-worker! :token-hash (str (random-uuid))
                                                  :policy {:workers 3 :worker-models [fable]}
                                                  :args (merge {:thread "T1" :workflow :change :brief "b" :repo "/r"} args)}))
        st0 (first (apply-cmd model/empty-state :owner {:command :thread! :args {:title "a"}}))
        [st err] (start st0 {:model fable})]
    (is (nil? err))
    (is (= fable (get-in st [:workers "W1" :model])))
    (is (not (contains? (get-in (first (start st0 {})) [:workers "W1"]) :model)) "no :model is the default")
    (is (= :invalid (second (start st0 {:model "claude/claude-fabel-5-2"}))))
    (is (re-find #"unknown model .*claude-fabel.*allowed: claude/claude-fable-5-2"
                 (try (model/decide st0 :owner {:command :start-worker! :token-hash "h" :policy {:worker-models [fable]}
                                                :args {:thread "T1" :workflow :change :brief "b" :repo "/r" :model "claude/claude-fabel-5-2"}})
                      "" (catch clojure.lang.ExceptionInfo e (ex-message e)))))
    (let [ended (first (apply-cmd (first (apply-cmd st :controller (second (running "W1")))) :controller {:command :health! :args {:worker "W1" :health {:classification :session-ended :process {} :jobs []}}}))]
      (is (= fable (get-in (first (start ended {:replaces "W1"})) [:workers "W2" :model])) ":replaces carries it over")
      (is (nil? (get-in (first (start ended {})) [:workers "W2" :model])) "a new Worker without :replaces gets the default"))))
