(ns bbh.env-test
  (:require [babashka.fs :as fs]
            [bbh.repl :as repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

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
      (is (= {:value [{:exit 0 :out "ready" :err ""} 7 "ready" ["sample.txt"] ["sample.txt"]]}
             (repl/call conn
                        "[(sh \"printf ready\") (:exit (sh \"exit 7\"))
                          (:out (p/shell {:out :string :err :string :continue true} \"printf\" \"%s\" \"ready\"))
                          (mapv fs/file-name (fs/list-dir \".\"))
                          (mapv fs/file-name (fs/glob \".\" \"*.txt\"))]"
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
        (is (= "    2  second\n[1 of 2 lines]\n" (:out printed))))
      (is (= {:value [:ok "first\nchanged\n"]}
             (repl/call conn "[(edit! \"sample.txt\" \"second\" \"changed\") (slurp \"sample.txt\")]" 10000)))
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
      (let [text (:value (repl/call conn "(#'bbh.env/project-instructions)" 10000))]
        (is (< (str/index-of text "root rules") (str/index-of text "sub rules")))
        (is (str/includes? text (str "## " (fs/canonicalize (fs/path sub "CLAUDE.md")))))
        (is (not (str/includes? text "outside the repo"))))
      (finally (repl/kill! conn)))))
