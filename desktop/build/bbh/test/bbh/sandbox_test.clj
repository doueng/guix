(ns bbh.sandbox-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [bbh.sandbox :as sandbox]
            [bbh.super.client :as client]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- git [& args] (apply p/shell {:out :string :err :string} "git" "-c" "user.email=t@t" "-c" "user.name=t" args))

(deftest a-replayed-session-cannot-reach-outside-its-overlay
  (let [home (sandbox/home)
        real (str (fs/create-temp-dir {:dir (str (fs/create-dirs (str home "/.cache"))) :prefix "bbh-sandbox-test"}))
        remote (str real "/remote.git")
        clone (str real "/clone")
        scratch (str (fs/create-temp-dir {:dir "/var/tmp" :prefix "bbh-sandbox-scratch"})) ; the test tmpdir lies under home
        up (str scratch "/up")
        steps (str home "/.local/share/bbh-replay/steps.edn")
        uid (str/trim (:out (p/shell {:out :string} "id" "-u")))
        abs-git (str (fs/which "git"))
        port-before (some-> (fs/path (client/super-dir) "port") str (#(when (fs/exists? %) (slurp %))))
        code (str "(let [h (System/getProperty \"user.home\")]
  (spit (str h \"/.local/state/bbh-replay-probe\") \"x\")
  (spit (str h \"/.local/share/bbh-replay/run/probe.edn\")
        (pr-str {:sudo (:exit (sh \"sudo true\"))
                 :emacs (:exit (sh \"emacsclient -e 1\"))
                 :shim-push (:exit (sh \"git -C " clone " push -q origin HEAD:refs/heads/viashim\"))
                 :abs-push (:exit (sh \"" abs-git " -C " clone " push -q origin HEAD:refs/heads/probe\"))
                 :run-user (count (fs/list-dir \"/run/user/" uid "\"))
                 :tmp (mapv str (fs/list-dir \"/tmp\"))
                 :done (super-done!)})))")]
    (git "init" "-q" "--bare" remote)
    (git "clone" "-q" remote clone)
    (git "-C" clone "commit" "-q" "--allow-empty" "-m" "one")
    (git "-C" clone "push" "-q" "origin" "HEAD:refs/heads/main")
    (fs/create-dirs (str up "/.local/share/bbh-replay"))
    (spit (str/replace steps home up) (pr-str [{:code code} "Done."]))
    (try
      (let [r (sandbox/run! scratch {:init (str (fs/absolutize "../../configs/bbh/init.clj"))
                                     :cwd (str (fs/absolutize "../..")) :model (str "script/" steps) :prompt "go"})
            probe (edn/read-string (slurp (str (:out r) "/probe.edn")))]
        (is (= :end-turn (:end (:result r))) (:err r))
        (is (= {:sudo 126 :emacs 126 :shim-push 126 :abs-push 0 :run-user 0 :tmp []} (dissoc probe :done))
            "blocked by the shim, or contained: the absolute-path push reached only the overlay's copy of the remote")
        (is (= ["sudo" "emacsclient" "git"] (mapv :cmd (:blocked r))))
        (is (= ["finish-worker!"] (map :command (:super r))) "super-done! reached the stub")
        (is (some #{".local/state/bbh-replay-probe"} (:changed r)))
        (is (not (fs/exists? (str home "/.local/state/bbh-replay-probe"))) "the write stayed in the overlay")
        (is (= "main\n" (:out (git "-C" remote "for-each-ref" "--format=%(refname:short)"))) "the real remote got no push")
        (is (= port-before (some-> (fs/path (client/super-dir) "port") str (#(when (fs/exists? %) (slurp %)))))
            "the real controller's port file is untouched"))
      (finally (sandbox/cleanup! scratch) (fs/delete-tree real)))))
