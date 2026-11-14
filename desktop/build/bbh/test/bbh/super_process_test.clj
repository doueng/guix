(ns bbh.super-process-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.super.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- wait-for [f]
  (loop [n 50] (or (f) (when (pos? n) (Thread/sleep 100) (recur (dec n))))))

(deftest stop-job-kills-what-the-job-moved-to-another-group
  ;; T179: a test script's Chrome ran in a process group of its own inside the
  ;; job's session, so killing only the job's group left it running.
  (let [dir (fs/create-temp-dir {:prefix "bbh-stop-job"})
        child (str (fs/path dir "child"))
        leader (p/process {:out :discard :err :discard}
                          "setsid" "bash" "-c" (str "set -m; sleep 300 & echo $! > " child "; wait"))]
    (try
      (let [cpid (wait-for #(some-> (process/read-text child) not-empty parse-long))
            ;; setsid execs in place, so the process started is the job's leader.
            lpid (.pid (:proc leader))
            job (process/identity-of lpid)
            cjob (process/identity-of cpid)]
        (is (some? cjob))
        (is (not= (str lpid) (str/trim (:out (p/shell {:out :string} "ps" "-o" "pgid=" "-p" (str cpid)))))
            "the child left the job's process group")
        (is (true? (process/stop-job! job)))
        (is (wait-for #(nil? (process/identity-of cpid))) "the child in its own group is gone too")
        (is (wait-for #(nil? (process/identity-of lpid)))))
      (finally
        (p/destroy-tree leader)
        (when-let [c (some-> (process/read-text child) not-empty)]
          (p/shell {:continue true :out :discard :err :discard} "kill" "-KILL" c))
        (fs/delete-tree dir)))))
