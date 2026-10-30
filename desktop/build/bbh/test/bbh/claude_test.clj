(ns bbh.claude-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.claude]
            [bbh.models :as models]
            [bbh.runs :as runs]
            [bbh.session :as session]
            [bbh.transcript :as transcript]
            [clojure.string :as str]
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

(defn- with-recorded-cli [f]
  (let [process p/process
        fixture (str (fs/absolutize "test/fake-claude.clj"))
        started (atom [])]
    (with-redefs [p/process (fn [opts cmd & args]
                              (when (= "claude" cmd) (swap! started conj {:env (:extra-env opts) :args (vec args)}))
                              (apply process opts (if (= "claude" cmd) fixture cmd) args))]
      (f started))))

(deftest a-full-claude-context-mid-turn-restarts-claude-code-in-a-new-conversation
  (with-recorded-cli
    (fn [started]
      (let [{:keys [s]} (harness)
            old (:dir @s)]
        (try
          (is (= :end-turn (session/prompt! s "full")))
          (let [new-dir (:dir @s)
                messages (transcript/load-messages new-dir)]
            (is (not= old new-dir))
            (is (= ["user" "assistant" "tool"] (mapv :role (take-last 3 (transcript/load-messages old)))))
            (is (= ["user" "assistant" "tool" "assistant"] (mapv :role messages)))
            (is (str/starts-with? (:content (first messages)) "[bbh] This is a continuation session."))
            (is (str/includes? (:content (first messages)) (str old "/messages.jsonl")))
            (is (= [["1" false] ["1" false]]
                   (for [{:keys [env args]} @started]
                     [(get env "DISABLE_AUTO_COMPACT") (boolean (some #{"--resume"} args))]))))
          (finally (session/close! s)))))))

(deftest a-claude-overflow-continues-in-a-new-conversation
  (with-cli
    (fn []
      (let [{:keys [s]} (harness)
            old (:dir @s)]
        (try
          (is (= :end-turn (session/prompt! s "overflow")))
          (is (= ["overflow"] (mapv :content (transcript/load-messages old))))
          (is (= ["user" "assistant" "tool" "assistant"] (mapv :role (:messages @s))))
          (finally (session/close! s)))))))

(deftest claude-errors-are-not-successful-empty-replies
  (with-cli
    (fn []
      (let [{:keys [s]} (harness)]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"synthetic provider failure"
                               (session/prompt! s "fail")))
          (finally (session/close! s)))))))

(deftest a-crash-leaves-its-exit-code-and-output-in-claude-log
  (with-cli
    (fn []
      (let [{:keys [s]} (harness)]
        (try
          (let [e (try (session/prompt! s "crash") nil (catch clojure.lang.ExceptionInfo e e))
                log (slurp (str (fs/path (:dir @s) "claude.log")))]
            (is (some? e))
            (is (re-find #"exited with code 3: fatal: synthetic crash; see " (ex-message e)))
            (is (re-find #"panic: stray stdout before dying" log))
            (is (re-find #"fatal: synthetic crash" log))
            (is (re-find #"claude exited with code 3 at " log)))
          (finally (session/close! s)))))))

(deftest usage-is-per-request-and-cost-is-per-turn
  (with-cli
    (fn []
      (let [{:keys [s events]} (harness)]
        (try
          (session/prompt! s "one")
          (session/prompt! s "two")
          (let [requests (keep :request (filter #(= :usage (:type %)) @events))]
            (is (= [{:input 1000 :cached 900 :written 98 :output 7 :thinking 0 :effort "medium" :n 1 :cache :cold :ratio 0.9}
                    {:input 1000 :cached 900 :written 98 :output 7 :thinking 0 :effort "medium" :n 1 :cache :hit :ratio 0.9}]
                   requests)))
          (is (= 200000 (:size (last @events))))
          (is (< (abs (- 0.002 (:spent @s))) 1e-9))
          (finally (session/close! s)))))))

(deftest a-claude-turn-writes-tool-llm-and-turn-runs
  (with-cli
    (fn []
      (let [{:keys [s]} (harness)]
        (try
          (session/prompt! s "one")
          (let [rs (runs/load-runs (:dir @s))
                [tool llm turn] rs]
            (is (= ["tool" "llm" "turn"] (mapv :kind rs)))
            (is (= #{(:id turn)} (set (map :parent [tool llm]))))
            (is (= {:input 1000 :cached 900 :written 98 :output 7 :thinking 0} (:usage llm)))
            (is (= ["ok" "claude/fake"] [(:status tool) (:model turn)]))
            (is (< (abs (- 0.001 (get-in turn [:usage :cost]))) 1e-9)))
          (finally (session/close! s)))))))

(deftest an-interrupted-claude-request-keeps-its-time-and-says-it-was-interrupted
  (with-cli
    (fn []
      (let [{:keys [s events]} (harness)]
        (try
          (let [turn (future (session/prompt! s "stream"))]
            (loop [n 200]
              (when (and (pos? n) (not-any? #(= :text (:type %)) @events))
                (Thread/sleep 25)
                (recur (dec n))))
            (Thread/sleep 200)
            (session/interrupt! s)
            (is (= :cancelled (deref turn 10000 :timed-out))))
          (let [[llm turn] (runs/load-runs (:dir @s))]
            (is (= ["llm" "interrupted"] [(:kind llm) (:status llm)]))
            (is (>= (:ms llm) 200))
            (is (= ["turn" "interrupted"] [(:kind turn) (:status turn)])))
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
