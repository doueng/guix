(ns bbh.hermetic
  "Run tests under the caller's environment minus the variables that tie a
  process to a bbh session, a super-agent role or a workflow run, so the suite
  behaves the same in a clean shell and inside a Worker."
  (:require [bbh.sysenv :as sysenv]))

(defn- session-var? [k]
  (and (re-find #"^(BBH_|WF_|PI_CODING_AGENT_)" k)
       ;; Locates a host library, not a session.
       (not= k "BBH_SQLITE_LIB")))

(defn env []
  (into {} (remove (comp session-var? key)) (System/getenv)))

(defn fixture
  "A :once fixture. with-redefs, not binding, so threads the code under test
  starts see it too; child REPLs inherit it through `(sysenv/getenv)`."
  [f]
  (let [e (env)]
    (with-redefs [sysenv/getenv (fn ([] e) ([k] (get e k)))]
      (f))))
