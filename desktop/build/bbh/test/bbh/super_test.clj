(ns bbh.super-test
  (:require [bbh.hermetic :as hermetic]
            [babashka.fs :as fs]
            [bbh.super :as super]
            [bbh.super.client :as client]
            [bbh.super.pane :as pane]
            [bbh.super.sandbox :as sandbox]
            [bbh.super.voice :as voice]
            [babashka.process]
            [cheshire.core]
            [bbh.super.model :as model]
            [bbh.super.store :as store]
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

(deftest a-repeated-command-id-returns-its-original-result-and-one-reply
  (with-controller
    (fn [c dir _]
      (let [{:keys [version]} (thread-with-ask dir)
            reply {:id "c-9f2" :command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "no"}}}]
        (is (= {:ok "c-9f2"} (call dir reply)))
        (is (= {:ok "c-9f2"} (call dir reply)) "the second send is the same reply, not a stale conflict")
        (is (= ["o1" "o2"] (sort (keys (:outbox @(:state c))))) "the brief and one reply")
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
        (call dir {:token token :command :note! :args {:text "looking at kanata"}})
        (call dir {:command :reply! :args {:thread "T1" :asks-version version :picks {"a1" "week"} :note "and say why"}})
        (is (wait-for #(= #{:done :sent} (set (map :state (vals (:outbox @(:state c))))))))
        (let [db (:db c)]
          (locking db
            (is (= (store/replay db) (store/load db) @(:state c)))))))))

(deftest a-retired-event-kind-folds-to-nothing-and-an-unknown-one-throws
  (let [state (model/fold model/empty-state {:seq 1 :at "t" :kind :thread-created :thread "T1" :data {:id "T1" :title "x"}})]
    (is (= state (model/fold state {:seq 2 :at "t" :kind :message-routed :thread nil :data {:outbox "o1"}})))
    (is (= :unknown-event (:error (ex-data (try (model/fold state {:seq 3 :at "t" :kind :no-such-kind :data {}})
                                                (catch Exception e e))))))))

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
          (is (= [:answered] (map :state (vals (:replies @(:state c))))) "the Worker's answered! does"))))))

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

(deftest a-worker-token-acts-on-its-own-thread-only
  (with-controller
    (fn [_ dir _]
      (let [{:keys [token]} (thread-with-ask dir)]
        (call dir {:command :thread! :args {:title "another"}})
        (is (= :forbidden (:error (call dir {:token token :command :ask! :args {:thread "T2" :kind :question :text "?" :options []}}))))
        (is (= :forbidden (:error (call dir {:token token :command :reply! :args {:thread "T1" :asks-version 0 :picks {}}}))))
        (is (= :unauthorized (:error (call dir {:token "not a token" :command :threads}))))))))

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
      (is (wait-for #(seq (fs/glob conversations "super-W1/mailbox/*.json"))))
      (is (re-find #"Not answered, do not wait: a1" (slurp (str (first (fs/glob conversations "super-W1/mailbox/*.json"))))))
      (is (= :working (model/execution @(:state c) "T1")) "the Worker is told, so the thread is no longer waiting")
      (is (= {:ok "T1"} (call dir {:command :dismiss! :args {:thread "T1"}})) "nothing open: no second message")
      (is (= 2 (count (:outbox @(:state c)))) "the brief and one dismissal"))))

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
        (is (= [:archived true] (section-of)))
        (call dir {:token token :command :ask! :args {:kind :question :text "Still there?" :options [] :stopped true}})
        (is (= [:stopped true] (section-of)) "an ask opened after archiving needs you")
        (call dir {:command :thread! :args {:title "done"}})
        (call dir {:command :archive! :args {:thread "T2"}})
        (is (= :archived (:error (call dir {:command :start-worker! :args {:thread "T2" :workflow :investigation :brief "b" :repo "/tmp"}}))))
        (is (nil? (:worker (:ok (call dir {:command :thread :args {:id "T2"}})))))))))

