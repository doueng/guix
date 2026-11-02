(ns bbh.super-test
  (:require [babashka.fs :as fs]
            [bbh.super :as super]
            [bbh.super.client :as client]
            [bbh.super.pane]
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
