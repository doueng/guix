(ns bbh.notify-test
  (:require [bbh.super.model :as model]
            [bbh.super.notify :as notify]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def base
  {:threads {"T1" {:id "T1" :title "Alt+W closes windows" :worker "W1" :milestone-seq 3 :read-seq 3}
             "T2" {:id "T2" :title "Doom theme" :worker "W2" :milestone-seq 1 :read-seq 1}}
   :workers {"W1" {:id "W1" :thread "T1" :state :working :started-at 0 :health {:classification :running}}
             "W2" {:id "W2" :thread "T2" :state :working :started-at 0 :health {:classification :running}}}
   :asks {}})

(defn- ask
  "STATE with an ask open since epoch ms 0, or since AT when the id is [id at]."
  [state id thread text & labels]
  (let [[id at] (if (vector? id) id [id 0])]
    (assoc-in state [:asks id] {:id id :thread thread :state :open :text text :kind :question :opened-at at
                                :options (vec (map-indexed (fn [i l] {:id (str i) :label l}) labels))})))

(defn- answer [state id] (assoc-in state [:asks id :state] :sent))

(defn- told
  "STATE after ITEMS, as tick! returned them, were recorded as notified."
  [state items]
  (reduce (fn [s [k item]] (model/fold s {:kind :timer-fired :data (:args (notify/timer k (:at item)))})) state items))

(deftest an-ask-is-unnotified-until-it-is-recorded
  (let [after (ask base "a1" "T1" "Bind it to Cmd+W?" "Yes" "No")
        items (notify/unnotified after {})]
    (is (= #{[:ask "a1"]} (set (keys items))))
    (is (= "timer-notify-ask-a1-0" (:id (notify/timer [:ask "a1"] 0))) "the record's id is a function of the item")
    (testing "once recorded, the ask still open brings nothing new"
      (is (empty? (notify/unnotified (told after items) {}))))
    (testing "a re-ask is a new ask id, so it counts again"
      (is (= #{[:ask "a2"]} (set (keys (notify/unnotified (ask (answer (told after items) "a1") "a2" "T1" "Cmd+Q then?") {}))))))
    (testing "an item whose time the log does not say is left out"
      (is (empty? (notify/unnotified (update-in after [:asks "a1"] dissoc :opened-at) {}))))))

(deftest needs-human-is-critical-and-session-ended-is-not
  (let [sick (assoc-in base [:workers "W1" :health] {:classification :needs-human :problem "Its REPL does not answer." :since 5})
        both (assoc-in sick [:workers "W2" :health] {:classification :session-ended :problem "Its session ended." :since 6})
        a (notify/unnotified both {})]
    (is (= :critical (:urgency (a [:needs-human "W1"]))))
    (is (= "Its REPL does not answer." (:text (a [:needs-human "W1"]))))
    (is (= :normal (:urgency (a [:session-ended "W2"]))))
    (testing "a Worker that needs help again, at a later time, counts again"
      (let [s (told both a)]
        (is (empty? (notify/unnotified s {})))
        (is (= [[:needs-human "W1"]] (keys (notify/unnotified (assoc-in s [:workers "W1" :health :since] 90) {})))))))
  (testing "a Worker that failed on an open thread needs you; a resolved thread does not"
    (let [failed (-> base (assoc-in [:workers "W1" :state] :failed) (assoc-in [:workers "W1" :reason] "Launch failed")
                     (assoc-in [:threads "T1" :worker] nil) (assoc-in [:threads "T1" :history] ["W1"]))]
      (is (contains? (notify/waiting failed) [:needs-human "W1"]))
      (is (not (contains? (notify/waiting (assoc-in failed [:threads "T1" :resolved-at] 9)) [:needs-human "W1"])))))
  (testing "a Worker past the deadline of its state needs you, and says why"
    (let [late (update-in base [:workers "W1"] assoc :state :starting :state-at 7 :overdue :starting :health {:classification :starting})]
      (is (= {:urgency :critical :at 7 :text "W1 has been starting for 10 minutes and its session has not opened."}
             (select-keys ((notify/waiting late) [:needs-human "W1"]) [:urgency :at :text])))
      (is (empty? (notify/waiting (assoc-in late [:workers "W1" :state] :working))) "leaving the state clears it"))))

(deftest a-long-finish-counts-a-short-one-does-not
  (let [done (fn [s minutes] (-> s (update-in [:workers "W1"] assoc :state :done :state-at (* minutes 60000))
                                 (assoc-in [:threads "T1" :milestone-seq] 4)
                                 (assoc-in [:threads "T1" :summary] "W1 landed abc12345")))]
    (is (= {[:finished "W1"] {:thread "T1" :worker "W1" :urgency :normal :text "W1 landed abc12345" :at (* 11 60000)}}
           (notify/unnotified (done base 11) {})))
    (is (empty? (notify/unnotified (done base 9) {})))
    (is (seq (notify/unnotified (done base 2) {:notify-long-minutes 1})))
    (testing "it stays only while its thread is unread"
      (let [p (notify/unnotified (done base 11) {})]
        (is (= "T1 finished: Alt+W closes windows" (:summary (first (notify/plan p (done base 11))))))
        (is (empty? (notify/plan p (assoc-in (done base 11) [:threads "T1" :read-seq] 4))))
        (is (empty? (notify/unnotified (assoc-in (done base 11) [:threads "T1" :read-seq] 4) {})))))))

(deftest plan-one-thread
  (let [s (-> base (ask "a1" "T1" "Bind it to Cmd+W?" "Yes" "No" "Cmd+Q" "Leave it")
              (ask "a2" "T1" "Also in Emacs?"))
        [n & more] (notify/plan (notify/unnotified s {}) s)]
    (is (nil? more))
    (is (= {:key "T1" :thread "T1" :ask "a1" :urgency :normal :keys #{[:ask "a1"] [:ask "a2"]}
            :summary "T1 needs you: Alt+W closes windows"
            :body "Bind it to Cmd+W?  [Yes | No | Cmd+Q | …]\nAlso in Emacs?"}
           n))))

(deftest plan-drops-what-was-answered-meanwhile
  (let [s (ask base "a1" "T1" "Bind it to Cmd+W?" "Yes")
        pending (notify/unnotified s {})]
    (is (empty? (notify/plan pending (answer s "a1"))))))

(deftest plan-coalesces-threads-into-one
  (let [s (-> base (ask "a1" "T1" "Bind it to Cmd+W?" "Yes" "No")
              (assoc-in [:workers "W2" :health] {:classification :needs-human :problem "Job j1 is lost." :since 0}))
        [n & more] (notify/plan (notify/unnotified s {}) s)]
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
  (let [s (-> base (ask ["a" 5] "T1" "One?") (ask ["b" 9] "T1" "Two?"))]
    (is (empty? (notify/due base 10000 {})))
    (is (empty? (notify/due s 5004 {})) "not before the oldest has waited the delay")
    (is (= #{[:ask "a"] [:ask "b"]} (set (keys (notify/due s 5005 {})))) "then all that wait go together")))

(deftest notify-send-args
  (is (= ["notify-send" "--app-name=bbh" "--urgency=critical" "--expire-time=3000" "--print-id"
          "--action=default=Open" "--wait" "--replace-id=7" "T1 needs you" "body"]
         (notify/notify-send-args {:summary "T1 needs you" :body "body" :urgency :critical :expire-ms 3000} 7)))
  (testing "a notification saved before :expire-ms expires after the default, 5 s"
    (is (some #{"--expire-time=5000"} (notify/notify-send-args {:summary "s" :body "b" :urgency :normal} nil))))
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
          (notify/tick! n s 10000 {:do-not-disturb true})
          (is (= ["T1"] @log)))))
    (babashka.fs/delete-tree dir)))

(deftest a-restarted-controller-resumes-what-it-showed-without-showing-it-again
  (let [file (str (babashka.fs/create-temp-file {:suffix ".edn"}))
        effects (fn [log] {:show! (fn [n prev] (swap! log conj [:show (:key n) (some-> prev :id deref) (:body n)])
                                    {:id (doto (promise) (deliver 40))})
                           :close! (fn [{:keys [handle]}] (swap! log conj [:close @(:id handle)]))})
        s (-> base (ask "a1" "T1" "Bind it?" "Yes") (ask ["a2" 20000] "T2" "Dark theme?"))
        log1 (atom [])
        n1 (notify/new-notifier ((fn [log] {:show! (fn [n _] (swap! log conj (:key n)) {:id (doto (promise) (deliver (count @log)))})
                                            :close! (fn [_])}) log1)
                                file)]
    (let [s1 (ask base "a1" "T1" "Bind it?" "Yes")
          s1 (told s1 (notify/tick! n1 s1 10000 {}))]
      (notify/tick! n1 (ask s1 ["a2" 20000] "T2" "Dark theme?") 30000 {}))
    (is (= ["T1" "T2"] @log1))
    (testing "a restart shows nothing; what no longer waits and is still on screen is closed"
      (let [log2 (atom [])
            n2 (notify/new-notifier (effects log2) file)]
        (notify/resume! n2 (answer s "a2") 32000)
        (is (= [[:close 2]] @log2))
        (is (= ["T1"] (keys @(:shown n2))) "T1 still waits, so it is kept")
        (is (nil? (:handle (@(:shown n2) "T1"))) "without the id of an expired notification, which the daemon reuses")
        (testing "T1's next ask replaces it and lists both"
          (let [s3 (-> s (answer "a2") (ask ["a3" 40000] "T1" "Also Emacs?"))]
            (notify/tick! n2 s3 50000 {})
            (is (= [:show "T1" nil "Bind it?  [Yes]\nAlso Emacs?"] (last @log2)))
            (testing "and answering both closes it under its id"
              (notify/tick! n2 (-> s3 (answer "a1") (answer "a3")) 60000 {})
              (is (= [:close 40] (last @log2))))))))
    (babashka.fs/delete file)))

(deftest a-replayed-history-notifies-nothing-and-one-live-ask-once
  (let [log (atom [])
        effects {:show! (fn [n _] (swap! log conj (:key n)) {}) :close! (fn [_])}
        boot 1000000
        n (notify/new-notifier effects nil (- boot notify/replay-grace-ms))
        old (reduce (fn [s i] (-> s (assoc-in [:threads (str "T" i)] {:id (str "T" i) :title "old"})
                                  (ask [(str "a" i) (* i 1000)] (str "T" i) "Old question?" "Yes")))
                    base (range 1 40))
        due (notify/tick! n old (+ boot 10000) {})]
    (is (= [] @log) "history is not shown")
    (is (= 39 (count due)) "but it is returned, to be recorded as notified")
    (let [s (told old due)]
      (is (empty? (notify/tick! n s (+ boot 20000) {})))
      (testing "one live ask gives exactly one notification"
        (let [s (ask s ["a99" (+ boot 30000)] "T1" "New question?" "Yes")
              d (notify/tick! n s (+ boot 40000) {})]
          (is (= ["T1"] @log))
          (is (= [[:ask "a99"]] (keys d)))
          (notify/tick! n (told s d) (+ boot 50000) {})
          (is (= ["T1"] @log) "and no more"))))))

(deftest a-late-worker-is-news-at-its-deadline
  (let [w {:id "W1" :thread "T1" :state :starting :state-at 1000 :overdue :starting :health {:classification :starting}}
        s (assoc-in base [:workers "W1"] w)
        item ((notify/waiting s) [:needs-human "W1"])]
    (is (= 1000 (:at item)))
    (is (= (+ 1000 (model/deadlines :starting)) (:news-at item)))
    (let [log (atom [])
          n (notify/new-notifier {:show! (fn [n _] (swap! log conj (:key n)) {}) :close! (fn [_])} nil 5000)]
      (notify/tick! n s (+ 1000 (model/deadlines :starting) 10000) {})
      (is (= ["T1"] @log) "a deadline passed after the start is news though the state began before it"))))

(deftest an-archived-thread-s-finish-is-not-news
  (let [done (-> base (update-in [:workers "W1"] assoc :state :done :state-at (* 11 60000))
                 (assoc-in [:threads "T1" :milestone-seq] 4))]
    (is (seq (notify/unnotified done {})))
    (is (empty? (notify/unnotified (assoc-in done [:threads "T1" :resolved-at] 5) {})))
    (is (seq (notify/unnotified (-> done (assoc-in [:threads "T1" :resolved-at] 5) (assoc-in [:threads "T1" :milestone-seq] 6)) {}))
        "unless it came back")))

(deftest hyprland-dispatchers-are-lua
  (is (= "hl.dsp.focus({ window = \"pid:42\" })" (notify/focus-dispatch 42)))
  (is (= "hl.dsp.exec_cmd(\"ghostty -e emacsclient --eval '(f \\\"T1\\\")'\")"
         (notify/exec-dispatch "ghostty -e emacsclient --eval '(f \"T1\")'"))))

(deftest window-pid-walks-up-or-falls-back-to-herdr
  (let [parent {300 200 200 100 100 1 1 nil}]
    (is (= 100 (notify/window-pid [{:pid 100 :class "ghostty"}] [300] parent)))
    (is (= 50 (notify/window-pid [{:pid 50 :class "com.mitchellh.ghostty.herdr" :focusHistoryID 0}] [300] parent)))
    (is (nil? (notify/window-pid [{:pid 50 :class "google-chrome"}] [300] parent)))))

(deftest tick-shows-with-the-policy-expiry
  (let [shown (atom [])
        n (notify/new-notifier {:show! (fn [n _] (swap! shown conj (:expire-ms n)) {}) :close! (fn [_])})
        s1 (ask base "a1" "T1" "Bind it?")]
    (let [s2 (told s1 (notify/tick! n s1 10000 {:notify-delay-seconds 0 :notify-expire-ms 2500}))]
      (is (= [2500] @shown))
      (notify/tick! n (ask s2 ["a2" 20000] "T2" "And?") 30000 {}))
    (is (= [2500 5000] @shown) "5 s when policy.edn says nothing")))

(deftest tick-coalesces-replaces-and-closes
  (let [log (atom [])
        n (notify/new-notifier {:show! (fn [n prev] (swap! log conj [:show (:key n) (:summary n) prev]) {:h (count @log)})
                                :close! (fn [shown] (swap! log conj [:close (:key shown)]))})
        s2 (-> base (ask ["a1" 1000] "T1" "Bind it?" "Yes") (ask ["a2" 3000] "T1" "Also Emacs?"))
        policy {:notify-delay-seconds 5}]
    (is (empty? (notify/tick! n s2 4000 policy)))
    (is (= [] @log) "nothing before the delay")
    (let [due (notify/tick! n s2 6000 policy)
          s2 (told s2 due)]
      (is (= #{[:ask "a1"] [:ask "a2"]} (set (keys due))) "it returns what it showed, to be recorded")
      (is (= [[:show "T1" "T1 needs you: Alt+W closes windows" nil]] @log) "two asks, one notification")
      (notify/tick! n s2 60000 policy)
      (is (= 1 (count @log)) "once recorded, a later pass shows nothing")
      (let [s3 (ask s2 ["a3" 7000] "T1" "And Vim?")]
        (notify/tick! n s3 12000 policy)
        (is (= [:show "T1" "T1 needs you: Alt+W closes windows" {:h 1}] (last @log)) "replaces the thread's notification")
        (is (= #{[:ask "a1"] [:ask "a2"] [:ask "a3"]} (get-in @(:shown n) ["T1" :keys])) "and keeps the asks still open")
        (notify/tick! n (-> s3 (answer "a1") (answer "a2") (answer "a3")) 12500 policy)
        (is (= [:close "T1"] (last @log)) "closed once nothing waits")))
    (testing "do-not-disturb shows nothing, and what was due is still returned to be recorded"
      (reset! log [])
      (let [s (ask base ["a9" 20000] "T1" "Bind it?" "Yes")]
        (is (= [[:ask "a9"]] (keys (notify/tick! n s 30000 {:do-not-disturb true}))))
        (is (= [] @log))))))