(deftest threads-list-in-number-order
  (with-controller
    (fn [_ dir _]
      (dotimes [n 10] (call dir {:command :thread! :args {:title (str "t" (inc n))}}))
      (is (= "T10" (:id (last (:ok (call dir {:command :threads}))))))
      (is (= (map #(str "T" %) (range 1 11))
             (map :id (mapcat :threads (:sections (:ok (call dir {:command :sections}))))))))))

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
    (is (= "bbh inbox · 1 need you   RET open  1-4 pick  s send  A approve  r note  m mark read  a archive  x cancel Worker  R router  J jjui  gr reload  g? help" (bbh.super.pane/header sections [])))
    (is (clojure.string/starts-with? (bbh.super.pane/header sections ["controller" "web"]) "bbh inbox · 1 need you · old code: controller, web   RET open"))
    (is (= ["T1  Alt+W" "execution waiting, W1 waiting" "" "STOPPED  Keep?" "  1. No  (recommended)" "  2. Yes  <- picked" "" "note:" "and why"]
           (clojure.string/split-lines
            (bbh.super.pane/preview {:id "T1" :title "Alt+W" :execution :waiting :workers [{:id "W1" :state :waiting}]
                                     :asks [{:id "a1" :state :open :seq 3 :text "Keep?" :stopped true :recommended "no"
                                             :options [{:id "no" :label "No"} {:id "yes" :label "Yes"}]}]
                                     :draft {:picks {"a1" "yes"} :note "and why" :asks-version 3}}))))))

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
        path (super/workspace! dir {:repo repo :thread "T1" :generation 1})]
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
  its dir, the repo and the atom of launches."
  [f]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-w"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-wconv"}))
        repo (jj-repo)
        launches (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :policy {:workers 2}
                         :launch {:wf-start! (fn [workspace w] (str "run-" (:id w)))
                                  :open! (fn [spec] (swap! launches conj spec) (str "wC:p" (count @launches)))
                                  :release! (constantly nil)}})]
    (try (f c dir repo launches)
         (finally (super/stop! c)))))

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
               (set (map #(first (clojure.string/split % #":")) (clojure.string/split-lines (:out (babashka.process/shell {:dir repo :out :string} "jj" "workspace" "list")))))))
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

(deftest a-full-cap-queues-a-worker-until-one-finishes
  (with-workers
    (fn [c dir repo launches]
      (doseq [t ["a" "b" "c"]] (call dir {:command :thread! :args {:title t}}))
      (is (= :working (:state (:ok (start-worker dir "T1" :repo repo)))))
      (is (= :working (:state (:ok (start-worker dir "T2" :repo repo)))))
      (let [w3 (:ok (start-worker dir "T3" :repo repo))]
        (is (= [:queued "2 Workers already running"] [(:state w3) (:reason w3)]))
        (call dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "REPL died"}})
        (is (wait-for #(= :working (:state (get-in @(:state c) [:workers "W3"])))))
        (is (= :failed (:execution (:ok (call dir {:command :thread :args {:id "T1"}})))))))))
(deftest there-is-no-lease-so-the-router-steers-any-worker
  (with-workers
    (fn [c dir repo launches]
      (call dir {:command :thread! :args {:title "a"}})
      (start-worker dir "T1" :repo repo)
      (let [token (:token (first @launches))
            router (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))]
        (is (= :unknown-command (:error (call dir {:command :takeover! :args {:worker "W1"}}))))
        (is (= {:ok "W1"} (call dir {:token router :command :steer! :args {:worker "W1" :text "router says"}})))
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
          (is (re-find #"run-W1" text))
          (is (clojure.string/includes? text (str "Work only in your jj workspace " (fs/path dir "workspaces" "T1-1"))) "it says where to work")
          (is (clojure.string/includes? text (str repo " is the main checkout"))))
        (doseq [f (fs/glob root "mailbox/*.json")] (fs/delete f))
        (is (wait-for #(= [:done] (map :state (vals (:outbox @(:state c)))))))
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
        (is (= "o1 [herdr] Make the page show cost per session\n\no2 [voice] (spoken) and what is T3 doing"
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
          (locking (:db c2) (is (= (store/replay (:db c2)) (store/load (:db c2)) @(:state c2))))
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
        (locking (:db c) (is (= (store/replay (:db c)) (store/load (:db c)) @(:state c))))))))

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
        (locking (:db c) (is (= (store/replay (:db c)) (store/load (:db c)) @(:state c))))))))

(deftest steering-is-durable-typed-and-idempotent
  (with-controller
    (fn [c dir conversations]
      (let [{:keys [token]} (thread-with-ask dir)
            command {:id "steer-once" :command :steer! :args {:worker "W1" :text "use the small build"}}]
        (is (= {:ok "W1"} (call dir command)))
        (is (= {:ok "W1"} (call dir command)))
        (is (wait-for #(seq (fs/glob conversations "super-W1/mailbox/*.json"))))
        (is (= [{:kind "steer" :text "[Steering] use the small build"}]
               (mapv #(select-keys % [:kind :text]) (bbh.inbox/pending (str (fs/path conversations "super-W1"))))))
        (is (= 1 (count (filter #(= :worker-steered (:kind %)) (store/events (:db c))))))
        (is (= :forbidden (:error (call dir {:token token :command :steer! :args {:worker "W1" :text "worker says"}}))))
        (is (= :invalid (:error (call dir {:command :steer! :args {:worker "W1" :text " "}}))))
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
            (is (= {:ok "W1"} (call dir {:id "cancel-once" :command :cancel! :args {:worker "W1" :reason "stop the build"}})))
            (is (= {:ok "W1"} (call dir {:id "cancel-once" :command :cancel! :args {:worker "W1" :reason "stop the build"}})))
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
          (is (= :needs-attention (model/section @(:state c) (get-in @(:state c) [:threads "T1"]) 0)))
          (is (re-find #"Resume it" (:line (first (:threads (first (model/sections @(:state c) {})))))))
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
              (is (re-find #"2\. Yes, for a week  <- picked" (pane/preview (:ok (call dir {:command :thread :args {:id "T1"}}))))
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
            row #(first (:threads (first (filter (fn [s] (= :needs-attention (:section s))) (model/sections @(:state c) {})))))]
        (try
          (call dir {:command :steer! :args {:worker "W1" :text "use plan B"}})
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

(deftest only-a-handle-routes-a-message-to-a-worker
  (with-controller
    (fn [_ dir _]
      (call dir {:command :register-router! :args {:conversation "router"}})
      (call dir {:command :thread! :args {:title "Alt+W"}})
      (run-worker dir "T1")
      (is (= :unknown-command (:error (call dir {:command :link! :args {:thread "T1" :resource "repo:engstrand/guix"}}))))
      (is (= {:to :router} (:ok (call dir {:command :say! :args {:origin :herdr :text "the review on engstrand/guix#12 wants tests"}}))))
      (is (= {:thread "T1" :to "W1"} (:ok (call dir {:command :say! :args {:origin :herdr :text "T1 the review wants tests"}})))))))
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
        (is (= ["[routed to T1 W1 from herdr] keep the alias\n\no4 [phone] what is running"] (prompts)))))))

(deftest a-workers-origin-comes-from-the-message-the-router-names
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super-cap"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-capconv"}))
        repo (jj-repo)
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :launch {:wf-start! (fn [_ w] (str "run-" (:id w))) :open! (fn [_] "wC:p1")}})]
    (try
      (let [router (:token (:ok (call dir {:command :register-router! :args {:conversation "router"}})))
            start (fn [thread for & {:as more}]
                    (call dir {:token router :command :start-worker!
                               :args (merge {:thread thread :for for :workflow :investigation :brief "look" :repo repo} more)}))]
        (call dir {:command :say! :args {:text "push the fix" :origin :phone}})
        (doseq [t ["a" "b"]] (call dir {:command :thread! :args {:title t}}))
        (is (= :invalid (:error (start "T1" nil))) "the Router must name the message")
        (is (= :invalid (:error (start "T1" "o9"))) "and a real one")
        (let [r (start "T1" "o1")]
          (is (= :phone (:origin (get-in @(:state c) [:workers (:id (:ok r))]))) (pr-str r)))
        (let [r (start-worker dir "T2" :repo repo :workflow :investigation)]
          (is (= :owner (:origin (get-in @(:state c) [:workers (:id (:ok r))]))) (pr-str r))))
      (finally (super/stop! c)))))
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
              (is (clojure.string/includes? (pane/preview (:ok (call dir {:command :thread :args {:id "T1"}})))
                                            "REVIEW revision c068de11 evidence e3b0c442  A approve")
                  "the preview shows what A approves")
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
            {:db db :state (store/load db) :results []}
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

(deftest attach-worker-is-gone
  (is (= [:unknown-command]
         (map :error (drop 1 (:results (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                                      [:owner {:id "a1" :command :attach-worker! :args {:thread "T1" :conversation "c"}}]])))))))

(deftest a-stopped-ask-does-not-undo-a-cancel
  (let [{:keys [state results]} (run-commands [[:owner {:id "t1" :command :thread! :args {:title "a"}}]
                                               (start-cmd "a1" "T1") (running "W1")
                                               [:owner {:id "c1" :command :cancel! :args {:worker "W1" :reason "stop"}}]
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
    (is (= :waiting (:execution (model/thread-view state (get-in state [:threads "T1"])))))))

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

(deftest the-inbox-cancels-a-worker-on-an-archived-thread
  (with-controller
    (fn [c dir _]
      (binding [pane/*state-file* (str (fs/path dir "inbox.edn"))]
        (let [original client/call!]
          (with-redefs [client/call! (fn ([r] (original dir r)) ([d r] (original d r)))]
            (call dir {:command :thread! :args {:title "a"}})
            (run-worker dir "T1")
            (call dir {:command :archive! :args {:thread "T1"}})
            (pane/-main "cancel" "T1")
            (is (wait-for #(= :cancelled (get-in @(:state c) [:workers "W1" :state]))) "the inbox cancels W1 though T1 is archived")
            (is (nil? (get-in @(:state c) [:threads "T1" :worker])))))))))

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
            (let [{:keys [token]} (thread-with-ask dir)]
              (call dir {:token token :command :ask! :args {:kind :action :text "Switch?" :options [{:id "yes" :label "Yes"}]}})
              (is (thrown-with-msg? Exception #"changed" (pane/pick! "T1" 1 :ask "a1")))
              (is (nil? (:draft (:ok (call dir {:command :thread :args {:id "T1"}}))))))))))))


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
              "(eng/bbh-super-open \"super-W1\" \"/w\" \"T1 a\" '((\"BBH_SUPER_TOKEN\" . \"tok\") (\"WF_RUN\" . \"run-1\")))"]
             (args))))))

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
          (commit-file (:workspace w2) "two" "2\n" "two")
          (is (= {:ok "W2"} (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})))
          (is (= {:ok "W1"} (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})))
          (is (= ["one" "two" "start"] (line-of repo (landed c "W1"))) "the later finish lands on the earlier one")
          (is (= #{"default"} (workspaces repo)) "a done Worker's workspace is forgotten")
          (is (not (fs/exists? (:workspace w1))) "and its directory removed")
          (is (= "one" (jj-in repo "log" "--no-graph" "--ignore-working-copy" "-r" "heads(all()) ~ default@" "-T" "description.first_line()"))
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
            (is (= ["three" "one" "two" "start"] (line-of repo "T3-1@")) "its commits moved, conflicted, onto the tip")
            (spit (str (fs/path (:workspace w3) "one")) "clash\n")
            (jj-in (:workspace w3) "squash")
            (is (= {:ok "W3"} (call dir {:command :finish-worker! :args {:worker "W3" :state-after :done}})) "once resolved it lands")
            (is (= ["three" "one" "two" "start"] (line-of repo (landed c "W3"))))
            (is (= #{"default"} (workspaces repo)))))))))

(deftest an-unclear-tip-is-refused-until-the-worker-names-one
  (with-workers
    (fn [c dir repo _]
      (doseq [t ["one" "two"]] (call dir {:command :thread! :args {:title t}}))
      (let [[w1 w2] (map #(:ok (start-worker dir % :repo repo)) ["T1" "T2"])]
        (assert (every? :workspace [w1 w2]) "both Workers started")
        (commit-file (:workspace w1) "one" "1\n" "one")
        (commit-file (:workspace w2) "two" "2\n" "two")
        (call dir {:command :finish-worker! :args {:worker "W1" :state-after :done}})
        (commit-file repo "yours" "y\n" "yours")
        (let [r (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done}})]
          (is (= :unclear-tip (:error r)))
          (is (re-find #"2 heads.*one|2 heads.*yours" (:message r)) (:message r)))
        (is (= {:ok "W2"} (call dir {:command :finish-worker! :args {:worker "W2" :state-after :done :onto (landed c "W1")}})))
        (is (= ["two" "one" "start"] (line-of repo (landed c "W2"))))))))

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
    (is (nil? (super/retire! {:repo repo :workspace ws})))
    (is (= #{"default"} (workspaces repo)))
    (fs/create-dirs ws)
    (is (nil? (super/retire! {:repo repo :workspace ws :keep? true})) "a forgotten workspace's directory still goes")
    (is (not (fs/exists? ws)))
    (is (nil? (super/retire! {:repo repo :workspace ws})) "a second retire is a no-op")
    (fs/create-dirs ws)
    (is (nil? (super/retire! {:repo (str repo "-gone") :workspace ws})) "a missing repo is skipped")
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

(deftest restarts-are-gone
  (let [{:keys [results]} (run-commands [[:controller {:id "r" :command :restart-worker! :args {:worker "W1" :at 1}}]
                                         [:controller {:id "w" :command :worker-restarted! :args {:worker "W1" :buffer "p"}}]])]
    (is (= [:forbidden :forbidden] (map :error results)))))
