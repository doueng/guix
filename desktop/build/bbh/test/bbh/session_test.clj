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
                            (when (string? s) (on-text s))
                            {:message (reply s)}))}
        s (session/new-session {:dir dir :init init :model "faux" :make-engine (constantly engine)
                                :emit #(swap! events conj %)})]
    (spit init init-src)
    (session/start-repl! s)
    {:s s :requests requests :events events}))

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

(deftest render-elides-the-middle-of-long-output
  (is (= "out\n[... 9 chars elided ...]\nhij" (session/render {:out "abcdefghij"} 6))))
