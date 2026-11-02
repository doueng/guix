(ns bbh.super-test
  (:require [babashka.fs :as fs]
            [bbh.super :as super]
            [bbh.super.client :as client]
            [bbh.super.pane]
            [babashka.process]
            [bbh.super.model :as model]
            [bbh.super.store :as store]
            [clojure.test :refer [deftest is testing]]))

(defn- wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 300) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

(defn- with-controller [f]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-conv"}))
        c (super/start! {:dir dir :conversations conversations :scan-ms 10})]
    (try (f c dir conversations)
         (finally (super/stop! c)))))

(defn- call [dir request] (client/call! dir request))

(defn- thread-with-ask
  "A thread, a Worker on it and one open ask. Returns the Worker's token and
  the thread's asks-version."
  [dir]
  (call dir {:command :thread! :args {:title "Alt+W closes windows everywhere"}})
  (let [token (:token (:ok (call dir {:command :attach-worker! :args {:thread "T1" :conversation "conv-1"}})))]
    (call dir {:token token :command :ask! :args {:kind :question :text "Keep Ctrl+Alt+W as an alias?"
                                                     :options [{:id "no" :label "No"} {:id "week" :label "Yes, for a week"}]
                                                     :stopped true}})
    {:token token :version (:asks-version (:ok (call dir {:command :thread :args {:id "T1"}})))}))

(deftest a-repeated-command-id-returns-its-original-result-and-one-reply
  (with-controller
    (fn [c dir _]
      (let [{:keys [version]} (thread-with-ask dir)
            reply {:id "c-9f2" :command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}}]
        (is (= {:ok "c-9f2"} (call dir reply)))
        (is (= {:ok "c-9f2"} (call dir reply)) "the second send is the same reply, not a stale conflict")
        (is (= 1 (count (:outbox @(:state c)))))
        (is (= [:sent] (map :state (:replies (:ok (call dir {:command :thread :args {:id "T1"}}))))))))))

(deftest a-send-with-a-stale-version-is-rejected-with-the-current-asks
  (with-controller
    (fn [_ dir _]
      (let [{:keys [version]} (thread-with-ask dir)
            {:keys [error data]} (call dir {:command :reply! :args {:thread "T1" :asks-version (dec version) :picks {"a1" "no"}}})]
        (is (= :stale error))
        (is (= version (:current data)))
        (is (= ["a1"] (map :id (:asks data))))))))

(deftest a-command-that-fails-after-its-events-leaves-no-row
  (let [db (store/open nil)
        state (store/load db)]
    (is (thrown? Exception
                 (with-redefs [model/changed (fn [_ _] (throw (ex-info "disk full" {})))]
                   (store/commit! db state :owner {:id "c1" :command :thread! :args {:title "x"}}))))
    (is (empty? (store/events db)))
    (is (= model/empty-state (store/load db)))
    (is (= "T1" (:result (store/commit! db state :owner {:id "c1" :command :thread! :args {:title "x"}})))
        "the id is free again, so a retry runs")
    (store/close! db)))

(deftest replaying-the-events-reproduces-the-materialized-state
  (with-controller
    (fn [c dir _]
      (let [{:keys [token version]} (thread-with-ask dir)]
        (call dir {:command :link! :args {:thread "T1" :resource "repo:engstrand/guix"}})
        (call dir {:token token :command :note! :args {:text "looking at kanata"}})
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "week"} :note "and say why"}})
        (is (wait-for #(= #{:sent} (set (map :state (vals (:outbox @(:state c))))))))
        (let [db (:db c)]
          (locking db
            (is (= (store/replay db) (store/load db) @(:state c)))))))))

