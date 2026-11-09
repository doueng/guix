(ns bbh.notify-test
  (:require [bbh.super.notify :as notify]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def base
  {:threads {"T1" {:id "T1" :title "Alt+W closes windows" :worker "W1" :milestone-seq 3 :read-seq 3}
             "T2" {:id "T2" :title "Doom theme" :worker "W2" :milestone-seq 1 :read-seq 1}}
   :workers {"W1" {:id "W1" :thread "T1" :state :working :started-at 0 :health {:classification :running}}
             "W2" {:id "W2" :thread "T2" :state :working :started-at 0 :health {:classification :running}}}
   :asks {}})

(defn- ask [state id thread text & labels]
  (assoc-in state [:asks id] {:id id :thread thread :state :open :text text :kind :question
                              :options (vec (map-indexed (fn [i l] {:id (str i) :label l}) labels))}))

(defn- answer [state id] (assoc-in state [:asks id :state] :sent))

(deftest an-ask-arrives-once
  (let [after (ask base "a1" "T1" "Bind it to Cmd+W?" "Yes" "No")]
    (is (= #{[:ask "a1"]} (set (keys (notify/arrivals base after 0 {})))))
    (testing "a later commit with the ask still open brings nothing new"
      (is (empty? (notify/arrivals after (assoc-in after [:threads "T1" :summary] "x") 0 {}))))
    (testing "a re-ask is a new ask id, so it arrives again"
      (is (= #{[:ask "a2"]} (set (keys (notify/arrivals after (ask (answer after "a1") "a2" "T1" "Cmd+Q then?") 0 {}))))))))

(deftest needs-human-is-critical-and-session-ended-is-not
  (let [sick (assoc-in base [:workers "W1" :health] {:classification :needs-human :problem "Its REPL does not answer."})
        both (assoc-in sick [:workers "W2" :health] {:classification :session-ended :problem "Its session ended."})
        a (notify/arrivals base both 0 {})]
    (is (= :critical (:urgency (a [:needs-human "W1"]))))
    (is (= "Its REPL does not answer." (:text (a [:needs-human "W1"]))))
    (is (= :normal (:urgency (a [:session-ended "W2"])))))
  (testing "a Worker that failed on an open thread needs you; a resolved thread does not"
    (let [failed (-> base (assoc-in [:workers "W1" :state] :failed) (assoc-in [:workers "W1" :reason] "Launch failed")
                     (assoc-in [:threads "T1" :worker] nil) (assoc-in [:threads "T1" :history] ["W1"]))]
      (is (contains? (notify/waiting failed) [:needs-human "W1"]))
      (is (not (contains? (notify/waiting (assoc-in failed [:threads "T1" :resolved-at] 9)) [:needs-human "W1"]))))))

(deftest a-long-finish-arrives-a-short-one-does-not
  (let [done (fn [s] (-> s (assoc-in [:workers "W1" :state] :done) (assoc-in [:threads "T1" :milestone-seq] 4)
                         (assoc-in [:threads "T1" :summary] "W1 landed abc12345")))]
    (is (= {[:finished "W1"] {:thread "T1" :worker "W1" :urgency :normal :text "W1 landed abc12345"}}
           (notify/arrivals base (done base) (* 11 60000) {})))
    (is (empty? (notify/arrivals base (done base) (* 9 60000) {})))
    (is (seq (notify/arrivals base (done base) (* 2 60000) {:notify-long-minutes 1})))
    (testing "it stays only while its thread is unread"
      (let [p (notify/arrivals base (done base) (* 11 60000) {})]
        (is (= "T1 finished: Alt+W closes windows" (:summary (first (notify/plan p (done base))))))
        (is (empty? (notify/plan p (assoc-in (done base) [:threads "T1" :read-seq] 4))))))))

(deftest plan-one-thread
  (let [s (-> base (ask "a1" "T1" "Bind it to Cmd+W?" "Yes" "No" "Cmd+Q" "Leave it")
              (ask "a2" "T1" "Also in Emacs?"))
        [n & more] (notify/plan (notify/arrivals base s 0 {}) s)]
    (is (nil? more))
    (is (= {:key "T1" :thread "T1" :ask "a1" :urgency :normal :keys #{[:ask "a1"] [:ask "a2"]}
            :summary "T1 needs you: Alt+W closes windows"
            :body "Bind it to Cmd+W?  [Yes | No | Cmd+Q | …]\nAlso in Emacs?"}
           n))))

(deftest plan-drops-what-was-answered-meanwhile
  (let [s (ask base "a1" "T1" "Bind it to Cmd+W?" "Yes")
        pending (notify/arrivals base s 0 {})]
    (is (empty? (notify/plan pending (answer s "a1"))))))

(deftest plan-coalesces-threads-into-one
  (let [s (-> base (ask "a1" "T1" "Bind it to Cmd+W?" "Yes" "No")
              (assoc-in [:workers "W2" :health] {:classification :needs-human :problem "Job j1 is lost."}))
        [n & more] (notify/plan (notify/arrivals base s 0 {}) s)]
    (is (nil? more))
    (is (= :burst (:key n)))
    (is (= :critical (:urgency n)))
    (is (= "bbh: 2 threads need you" (:summary n)))
    (is (= ["T1 Bind it to Cmd+W?  [Yes | No]" "T2 Job j1 is lost."] (str/split-lines (:body n))))
    (is (= ["T1" "a1"] [(:thread n) (:ask n)]))))

(deftest stale-and-due
  (let [s (ask base "a1" "T1" "Bind it?")
        shown {"T1" {:keys #{[:ask "a1"]}}}]
    (is (= [] (notify/stale shown s)))
    (is (= ["T1"] (notify/stale shown (answer s "a1")))))
  (is (not (notify/due? {} 10 5)))
  (is (not (notify/due? {[:ask "a"] {:at 8}} 10 5)))
  (is (notify/due? {[:ask "a"] {:at 5} [:ask "b"] {:at 9}} 10 5)))

(deftest notify-send-args
  (is (= ["notify-send" "--app-name=bbh" "--urgency=critical" "--expire-time=0" "--print-id"
          "--action=default=Open" "--wait" "--replace-id=7" "T1 needs you" "body"]
         (notify/notify-send-args {:summary "T1 needs you" :body "body" :urgency :critical} 7)))
  (is (not-any? #(str/starts-with? % "--replace-id") (notify/notify-send-args {:summary "s" :body "b" :urgency :normal} nil)))
  (is (= "(eng/bbh-inbox-goto \"T1\" nil)" (notify/goto-form "T1" nil))))

(deftest the-inbox-key-overrides-policy
  (let [dir (str (babashka.fs/create-temp-dir))]
    (with-redefs [notify/override-file (fn [] (babashka.fs/path dir "bbh" "notify.edn"))]
      (is (= {} (notify/overrides)))
      (is (true? (notify/toggle-dnd! {})))
      (is (= {:do-not-disturb true} (notify/overrides)))
      (is (false? (notify/toggle-dnd! {})))
      (testing "from a policy that says do not disturb, the key turns notifications on"
        (babashka.fs/delete (notify/override-file))
        (is (false? (notify/toggle-dnd! {:do-not-disturb true}))))
      (testing "tick! obeys the override over the policy"
        (let [log (atom [])
              n (notify/new-notifier {:show! (fn [n _] (swap! log conj (:key n)) {}) :close! (fn [_])})
              s (ask base "a1" "T1" "Bind it?")]
          (notify/observe! n base s 0 {})
          (notify/tick! n s 10000 (constantly {:do-not-disturb true}))
          (is (= ["T1"] @log)))))
    (babashka.fs/delete-tree dir)))

(deftest a-restarted-controller-resumes-what-it-showed
  (let [file (str (babashka.fs/create-temp-file {:suffix ".edn"}))
        effects (fn [log] {:show! (fn [n prev] (swap! log conj [:show (:key n) @(:id prev)]) {:id (doto (promise) (deliver 40))})
                           :close! (fn [{:keys [handle]}] (swap! log conj [:close @(:id handle)]))})
        s (-> base (ask "a1" "T1" "Bind it?" "Yes") (ask "a2" "T2" "Dark theme?"))
        log1 (atom [])
        n1 (notify/new-notifier ((fn [log] {:show! (fn [n _] (swap! log conj (:key n)) {:id (doto (promise) (deliver (count @log)))})
                                            :close! (fn [_])}) log1)
                                file)]
    (notify/observe! n1 base (ask base "a1" "T1" "Bind it?" "Yes") 0 {})
    (notify/tick! n1 (ask base "a1" "T1" "Bind it?" "Yes") 10000 (constantly {}))
    (notify/observe! n1 base (ask base "a2" "T2" "Dark theme?") 20000 {})
    (notify/tick! n1 s 30000 (constantly {}))
    (is (= ["T1" "T2"] @log1))
    (testing "after a restart, what still waits is shown again in place and the rest closed"
      (let [log2 (atom [])
            n2 (notify/new-notifier (effects log2) file)]
        (notify/resume! n2 (answer s "a2"))
        (is (= #{[:show "T1" 1] [:close 2]} (set @log2)))
        (is (= ["T1"] (keys @(:shown n2))))
        (testing "and answering it later closes it under its id"
          (notify/tick! n2 (-> s (answer "a1") (answer "a2")) 40000 (constantly {}))
          (is (= [:close 40] (last @log2))))))
    (babashka.fs/delete file)))

(deftest hyprland-dispatchers-are-lua
  (is (= "hl.dsp.focus({ window = \"pid:42\" })" (notify/focus-dispatch 42)))
  (is (= "hl.dsp.exec_cmd(\"ghostty -e emacsclient --eval '(f \\\"T1\\\")'\")"
         (notify/exec-dispatch "ghostty -e emacsclient --eval '(f \"T1\")'"))))

(deftest window-pid-walks-up-or-falls-back-to-herdr
  (let [parent {300 200 200 100 100 1 1 nil}]
    (is (= 100 (notify/window-pid [{:pid 100 :class "ghostty"}] [300] parent)))
    (is (= 50 (notify/window-pid [{:pid 50 :class "com.mitchellh.ghostty.herdr" :focusHistoryID 0}] [300] parent)))
    (is (nil? (notify/window-pid [{:pid 50 :class "google-chrome"}] [300] parent)))))

(deftest tick-coalesces-replaces-and-closes
  (let [log (atom [])
        n (notify/new-notifier {:show! (fn [n prev] (swap! log conj [:show (:key n) (:summary n) prev]) {:h (count @log)})
                                :close! (fn [shown] (swap! log conj [:close (:key shown)]))})
        s1 (ask base "a1" "T1" "Bind it?" "Yes")
        s2 (ask s1 "a2" "T1" "Also Emacs?")
        policy (constantly {:notify-delay-seconds 5})]
    (notify/observe! n base s1 1000 {})
    (notify/observe! n s1 s2 3000 {})
    (notify/tick! n s2 4000 policy)
    (is (= [] @log) "nothing before the delay")
    (notify/tick! n s2 6000 policy)
    (is (= [[:show "T1" "T1 needs you: Alt+W closes windows" nil]] @log) "two asks, one notification")
    (let [s3 (ask s2 "a3" "T1" "And Vim?")]
      (notify/observe! n s2 s3 7000 {})
      (notify/tick! n s3 12000 policy)
      (is (= [:show "T1" "T1 needs you: Alt+W closes windows" {:h 1}] (last @log)) "replaces the thread's notification")
      (is (= #{[:ask "a1"] [:ask "a2"] [:ask "a3"]} (get-in @(:shown n) ["T1" :keys])) "and keeps the asks still open")
      (notify/tick! n (-> s3 (answer "a1") (answer "a2") (answer "a3")) 12500 policy)
      (is (= [:close "T1"] (last @log)) "closed once nothing waits"))
    (testing "do-not-disturb drops what is due"
      (reset! log [])
      (notify/observe! n base s1 20000 {})
      (notify/tick! n s1 30000 (constantly {:do-not-disturb true}))
      (is (= [] @log))
      (is (empty? @(:pending n))))))
