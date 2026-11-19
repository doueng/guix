(ns rexmux.bombadil
  "Bombadil types into `rexmux tty` on a throwaway Rex server and checks
  test/bombadil/rexmux.ts on every screen: one focused pane named by the
  status line, M-x selection, Escape leaving the minibuffer, prefix keys
  showing what follows, and Ctrl+b u reaching a blocked agent.

    bb test:bombadil [--spec tabs|startup] [--time 60] [--reproduce DIR] [--keep]

  --spec startup, test/bombadil/startup.ts: the shipped :startup tabs
  (shell, bbh, pi, jjui) appear on an empty server, stay, and each runs its
  program; tiny fingers labels what a pane shows, a label copies or pastes,
  and Escape leaves.

  The fixture session has a shell, a blocked agent (OSC 7501 state=blocked,
  app=pi) in its own tab and a working one, so attention navigation crosses
  tabs. Nothing touches your Rex: the server has its own HOME and socket."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rexmux.e2e :as e2e]
            [rexmux.testserver :as ts]))

(def ^:private here (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))
(def ^:private checkout (str (fs/canonicalize (fs/path here ".." ".." ".."))))

(defn- parse [args]
  (loop [[a v & more] args o {:time 60 :keep false :spec "tabs"}]
    (case a
      nil o
      "--time" (recur more (assoc o :time (parse-long v)))
      "--reproduce" (recur more (assoc o :reproduce (str (fs/absolutize v))))
      "--spec" (recur more (assoc o :spec v))
      "--keep" (recur (cons v more) (assoc o :keep true))
      (throw (ex-info (str "bb test:bombadil: unknown argument " a) {})))))

(defn- bombadil-bin []
  (or (some-> (fs/which "bombadil") str)
      (let [{:keys [exit out err]} (p/sh {:dir checkout} "guix" "build" "-L" "modules" "-e" "(@ (engstrand packages bombadil) bombadil)")]
        (when-not (zero? exit) (throw (ex-info (str "building bombadil failed: " err) {})))
        (str (fs/path (last (str/split-lines (str/trim out))) "bin" "bombadil")))))

(defn- rexmux-bin
  "REXMUX_BIN, else target/release/rexmux, built first when missing."
  []
  (or (System/getenv "REXMUX_BIN")
      (let [bin (str (fs/path here "target" "release" "rexmux"))]
        (when-not (fs/exists? bin)
          (let [{:keys [exit]} @(p/process {:dir here :inherit true} "./build.bb" "build" "--release")]
            (when-not (zero? exit) (throw (ex-info "building rexmux failed" {})))))
        bin)))

(defn- osc [state app]
  (str "printf '\\033]7501;state=" state ":app=" app "\\033\\\\'; exec sleep 100000"))

(defn- fixture! [s]
  (ts/rex! s "new" "work" "--keep-open" "--shell" "none" "--" "sh")
  (let [sid (:out (ts/rex! s "api" "call" "-s" "work" "session.view"))]
    (doseq [[label state app] [["blocked" "blocked" "pi"] ["busy" "working" "claude"]]]
      (ts/rex! s "api" "call" "-s" "work" "session.new_window"
               (str "{\"label\":\"" label "\",\"focus\":false,\"layout\":{\"block\":{\"flavor\":\"com.superlogical.terminal.shell\","
                    "\"options\":{\"command\":[\"sh\",\"-c\"," (pr-str (osc state app)) "]}}}}")))
    sid))

(defn- tabs-setup
  "The rexmux.ts fixture: a session named work with a shell, a blocked agent
  and a working one; rexmux shows it."
  [s config]
  (fixture! s)
  (spit config (pr-str {:servers [{:label "test" :address (:address s)}]}))
  {"REXMUX_SESSION" "work"})

(defn- startup-setup
  "The startup.ts fixture: an empty server and the shipped config, so rexmux
  makes its :startup session; stubs stand in for emacs-bbh, pi and jjui and
  print a URL and a path for tiny fingers, as does the shell tab's fish."
  [s config]
  (e2e/config! s config)
  (spit (str (fs/path (:root s) "config" "fish" "config.fish"))
        "echo 'see https://example.com/shell and /tmp/stub-shell'\n" :append true)
  {})

(def ^:private specs
  {"tabs" {:file "rexmux.ts" :setup tabs-setup}
   "startup" {:file "startup.ts" :setup startup-setup}})

(defn -main [& args]
  (let [{:keys [time reproduce keep spec]} (parse args)
        {:keys [file setup]} (or (specs spec) (throw (ex-info (str "bb test:bombadil: no spec " spec) {})))
        root (str (fs/create-temp-dir {:prefix "rexmux-bombadil-"}))
        s (ts/start! {:root (str root "/srv") :path-prepend (e2e/stubs! (str root "/stubs"))})
        trace (str (fs/path root "trace"))
        log (str (fs/path root "rexmux.log"))
        config (str (fs/path root "rexmux.edn"))]
    (try
      (let [env (merge (ts/env s) (setup s config)
                       {"REXMUX_CONFIG" config "REXMUX_SIDECAR_DIR" here "REXMUX_BB" (str (fs/which "bb"))
                        ;; Copies and opened URLs go to files, never your clipboard or browser.
                        "REXMUX_CLIPBOARD_FILE" (str root "/clipboard") "REXMUX_OPEN_FILE" (str root "/opened")})
            cmd (concat [(bombadil-bin) "terminal" "test"
                         "--specification" (str (fs/path here "test" "bombadil" file))
                         "--time-limit" (str time "s") "--exit-on-violation"
                         "--columns" "110" "--rows" "30" "--quiescence-timeout-ms" "150"
                         "--output-path" trace "--output-path-overwrite"]
                        (when reproduce ["--reproduce" reproduce])
                        ["--" "sh" "-c" (str "exec \"$0\" tty 2>>" log) (rexmux-bin)])
            {:keys [exit out err]} @(apply p/process {:env env :out :string :err :string :in nil} cmd)]
        (println (str/join "\n" (take-last 60 (remove #(str/includes? % "\u001b[") (str/split-lines (str out "\n" err))))))
        (println (format "bombadil %s: exit %d; trace %s; rexmux log %s" spec exit trace log))
        (when (and (zero? exit) (not keep)) (ts/stop! s) (fs/delete-tree root))
        (System/exit exit))
      (catch Throwable e
        (ts/stop! s)
        (throw e)))))