(deftest an-outbox-row-is-acked-when-its-mailbox-file-is-removed
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [version]} (thread-with-ask dir)]
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}})
        (is (wait-for #(seq (fs/glob conversations "conv-1/mailbox/*.json"))))
        (let [files (fs/glob conversations "conv-1/mailbox/*.json")
              text (slurp (str (first files)))]
          (is (= 1 (count files)))
          (is (re-find #"Keep Ctrl\+Alt\+W as an alias\?: No" text))
          (is (wait-for #(= [:sent] (map :state (vals (:outbox @(:state c)))))) "the send is recorded after the file lands")
          (doseq [f files] (fs/delete f))
          (is (wait-for #(= [:acked] (map :state (vals (:outbox @(:state c)))))))
          (is (= [:delivered] (map :state (vals (:asks @(:state c)))))))))))

(deftest a-second-controller-on-the-same-store-is-refused
  (with-controller
    (fn [_ dir _]
      (is (thrown-with-msg? Exception #"already running" (super/start! {:dir dir}))))))

(deftest a-worker-token-acts-on-its-own-thread-only
  (with-controller
    (fn [_ dir _]
      (let [{:keys [token]} (thread-with-ask dir)]
        (call dir {:command :thread! :args {:title "another"}})
        (is (= :forbidden (:error (call dir {:token token :command :ask! :args {:thread "T2" :kind :question :text "?" :options []}}))))
        (is (= :forbidden (:error (call dir {:token token :command :reply! :args {:thread "T1" :asks-version 0 :picks {}}}))))
        (is (= :unauthorized (:error (call dir {:token "not a token" :command :threads}))))))))

(deftest the-fold-is-pure-over-a-fixed-event-list
  (testing "a stopped ask puts the thread in waiting and the reply on its way puts it back to working"
    (let [events [{:seq 1 :kind :thread-created :thread "T1" :data {:id "T1" :title "t"}}
                  {:seq 2 :kind :worker-attached :thread "T1" :data {:id "W1" :conversation "c" :generation 1}}
                  {:seq 3 :kind :ask-opened :thread "T1" :data {:id "a1" :worker "W1" :kind :question :text "?" :options [{:id "y" :label "Y"}] :stopped true}}
                  {:seq 4 :kind :reply-sent :thread "T1" :data {:id "r1" :thread "T1" :worker "W1" :picks {"a1" "y"} :note "" :dismiss #{} :outbox "o1" :conversation "c" :text "m"}}
                  {:seq 5 :kind :outbox-sent :thread "T1" :data {:id "o1" :message "m1"}}
                  {:seq 6 :kind :outbox-acked :thread "T1" :data {:id "o1"}}
                  {:seq 7 :kind :reply-answered :thread "T1" :data {:id "r1"}}]
          states (reductions model/fold model/empty-state events)]
      (is (= [:none :working :waiting :working :working :working :working]
             (map #(get-in % [:threads "T1" :execution]) (rest states))))
      (is (= [:open :sent :sent :delivered :answered]
             (map #(get-in % [:asks "a1" :state]) (drop 3 states))))
      (is (= :processed (get-in (last states) [:outbox "o1" :state]))))))

(deftest a-newer-ask-supersedes-the-old-one-and-a-reply-to-it-is-refused
  (with-controller
    (fn [_ dir _]
      (let [{:keys [token version]} (thread-with-ask dir)]
        (is (= {:ok "a2"} (call dir {:token token :command :ask! :args {:kind :question :text "Alias, or a new chord?"
                                                                          :options [{:id "alias" :label "Alias"} {:id "chord" :label "New chord"}]
                                                                          :supersedes ["a1"]}})))
        (let [t (:ok (call dir {:command :thread :args {:id "T1"}}))]
          (is (= {"a1" :superseded "a2" :open} (into {} (map (juxt :id :state) (:asks t)))))
          (is (= :no-such-ask (:error (call dir {:command :reply! :args {:thread "T1" :asks-version (:asks-version t) :picks {"a1" "no"}}}))))
          (is (= :stale (:error (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a2" "alias"}}})))))))))

(deftest mark-read-dismisses-every-open-ask-and-tells-the-worker-not-to-wait
  (with-controller
    (fn [c dir conversations]
      (thread-with-ask dir)
      (is (= {:ok "T1"} (call dir {:command :dismiss! :args {:thread "T1"}})))
      (is (= [:dismissed] (map :state (:asks (:ok (call dir {:command :thread :args {:id "T1"}}))))))
      (is (wait-for #(seq (fs/glob conversations "conv-1/mailbox/*.json"))))
      (is (re-find #"Not answered, do not wait: a1" (slurp (str (first (fs/glob conversations "conv-1/mailbox/*.json"))))))
      (is (= :working (get-in @(:state c) [:threads "T1" :execution])) "the Worker is told, so the thread is no longer waiting")
      (is (= {:ok "T1"} (call dir {:command :dismiss! :args {:thread "T1"}})) "nothing open: no second message")
      (is (= 1 (count (:outbox @(:state c))))))))

(deftest archive-resolves-the-thread-and-a-milestone-shows-it-came-back
  (with-controller
    (fn [_ dir _]
      (let [{:keys [token]} (thread-with-ask dir)
            section-of (fn [] (let [{:keys [sections]} (:ok (call dir {:command :sections}))]
                                (some (fn [{:keys [section threads]}] (when (some #(= "T1" (:id %)) threads) [section (:came-back (first threads))])) sections)))]
        (is (= [:stopped false] (section-of)))
        (call dir {:command :archive! :args {:thread "T1"}})
        (is (= [:archived false] (section-of)))
        (call dir {:token token :command :milestone! :args {:text "pushed 3 commits"}})
        (is (= [:archived true] (section-of)))))))

(deftest sections-derive-from-a-fixed-event-list-as-the-table-says
  (let [base [{:seq 1 :at "2026-11-02T10:00:00Z" :kind :thread-created :thread "T1" :data {:id "T1" :title "t"}}
              {:seq 2 :at "2026-11-02T10:00:01Z" :kind :worker-attached :thread "T1" :data {:id "W1" :conversation "c" :generation 1}}]
        ask (fn [n & {:as more}] {:seq n :kind :ask-opened :thread "T1" :data (merge {:id "a1" :worker "W1" :kind :question :text "?" :options [{:id "y" :label "Y"}] :stopped false} more)})
        reply {:seq 4 :kind :reply-sent :thread "T1" :data {:id "r1" :thread "T1" :worker "W1" :picks {"a1" "y"} :note "" :dismiss #{} :outbox "o1" :conversation "c" :text "m"}}
        section (fn [events cursor]
                  (let [state (reduce model/fold model/empty-state events)]
                    (model/section state (get-in state [:threads "T1"]) cursor)))]
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

(deftest the-pane-rows-carry-the-section-and-the-preview-numbers-the-options
  (let [sections {:badge 1
                  :sections [{:section :stopped :title "Stopped on you"
                              :threads [{:id "T1" :title "Alt+W closes windows" :updated-at (str (java.time.Instant/now))
                                         :ask {:text "Keep the alias?\nSecond line"}}]}
                             {:section :archived :title "Archived" :threads [{:id "T3" :title "old" :came-back true}]}]}
        [row1 row3] (clojure.string/split-lines (bbh.super.pane/rows sections))]
    (is (re-matches #"T1\tSTOPPED ON YOU   T1   Alt\+W closes windows  Keep the alias\?  now" row1))
    (is (re-matches #"T3\tARCHIVED         T3   old \(came back\)    " row3))
    (is (= "bbh inbox · 1 need you   1-4 pick  s send  r note  m mark read  a archive" (bbh.super.pane/header sections)))
    (is (= ["T1  Alt+W" "execution waiting, W1 waiting" "" "STOPPED  Keep?" "  1. No  (recommended)" "  2. Yes  <- picked" "" "note:" "and why"]
           (clojure.string/split-lines
            (bbh.super.pane/preview {:id "T1" :title "Alt+W" :execution :waiting :workers [{:id "W1" :state :waiting}]
                                     :asks [{:id "a1" :state :open :seq 3 :text "Keep?" :stopped true :recommended "no"
                                             :options [{:id "no" :label "No"} {:id "yes" :label "Yes"}]}]}
                                    {:picks {"T1" {"a1" "yes"}} :notes {"T1" "and why"}}))))))

;; ---- Workers ---------------------------------------------------------------

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
  "A controller whose pane and wf start are recorded instead of run, on a
  temporary jj repo, with a policy of two Workers. Calls F with the controller,
  its dir, the repo and the atom of launches."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-w"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-wconv"}))
        repo (jj-repo)
        launches (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :policy {:workers 2 :locks ["guix-build"] :never-granted ["guix-system"]}
                         :launch {:wf-start! (fn [workspace w] (str "run-" (:id w)))
                                  :pane! (fn [spec] (swap! launches conj spec) (str "wC:p" (count @launches)))}})]
    (try (f c dir repo launches)
         (finally (super/stop! c)))))

(defn- start-worker [dir thread & {:as more}]
  (call dir {:command :start-worker! :args (merge {:thread thread :workflow :change :brief (str "work on " thread) :repo (:repo more)} more)}))

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
               (set (map #(first (clojure.string/split % #":")) (clojure.string/split-lines (:out (babashka.process/shell {:dir repo :out :string} "jj" "workspace" "list")))))))
        (is (= ["T1 first" "T2 second"] (map #(str (:thread %) " " (:title %)) @launches)))
        (is (every? #(re-matches #"[0-9a-f]{48}" (:token %)) @launches) "each pane gets its Worker's token")
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

(deftest a-lock-holder-blocks-a-second-worker-until-it-finishes
  (with-workers
    (fn [c dir repo launches]
      (doseq [t ["a" "b"]] (call dir {:command :thread! :args {:title t}}))
      (is (= :working (:state (:ok (start-worker dir "T1" :repo repo :locks ["guix-build"])))))
      (is (= {"guix-build" "W1"} (:locks @(:state c))))
      (let [w2 (:ok (start-worker dir "T2" :repo repo :locks ["guix-build"]))]
        (is (= [:queued "guix-build held by W1"] [(:state w2) (:reason w2)]))
        (is (= :never-granted (:error (call dir {:command :start-worker! :args {:thread "T2" :workflow :change :brief "x" :repo repo :locks ["guix-system"]}}))))
        (is (= :unknown-lock (:error (call dir {:command :start-worker! :args {:thread "T2" :workflow :change :brief "x" :repo repo :locks ["repo-push"]}}))))
        (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "REPL died"}})
        (is (= {"guix-build" "W2"} (:locks @(:state c))))
        (is (= :working (:state (get-in @(:state c) [:workers "W2"]))))
        (is (= :failed (:execution (:ok (call dir {:command :thread :args {:id "T1"}})))))))))

(deftest takeover-moves-the-lease-to-you-and-handback-returns-it
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "a"}})
      (start-worker dir "T1" :repo repo)
      (let [token (:token (first @launches))
            lease #(get-in @(:state c) [:workers "W1" :lease])]
        (is (= :router (lease)))
        (is (= {:ok "W1"} (call dir {:command :takeover! :args {:worker "W1"}})))
        (is (= :you (lease)))
        (is (= :forbidden (:error (call dir {:token token :command :handback! :args {:worker "W1"}}))) "a Worker cannot move its own lease")
        (is (= {:ok "W1"} (call dir {:command :handback! :args {:worker "W1"}})))
        (is (= :router (lease)))
        (is (= {:ok "W1"} (call dir {:token token :command :finish-worker! :args {:state-after :done}})) "the Worker ends itself")
        (is (= :working (:state (:ok (start-worker dir "T1" :repo repo)))))
        (is (= (str (fs/path dir "workspaces" "T1-2")) (get-in @(:state c) [:workers "W2" :workspace])) "the next generation has its own workspace")))))

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
          (is (re-find #"run-W1" text)))
        (doseq [f (fs/glob root "mailbox/*.json")] (fs/delete f))
        (is (wait-for #(= [:acked] (map :state (vals (:outbox @(:state c)))))))
        (is (= {} (:replies @(:state c))) "a brief is not a reply")
        (locking (:db c) (is (= (store/replay (:db c)) (store/load (:db c)) @(:state c))))))))
