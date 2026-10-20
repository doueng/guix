(ns bbh.session-test
  (:require [babashka.fs :as fs]
            [bbh.session :as session]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- reply [step]
  (if (string? step)
    {:role "assistant" :content step}
    {:role "assistant" :content ""
     :tool_calls [{:id (str "call-" (hash step)) :type "function"
                   :function {:name "clojure" :arguments (json/generate-string step)}}]}))

(defn- harness [init-src steps]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-test"}))
        init (str (fs/path dir "init.clj"))
        requests (atom [])
        events (atom [])
        script (atom steps)
        engine {:kind :loop
                :sample (fn [req {:keys [on-text]}]
                          (swap! requests conj req)
                          (let [s (first @script)]
                            (swap! script rest)
                            (when (= :overflow s)
                              (throw (ex-info "OpenRouter 400: maximum context length" {:overflow true})))
                            (when (string? s) (on-text s))
                            {:message (reply s)}))}
        s (session/new-session {:dir dir :init init :model "faux" :make-engine (constantly engine)
                                :emit #(swap! events conj %)})]
    (spit init init-src)
    (session/start-repl! s)
    {:s s :requests requests :events events :dir dir}))

(defn- tool-results [{:keys [s]}]
  (->> (:messages @s) (filter #(= "tool" (:role %))) (mapv :content)))

(defn- system-prompt [{:keys [requests]} n]
  (get-in @requests [n :messages 0 :content]))

(defn- stop! [{:keys [s]}] (session/close! s))

(deftest definitions-survive-across-turns
  (let [h (harness "" [{:code "(def x 41)"} "ok" {:code "(println :hi) (inc x)"} "ok"])]
    (session/prompt! (:s h) "define x")
    (session/prompt! (:s h) "use x")
    (stop! h)
    (is (= ["=> #'user/x" "out:\n:hi\n=> 42"] (tool-results h)))))

(deftest a-turn-reports-its-call-result-and-text-as-events
  (let [h (harness "" [{:code "(+ 1 2)"} "three"])]
    (session/prompt! (:s h) "add")
    (stop! h)
    (is (= [[:tool-call "(+ 1 2)"] [:tool-result "=> 3" :completed] [:text "three"]]
           (for [{:keys [type code text status]} @(:events h)]
             (case type :tool-call [type code] :tool-result [type text status] :text [type text]))))))

(deftest a-truncated-call-is-healed-and-says-so
  (let [h (harness "" [{:code "(+ 20 (* 2 11)"} {:code "(def x (+ 1 2)\n(inc x)"} "done"])]
    (session/prompt! (:s h) "add")
    (stop! h)
    (let [[healed swallowed] (tool-results h)]
      (is (= "[auto-repair] Unbalanced delimiters: appended `)` to close the `(` opened at line 1, column 1. If that placement is wrong, resend the corrected text.\n=> 42"
             healed))
      (is (str/includes? swallowed "EOF while reading"))
      (is (not (str/includes? swallowed "auto-repair"))))))

(deftest a-failing-evaluation-reports-failed
  (let [h (harness "" [{:code "(/ 1 0)"} "done"])]
    (session/prompt! (:s h) "divide")
    (stop! h)
    (is (= :failed (:status (first (filter #(= :tool-result (:type %)) @(:events h))))))))

(deftest deadline-kills-the-repl-and-reloads-init
  (let [h (harness "(ns bbh.env) (def manifest {:deadline-ms 500}) (def init-marker 7)"
                   [{:code "(def y 1)"} {:code "(loop [] (recur))"} {:code "[(resolve 'y) bbh.env/init-marker]"} "ok"])
        start (System/currentTimeMillis)]
    (session/prompt! (:s h) "spin")
    (stop! h)
    (is (< (- (System/currentTimeMillis) start) 5000))
    (is (= ["=> #'user/y"
            "Timed out after 500 ms. The REPL process was killed and restarted, so live definitions are gone and init.clj was reloaded."
            "=> [nil 7]"]
           (tool-results h)))))

(deftest redefined-manifest-changes-the-next-system-prompt
  (let [h (harness "" [{:code "(intern (create-ns 'bbh.env) 'manifest (fn [] {:system \"Speak like a pirate.\"}))"} "arr"])]
    (session/prompt! (:s h) "go")
    (stop! h)
    (is (= "Speak like a pirate." (system-prompt h 1)))))

(deftest invalid-manifest-falls-back-and-says-why
  (let [h (harness "(ns bbh.env) (def manifest {:deadline-ms \"soon\" :system \"Custom.\"})" ["ok"])]
    (session/prompt! (:s h) "go")
    (stop! h)
    (is (= "Custom.\n\n[bbh] bbh.env/manifest is invalid, so these defaults apply:\n:deadline-ms must be a positive integer, got \"soon\""
           (system-prompt h 0)))))

(deftest interrupt-kills-evaluation-and-ends-the-turn
  (let [h (harness "" [{:code "(println :before) (Thread/sleep 60000)"} "not reached"])]
    (future (Thread/sleep 1000) (session/interrupt! (:s h)))
    (is (= :cancelled (session/prompt! (:s h) "sleep")))
    (stop! h)
    (is (str/starts-with? (first (tool-results h)) "The user interrupted the evaluation."))
    (is (= 1 (count @(:requests h))))))

(deftest expand-prompt-rewrites-input-and-can-answer-directly
  (let [h (harness "(ns bbh.env)
                     (defn expand-prompt [{:keys [text first?]}]
                       (cond (= text \"/ping\") {:reply \"pong\"}
                             first? (str \"FIRST \" text)))"
                   ["one" "two"])]
    (session/prompt! (:s h) "hello")
    (session/prompt! (:s h) "/ping")
    (session/prompt! (:s h) "again")
    (stop! h)
    (is (= ["FIRST hello" "again"] (->> (:messages @(:s h)) (filter #(= "user" (:role %))) (map :content))))
    (is (= "pong" (:text (nth (filter #(= :text (:type %)) @(:events h)) 1))))))

(deftest commands-run-without-the-model
  (let [h (harness "" [])]
    (session/prompt! (:s h) "/port")
    (stop! h)
    (is (= (str "nREPL 127.0.0.1:" (:port (:repl @(:s h)))) (:text (first @(:events h)))))
    (is (= 0 (count @(:requests h))))))

(defn- texts [{:keys [events]}]
  (->> @events (filter #(= :text (:type %))) (mapv :text)))

(def ^:private long-answer (apply str (repeat 400 "x")))

(deftest a-large-context-is-compacted-before-the-next-request
  (let [h (harness "(ns bbh.env) (def manifest {:compact-at-tokens 50 :keep-recent-tokens 10})"
                   [long-answer "SUMMARY" "second"])
        _ (session/prompt! (:s h) "hello")
        _ (session/prompt! (:s h) "next")
        resumed (session/new-session {:dir (:dir h) :model "faux" :make-engine (constantly {:kind :loop})})]
    (stop! h)
    (is (nil? (:tools (get @(:requests h) 1))))
    (is (= [{:role "system" :content (system-prompt h 2)}
            {:role "user" :content "[bbh] Earlier messages in this conversation were compacted into this summary:\n\nSUMMARY"}
            {:role "user" :content "next"}]
           (:messages (get @(:requests h) 2))))
    (is (= ["hello" long-answer "next" "second"] (mapv :content (:messages @(:s h)))))
    (is (= "[bbh] Compacted 2 messages into a summary of about 2 tokens.\n\n" (get (texts h) 1)))
    (is (= {:first-kept 2 :summary "SUMMARY"} (:compaction @resumed)))))

(deftest an-overflowing-request-compacts-and-retries-once
  (let [h (harness "" [long-answer :overflow "SUMMARY" "after"])]
    (session/prompt! (:s h) "hello")
    (is (= :end-turn (session/prompt! (:s h) "next")))
    (stop! h)
    (is (= "after" (:content (peek (:messages @(:s h))))))
    (is (= "[bbh] The request exceeded the model's context. Compacted 1 messages into a summary of about 2 tokens.\n\n"
           (get (texts h) 1)))))

(deftest compact-command-passes-the-users-focus
  (let [h (harness "" ["answer" "SUMMARY"])]
    (session/prompt! (:s h) "hello")
    (session/prompt! (:s h) "/compact keep the numbers")
    (stop! h)
    (is (str/ends-with? (get-in @(:requests h) [1 :messages 1 :content])
                        "The user asks the summary to focus on: keep the numbers"))
    (is (= "Compacted 1 messages into a summary of about 2 tokens." (peek (texts h))))))

(deftest render-elides-the-middle-of-long-output
  (is (= "out\n[... 9 chars elided ...]\nhij" (session/render {:out "abcdefghij"} 6))))
