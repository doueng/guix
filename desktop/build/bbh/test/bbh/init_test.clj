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
        (is (= "true" (:value (repl/eval! conn "(boolean (resolve 'inbox))" 30000))))
        (is (= "\"0\\n\"" (:value (repl/eval! conn "(:out (sh \"timeout 5 cat; echo $?\"))" 30000)))
            "a command that reads stdin should see EOF, not hang")
        (is (= "\"0\\n\"" (:value (repl/eval! conn "(:out @(p/process {:in :inherit :out :string} \"bash\" \"-c\" \"timeout 5 cat; echo $?\"))" 30000)))
            "the REPL's own stdin should be at EOF for children that inherit it"))
      (finally (session/close! s)))))

(deftest jobs-survive-a-repl-restart
  (let [init (str (fs/absolutize "../../configs/bbh/init.clj"))
        s (session/new-session {:dir (str (fs/create-temp-dir {:prefix "bbh-job-test"}))
                                :init init
                                :cwd (str (fs/absolutize "../.."))
                                :model (models/default-model)
                                :make-engine models/engine
                                :emit (fn [_])})]
    (try
      (is (nil? (session/start-repl! s)))
      (let [id (:value (repl/eval! (:repl @s) "(job \"sleep 3; echo done; exit 7\")" 30000))]
        (is (string? id))
        (is (= "false" (:value (repl/eval! (:repl @s) (str "(:done? (job-status " id "))") 30000)))
            "job should return while the command still runs")
        (is (nil? (session/restart-repl! s)))
        (is (= "[true 7 \"done\"]"
               (:value (repl/eval! (:repl @s) (str "((juxt :done? :exit :tail) (job-wait " id " :ms 20000))") 30000))))
        (is (= "1" (:value (repl/eval! (:repl @s) "(count (jobs))" 30000)))))
      (is (= "true" (:value (repl/eval! (:repl @s) "(let [t (:system (bbh.env/manifest))] (boolean (and (str/includes? t \"job-wait\") (str/includes? t \"120 s\"))))" 30000)))
          "the prompt should name the deadline and the job helpers")
      (finally (session/close! s)))))
