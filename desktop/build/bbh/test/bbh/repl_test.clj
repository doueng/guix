(ns bbh.repl-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [bbh.repl :as repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- shim
  "Run the emacsclient shim with ARGS and EXTRA env against a pretend live
  daemon, so a broken shim reaches nothing. Returns {:exit :err}."
  [extra & args]
  (select-keys (apply p/shell {:continue true :out :string :err :string
                               :extra-env (merge {"BBH_EMACS_SERVER" "bbh-test-live" "EMACS_SOCKET_NAME" ""} extra)}
                      (str (fs/path repl/shims "emacsclient")) args)
               [:exit :err]))

(deftest a-worker-cannot-reach-the-live-emacs
  (doseq [args [["-s" "bbh-test-live" "--eval" "t"] ["--socket-name=bbh-test-live" "-e" "t"]
                ["-sbbh-test-live" "-e" "t"] ["--socket-name" "/run/user/1000/emacs/bbh-test-live" "-t"]]]
    (let [{:keys [exit err]} (apply shim {} args)]
      (is (= 126 exit) (pr-str args))
      (is (str/includes? err "emacs-throwaway") "it says what to use instead")))
  (is (= 126 (:exit (shim {"EMACS_SOCKET_NAME" "bbh-test-live"} "-e" "t"))) "named by the environment")
  (let [{:keys [exit err]} (shim {} "-s" "bbh-test-nosuch" "--timeout=2" "-e" "t")]
    (is (not= 126 exit) "another daemon goes to the real emacsclient")
    (is (not (str/includes? err "may not use"))))
  (is (= 0 (:exit (shim {} "--version")))))

(deftest a-workers-repl-finds-the-shims-first
  (let [path "/usr/bin:/bin"]
    (is (= (str repl/shims ":" path)
           (get (repl/repl-env {"PATH" path} {"BBH_ROLE" "worker"} "/p") "PATH")))
    (is (= path (get (repl/repl-env {"PATH" path} {"BBH_ROLE" "router"} "/p") "PATH")) "the Router's calls are its own")
    (is (= path (get (repl/repl-env {"PATH" path} {} "/p") "PATH")))))
