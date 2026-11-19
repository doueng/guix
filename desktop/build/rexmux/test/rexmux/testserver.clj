(ns rexmux.testserver
  "A throwaway Rex server for tests: its own HOME, state and socket under a
  temp root, started with --restore=false, so it never touches your Rex at
  /run/user/UID/rex. The rex binary is REXMUX_REX, else the pinned Guix
  package (modules/engstrand/packages/rex.scm)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def repo (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".." ".." ".." ".."))))

(defn rex-bin []
  (or (System/getenv "REXMUX_REX")
      (let [{:keys [exit out err]} (p/sh {:dir repo} "guix" "build" "-L" "modules" "-e" "(@ (engstrand packages rex) rex)")]
        (when-not (zero? exit) (throw (ex-info (str "building rex failed: " err) {})))
        (str (last (str/split-lines (str/trim out))) "/bin/rex"))))

(defn env
  "The environment of server S and of clients under test. S's :path-prepend,
  a directory, goes first on PATH, so stubs stand in for real programs."
  [{:keys [root address bin path-prepend]}]
  {"HOME" root "XDG_RUNTIME_DIR" (str root "/run") "XDG_CONFIG_HOME" (str root "/config")
   "XDG_STATE_HOME" (str root "/state") "XDG_DATA_HOME" (str root "/data")
   "PATH" (str (when path-prepend (str path-prepend ":")) (System/getenv "PATH"))
   "TERM" "xterm-256color" "REX_SERVER" address "REXMUX_REX" bin})

(defn rex!
  "Run the rex CLI against server S; returns {:exit :out :err}."
  [s & args]
  (apply p/sh {:env (env s)} (:bin s) "--autostart=false" args))

(defn- wait-up [s]
  (loop [n 0]
    (cond (zero? (:exit (rex! s "server" "status"))) s
          (> n 100) (throw (ex-info "rex server did not start" {:log (slurp (str (:root s) "/server.log"))}))
          :else (do (Thread/sleep 100) (recur (inc n))))))

(defn start!
  "Start a server; returns {:root :address :bin :proc} once it answers.
  :root reuses a directory, :restore true keeps sessions across restarts
  (as the real server does), :path-prepend puts a directory first on PATH."
  ([] (start! {}))
  ([{:keys [root restore path-prepend]}]
   (let [root (str (or root (fs/create-temp-dir {:prefix "rexmux-test-"})))
         bin (rex-bin)
         s (cond-> {:root root :address (str "unix://" root "/run/rex.sock") :bin bin}
             path-prepend (assoc :path-prepend path-prepend))
         log (str root "/server.log")]
     (fs/create-dirs (str root "/run"))
     (fs/create-dirs (str root "/config/rex"))
     (spit (str root "/config/rex/init.lua") "")
     (wait-up (assoc s :proc (p/process {:env (env s) :out log :err log}
                                        bin "server" "run" "--listen" (:address s) "--state-dir" (str root "/state/srv")
                                        (str "--restore=" (boolean restore)) "--config" (str root "/config/rex/init.lua")))))))

(defn kill!
  "Kill server S and every program it runs, as a reboot would, keeping its
  state directory so a new start! with :restore brings the sessions back."
  [{:keys [proc]}]
  (when proc
    ;; The server dies first and uncleanly, as at a power cut, so it saves
    ;; nothing more; its programs follow. Killing them first would close
    ;; their blocks and save a session with no tabs.
    (let [handle (.toHandle ^Process (:proc proc))
          kids (vec (.toArray (.descendants handle)))]
      (.destroyForcibly handle)
      (deref proc 5000 nil)
      (doseq [^java.lang.ProcessHandle k kids] (.destroyForcibly k)))))

(defn stop! [{:keys [proc root] :as s}]
  (when (fs/exists? root)
    (rex! s "server" "stop")
    (when proc (p/destroy-tree proc) (deref proc 5000 nil))
    (fs/delete-tree root)))
