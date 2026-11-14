(ns bbh.grade-test
  (:require [babashka.fs :as fs]
            [bbh.grade :as grade]
            [bbh.hermetic :as hermetic]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- jsonl [f ms] (spit f (str/join (for [m ms] (str (json/generate-string m) "\n")))))

(defn- call [id code] {:id id :type "function" :function {:name "clojure" :arguments (json/generate-string {:code code})}})

(deftest metrics-outcomes-verdicts-and-the-report
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-grade-test"}))
        journal (str dir "/journal.edn")]
    (jsonl (str dir "/messages.jsonl")
           [{:role "user" :content "go"}
            {:role "assistant" :content "" :tool_calls [(call "c1" "($ \"grep -n x .\")") (call "c2" "(read-lines \"a\")")]}
            {:role "tool" :tool_call_id "c1" :content "x"}
            {:role "tool" :tool_call_id "c2" :content "err:\nboom"}
            {:role "assistant" :content "All done."}])
    (jsonl (str dir "/runs.jsonl")
           [{:kind "llm" :ms 30000 :usage {:input 5000 :output 300}}
            {:kind "tool" :tool-call-id "c1" :ms 6000 :status "ok"}
            {:kind "tool" :tool-call-id "c2" :ms 0 :status "error"}
            {:kind "llm" :ms 24000 :usage {:input 7000 :output 200}}])
    (is (= {:calls 2 :sh 1 :shell-share [1 2] :errors 1 :input 12000 :output 500 :minutes 1.0 :final "All done."} (grade/metrics dir)))
    (spit journal (str (pr-str {:workflow :change}) "\n" (pr-str {:type :attest :phase :verify}) "\n"))
    (is (= "verify (active)" (grade/wf-outcome journal)))
    (spit journal (str (pr-str {:type :done}) "\n") :append true)
    (is (= "done" (grade/wf-outcome journal)))
    (is (= {:verdict :worse :critique "It skipped the tests."} (grade/parse-verdict "It skipped the tests.\nVERDICT: worse")))
    (is (nil? (:verdict (grade/parse-verdict "no verdict line"))))
    (let [m (grade/metrics dir)
          md (grade/report {"W1/b" {:state :graded :data {:arm "b" :trace dir :original m :original-wf "done" :replay m :wf "done"
                                                          :blocked 0 :judge {:verdict :same}}}})]
      (is (str/includes? md (str "| " (fs/file-name dir) " | original |  | done | 2 | 1 | 50% (1/2) | 1 | 12k | 0k | 1.0 |  |  |")))
      (is (str/includes? md "| W1/b | b | graded | done | 2 | 1 | 50% (1/2) | 1 | 12k | 0k | 1.0 | 0 | same |"))
      (is (str/includes? md "| b | 1 | 50% (1/2) | 2 | 12k | {:same 1} |")))
    (let [out (str (fs/create-temp-dir {:prefix "bbh-grade-replay"}))
          data {:out out :trace dir :prompt "go"}
          calls (atom 0)]
      (fs/copy-tree dir (str out "/conversation"))
      (with-redefs [babashka.process/shell (fn [& _] (swap! calls inc) {:out (json/generate-string {:result "Fine.\nVERDICT: same"})})]
        (is (nil? (:judge (grade/grade data {:judge false}))))
        (is (zero? @calls) "{:judge false} spends no judge call")
        (let [j (:judge (grade/grade data {}))]
          (is (= :same (:verdict j)))
          (is (re-matches #"replay-judge:opus:[0-9a-f]{8}" (:labeler j)))
          (is (= 1 @calls)))))))
