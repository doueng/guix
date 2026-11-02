(ns bbh.super-test
  (:require [babashka.fs :as fs]
            [bbh.super :as super]
            [bbh.super.client :as client]
            [bbh.super.pane :as pane]
            [bbh.super.sandbox :as sandbox]
            [bbh.super.voice :as voice]
            [babashka.process]
            [cheshire.core]
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
                                         :line "Keep the alias?\nSecond line"}]}
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
  (call dir {:command :start-worker! :args (merge {:thread thread :workflow :change :brief (str "work on " thread) :repo (:repo more) :check "true"} more)}))

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
        (is (= :never-granted (:error (call dir {:command :start-worker! :args {:thread "T2" :workflow :change :brief "x" :repo repo :check "true" :locks ["guix-system"]}}))))
        (is (= :unknown-lock (:error (call dir {:command :start-worker! :args {:thread "T2" :workflow :change :brief "x" :repo repo :check "true" :locks ["repo-push"]}}))))
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

;; ---- the Router ------------------------------------------------------------

(deftest a-handle-is-read-typed-or-spoken
  (is (= ["T12" "also handle the phone layout"] (model/parse-handle "T12 also handle the phone layout")))
  (is (= ["T12" "use plan B"] (model/parse-handle "t12: use plan B")))
  (is (= ["T12" "option two"] (model/parse-handle "tee twelve, option two")))
  (is (= ["T22" "go"] (model/parse-handle "Tee twenty two go")))
  (is (= ["T7" ""] (model/parse-handle "T seven")))
  (is (nil? (model/parse-handle "Make the page show cost per session")))
  (is (nil? (model/parse-handle "tea for two")) "a word that is not a number is not a handle"))

