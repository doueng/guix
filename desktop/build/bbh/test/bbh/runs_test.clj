(ns bbh.runs-test
  (:require [babashka.fs :as fs]
            [bbh.metrics :as metrics]
            [bbh.runs :as runs]
            [bbh.session :as session]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))

(defn- session-with [steps]
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-runs-test"}))
        script (atom steps)
        engine {:kind :loop
                :sample (fn [_ {:keys [on-text]}]
                          (let [{:keys [throw-msg content calls usage]} (first @script)]
                            (swap! script rest)
                            (when throw-msg (throw (ex-info throw-msg {})))
                            (on-text (or content ""))
                            {:message (cond-> {:role "assistant" :content (or content "")}
                                        calls (assoc :tool_calls calls))
                             :usage usage}))}
        s (session/new-session {:dir dir :model "faux" :make-engine (constantly engine)})]
    (session/start-repl! s)
    s))

(defn- call [id code]
  {:id id :type "function" :function {:name "clojure" :arguments (json/generate-string {:code code})}})

(deftest a-turn-writes-a-run-tree-that-points-into-the-transcript
  (let [s (session-with [{:calls [(call "c1" "(+ 1 2)")]
                          :usage {:input 100 :cached 40 :output 5 :cost 0.01}}
                         {:content "three" :usage {:input 120 :cached 100 :output 2 :cost 0.02}}])]
    (session/prompt! s "add")
    (let [dir (:dir @s)
          _ (session/close! s)
          rs (runs/load-runs dir)
          {:strs [turn llm tool]} (group-by :kind rs)
          [t] turn]
      (is (= ["llm" "tool" "llm" "turn"] (mapv :kind rs)))
      (is (= #{(:id t)} (set (map :parent (concat llm tool)))))
      (is (nil? (:parent t)))
      (is (= #{(:id t)} (set (map :trace rs))))
      (is (= [0 4] (:messages t)))
      (is (= 0.03 (get-in t [:usage :cost])))
      (is (= 220 (get-in t [:usage :input])))
      (is (= [{:input 100 :cached 40 :output 5 :cost 0.01} {:input 120 :cached 100 :output 2 :cost 0.02}]
             (mapv :usage llm)))
      (is (= [1 0] (mapv :tool-calls llm)))
      (is (= [["c1" "ok" 4]] (mapv (juxt :tool-call-id :status :chars) tool)))
      (is (every? #(and (nat-int? (:ms %)) (string? (:start %))) rs)))))

(deftest a-failed-request-records-the-error-on-the-llm-and-turn-runs
  (let [s (session-with [{:throw-msg "boom"}])]
    (is (thrown? Exception (session/prompt! s "go")))
    (let [dir (:dir @s)
          _ (session/close! s)
          rs (runs/load-runs dir)]
      (is (= [["llm" "error" "boom"] ["turn" "error" "boom"]]
             (mapv (juxt :kind :status :error) rs))))))

(deftest a-failing-evaluation-is-an-error-run
  (let [s (session-with [{:calls [(call "c1" "(/ 1 0)")]} {:content "sorry"}])]
    (session/prompt! s "divide")
    (let [dir (:dir @s)
          _ (session/close! s)
          {:strs [tool turn]} (group-by :kind (runs/load-runs dir))]
      (is (= ["error"] (mapv :status tool)))
      (is (= ["ok"] (mapv :status turn))))))

(deftest an-interrupted-evaluation-is-recorded-before-the-repl-restarts
  (let [s (session-with [{:calls [(call "c1" "(Thread/sleep 60000)")]}])
        at-restart (atom nil)
        restart session/restart-repl!]
    (with-redefs [session/restart-repl! (fn [st]
                                          (reset! at-restart (mapv (juxt :kind :status :restart) (runs/load-runs (:dir @st))))
                                          (restart st))]
      (let [turn (future (session/prompt! s "sleep"))]
        (loop [n 200]
          (when (and (pos? n) (not (:evaluating @s)))
            (Thread/sleep 25)
            (recur (dec n))))
        (Thread/sleep 200)
        (session/interrupt! s)
        (is (= :cancelled (deref turn 10000 :timed-out)))))
    (session/close! s)
    (is (= [["llm" "ok" nil] ["tool" "interrupted" true]] @at-restart))))

(deftest metrics-group-runs-by-day-and-model
  (let [day "2026-10-30T09:00:00Z"
        rs [{:kind "turn" :model "m" :start day :ms 100 :status "ok" :usage {:cost 0.5}}
            {:kind "turn" :model "m" :start day :ms 300 :status "error" :usage {:cost 0.25}}
            {:kind "llm" :model "m" :start day :ms 50 :usage {:input 200 :cached 50 :output 9}}
            {:kind "tool" :model "m" :start day :ms 7 :status "error" :restart true}
            {:kind "tool" :model "m" :start day :ms 9 :status "ok"}
            {:kind "turn" :model "other" :start day :ms 1 :status "ok"}]
        report (metrics/report rs)
        m (get report ["2026-10-30" "m"])]
    (is (= [["2026-10-30" "m"] ["2026-10-30" "other"]] (keys report)))
    (is (= {:turns 2 :errors 1 :tool-failures 1 :restarts 1 :input 200 :output 9 :cache-share 0.25 :cost 0.75}
           (select-keys m [:turns :errors :tool-failures :restarts :input :output :cache-share :cost])))
    (is (= {:n 2 :p50 300 :p95 300} (:turn m)))
    (is (= {:n 2 :p50 9 :p95 9} (:tool m)))))
