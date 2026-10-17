(ns bbh.claude-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.claude]
            [bbh.models :as models]
            [bbh.session :as session]
            [clojure.test :refer [deftest is]]))

(defn- harness []
  (let [events (atom [])
        s (session/new-session {:dir (str (fs/create-temp-dir {:prefix "bbh-claude-test"}))
                                :model "claude/fake" :make-engine models/engine
                                :emit #(swap! events conj %)})]
    (session/start-repl! s)
    {:s s :events events}))

(defn- with-cli [f]
  (let [process p/process
        fixture (str (fs/absolutize "test/fake-claude.clj"))]
    (with-redefs [p/process (fn [opts cmd & args]
                             (apply process opts (if (= "claude" cmd) fixture cmd) args))]
      (f))))

(defn- await-call [events]
  (loop [n 200]
    (cond (some #(= :tool-call (:type %)) @events) true
          (zero? n) (throw (ex-info "No tool call" {:events @events}))
          :else (do (Thread/sleep 25) (recur (dec n))))))

(deftest claude-adapter-uses-real-mcp-and-recovers-after-cancel
  (with-cli
    (fn []
      (let [{:keys [s events]} (harness)]
        (try
          (let [turn (future (session/prompt! s "sleep"))]
            (await-call events)
            (session/interrupt! s)
            (is (= :cancelled (deref turn 10000 :timed-out))))
          (is (= :end-turn (session/prompt! s "again")))
          (is (= "=> 42" (:text (last (filter #(= :tool-result (:type %)) @events)))))
          (is (= ["tool" "assistant" "user" "assistant" "tool" "assistant"]
                 (mapv :role (take-last 6 (:messages @s)))))
          (let [server (get-in @s [:bridge :server])]
            (session/close! s)
            (is (.isClosed server)))
          (finally (session/close! s)))))))

(deftest claude-errors-are-not-successful-empty-replies
  (with-cli
    (fn []
      (let [{:keys [s]} (harness)]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"synthetic provider failure"
                               (session/prompt! s "fail")))
          (finally (session/close! s)))))))