(deftest a-message-with-a-handle-goes-to-its-worker-without-the-router
  (with-workers
    (fn [c dir repo _]
      (call dir {:command :thread! :args {:title "phone layout"}})
      (start-worker dir "T1" :repo repo)
      (let [root (fs/path (:conversations c) "super-W1")
            files #(set (map str (fs/glob root "mailbox/*.json")))]
        (is (wait-for #(= 1 (count (files)))) "the brief")
        (let [brief (files)]
          (doseq [[origin text] {:phone "T1 also handle the phone layout" :herdr "t1: keep the alias" :voice "tee one, option two"}]
            (is (= {:ok {:thread "T1" :to "W1"}} (call dir {:command :say! :args {:text text :origin origin}})))
            (is (wait-for #(= 2 (count (files)))))
            (let [new (first (remove brief (files)))
                  text (:text (cheshire.core/parse-string (slurp new) true))]
              (is (re-find (case origin :phone #"\[T1 from phone\] also handle the phone layout"
                                 :herdr #"\[T1 from herdr\] keep the alias"
                                 :voice #"\[T1 from voice, dictated\] option two") text))
              (fs/delete new))
            (is (wait-for #(= 1 (count (files)))))))
        (is (= :no-such-thread (:error (call dir {:command :say! :args {:text "T9 hello" :origin :phone}}))))
        (is (= :no-router (:error (call dir {:command :say! :args {:text "no handle here" :origin :phone}}))) "nothing is owed to a Router that is not registered")))))

(deftest unresolved-messages-reach-the-router-as-one-batched-prompt
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
        (is (= 1 (count (fs/glob root "mailbox/*.json"))) "one prompt for both")
        (is (= "[herdr] Make the page show cost per session\n\n[voice] (spoken) and what is T3 doing"
               (:text (cheshire.core/parse-string (slurp (str (first (fs/glob root "mailbox/*.json")))) true))))
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
        opts {:dir dir :conversations conversations :scan-ms 10}
        c (super/start! opts)
        jobs (fs/create-dirs (fs/path conversations "conv-1" "jobs"))
        base (str (fs/path jobs "build-1"))]
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
      (is (= {} (:outbox @(:state c))) "a running job does not wake the Worker")
      (super/stop! c)
      (let [c2 (super/start! opts)]
        (try
          (super/observe-jobs! c2)
          (is (= {} (:outbox @(:state c2))) "restart does not manufacture an exit")
          (spit (str base ".release") "go")
          (is (wait-for #(seq (fs/glob conversations "conv-1/mailbox/*.json"))))
          (dotimes [_ 3] (super/observe-jobs! c2))
          (is (= [{:id "build-1" :exit 17 :log (str base ".log")}]
                 (vec (vals (get-in @(:state c2) [:workers "W1" :jobs])))))
          (is (= 1 (count (filter #(= :job-exited (:kind %)) (store/events (:db c2))))))
          (is (= 1 (count (:outbox @(:state c2)))))
          (is (re-find #"Job build-1 exited with status 17"
                       (:text (cheshire.core/parse-string
                                (slurp (str (first (fs/glob conversations "conv-1/mailbox/*.json")))) true))))
          (locking (:db c2) (is (= (store/replay (:db c2)) (store/load (:db c2)) @(:state c2))))
          (finally (super/stop! c2))))
      (let [c3 (super/start! opts)]
        (try
          (super/observe-jobs! c3)
          (is (= 1 (count (:outbox @(:state c3)))))
          (is (= 1 (count (fs/glob conversations "conv-1/mailbox/*.json"))))
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
            jobs (fs/create-dirs (fs/path conversations "conv-1" "jobs"))]
        (spit (str (fs/path jobs "partial.cmd")) "test")
        (spit (str (fs/path jobs "partial.exit")) "")
        (spit (str (fs/path jobs "orphan.exit")) "0")
        (super/observe-jobs! c)
        (is (= {} (:outbox @(:state c))))
        (is (= :forbidden (:error (call dir {:token token :command :job-exited!
                                            :args {:job "fake" :exit 0 :log "fake.log"}}))))
        (spit (str (fs/path jobs "partial.exit")) "0")
        (is (wait-for #(= 0 (get-in @(:state c) [:workers "W1" :jobs "partial" :exit]))))))))

(deftest steering-is-durable-typed-idempotent-and-respects-router-lease
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [token]} (thread-with-ask dir)
            router (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))
            command {:id "steer-once" :command :steer! :args {:worker "W1" :text "use the small build"}}]
        (is (= {:ok "W1"} (call dir command)))
        (is (= {:ok "W1"} (call dir command)))
        (is (wait-for #(seq (fs/glob conversations "conv-1/mailbox/*.json"))))
        (is (= [{:kind "steer" :text "[Steering] use the small build"}]
               (mapv #(select-keys % [:kind :text]) (bbh.inbox/pending (str (fs/path conversations "conv-1"))))))
        (is (= 1 (count (filter #(= :worker-steered (:kind %)) (store/events (:db c))))))
        (call dir {:command :takeover! :args {:worker "W1"}})
        (is (= :human-lease (:error (call dir {:token router :command :steer! :args {:worker "W1" :text "router says"}}))))
        (is (= :forbidden (:error (call dir {:token token :command :steer! :args {:worker "W1" :text "worker says"}}))))
        (is (= :invalid (:error (call dir {:command :steer! :args {:worker "W1" :text " "}}))))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest cancellation-kills-only-identity-verified-detached-jobs
  (with-controller
    (fn [c dir conversations]
      (thread-with-ask dir)
      (let [root (fs/path conversations "conv-1")
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
            (is (= {:ok "W1"} (call dir {:id "cancel-once" :command :cancel! :args {:worker "W1" :reason "stop the build"}})))
            (is (= {:ok "W1"} (call dir {:id "cancel-once" :command :cancel! :args {:worker "W1" :reason "stop the build"}})))
            (is (wait-for #(nil? (bbh.super.process/identity-of pid))))
            (is (= "137\n" (slurp (str base ".exit"))))
            (is (= :cancelled (get-in @(:state c) [:workers "W1" :state])))
            (is (= [] (filter #(= :open (:state %)) (vals (:asks @(:state c))))))
            (finally (when (bbh.super.process/matches? identity) (bbh.super.process/stop-job! identity)))))))))

(deftest dead-workers-retry-three-times-with-the-same-conversation-and-workflow
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "recover me"}})
      (let [w (:ok (start-worker dir "T1" :repo repo))
            c (assoc c :restart-grace-ms -1)]
        (with-redefs [bbh.workflow/status (fn [_] {:status :active :phase :implement})]
          (dotimes [_ 4] (super/reconcile! c)))
        (is (= 4 (count @launches)) "original launch plus three persisted attempts")
        (is (= ["super-W1" "super-W1" "super-W1" "super-W1"] (mapv :conversation @launches)))
        (is (= ["run-W1" "run-W1" "run-W1" "run-W1"] (mapv :wf-run @launches)))
        (is (= 3 (get-in @(:state c) [:workers "W1" :restarts])))
        (is (= :failed (get-in @(:state c) [:workers "W1" :state])))
        (is (= :needs-attention (model/section @(:state c) (get-in @(:state c) [:threads "T1"]) 0)))
        (is (= "Worker failed three restart attempts; needs human"
               (:line (first (:threads (first (model/sections @(:state c) {})))))))
        (locking (:db c) (is (= (store/replay (:db c)) @(:state c))))))))

(deftest surviving-workers-are-observed-not-restarted-and-cancel-waits-for-receipt
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
          (is (= {:ok "W1"} (call dir {:command :cancel! :args {:worker "W1" :reason "stop now"}})))
          (is (= :cancelling (get-in @(:state c) [:workers "W1" :state])))
          (is (= "W1" (get-in @(:state c) [:threads "T1" :worker])))
          (is (= [{:kind "cancel" :text "[Cancelled] stop now"}]
                 (mapv #(select-keys % [:kind :text]) (filter #(= "cancel" (:kind %)) (bbh.inbox/pending root)))))
          (spit (str (fs/path root "0-cancel-W1.received")) "interrupted")
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
                (str "(require '[bbh.super :as s]) (s/start! " (pr-str opts) ") @(promise)"))
        base (str (fs/path conversations "conv-1" "jobs" "survivor"))]
    (try
      (is (wait-for #(fs/exists? (fs/path dir "port"))))
      (thread-with-ask dir)
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
          (is (= {} (:outbox @(:state c))))
          (spit (str base ".release") "go")
          (is (wait-for #(seq (bbh.inbox/pending (str (fs/path conversations "conv-1"))))))
          (is (= "ran\n" (slurp (str base ".log"))))
          (is (= 1 (count (filter #(= :job-exited (:kind %)) (store/events (:db c))))))
          (finally (super/stop! c))))
      (finally
        (spit (str base ".release") "go")
        (when (babashka.process/alive? proc) (.destroyForcibly (:proc proc)))
        (fs/delete-tree root)))))

(deftest voice-drafts-need-send-and-action-asks-require-keyboard
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)]
              (pane/select! "T1")
              (is (= {:thread "T1" :draft true :option 2} (voice/submit! "option two")))
              (is (= {"T1" {"a1" "week"}} (:picks (pane/client-state))))
              (is (= {} (:replies @(:state c))))
              (is (= {:thread "T1" :sent true} (voice/submit! "send")))
              (is (= [{"a1" "week"}] (mapv :picks (vals (:replies @(:state c))))))
              (call dir {:token token :command :ask! :args {:kind :action :text "Switch system?" :options [{:id "yes" :label "Yes"}]}})
              (is (thrown-with-msg? Exception #"Voice cannot approve" (voice/submit! "option one")))
              (pane/with-state-lock! #(pane/pick! "T1" 1))
              (is (thrown-with-msg? Exception #"keyboard" (voice/submit! "send"))))))))))

(deftest voice-expiry-handle-precedence-and-stale-drafts
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (let [{:keys [token]} (thread-with-ask dir)]
              (call dir {:command :register-router! :args {:conversation "router"}})
              (call dir {:command :thread! :args {:title "second"}})
              (call dir {:command :attach-worker! :args {:thread "T2" :conversation "conv-2"}})
              (pane/select! "T2")
              (is (= {:thread "T1" :draft true :option 2} (voice/submit! "tee one, option two")))
              (call dir {:token token :command :ask! :args {:kind :question :text "A new question" :options []}})
              (is (thrown? Exception (voice/submit! "tee one send")))
              (spit pane/*state-file* (pr-str {:selected {:thread "T2" :at 1}}))
              (is (= {:to :router} (voice/submit! "investigate my build")))
              (is (= :voice (:origin (:data (last (filter #(= :router-queued (:kind %)) (store/events (:db c)))))))))))))))

(deftest hex-stop-does-not-paste-or-route-silence
  (let [calls (atom [])]
    (with-redefs [voice/hex! (fn [& args] (swap! calls conj args) {:exit 4 :out ""})]
      (is (= {:silence true} (voice/stop!)))
      (is (= [["dictation" "stop" "--print"]] (mapv vec @calls))))))

(deftest handle-input-does-not-target-undeliverable-workers
  (doseq [[worker-state conversation] [[:queued nil] [:starting nil] [:cancelling "conv-1"]]]
    (let [state (-> model/empty-state
                    (assoc-in [:threads "T1"] {:id "T1" :worker "W1"})
                    (assoc-in [:workers "W1"] {:id "W1" :thread "T1" :state worker-state :conversation conversation}))]
      (is (= :not-running
             (try (model/decide state :owner {:id "voice-probe" :command :say!
                                              :args {:text "tee one use plan B" :origin :voice}})
                  :accepted
                  (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))))))

(deftest the-router-sandbox-never-falls-back-to-the-owner-token
  (when-not (System/getenv "BBH_SUPER_TOKEN")
    (is (= [:unauthorized "bbh super: no Router token"]
           (try (sandbox/eval-in (sandbox/context sandbox/bindings) "(super-threads)")
                (catch clojure.lang.ExceptionInfo e [(:error (ex-data e)) (ex-message e)]))))))

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

(deftest a-delivery-left-in-a-live-workers-mailbox-past-the-policy-age-needs-you
  (with-controller
    (fn [c dir conversations]
      (call dir {:command :thread! :args {:title "stuck mailbox"}})
      (call dir {:command :attach-worker! :args {:thread "T1" :conversation "conv-1"}})
      (let [root (str (fs/path conversations "conv-1"))
            poller (bbh.inbox/start! {:root root :project root :scan-ms 5 :deliver (fn [_])})
            server (fake-nrepl! root)
            health #(get-in @(:state c) [:workers "W1" :health])
            row #(first (:threads (first (filter (fn [s] (= :needs-attention (:section s))) (model/sections @(:state c) {})))))]
        (try
          (call dir {:command :steer! :args {:worker "W1" :text "use plan B"}})
          (is (wait-for #(= :sent (:state (first (vals (:outbox @(:state c))))))))
          (super/reconcile! c)
          (is (= [:running nil] [(:classification (health)) (:problem (health))]) "a fresh delivery is not an anomaly")
          (super/reconcile! (assoc c :policy {:stale-delivery-minutes 0}))
          (let [id (:id (first (vals (:outbox @(:state c)))))
                problem (str "Delivery " id " has waited 0 min in its mailbox while the Worker runs; it is not taking messages.")]
            (is (= [:needs-human problem] [(:classification (health)) (:problem (health))]))
            (is (= ["T1" problem] [(:id (row)) (:line (row))]) "the row says why it needs you"))
          (doseq [m (bbh.inbox/pending root)] (bbh.inbox/ack! {:root root} (:id m)))
          (is (wait-for #(= :acked (:state (first (vals (:outbox @(:state c))))))))
          (super/reconcile! (assoc c :policy {:stale-delivery-minutes 0}))
          (is (= [:running nil] [(:classification (health)) (:problem (health))]) "it clears once the Worker takes it")
          (bbh.inbox/stop! poller)
          (super/reconcile! c)
          (is (= ["T1" "Its session is gone and it has no workflow to restart it from."] [(:id (row)) (:line (row))]))
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
      (let [row (first (:threads (first (model/sections @(:state c) {}))))]
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

(deftest reads-name-their-neighbours-so-a-reader-can-hop-by-id
  (with-controller
    (fn [c dir _]
      (let [{:keys [version]} (thread-with-ask dir)
            reply (:ok (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "week"}}}))
            worker (-> (call dir {:command :thread :args {:id "T1"}}) :ok :workers first :id)
            w (:ok (call dir {:command :worker :args {:id worker}}))
            ask (:ok (call dir {:command :ask :args {:id (first (:asks w))}}))
            r (:ok (call dir {:command :reply :args {:id (:reply ask)}}))]
        (is (= ["W1" ["a1"] [reply]] [worker (:asks w) (:replies w)]))
        (is (= ["T1" "W1" reply] ((juxt :thread :worker :reply) ask)))
        (is (= ["a1"] (:asks r)))
        (is (= reply (-> (call dir {:command :thread :args {:id "T1"}}) :ok :asks first :reply)))))))

(deftest a-message-naming-a-linked-resource-reaches-that-threads-worker
  (with-controller
    (fn [c dir _]
      (call dir {:command :register-router! :args {:conversation "router"}})
      (doseq [[title conv res] [["Alt+W" "conv-1" "pr:engstrand/guix#12"] ["Fish" "conv-2" "repo:engstrand/dotfish"] ["Also fish" "conv-3" "repo:engstrand/dotfish"]]]
        (let [t (:ok (call dir {:command :thread! :args {:title title}}))]
          (call dir {:command :attach-worker! :args {:thread t :conversation conv}})
          (call dir {:command :link! :args {:thread t :resource res}})))
      (is (= {:thread "T1" :to "W1" :via :resource}
             (:ok (call dir {:command :say! :args {:origin :herdr :text "the review on engstrand/guix#12 wants tests"}}))))
      (is (= {:to :router} (:ok (call dir {:command :say! :args {:origin :herdr :text "bump engstrand/dotfish"}}))) "two threads match")
      (is (= {:to :router} (:ok (call dir {:command :say! :args {:origin :herdr :text "see engstrand/guix#123"}}))) "a longer name is not this one")
      (call dir {:command :thread! :args {:title "No worker yet"}})
      (call dir {:command :link! :args {:thread "T4" :resource "repo:engstrand/hex"}})
      (is (= {:to :router :thread "T4"} (:ok (call dir {:command :say! :args {:origin :herdr :text "engstrand/hex is slow"}})))
          "a matched thread without a session goes to the Router, tagged")
      (call dir {:command :archive! :args {:thread "T1"}})
      (is (= {:to :router} (:ok (call dir {:command :say! :args {:origin :herdr :text "engstrand/guix#12 again"}}))) "archived threads do not match"))))

(deftest routed-messages-ride-along-in-the-routers-next-batch-but-never-wake-it
  (with-controller
    (fn [c dir conversations]
      (call dir {:command :register-router! :args {:conversation "router"}})
      (call dir {:command :thread! :args {:title "Alt+W"}})
      (call dir {:command :attach-worker! :args {:thread "T1" :conversation "conv-1"}})
      (let [router (fs/path conversations "router")
            prompts #(mapv (fn [f] (:text (cheshire.core/parse-string (slurp (str f)) true))) (fs/glob router "mailbox/*.json"))]
        (call dir {:command :say! :args {:text "T1 keep the alias" :origin :herdr}})
        (is (wait-for #(seq (fs/glob (fs/path conversations "conv-1") "mailbox/*.json"))) "the Worker got it")
        (Thread/sleep 100)
        (is (= [] (prompts)) "a routed message alone wakes no Router")
        (call dir {:command :say! :args {:text "what is running" :origin :phone}})
        (is (wait-for #(seq (prompts))))
        (is (= ["[routed to T1 W1 from herdr] keep the alias\n\n[phone] what is running"] (prompts)))))))
