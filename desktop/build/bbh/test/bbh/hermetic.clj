(ns bbh.hermetic
  "Run tests under the caller's environment minus the variables that tie a
  process to a bbh session, a super-agent role or a workflow run, so the suite
  behaves the same in a clean shell and inside a Worker. HOME and the XDG dirs
  point at a temp root, so no test writes the person's bbh state, conversations,
  super.db or config."
  (:require [babashka.fs :as fs]
            [bbh.sysenv :as sysenv]))

(def root-var
  "Names the temp root the test runner made for this suite, so every worker shares it."
  "BB_TEST_HOME")

(defn- session-var? [k]
  (and (re-find #"^(BBH_|WF_|PI_CODING_AGENT_)" k)
       ;; Locates a host library, not a session.
       (not= k "BBH_SQLITE_LIB")))

(def ^:private own-root (delay (str (fs/create-temp-dir {:prefix "bbh-test-home"}))))

(defn root
  "The suite's temp home: the runner's, or one made for this process."
  []
  (or (System/getenv root-var) @own-root))

(defn dirs
  "HOME and XDG dirs under ROOT."
  [root]
  {"HOME" root
   "XDG_STATE_HOME" (str (fs/path root ".local" "state"))
   "XDG_DATA_HOME" (str (fs/path root ".local" "share"))
   "XDG_CONFIG_HOME" (str (fs/path root ".config"))})

(defn env []
  (into (into {} (remove (comp session-var? key)) (System/getenv))
        (dirs (root))))

(defn fixture
  "A :once fixture. with-redefs, not binding, so threads the code under test
  starts see it too; child REPLs inherit it through `(sysenv/getenv)`."
  [f]
  (let [e (env)]
    (with-redefs [sysenv/getenv (fn ([] e) ([k] (get e k)))]
      (f))))
