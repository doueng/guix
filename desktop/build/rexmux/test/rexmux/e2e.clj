(ns rexmux.e2e
  "rexmux's window end to end on the hidden test display (test-display, a
  headless sway: nothing reaches your screen) against a throwaway Rex server
  (rexmux.testserver: its own HOME and socket, never /run/user/UID/rex).

    bb test:e2e [--keep]

  It checks what Herdr gave and the parity list (docs/rex-parity.html)
  marks tested:
  - startup: a server with only a stray session gets the shipped config's
    :startup session, its tabs in order (shell, bbh, pi, jjui) running their
    programs (stubs named emacs-bbh, pi and jjui) in the configured shell;
  - tiny fingers: Ctrl+b f labels a URL and a path, a label copies (and a web
    URL opens), Shift and a label pastes into the pane;
  - mouse: a drag across a line copies it, and the wheel scrolls history
    (through rexmux tty's SGR mouse reports: the headless display has no pointer);
  - rename and resize: Ctrl+b , names the tab, Ctrl+b L moves a border;
  - persistence: after the server dies with its programs, as in a reboot, a
    new rexmux brings each startup tab's program back (pi with --continue).
  Copies and opened URLs go to files (REXMUX_CLIPBOARD_FILE, REXMUX_OPEN_FILE),
  and a screenshot of each step lands in the run's directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rexmux.testserver :as ts]))

(def ^:private here (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))
(def ^:private checkout (str (fs/canonicalize (fs/path here ".." ".." ".."))))
(def display "rexmux-e2e")

(def results (atom []))

(defn- check! [name ok & [detail]]
  (swap! results conj {:name name :ok (boolean ok) :detail detail})
  (println (if ok "PASS" "FAIL") name (if detail (str "- " detail) "")))

(defn- wait-for
  "Poll F every 200 ms for up to MS; its first truthy value, else nil."
  [ms f]
  (loop [t 0]
    (or (try (f) (catch Exception _ nil))
        (when (< t ms) (Thread/sleep 200) (recur (+ t 200))))))

(defn- td [& args]
  (apply p/sh "test-display" "--name" display args))

(defn- in-display [& argv]
  (apply td "exec" "--" argv))

(defn- keys!
  "Type KS with one wtype in the test display: strings are text, [:ctrl
  \"b\"] a chord, [:key \"Return\"] a named key. A new wtype's first key can be
  lost while the window takes its keymap, so a bare Shift, which rexmux
  ignores, goes first."
  [& ks]
  (apply in-display "wtype" "-k" "Shift_L" "-s" "100"
         (mapcat (fn [k]
                   (concat (cond (string? k) [k]
                                 (= :key (first k)) ["-k" (second k)]
                                 :else (let [[mod key] k] ["-M" (name mod) key "-m" (name mod)]))
                           ["-s" "120"]))
                 ks))
  (Thread/sleep 300))

(defn- shot! [dir name]
  (let [f (str (fs/path dir (str name ".png")))]
    (in-display "grim" f)
    f))

(defn- api [s method & [params]]
  (let [{:keys [exit out err]} (ts/rex! s "api" "call" method (json/generate-string (or params {})))]
    (when-not (zero? exit) (throw (ex-info (str method ": " err) {})))
    (json/parse-string out true)))

(defn- session [s label]
  (some #(when (= label (:label %)) %) (:sessions (api s "session.list"))))

(defn- view [s sid] (api s "session.view" {:session_id sid}))

(defn- foreground [s sid block]
  (get-in (api s "block.method" {:session_id sid :block_id block :method "process"}) [:foreground :name]))

(defn- capture [s sid block]
  (:out (ts/rex! s "capture" "-s" sid "-b" block "--trim")))

(defn stubs!
  "Stand-ins for the startup programs: each prints its name and arguments, then waits."
  [dir]
  (fs/create-dirs dir)
  (let [f (str (fs/path dir "nvim"))
        root (str (fs/parent dir))]
    (spit f (str "#!" (fs/which "bb") "\n(spit \"" root "/editor-args\" (pr-str *command-line-args*))\n"
                 "(spit \"" root "/editor-file\" (slurp (last *command-line-args*)))\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x"))
  (doseq [n ["emacs-bbh" "pi" "jjui"]]
    (let [f (str (fs/path dir n))]
      (spit f (str "#!" (fs/which "bb") "\n(println \"STUB " n "\" (clojure.string/join \" \" *command-line-args*))\n(println \"see https://example.com/" n " and /tmp/stub-" n "\")\n(Thread/sleep 100000000)\n"))
      (fs/set-posix-file-permissions f "rwxr-xr-x")))
  dir)

(defn config!
  "The shipped config, pointed at server S. Rex starts blocks with a login
  environment, which drops the stubs from PATH, so the test HOME's fish
  config puts them back first."
  [s file]
  (let [fish (str (fs/path (:root s) "config" "fish" "config.fish"))]
    (fs/create-dirs (fs/parent fish))
    (spit fish (str "set -gx PATH " (:path-prepend s) " $PATH\nset -gx EDITOR nvim\n"))
    (fs/create-dirs (fs/path (:root s) "projects" "demo")))
  (let [shipped (edn/read-string (slurp (str (fs/path checkout "desktop" "configs" "rexmux" "config.edn"))))]
    (spit file (pr-str (assoc shipped :servers [{:label "test" :address (:address s)}]
                              :startup (assoc (:startup shipped) :cwd (:root s)))))
    shipped))

(defn- start-window! [s root config]
  (p/process {:env (merge (ts/env s)
                          ;; The window needs the real runtime directory for the
                          ;; display's Wayland socket; the server's address is explicit.
                          {"XDG_RUNTIME_DIR" (System/getenv "XDG_RUNTIME_DIR")
                           "REXMUX_CONFIG" config "REXMUX_SIDECAR_DIR" here "REXMUX_BB" (str (fs/which "bb"))
                           "REXMUX_CLIPBOARD_FILE" (str root "/clipboard") "REXMUX_OPEN_FILE" (str root "/opened")})
              :out (str root "/rexmux.log") :err (str root "/rexmux.log")}
             "test-display" "--name" display "exec" "--" (str (fs/path here "target" "release" "rexmux")) "gui"))

(defn- window-rect []
  (let [tree (json/parse-string (:out (in-display "swaymsg" "-t" "get_tree")) true)
        nodes (tree-seq map? #(concat (:nodes %) (:floating_nodes %)) tree)]
    (some #(when (= "rexmux" (:app_id %)) (:rect %)) nodes)))

(defn- clipboard [root] (let [f (str root "/clipboard")] (when (fs/exists? f) (slurp f))))

(defn- tty-client!
  "rexmux tty on session SID, in a pty made by script(1), so raw mode and
  SGR mouse reports work; returns {:proc :in}."
  [s root config sid]
  (let [proc (p/process {:env (merge (ts/env s)
                                     {"REXMUX_CONFIG" config "REXMUX_SESSION" sid "REXMUX_SIDECAR_DIR" here
                                      "REXMUX_BB" (str (fs/which "bb")) "REXMUX_CLIPBOARD_FILE" (str root "/clipboard")})
                         :out :string :err (str root "/tty.log")}
                        "script" "-qfec" (str (fs/path here "target" "release" "rexmux") " tty 2>>" root "/tty.log") "/dev/null")]
    {:proc proc :in (:in proc)}))

(defn- send-bytes! [{:keys [in]} s]
  (.write in (.getBytes ^String s "UTF-8"))
  (.flush in))

(defn- stop-tty!
  "Stop the tty client; what it drew."
  [{:keys [proc]}]
  (p/destroy-tree proc)
  (str (:out (deref proc 3000 {}))))

(defn- stop-window! [proc]
  (when proc (p/destroy-tree proc) (deref proc 3000 nil))
  (in-display "pkill" "-f" "target/release/rexmux gui"))

(defn -main [& args]
  (let [keep? (some #{"--keep"} args)
        root (str (fs/create-temp-dir {:prefix "rexmux-e2e-"}))
        bin (stubs! (str root "/stubs"))
        s (ts/start! {:root (str root "/srv") :restore true :path-prepend bin})
        config (str root "/rexmux.edn")
        shipped (config! s config)
        labels (mapv :label (get-in shipped [:startup :windows]))]
    (println "e2e run in" root)
    (td "start" "--size" "1400x900")
    (try
      ;; A stray session whose shell has exited.
      (ts/rex! s "new" "w242" "--keep-open" "--shell" "none" "--" "sh" "-c" "exit 0")
      (let [win (start-window! s root config)
            main (wait-for 20000 #(session s "main"))
            sid (:session_id main)
            v (wait-for 10000 #(let [v (view s sid)] (when (= (count labels) (count (:windows v))) v)))
            blocks (mapv #(get-in % [:layers 0 :layout :block_id]) (:windows v))]
        (check! "startup session made beside a stray one" main)
        (check! "startup tabs in Herdr's order" (= labels (mapv :label (:windows v))) (pr-str (mapv :label (:windows v))))
        (doseq [[label block] (map vector labels blocks)
                :let [cmd (some #(when (= label (:label %)) (:command %)) (get-in shipped [:startup :windows]))]]
          (if cmd
            (check! (str "tab " label " runs " (first cmd))
                    (wait-for 15000 #(str/includes? (capture s sid block) (str "STUB " (first cmd))))
                    (some #(when (str/includes? % "STUB") %) (str/split-lines (str (capture s sid block)))))
            (check! (str "tab " label " runs " (:shell shipped))
                    (wait-for 15000 #(= (:shell shipped) (foreground s sid block))) (foreground s sid block))))
        (wait-for 15000 window-rect)
        (shot! root "01-startup")
        ;; Hyprland's Super+Return, E, D and G: workspace-or-herdr-tab runs this in the window.
        (let [{:keys [exit err]} (ts/rex! s "-C" "rexmux" "do" "mx.tab.select_3")]
          (check! "rex do mx.tab.select_3 (Super+D) shows the pi tab"
                  (and (zero? exit) (wait-for 3000 #(= (get-in v [:windows 2 :window_id]) (:active_window_id (view s sid)))))
                  (str exit " " err " " (:active_window_id (view s sid)))))
        ;; Tiny fingers on the shell tab.
        (api s "session.focus_window" {:session_id sid :window_id (get-in v [:windows 0 :window_id])})
        (Thread/sleep 500)
        (ts/rex! s "send" "-s" sid "-b" (first blocks) "clear; printf 'see https://example.com/e2e and /tmp/e2e-path\\n'\r")
        (wait-for 5000 #(str/includes? (capture s sid (first blocks)) "e2e-path"))
        (Thread/sleep 800)
        (keys! [:ctrl "b"] "f")
        (shot! root "02-tiny-fingers")
        (keys! "a")
        (check! "tiny fingers: a label copies the URL" (wait-for 3000 #(= "https://example.com/e2e" (clipboard root))) (clipboard root))
        (check! "tiny fingers: a web URL opens" (wait-for 3000 #(= "https://example.com/e2e" (slurp (str root "/opened")))))
        (keys! [:ctrl "b"] "f" "S")
        (check! "tiny fingers: Shift and a label pastes into the pane"
                ;; The prompt line, not the printed one, ends with the pasted path.
                (wait-for 3000 #(let [l (last (str/split-lines (capture s sid (first blocks))))]
                                  (and (str/ends-with? l "/tmp/e2e-path") (not (str/starts-with? l "see "))))))
        (ts/rex! s "send" "-s" sid "-b" (first blocks) "\u0015clear; echo mouse-drag-line\r")
        (wait-for 5000 #(str/includes? (capture s sid (first blocks)) "mouse-drag-line"))
        (Thread/sleep 800)
        ;; Mouse: headless sway has no pointer, so the drag goes through
        ;; rexmux tty, which reads SGR mouse reports into the same App::mouse
        ;; the window uses. Tabs, title, then the pane's first row on row 3.
        (spit (str root "/clipboard") "")
        (let [tty (tty-client! s root config sid)]
          (Thread/sleep 3000)
          (send-bytes! tty "\u001b[<0;1;3M\u001b[<32;20;3M\u001b[<32;40;3M\u001b[<0;40;3m")
          (check! "mouse: a drag copies the line" (wait-for 3000 #(= "mouse-drag-line" (some-> (clipboard root) str/trim))) (clipboard root))
          (ts/rex! s "send" "-s" sid "-b" (first blocks) "seq 1 300\r")
          (Thread/sleep 1500)
          (send-bytes! tty "\u001b[<64;10;8M\u001b[<64;10;8M")
          (Thread/sleep 1000)
          (let [out (stop-tty! tty)]
            (check! "mouse: the wheel scrolls the pane into its history" (str/includes? out "↑ history"))))
        (shot! root "03-mouse")
        ;; Rename the tab and split, then move the border.
        (keys! [:ctrl "b"] "," "logs" [:key "Return"])
        (check! "Ctrl+b , renames the tab" (wait-for 3000 #(= "logs" (get-in (view s sid) [:windows 0 :label]))))
        (ts/rex! s "send" "-s" sid "-b" (first blocks) "cd /tmp\r")
        (Thread/sleep 800)
        (keys! [:ctrl "b"] "%")
        (let [after (wait-for 3000 #(get-in (view s sid) [:windows 0 :layers 0 :layout :split :after :block_id]))]
          (check! "a split opens in the focused pane's directory (Herdr's new_cwd follow)"
                  (wait-for 5000 #(= "/tmp" (get-in (api s "block.method" {:session_id sid :block_id after :method "process"}) [:foreground :cwd])))))
        (keys! [:ctrl "b"] "L")
        (check! "Ctrl+b L moves the border"
                (wait-for 3000 #(> (get-in (view s sid) [:windows 0 :layers 0 :layout :split :ratio] 0.5) 0.5))
                (get-in (view s sid) [:windows 0 :layers 0 :layout :split :ratio]))
        (shot! root "04-rename-resize")
        ;; Copy mode and search: the scrollback in $EDITOR, a stub nvim that
        ;; records its arguments and the file it was given, then exits.
        (keys! [:ctrl "b"] "1")
        (Thread/sleep 500)
        (let [focused (get-in (view s sid) [:windows 0 :focused_block_id])]
          (ts/rex! s "send" "-s" sid "-b" focused "echo scrollback-marker\r")
          (wait-for 3000 #(str/includes? (capture s sid focused) "scrollback-marker")))
        (keys! [:ctrl "b"] "[")
        (check! "Ctrl+b [ opens the scrollback in the editor"
                (wait-for 8000 #(str/includes? (slurp (str root "/editor-file")) "scrollback-marker"))
                (some-> (str root "/editor-args") (#(when (fs/exists? %) (slurp %))) str/trim))
        (keys! [:ctrl "b"] "/" "e2e-path" [:key "Return"])
        (check! "Ctrl+b / searches the scrollback"
                (wait-for 8000 #(str/includes? (slurp (str root "/editor-args")) "e2e-path"))
                (some-> (slurp (str root "/editor-args")) str/trim))
        ;; Paste: Ctrl+Shift+V types the clipboard into the pane.
        (spit (str root "/clipboard") "pasted-from-clipboard")
        (keys! [:ctrl "b"] "x")
        (wait-for 3000 #(nil? (get-in (view s sid) [:windows 0 :layers 0 :layout :split])))
        (apply in-display "wtype" ["-k" "Shift_L" "-s" "100" "-M" "ctrl" "-M" "shift" "v" "-m" "shift" "-m" "ctrl"])
        (check! "Ctrl+Shift+V pastes the clipboard"
                (wait-for 3000 #(some (fn [b] (str/includes? (capture s sid b) "pasted-from-clipboard"))
                                      (map (fn [w] (get-in w [:layers 0 :layout :block_id])) (:windows (view s sid))))))
        ;; Projects: Ctrl+b O picks a directory and makes its session; Ctrl+b B returns.
        (keys! [:ctrl "b"] "O" "demo" [:key "Return"])
        (let [demo (wait-for 8000 #(session s "demo"))]
          (check! "Ctrl+b O opens a project session with the startup tabs"
                  (and demo (= labels (mapv :label (:windows (view s (:session_id demo)))))))
          (shot! root "05-project")
          (keys! [:ctrl "b"] "O" "demo" [:key "Return"])
          (Thread/sleep 1500)
          (check! "Ctrl+b O on an open project shows it, not a second one"
                  (= 1 (count (filter #(= "demo" (:label %)) (:sessions (api s "session.list"))))))
          ;; Back in main, a rename lands on main's first tab.
          (keys! [:ctrl "b"] "B")
          (Thread/sleep 800)
          (keys! [:ctrl "b"] "," "back" [:key "Return"])
          (check! "Ctrl+b B returns to the previous session"
                  (wait-for 3000 #(= "back" (get-in (view s sid) [:windows 0 :label])))
                  (get-in (view s sid) [:windows 0 :label]))
          (keys! [:ctrl "b"] "," "logs" [:key "Return"]))
        ;; Persistence: the server dies with its programs, as in a reboot.
        (stop-window! win)
        (ts/kill! s)
        (let [s2 (ts/start! {:root (:root s) :restore true :path-prepend bin})
              win2 (start-window! s2 root config)
              v2 (wait-for 15000 #(view s2 sid))
              pi-block (some #(when (= "pi" (:label %)) (get-in % [:layers 0 :layout :block_id])) (:windows v2))]
          (check! "after a reboot the session comes back" (= labels (assoc (mapv :label (:windows v2)) 0 (first labels))))
          (check! "after a reboot pi resumes with --continue"
                  (wait-for 20000 #(str/includes? (capture s2 sid pi-block) "STUB pi --continue"))
                  (some #(when (str/includes? % "STUB") %) (str/split-lines (str (capture s2 sid pi-block)))))
          (let [panes (->> (get-in v2 [:windows 0 :layers 0 :layout]) (tree-seq map? (comp (juxt :before :after) :split))
                           (keep :block_id))]
            (check! "after a reboot a renamed tab's panes, a split too, run the configured shell"
                    (and (= 2 (count panes)) (wait-for 15000 #(every? (fn [b] (= (:shell shipped) (foreground s2 sid b))) panes)))
                    (pr-str (map #(foreground s2 sid %) panes))))
          (shot! root "06-restored")
          (stop-window! win2)
          (ts/stop! s2)))
      (catch Throwable e
        (check! "no exception" false (ex-message e)))
      (finally
        (td "stop")
        (try (ts/stop! s) (catch Exception _))))
    (let [failed (remove :ok @results)]
      (spit (str root "/results.edn") (pr-str @results))
      (println (format "e2e: %d checks, %d failed; screenshots and log in %s" (count @results) (count failed) root))
      (when (and (empty? failed) (not keep?)) nil)
      (System/exit (if (empty? failed) 0 1)))))
