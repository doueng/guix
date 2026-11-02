(ns bbh.super-test
  (:require [babashka.fs :as fs]
            [bbh.super :as super]
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

(defn- call [dir request] (super/call! dir request))

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
  (testing "a stopped ask puts the thread in waiting and the answer puts it back to working"
    (let [events [{:seq 1 :kind :thread-created :thread "T1" :data {:id "T1" :title "t"}}
                  {:seq 2 :kind :worker-attached :thread "T1" :data {:id "W1" :conversation "c" :generation 1}}
                  {:seq 3 :kind :ask-opened :thread "T1" :data {:id "a1" :worker "W1" :kind :question :text "?" :options [{:id "y" :label "Y"}] :stopped true}}
                  {:seq 4 :kind :reply-sent :thread "T1" :data {:id "r1" :thread "T1" :worker "W1" :picks {"a1" "y"} :note "" :dismiss #{} :outbox "o1" :conversation "c" :text "m"}}
                  {:seq 5 :kind :outbox-sent :thread "T1" :data {:id "o1" :message "m1"}}
                  {:seq 6 :kind :outbox-acked :thread "T1" :data {:id "o1"}}
                  {:seq 7 :kind :reply-answered :thread "T1" :data {:id "r1"}}]
          states (reductions model/fold model/empty-state events)]
      (is (= [:none :working :waiting :waiting :waiting :waiting :working]
             (map #(get-in % [:threads "T1" :execution]) (rest states))))
      (is (= [:open :sent :sent :delivered :answered]
             (map #(get-in % [:asks "a1" :state]) (drop 3 states))))
      (is (= :processed (get-in (last states) [:outbox "o1" :state]))))))
