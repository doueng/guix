(ns bbh.init-test
  "Loads the installed init.clj in a real session, the same path a conversation takes."
  (:require [babashka.fs :as fs]
            [bbh.models :as models]
            [bbh.repl :as repl]
            [bbh.session :as session]
            [clojure.test :refer [deftest is]]))

(deftest init-loads-in-a-real-session
  (let [init (str (fs/absolutize "../../configs/bbh/init.clj"))
        s (session/new-session {:dir (str (fs/create-temp-dir {:prefix "bbh-init-test"}))
                                :init init
                                :cwd (str (fs/absolutize "../.."))
                                :model (models/default-model)
                                :make-engine models/engine
                                :emit (fn [_])})]
    (try
      (is (nil? (session/start-repl! s)) "init.clj should load in the child REPL")
      (let [conn (:repl @s)]
        (is (= "true" (:value (repl/eval! conn "(boolean (resolve 'wf-attest))" 30000))))
        (is (= "true" (:value (repl/eval! conn "(boolean (resolve 'jev-classify-task))" 30000))))
        (is (= "\"0\\n\"" (:value (repl/eval! conn "(:out (sh \"timeout 5 cat; echo $?\"))" 30000)))
            "a command that reads stdin should see EOF, not hang")
        (is (= "\"0\\n\"" (:value (repl/eval! conn "(:out @(p/process {:in :inherit :out :string} \"bash\" \"-c\" \"timeout 5 cat; echo $?\"))" 30000)))
            "the REPL's own stdin should be at EOF for children that inherit it"))
      (finally (session/close! s)))))
