(ns bbh.eval-test
  (:require [babashka.fs :as fs]
            [bbh.compare :as compare]
            [bbh.labels :as labels]
            [bbh.traces :as traces]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest fingerprints-cover-root-level-and-nested-sci-sources
  (let [desktop (str (fs/create-temp-dir {:prefix "bbh-fingerprint-test"}))
        root (str (fs/path desktop "build/bbh"))
        direct (str (fs/path desktop "build/pi-sci/src/extension.ts"))
        nested (str (fs/path desktop "build/pi-sci/src/nested/helper.ts"))
        empty-sha "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"]
    (doseq [path [direct nested]]
      (fs/create-dirs (fs/parent path))
      (spit path ""))
    (with-redefs [compare/desktop desktop compare/root root]
      (is (= {direct empty-sha nested empty-sha} (compare/fingerprint))))))

(defn- call [id code]
  {:id id :type "function" :function {:name "clojure" :arguments (json/generate-string {:code code})}})

(defn- conversation!
  "A conversation whose first turn guesses sh's arity and ends on a tool call."
  []
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-trace-test"}))]
    (spit (str (fs/path dir "messages.jsonl"))
          (str/join (for [m [{:role "user" :content "<skill name=\"poteto-mode\" location=\"x\">\nskill body\n</skill>\n\nlist the files"}
                             {:role "assistant" :content "" :tool_calls [(call "c1" "(fs/glob \".\" \"**/*.js\")")
                                                                         (call "c2" "(sh \"ls\" \"-la\")")]}
                             {:role "tool" :tool_call_id "c1" :content "=> []"}
                             {:role "tool" :tool_call_id "c2" :content "err:\nclojure.lang.ArityException: Wrong number of args (2) passed to: sci.impl.fns/fun/arity-1--1 [at init.clj:1:1]\n=> nil"}
                             {:role "user" :content "and now?"}
                             {:role "assistant" :content "Done."}]]
                      (str (json/generate-string m) "\n"))))
    (str (fs/canonicalize dir))))

(deftest assertions-locate-failures-in-a-real-transcript-shape
  (let [dir (conversation!)
        [trace] (traces/find-traces [dir])]
    (is (= {:guessed-arity ["step 2"] :no-final-answer ["turn 1"] :root-blind-glob ["step 1"]}
           (traces/check trace)))
    (is (= :real (:source trace)))))

(deftest rendering-elides-skills-and-flags-steps
  (let [text (traces/render (traces/load-trace (conversation!)))]
    (is (str/includes? text "> [skill poteto-mode expanded here]"))
    (is (not (str/includes? text "skill body")))
    (is (str/includes? text "### Step 2  FAILED clojure.lang.ArityException  [guessed-arity]"))
    (is (str/includes? text "## Turn 1  [no-final-answer]"))))

(deftest review-records-a-human-label-with-critique
  (let [dir (conversation!)
        data (str (fs/create-temp-dir {:prefix "bbh-labels-test"}))]
    (with-redefs [labels/data-dir (constantly data)]
      (with-out-str (with-in-str "b\nended on a tool call\n" (labels/review! dir)))
      (is (= {:trace dir :labeler "human" :outcome "bad" :critique "ended on a tool call"}
             (select-keys (get (labels/load-labels) [dir "human"]) [:trace :labeler :outcome :critique]))))))

(deftest judge-sees-no-grader-verdict-and-returns-its-critique
  (let [prompt (atom nil)
        trace (assoc (traces/load-trace (conversation!)) :task "rename-function" :passed true :source :eval)
        verdict (labels/judge-trace (fn [text] (reset! prompt text)
                                      "Here you go.\n{\"critique\": \"Step 2 failed.\", \"outcome\": \"bad\"}")
                                    trace)]
    (is (= {:critique "Step 2 failed." :outcome "bad"} verdict))
    (is (str/includes? @prompt "### Step 2"))
    (is (not (str/includes? @prompt "grader")))
    (is (thrown? Exception (labels/parse-verdict "{\"outcome\": \"maybe\"}")))))

(deftest agreement-treats-bad-as-the-positive-class
  (let [label (fn [t who outcome] [[t who] {:trace t :labeler who :outcome outcome :critique (str who " on " t)}])
        result (get (labels/agreement (into {} [(label "t1" "human" "bad") (label "t1" "judge:a" "bad")
                                                (label "t2" "human" "bad") (label "t2" "judge:a" "good")
                                                (label "t3" "human" "good") (label "t3" "judge:a" "bad")
                                                (label "t4" "human" "good") (label "t4" "judge:a" "good")
                                                (label "t5" "judge:a" "bad")]))
                    "judge:a")]
    (is (= {:n 4 :tp 1 :fp 1 :fn 1 :tn 1 :precision 0.5 :recall 0.5 :true-negative-rate 0.5}
           (dissoc result :disagreements)))
    (is (= ["t2" "t3"] (sort (map :trace (:disagreements result)))))))
