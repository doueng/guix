(ns bbh.inbox-test
  (:require [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.transcript :as transcript]
            [clojure.test :refer [deftest is]]))

(defn- wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 300) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

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

(deftest a-retried-message-is-delivered-again
  (with-poller
    (fn [root p delivered]
      (let [{:keys [id]} (inbox/send! root "again")]
        (is (wait-for #(= 1 (count @delivered))))
        (inbox/retry! p id)
        (is (wait-for #(= 2 (count @delivered))))))))

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
      (is (= {:project "/work/site" :busy false} (dissoc (inbox/agent root) :pid)))
      (inbox/busy! p true)
      (is (true? (:busy (inbox/agent root))))
      (inbox/stop! p)
      (is (nil? (inbox/agent root))))))

(deftest a-dead-pollers-announcement-is-ignored
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))]
    (spit (str (fs/path root "agent.json")) "{\"pid\": 2147483646, \"project\": \"/p\", \"busy\": false}")
    (is (nil? (inbox/agent root)))))

(deftest an-image-is-saved-and-named-in-the-prompt
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))
        {:keys [id text]} (inbox/send! root "what is this?" [(byte-array [1 2 3])])
        path (str (fs/path root "images" (str id "-0.jpg")))]
    (is (= [1 2 3] (vec (fs/read-all-bytes path))))
    (is (= (str "what is this?\n\n[Image from the phone: " path
                ". See it with (look \"your question\" \"" path "\").]")
           text))
    (is (= {:text "what is this?" :images [(str id "-0.jpg")]} (inbox/parts text)))))

(deftest a-message-of-only-images-has-no-words
  (let [root (str (fs/create-temp-dir {:prefix "bbh-inbox"}))
        {:keys [id text]} (inbox/send! root "" [(byte-array [1]) (byte-array [2])])]
    (is (= {:text "" :images [(str id "-0.jpg") (str id "-1.jpg")]} (inbox/parts text)))))

(deftest a-plain-prompt-has-no-images
  (is (= {:text "just words"} (inbox/parts "just words"))))

(deftest a-rolled-over-conversation-keeps-its-mailbox
  (let [base (str (fs/create-temp-dir {:prefix "bbh-inbox"}))
        root (str (fs/create-dirs (fs/path base "first")))
        rolled (transcript/continue! root "second")]
    (is (= root (inbox/root-dir rolled)))))
