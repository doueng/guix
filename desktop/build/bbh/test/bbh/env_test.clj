(ns bbh.env-test
  (:require [bbh.hermetic :as hermetic]
            [babashka.fs :as fs]
            [bbh.repl :as repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(deftest documented-helper-examples-work-in-the-real-repl
  (let [dir (fs/create-temp-dir {:prefix "bbh-env-test"})
        ws (fs/path dir "ws")
        init (str (fs/absolutize "../../configs/bbh/init.clj"))
        _ (fs/create-dirs ws)
        _ (spit (str (fs/path ws "sample.txt")) "first\nsecond\n")
        conn (repl/start! {:dir (str (fs/path dir "runtime")) :cwd (str ws)})]
    (try
      (let [loaded (repl/eval! conn (str "(load-file " (pr-str init) ")") 10000)]
        (is (= false (:error? loaded))))
      (is (= {:value [{:exit 0 :out "ready" :err ""} 7 "it's ready" ["sample.txt"] ["sample.txt"] (str (fs/canonicalize ws))]}
             (repl/call conn
                        "[(sh \"printf ready\") (:exit (sh \"exit 7\"))
                          (:out (sh \"printf\" \"%s\" \"it's ready\"))
                          (mapv fs/file-name (fs/list-dir \".\"))
                          (mapv fs/file-name (fs/glob \".\" \"*.txt\"))
                          (str (fs/canonicalize (pwd)))]"
                        10000)))
      (is (= {:value [["nested/child.txt" "sample.txt"] ["nested/child.txt"]]}
             (repl/call conn
                        "(do (fs/create-dirs \"nested\") (spit \"nested/child.txt\" \"child\")
                             [(->> (fs/glob \".\" \"**\" {:hidden true})
                                   (filter fs/regular-file?) (map str) sort vec)
                              (mapv str (fs/glob \".\" \"**/*\"))])"
                        10000)))
      (let [printed (repl/eval! conn "(read-lines \"sample.txt\" 2 1)" 10000)]
        (is (= "nil" (:value printed)))
        (is (= "(2 lines total, showing lines 2-2)\n\n2: second\n" (:out printed))))
      (is (= ["File not found: sample.tx. Did you mean `sample.txt`?" "Cannot read binary file: image.png"]
             (:value (repl/call conn "(vec (for [f [\"sample.tx\" \"image.png\"]]
                                           (try (spit \"image.png\" \"x\") (read-lines f) (catch Exception e (ex-message e)))))" 10000))))
      (is (= ["OLD not found in 'sample.txt'.\nEnsure the exact text matches including whitespace and line endings. Tried exact match, line-trimmed match, whitespace-normalized match, and indentation-flexible match."
              "OLD matched 2 times in sample.txt:\n  Line 1: first\n  Line 2: second\n\nPass :all true to replace every occurrence, or include more surrounding context in OLD."]
             (:value (repl/call conn "(do (spit \"sample.txt\" \"first\\nsecond\\n\")
                                          (vec (for [old [\"third\" \"s\"]]
                                                 (try (edit! \"sample.txt\" old \"x\") (catch Exception e (ex-message e))))))" 10000))))
      (is (= {:value ["Applied edit (matched via line-trimmed fallback — exact text not found; whitespace/indent tolerated) (+1 lines)\n--- a/sample.txt\n+++ b/sample.txt\n@@ -1,2 +1,3 @@\n first\n-second\n+changed\n+more\n"
                      "\ufefffirst\r\nchanged\r\nmore\r\n"]}
             (repl/call conn "(do (spit \"sample.txt\" \"\\ufefffirst\\r\\nsecond\\r\\n\")
                                 [(edit! \"sample.txt\" \"  second\" \"changed\\nmore\") (slurp \"sample.txt\")])" 10000)))
      (is (= {:value ["Applied edit (2 replacements)\n--- a/sample.txt\n+++ b/sample.txt\n@@ -1,2 +1,2 @@\n-a\n+b\n a\n" "b\nb\n"]}
             (repl/call conn "(do (spit \"sample.txt\" \"a\\na\\n\") [(edit! \"sample.txt\" \"a\" \"b\" :all true) (slurp \"sample.txt\")])" 10000)))
      (is (= {:value ["x\ny\nc\n" "Edit 2 of 2 failed, so sample.txt is unchanged. OLD not found" "x\ny\nc\n"]}
             (repl/call conn "(do (spit \"sample.txt\" \"a\\nb\\nc\\n\")
                                 (edit! \"sample.txt\" [[\"a\" \"x\"] [\"b\" \"y\"]])
                                 [(slurp \"sample.txt\")
                                  (try (edit! \"sample.txt\" [[\"x\" \"z\"] [\"nope\" \"w\"]]) (catch Exception e (subs (ex-message e) 0 61)))
                                  (slurp \"sample.txt\")])" 10000))
          "a multi-edit writes every pair or none")
      (let [printed (:out (repl/eval! conn "(do (spit \"long.txt\" (apply str (repeat 300 (str (apply str (repeat 99 \"x\")) \"\\n\")))) (read-lines \"long.txt\"))" 10000))]
        (is (< (count printed) 7200) "read-lines stays under the output cap")
        (is (str/includes? printed "showing lines 1-66)") printed)
        (is (str/ends-with? (str/trim printed) "next: (read-lines \"long.txt\" 67)") printed))
      (is (= {:value [true "0\n" "look needs existing png paths; not found: nil, \"missing.png\""]}
             (repl/call conn "[(:done? (job-wait (job \"true\") {:ms 10000 :lines 1}))
                               (with-out-str ($ \"echo $?\"))
                               (try (look \"q\" nil \"missing.png\") (catch Exception e (ex-message e)))]" 10000)))
      (is (= "oops\nstderr:\nbad\n[exit 3]\n" (:value (repl/call conn "(with-out-str ($ \"echo oops; echo bad >&2; exit 3\"))" 10000))))
      (finally (repl/kill! conn)))))

(deftest instructions-load-from-the-repository-root-down-to-the-working-directory
  (let [dir (fs/create-temp-dir {:prefix "bbh-env-test"})
        repo (fs/path dir "repo")
        sub (fs/path repo "a" "b")
        init (str (fs/absolutize "../../configs/bbh/init.clj"))
        _ (fs/create-dirs (fs/path repo ".jj"))
        _ (fs/create-dirs sub)
        _ (spit (str (fs/path dir "AGENTS.md")) "outside the repo")
        _ (spit (str (fs/path repo "AGENTS.md")) "root rules")
        _ (spit (str (fs/path sub "CLAUDE.md")) "sub rules")
        conn (repl/start! {:dir (str (fs/path dir "runtime")) :cwd (str sub)})]
    (try
      (repl/eval! conn (str "(load-file " (pr-str init) ")") 10000)
      (let [text (:value (repl/call conn "(:system (bbh.env/manifest))" 10000))]
        (is (< (str/index-of text "root rules") (str/index-of text "sub rules")))
        (is (str/includes? text (str "## " (fs/canonicalize (fs/path sub "CLAUDE.md")))))
        (is (not (str/includes? text "outside the repo"))))
      (finally (repl/kill! conn)))))
