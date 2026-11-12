(ns bbh.repl-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.conversation :as conversation]
            [bbh.hermetic :as hermetic]
            [bbh.repl :as repl]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(def ^:private http-report
  "What babashka's nREPL server printed for each GET to its port (T163)."
  ["ERROR: nil"
   "clojure.lang.ExceptionInfo: nREPL message must be a map."
   "Wrong first byte: 71 (must be 100). {}"
   "\tat sci.lang.Var.invoke(lang.cljc:219)"
   "\tat sci.impl.analyzer$return_call$reify__5364.eval(analyzer.cljc:1773)"])

(deftest a-connection-that-does-not-speak-nrepl-is-one-log-line
  (is (= ["before" "nREPL: closed a connection that did not speak nREPL, such as an HTTP request" "after"]
         (repl/quiet-log (concat ["before"] http-report ["after"]))))
  (is (= ["before" "nREPL: closed a connection that did not speak nREPL, such as an HTTP request"
          "nREPL: closed a connection that did not speak nREPL, such as an HTTP request" "after"]
         (repl/quiet-log (concat ["before"] (butlast http-report)
                                 [(str (last http-report) "Exception in thread \"nREPL-session-1\" ERROR: nil")]
                                 (rest http-report) ["after"])))
      "two reports interleaved")
  (is (= ["ERROR: a real one" "java.lang.Exception: kept" "\tat x"]
         (repl/quiet-log ["ERROR: a real one" "java.lang.Exception: kept" "\tat x"]))
      "other errors keep their stack"))

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

(deftest an-http-request-to-the-repl-port-leaves-it-answering
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-repl"}))
        conn (repl/start! {:dir dir :cwd dir})]
    (try
      (dotimes [_ 2]
        (with-open [s (java.net.Socket. "127.0.0.1" (int (:port conn)))]
          (doto (.getOutputStream s) (.write (.getBytes "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")) (.flush))
          (.read (.getInputStream s))))
      (is (= "42" (:value (repl/eval! conn "(+ 40 2)" 10000))) "the server goes on")
      (let [log (fn [] (slurp (str (conversation/file dir :repl-log))))
            deadline (+ (System/currentTimeMillis) 5000)]
        (while (and (< (count (re-seq #"did not speak nREPL" (log))) 2) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 50))
        (is (= 2 (count (re-seq #"did not speak nREPL" (log)))))
        (is (not (str/includes? (log) "Wrong first byte")) "no stack trace"))
      (finally (repl/kill! conn) (fs/delete-tree dir)))))
