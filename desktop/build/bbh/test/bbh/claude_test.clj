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

(deftest usage-is-per-request-and-cost-is-per-turn
  (with-cli
    (fn []
      (let [{:keys [s events]} (harness)]
        (try
          (session/prompt! s "one")
          (session/prompt! s "two")
          (let [requests (keep :request (filter #(= :usage (:type %)) @events))]
            (is (= [{:input 1000 :cached 900 :written 98 :output 7 :thinking 0 :n 1 :cache :cold :ratio 0.9}
                    {:input 1000 :cached 900 :written 98 :output 7 :thinking 0 :n 1 :cache :hit :ratio 0.9}]
                   requests)))
          (is (= 200000 (:size (last @events))))
          (is (< (abs (- 0.002 (:spent @s))) 1e-9))
          (finally (session/close! s)))))))

(deftest a-resumed-claude-session-hears-what-another-model-said
  (with-cli
    (fn []
      (let [sent (atom [])
            send-line @#'bbh.claude/send-line!
            script (str (fs/create-temp-file {:suffix ".edn"}))]
        (spit script (pr-str ["the script model's reply"]))
        (with-redefs [bbh.claude/send-line! (fn [w msg]
                                              (when (= "user" (:type msg)) (swap! sent conj (get-in msg [:message :content])))
                                              (send-line w msg))]
          (let [{:keys [s]} (harness)]
            (try
              (session/prompt! s "first")
              (session/set-model! s (str "script/" script))
              (session/prompt! s "told only to the script model")
              (session/set-model! s "claude/fake")
              (session/prompt! s "back")
              (session/prompt! s "again")
              (is (= ["first"
                      (str "[bbh] Messages in this conversation since you last took part:\n\n"
                           "user: told only to the script model\n\nassistant: the script model's reply\n\nback")
                      "again"]
                     @sent))
              (finally (session/close! s)))))))))
