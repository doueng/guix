(ns bbh.init-test
  "Loads the installed init.clj in a real session, the same path a conversation takes."
  (:require [babashka.fs :as fs]
            [bbh.models :as models]
            [bbh.repl :as repl]
            [bbh.session :as session]
            [clojure.string :as str]
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
        (is (= "false" (:value (repl/eval! conn "(boolean (resolve 'wf-attest))" 30000))) "runs go through the wf command, under sh's budget")
        (is (= "true" (:value (repl/eval! conn "(every? resolve '[shot look delegate])" 30000))))
        (is (= "true" (:value (repl/eval! conn "(let [t (:system (bbh.env/manifest))] (every? #(str/includes? t %) [\"(shot url)\" \"(look question\" \"(delegate brief)\"]))" 30000))))
        (is (= "true" (:value (repl/eval! conn "(boolean (resolve 'jev-classify-task))" 30000))))
        (is (= "true" (:value (repl/eval! conn "(boolean (resolve 'inbox))" 30000))))
        (is (= "\"0\\n\"" (:value (repl/eval! conn "(:out (sh \"timeout 5 cat; echo $?\"))" 30000)))
            "a command that reads stdin should see EOF, not hang")
        (is (= "\"0\\n\"" (:value (repl/eval! conn "(:out @(p/process {:in :inherit :out :string} \"bash\" \"-c\" \"timeout 5 cat; echo $?\"))" 30000)))
            "the REPL's own stdin should be at EOF for children that inherit it"))
      (finally (session/close! s)))))

(defn- session-with-deadline [ms]
  (let [s (session/new-session {:dir (str (fs/create-temp-dir {:prefix "bbh-budget-test"}))
                                :init (str (fs/absolutize "../../configs/bbh/init.clj"))
                                :cwd (str (fs/absolutize "../.."))
                                :model (models/default-model)
                                :make-engine models/engine
                                :emit (fn [_])})]
    (session/start-repl! s)
    (swap! s assoc :manifest (assoc repl/defaults :deadline-ms ms))
    s))

(deftest long-helpers-return-before-the-deadline-and-the-repl-survives
  (let [s (session-with-deadline 13000)
        run #(session/run-code! s (str (random-uuid)) %)]
    (try
      (run "(def kept 1)")
      (let [start (System/currentTimeMillis)
            slow (run "(def r (sh \"echo early; sleep 7; echo late; exit 4\")) (select-keys r [:exit :timeout :out])")
            waited (run "(def j (job \"sleep 30\")) (:done? (job-wait j :ms 60000))")]
        (is (str/includes? slow ":timeout true") slow)
        (is (str/includes? slow ":exit nil") slow)
        (is (str/includes? slow "early") "sh returns the output so far")
        (is (str/ends-with? (str/trim waited) "=> false") waited)
        (is (< (- (System/currentTimeMillis) start) 20000) "each helper stopped at its budget"))
      (is (str/ends-with? (run "kept") "=> 1") "the REPL was not killed")
      (Thread/sleep 2000)
      (let [finished (run "((juxt :done? :exit :tail) (job-wait (:job r) :ms 2000))")]
        (is (str/includes? finished "[true 4 \"early\\nlate\"]") finished))
      (finally (session/close! s)))))

(deftest sh-keeps-exit-and-streams-separate
  (let [s (session-with-deadline 60000)]
    (try
      (is (str/includes? (session/run-code! s "a" "(sh \"echo o; echo e >&2; exit 3\")")
                         "{:out \"o\\n\", :err \"e\\n\", :exit 3}"))
      (let [start (System/currentTimeMillis)
            broken (session/run-code! s "c" "(sh \"echo 'oops\")")]
        (is (not (str/includes? broken ":timeout")) broken)
        (is (str/includes? broken ":exit 2") broken)
        (is (str/includes? broken "unexpected EOF") "bash's error reaches :err")
        (is (< (- (System/currentTimeMillis) start) 2000) "a syntax error returns at once"))
      (is (str/includes? (session/run-code! s "d" "(sh \"echo hi # note\")") "{:out \"hi\\n\""))
      (is (str/ends-with? (session/run-code! s "b" "(count (jobs))") "=> 0") "a finished sh leaves no job")
      (finally (session/close! s)))))

(deftest an-interrupt-stops-a-running-sh
  (let [s (session-with-deadline 60000)
        marker (str (fs/path (fs/create-temp-dir {:prefix "bbh-sh-kill"}) "ran"))
        call (future (session/run-code! s "a" (str "(sh \"sleep 4; touch " marker "\")")))]
    (try
      (Thread/sleep 1500)
      (session/interrupt! s)
      (deref call 10000 nil)
      (Thread/sleep 4000)
      (is (not (fs/exists? marker)) "the detached command died with the interrupt")
      (is (empty? (fs/glob (:repl-dir @s) "jobs/*")) "an interrupted sh leaves no job files")
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
