(ns bbh.inbox-test
  (:require [bbh.hermetic :as hermetic :refer [wait-for]]
            [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.conversation :as conversation]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- with-poller [f]
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))
        delivered (atom [])
        p (inbox/start! {:root root :project "/work/site" :scan-ms 5
                         :deliver #(swap! delivered conj %)})]
    (try (f root p delivered)
         (finally (inbox/stop! p)))))

(deftest a-message-reaches-the-front-end-once-and-leaves-on-ack
  (with-poller
    (fn [root p delivered]
      (let [{:keys [id]} (inbox/send! root "Use the retries queue.")]
        (is (wait-for #(seq @delivered)))
        (Thread/sleep 50)
        (is (= [{:id id :text "Use the retries queue."}] @delivered))
        (is (= ["Use the retries queue."] (map :text (inbox/pending root))))
        (inbox/ack! p id)
        (is (empty? (inbox/pending root)))))))

(deftest messages-arrive-oldest-first
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))]
    (inbox/send! root "one")
    (Thread/sleep 2)
    (inbox/send! root "two")
    (is (= ["one" "two"] (map :text (inbox/pending root))))))

(deftest an-unread-message-waits-for-the-next-poller
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))
        delivered (atom [])
        _ (inbox/send! root "sent while no session ran")
        p (inbox/start! {:root root :project "/p" :scan-ms 5 :deliver #(swap! delivered conj %)})]
    (is (wait-for #(= ["sent while no session ran"] (map :text @delivered))))
    (inbox/stop! p)))

(deftest a-poller-announces-its-session-until-it-stops
  (with-poller
    (fn [root p _]
      (is (= {:project "/work/site" :busy false} (dissoc (inbox/agent root) :pid :start)))
      (inbox/announce! p true)
      (is (true? (:busy (inbox/agent root))))
      (inbox/stop! p)
      (is (nil? (inbox/agent root))))))

(deftest a-dead-pollers-announcement-is-ignored
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))]
    (spit (str (fs/path root "agent.json")) "{\"pid\": 2147483646, \"project\": \"/p\", \"busy\": false}")
    (is (nil? (inbox/agent root)))))

(deftest rollovers-chain-back-to-the-first-conversation
  (let [base (str (fs/create-temp-dir {:prefix "bbh-inbox"}))
        root (str (fs/create-dirs (fs/path base "first")))
        rolled (conversation/continue! root "second")
        grandchild (conversation/continue! rolled "third")]
    (is (= root (conversation/root rolled)))
    (is (= root (conversation/root grandchild)) "a second rollover still finds the first conversation's mailbox")))
