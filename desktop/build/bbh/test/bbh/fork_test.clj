(ns bbh.fork-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.fork :as fork]
            [bbh.hermetic :as hermetic]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(def ^:private fake-claude
  "Stands in for Claude Code: records what the fork gave it and answers with one tool call."
  "(require '[cheshire.core :as json] '[babashka.fs :as fs] '[clojure.string :as str] '[babashka.process :as p])
(let [args (vec *command-line-args*)
      arg (fn [k] (get args (inc (.indexOf args k))))
      sid (when (some #{\"--resume\"} args) (arg \"--resume\"))
      cwd (str (fs/cwd))
      proj (str (System/getenv \"CLAUDE_CONFIG_DIR\") \"/projects/\" (str/replace cwd #\"[/.]\" \"-\"))
      resumed (str proj \"/\" sid \".jsonl\")
      in (json/parse-string (first (line-seq (java.io.BufferedReader. *in*))) true)]
  (spit (str (fs/parent *file*) \"/marker.jsonl\")
        (str (json/generate-string {:cwd cwd :kept (when (fs/exists? resumed) (count (str/split-lines (slurp resumed))))
                                    :renamed (when (fs/exists? resumed) (not (str/includes? (slurp resumed) \"orig\")))
                                    :worktree (str/trim (:out (p/shell {:out :string :err :string :continue true} \"git\" \"rev-parse\" \"--is-inside-work-tree\")))
                                    :resend (get-in in [:message :content 0 :tool_use_id])
                                    :system (arg \"--system-prompt\") :fork (boolean (some #{\"--fork-session\"} args))})
             \"\\n\")
        :append true)
  (fs/create-dirs proj)
  (spit (str proj \"/fork-\" sid \".jsonl\") \"{}\\n\")
  (doseq [e [{:type \"system\" :session_id (str \"fork-\" sid)}
             {:type \"assistant\" :message {:content [{:type \"tool_use\" :id \"x\" :input {:code \"(grep \\\"x\\\")\"}}]}}
             {:type \"result\" :total_cost_usd 0.01}]]
    (println (json/generate-string e))))")

(defn- jsonl [f ms] (spit f (str/join (for [m ms] (str (json/generate-string m) "\n")))))

(defn- call [id code] {:id id :type "function" :function {:name "clojure" :arguments (json/generate-string {:code code})}})

(deftest a-fork-resumes-the-prefix-in-the-original-directory-and-leaves-nothing-behind
  (let [tmp (str (fs/canonicalize (fs/create-temp-dir {:prefix "bbh-fork-test"})))
        repo (str tmp "/repo")
        cwd (str tmp "/workspaces/W1-1")
        claude-dir (str tmp "/claude")
        proj (str claude-dir "/projects/" (str/replace cwd #"[/.]" "-"))
        conv (str tmp "/conv")
        fake (str tmp "/fake/claude.clj")
        user (fn [uuid content] {:type "user" :uuid uuid :sessionId "orig" :cwd cwd :message {:role "user" :content content}})
        asst (fn [rid blocks] {:type "assistant" :requestId rid :sessionId "orig" :cwd cwd :effort "low"
                               :requestedModel "claude-opus-5-5" :message {:role "assistant" :content blocks}})]
    (fs/create-dirs repo) (fs/create-dirs proj) (fs/create-dirs conv) (fs/create-dirs (fs/parent fake)) (fs/create-dirs (fs/parent cwd))
    (p/shell {:dir repo :out :string :err :string} "bash" "-c" "git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init")
    (spit fake fake-claude)
    (jsonl (str proj "/orig.jsonl")
           [{:type "api-request-shape" :shape {:system [{:text "preamble"} {:text "SYS"}]
                                                :tools [{:name "mcp__bbh__clojure" :description "DESC"}]}}
            (user "u1" "go")
            (asst "r1" [{:type "tool_use" :id "t1" :input {:code "($ \"cat a.txt\")"}}])
            (user "u2" [{:type "tool_result" :tool_use_id "t1" :content "a"}])
            {:type "attachment" :sessionId "orig"}
            (asst "r2" [{:type "thinking" :thinking "hm"}])
            (asst "r2" [{:type "tool_use" :id "t2" :input {:code "($ \"grep -n x .\")"}}])
            (user "u3" [{:type "tool_result" :tool_use_id "t2" :content "x"}])])
    (jsonl (str conv "/messages.jsonl")
           [{:role "user" :content "go"}
            {:role "assistant" :content "" :tool_calls [(call "t1" "($ \"cat a.txt\")")]}
            {:role "tool" :tool_call_id "t1" :content "a"}
            {:role "assistant" :content "" :tool_calls [(call "t2" "($ \"grep -n x .\")")]}
            {:role "tool" :tool_call_id "t2" :content "x"}
            {:role "assistant" :content "Done."}])
    (spit (str conv "/agent.json") (json/generate-string {:project cwd}))
    (let [opts {:roots [conv] :arms {:recorded :recorded} :claude ["bb" fake] :repo repo :claude-dir claude-dir :per-trace 5}
          file (fork/run! (str tmp "/out") opts)
          results (map #(json/parse-string % true) (str/split-lines (slurp file)))
          marks (into {} (for [m (map #(json/parse-string % true) (str/split-lines (slurp (str tmp "/fake/marker.jsonl"))))]
                           [(:resend m) m]))]
      (is (= #{["t1" "shell" "read" "helper" "search"] ["t2" "shell" "search" "helper" "search"]}
             (set (map (juxt :id (comp :choice :recorded) (comp :class :recorded) :choice :class) results))))
      (is (= {:cwd cwd :kept 3 :renamed true :worktree "true" :resend "t1" :system "SYS" :fork true}
             (get marks "t1"))
          "t2's fork resumes the lines before t1's result, which it resends, in a git worktree at the original path")
      (is (= {:kept nil :fork false} (select-keys (get marks nil) [:kept :fork])) "t1's fork is a new session sent the prompt")
      (is (not (fs/exists? cwd)) "the worktree lived only in the namespace")
      (is (= ["orig.jsonl"] (mapv (comp str fs/file-name) (fs/list-dir proj))) "both session files are gone")
      (fork/run! (str tmp "/out") opts)
      (is (= 2 (count (str/split-lines (slurp file)))) "a rerun skips forks already recorded")
      (is (str/includes? (fork/report results) "| recorded | 2 | 2 | 2 | 100% (2/2) | $0.02 |"))
      (is (str/includes? (fork/report results) "| conv | t2 | shell search | helper search |") "a row per step"))))
