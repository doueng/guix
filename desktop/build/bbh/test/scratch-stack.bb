#!/usr/bin/env bb
;; A scratch bbh super stack on this checkout's code, for checks that need a
;; live controller, Emacs daemon and bbh acp sessions without touching yours.
;;
;;   bb test/scratch-stack.bb up      start it, empty
;;   bb test/scratch-stack.bb env              print the environment to use it
;;   bb test/scratch-stack.bb down             stop it and leave no process
;;
;; Everything lives under $BBH_STACK (default /tmp/bbh-stack): data, state,
;; a bin/ with bbh and wf wrappers on this checkout, and the logs. The Emacs
;; daemon is named after the directory and loads this checkout's agent-shell.el.
;; It never takes a copy of your super.db: its controller would reconcile your
;; live Workers and reopen their sessions in your workspaces.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def checkout (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".." ".." ".."))))
(def root (str (fs/absolutize (or (System/getenv "BBH_STACK") "/tmp/bbh-stack"))))
(def server (str "stack-" (fs/file-name root)))

(def env
  {"XDG_DATA_HOME" (str root "/data") "XDG_STATE_HOME" (str root "/state")
   "BBH_EMACS_SERVER" server "BBH_STACK" root
   "PATH" (str root "/bin:" (System/getenv "PATH"))})

(defn- wrapper! [name ns]
  (let [f (fs/path root "bin" name)]
    (spit (str f) (str "#!/usr/bin/env bb\n"
                       "(require '[babashka.process :as p])\n"
                       "(apply p/exec {:extra-env " (pr-str (dissoc env "PATH")) "} \"bb\" \"--config\" "
                       (pr-str (str checkout "/desktop/build/bbh/bb.edn")) " \"-m\" " (pr-str ns) " *command-line-args*)\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")))

(def ^:private worker-env
  "What a Worker running this script carries and the stack must not inherit."
  ["BBH_SUPER_TOKEN" "WF_RUN" "BBH_ROLE"])

(defn- spawn! [name cmd]
  (let [proc (apply p/process {:env (merge (apply dissoc (into {} (System/getenv)) worker-env) env) :out (str root "/" name ".log") :err :out} cmd)]
    (spit (str root "/" name ".pid") (str (.pid (:proc proc))))
    proc))

(defn- wait-for [what pred]
  (loop [n 0]
    (cond (pred) true
          (> n 300) (throw (ex-info (str "scratch stack: " what " did not come up; see " root) {}))
          :else (do (Thread/sleep 100) (recur (inc n))))))

(defn- emacs-eval [form]
  (p/shell {:env (merge (apply dissoc (into {} (System/getenv)) worker-env) env) :out :string :err :string :continue true} "emacsclient" "-s" server "--eval" form))

(defn up []
  (fs/create-dirs (fs/path root "bin"))
  (fs/create-dirs (fs/path root "data" "bbh" "super"))
  (wrapper! "bbh" "bbh.main")
  (wrapper! "wf" "bbh.workflow")
  (spawn! "emacs" [(str (fs/expand-home "~/.local/bin/emacs")) (str "--fg-daemon=" server)])
  (wait-for "Emacs" #(zero? (:exit (emacs-eval "t"))))
  ;; Doom's env file overrides the daemon's environment at startup, so set it
  ;; again, then refuse to go on unless sessions would run on this stack only.
  (emacs-eval (str "(progn "
                   (str/join " " (for [[k v] env] (format "(setenv %s %s)" (pr-str k) (pr-str v))))
                   (str/join " " (for [k worker-env] (format "(setenv %s nil)" (pr-str k))))
                   (format " (setq exec-path (cons %s exec-path)))" (pr-str (str root "/bin")))))
  (let [seen (:out (emacs-eval "(list (executable-find \"bbh\") (getenv \"XDG_DATA_HOME\"))"))
        want (pr-str (list (str root "/bin/bbh") (str root "/data")))]
    (when-not (= want (str/trim seen))
      (emacs-eval "(kill-emacs)")
      (throw (ex-info (str "scratch stack: the daemon would reach your live bbh: " seen) {}))))
  (let [r (emacs-eval (format "(load %s nil t)" (pr-str (str checkout "/desktop/configs/doom/agent-shell.el"))))]
    (when-not (zero? (:exit r)) (throw (ex-info (str "scratch stack: loading agent-shell.el: " (:err r)) {}))))
  (spawn! "controller" [(str root "/bin/bbh") "super" "serve"])
  (wait-for "the controller" #(fs/exists? (fs/path root "data" "bbh" "super" "port")))
  (println "scratch stack up in" root "on" checkout))

(defn down []
  (emacs-eval "(kill-emacs)")
  (doseq [f (fs/glob root "*.pid")]
    (let [pid (parse-long (str/trim (slurp (str f))))]
      (some-> (java.lang.ProcessHandle/of pid) (.orElse nil)
              (#(do (doseq [d (iterator-seq (.iterator (.descendants %)))] (.destroy d)) (.destroy %))))
      (fs/delete f)))
  (println "scratch stack down;" (count (str/split-lines (:out (p/shell {:out :string :continue true} "pgrep" "-f" root)))) "pgrep lines (expect 1, pgrep's own shell)"))

(case (first *command-line-args*)
  "up" (up)
  "down" (down)
  "env" (do (doseq [[k v] env] (println (str k "=" v))) (doseq [k worker-env] (println (str k "="))))
  (do (println "usage: scratch-stack.bb up | env | down") (System/exit 2)))
