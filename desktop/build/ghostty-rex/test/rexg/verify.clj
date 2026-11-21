(ns rexg.verify
  "ghostty-rex end to end: the client in a real Ghostty on the hidden test
  display (test-display, a headless sway: nothing reaches your screen)
  against a throwaway Rex server (rexmux.testserver: its own HOME and
  socket, never /run/user/UID/rex), then Bombadil over the client.

    bb verify [--keep] [--no-bombadil] [--time 60]

  It checks the parity list (docs/rex-parity.html) as rexmux's e2e does:
  - startup: beside a stray session the shipped config's :startup session
    appears, its tabs in Herdr's order (shell, bbh, pi, jjui) running their
    programs (stubs named emacs-bbh, pi and jjui) in the configured shell;
  - Hyprland's Super+D: rex -C ghostty-rex do mx.tab.select_3 shows tab 3;
  - Ctrl+b binds nothing, so fish in a pane gets it;
  - tiny fingers: Alt+Space s l labels a URL and a path, a label copies (and a
    web URL opens), Shift and a label pastes into the pane;
  - mouse, through the client in a script(1) pty, since the headless display
    has no pointer: a drag copies a line, the wheel scrolls into history;
  - rename, split in the pane's directory, resize, copy mode and search
    (the scrollback in a stub $EDITOR), Ghostty's Ctrl+Shift+V paste,
    project sessions (Alt+Space p p) and Alt+Space TAB ` back;
  - jjui and bbh's inbox float over the panes and close when they exit,
    M-x bbh: tell runs bbh say --from rex, Alt+Space TAB r renames the session, and
    a blocked agent in another tab sends one desktop notification (stubs
    record notify-send and bbh);
  - persistence: after the server dies with its programs, as in a reboot,
    a new client brings each startup tab's program back (pi --continue).
  Copies and opened URLs go to files (REXG_CLIPBOARD_FILE, REXG_OPEN_FILE),
  and a screenshot of each step lands in the run's directory, which a
  passing run removes unless --keep."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rexg.model :as model]
            [rexmux.e2e :as e2e]
            [rexmux.testserver :as ts]))

(def ^:private here (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))
(def ^:private checkout (str (fs/canonicalize (fs/path here ".." ".." ".."))))
(def display "ghostty-rex-e2e")
(def app-id "dev.engstrand.Rex.test")

(def results (atom []))

(defn- check! [name ok & [detail]]
  (swap! results conj {:name name :ok (boolean ok) :detail detail})
  (println (if ok "PASS" "FAIL") name (if detail (str "- " detail) ""))
  ok)

(defn- wait-for
  "Poll F every 200 ms for up to MS; its first truthy value, else nil."
  [ms f]
  (loop [t 0]
    (or (try (f) (catch Exception _ nil))
        (when (< t ms) (Thread/sleep 200) (recur (+ t 200))))))

(defn- td [& args] (apply p/sh "test-display" "--name" display args))
(defn- in-display [& argv] (apply td "exec" "--" argv))

(defn- keys!
  "Type KS with one wtype in the test display: strings are text, [:ctrl
  \"b\"] a chord, [:key \"Return\"] a named key. A new wtype's first key can
  be lost while the window takes its keymap, so a bare Shift goes first."
  [& ks]
  (apply in-display "wtype" "-k" "Shift_L" "-s" "150"
         (mapcat (fn [k]
                   (concat (cond (string? k) [k]
                                 (= :key (first k)) ["-k" (second k)]
                                 (= :alt-space (first k)) ["-M" "alt" "-k" "space" "-m" "alt"]
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

(defn- session [s label] (some #(when (= label (:label %)) %) (:sessions (api s "session.list"))))
(defn- view [s sid] (api s "session.view" {:session_id sid}))
(defn- foreground [s sid block]
  (get-in (api s "block.method" {:session_id sid :block_id block :method "process"}) [:foreground :name]))
(defn- capture [s sid block] (:out (ts/rex! s "capture" "-s" sid "-b" block "--trim")))
(defn visible-rows
  "The last ROWS lines of CAP, a capture with --format vt: the screen without
  the scrollback, where a shell's Ctrl+L leaves what it cleared."
  [cap rows]
  (let [lines (str/split (str/replace (str cap) #"\u001b\[[0-9;?]*[A-Za-z]" "") #"\r?\n" -1)]
    (vec (take-last rows (if (= "" (last lines)) (butlast lines) lines)))))
(defn- screen [s sid block]
  (let [{:keys [rows]} (api s "block.method" {:session_id sid :block_id block :method "size"})]
    (visible-rows (:out (ts/rex! s "capture" "-s" sid "-b" block "--format" "vt")) rows)))
(defn- clipboard [root] (let [f (str root "/clipboard")] (when (fs/exists? f) (slurp f))))
(defn- slurp? [f] (when (fs/exists? f) (slurp f)))

(defn render-bin
  "REXG_RENDER, else make ghostty-rex's build, made first when missing or
  older than csrc/."
  []
  (or (System/getenv "REXG_RENDER")
      (let [bin (str (fs/path here "target" "rexg-render"))]
        (when (or (not (fs/exists? bin)) (seq (fs/modified-since bin (fs/path here "csrc"))))
          (let [{:keys [exit]} @(p/process {:dir here :inherit true} "bb" "build")]
            (when-not (zero? exit) (throw (ex-info "building rexg-render failed" {})))))
        bin)))

(defn config!
  "The shipped config, pointed at server S; rexmux.e2e/config! also gives the
  test HOME a fish config that puts the stubs first on PATH."
  [s file]
  (e2e/config! s (str file ".rexmux"))
  (let [shipped (edn/read-string (slurp (str (fs/path checkout "desktop" "configs" "ghostty-rex" "config.edn"))))]
    ;; This checkout's leader.edn, not the one linked into ~/.config.
    (spit file (pr-str (assoc shipped :servers [{:label "test" :address (:address s)}]
                              :leader-file (str (fs/path checkout "desktop" "configs" "keybindings" "leader.edn"))
                              :startup (assoc (:startup shipped) :cwd (:root s)))))
    shipped))

(defn hyprland-path
  "PATH as a window Hyprland opens has it: no ~/.local/bin, where make stow
  links keyleader and bbh, and no bbh shims. The client must find its
  helpers without them (Ctrl+L failed with keyleader not found, T207)."
  [path]
  (str/join ":" (remove #(or (= % (str (fs/path (fs/home) ".local" "bin"))) (str/includes? % "/bbh/shims"))
                         (str/split (str path) #":"))))

(defn client-env
  "The client's environment against server S: the test HOME, the config,
  Hyprland's PATH, the checkout's leader.edn, and files in place of the
  clipboard and the browser."
  [s root config]
  (merge (ts/env s)
         {"PATH" (hyprland-path (get (ts/env s) "PATH"))
          "KEYLEADER_CONFIG" (str (fs/path checkout "desktop" "configs" "keybindings" "leader.edn"))
          "REXG_CONFIG" config "REXMUX_CONFIG" config "REXG_RENDER" (render-bin)
          "REXG_LOG" (str root "/client.log")
          "REXG_CLIPBOARD_FILE" (str root "/clipboard") "REXG_OPEN_FILE" (str root "/opened")}))

(defn attach-script!
  "A script that runs `bb attach` in CLIENT-ENV; Ghostty runs it, so the
  window itself keeps the display's environment."
  [s root config]
  (let [f (str root "/attach.sh")]
    (spit f (str "#!/bin/sh\n"
                 (str/join "\n" (for [[k v] (client-env s root config)] (str "export " k "='" v "'")))
                 "\ncd '" here "' && exec '" (fs/which "bb") "' attach \"$@\" 2>>'" root "/client.err'\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(defn- start-window! [s root config]
  (p/process {:out (str root "/ghostty.log") :err (str root "/ghostty.log")}
             "test-display" "--name" display "exec" "--"
             "ghostty" "--config-default-files=false" (str "--config-file=" checkout "/desktop/configs/ghostty-rex/ghostty")
             "--gtk-single-instance=false" (str "--class=" app-id) "--font-size=11" "--window-decoration=false"
             "-e" (attach-script! s root config)))

(defn- stop-window! [proc]
  (when proc (p/destroy-tree proc) (deref proc 3000 nil))
  (in-display "pkill" "-f" (str "class=" app-id))
  (Thread/sleep 500))

(defn- window-rect []
  (let [tree (json/parse-string (:out (in-display "swaymsg" "-t" "get_tree")) true)
        nodes (tree-seq map? #(concat (:nodes %) (:floating_nodes %)) tree)]
    (some #(when (= app-id (:app_id %)) (:rect %)) nodes)))

(defn- tty-client!
  "The client in a 120x30 pty made by script(1), so raw mode and SGR mouse
  reports work; returns {:proc :in}."
  [s root config sid]
  (let [proc (p/process {:env (client-env s root config) :dir here :out :string :err (str root "/tty.log")}
                        "script" "-qfec" (str "stty cols 120 rows 30; exec " (fs/which "bb") " attach --session " sid " 2>>" root "/tty.log") "/dev/null")]
    {:proc proc :in (:in proc)}))

(defn- blocks-of
  "Every block of session SID's view, floating layers too."
  [s sid]
  (set (keep :block_id (tree-seq coll? seq (:windows (view s sid))))))

(defn- desktop-stubs!
  "Stand-ins that record what the client asks of the desktop: notify-send
  and bbh (inbox waits like a TUI; say writes its arguments)."
  [dir root]
  (doseq [[n body] [["notify-send" (str "(spit \"" root "/notified\" (str (clojure.string/join \" \" *command-line-args*) \"\\n\") :append true)")]
                    ["bbh" (str "(if (= \"inbox\" (first *command-line-args*)) (do (println \"STUB bbh inbox\") (Thread/sleep 100000000))"
                                " (spit \"" root "/bbh-args\" (pr-str *command-line-args*)))")]]]
    (let [f (str (fs/path dir n))]
      (spit f (str "#!" (fs/which "bb") "\n" body "\n"))
      (fs/set-posix-file-permissions f "rwxr-xr-x"))))

(defn- new-block!
  "Wait for a block of SID that BEFORE lacks and whose screen shows TEXT."
  [s sid before text]
  (wait-for 8000 #(some (fn [b] (when (str/includes? (str (capture s sid b)) text) b)) (remove before (blocks-of s sid)))))

(defn- send-bytes! [{:keys [in]} s] (.write in (.getBytes ^String s "UTF-8")) (.flush in))
(defn- stop-tty! [{:keys [proc]}] (p/destroy-tree proc) (str (:out (deref proc 3000 {}))))

(defn dusk-bin
  "DUSK_BIN, else this checkout's build, else the one make stow links."
  []
  (or (System/getenv "DUSK_BIN")
      (some #(when (fs/executable? %) (str %))
            [(fs/path checkout "desktop" "build" "dusk" "target" "release" "dusk")
             (fs/path (fs/parent (fs/real-path (fs/path (fs/home) ".local" "bin" "dusk"))) ".." "build" "dusk" "target" "release" "dusk")])
      "dusk"))

(defn- dusk-msg [& args]
  (str/trim (str (:out (apply in-display (dusk-bin) "msg" args)))))

(defn- start-dusk!
  "Dusk with this checkout's config in the test display, on a D-Bus session
  of its own so it never takes your notification name."
  [root]
  (let [proc (p/process {:out (str root "/dusk.log") :err (str root "/dusk.log")}
                        "test-display" "--name" display "exec" "--" "dbus-run-session" "--"
                        (dusk-bin) "--config" (str checkout "/desktop/configs/dusk/config.clj"))]
    (wait-for 10000 #(str/includes? (dusk-msg "surfaces") "wallpaper"))
    proc))

(defn- launcher-matches
  "The first N rows the open launcher shows, as [label keys source]."
  [n]
  (some-> (dusk-msg "eval" (str "(mapv (juxt :label :keys :source) (take " n " (:matches @config/launcher)))"))
          not-empty edn/read-string))

(defn- launch!
  "Open the launcher (desktop/bin/palette) as Cmd+Space would with the Rex
  window focused, type QUERY and return the rows shown, then press KEY,
  Return to run the first or Escape to close. It runs on a D-Bus session of
  its own, so a Ghostty it starts opens here."
  [s root query key]
  (let [proc (p/process {:out (str root "/palette.log") :err (str root "/palette.log")
                         ;; Only a local command needs the test server; a Ghostty
                         ;; given REX_SERVER would attach to it as a stray tab.
                         :extra-env (merge (when (= "new tab" query) (select-keys (ts/env s) ["REX_SERVER"]))
                                           {"PALETTE_WINDOW" (json/generate-string {:class "dev.engstrand.Rex"})
                                            "KEYLEADER_CONFIG" (str checkout "/desktop/configs/keybindings/leader.edn")
                                            "KEYGUIDE_CONFIG" (str checkout "/desktop/configs/keybindings/bindings.edn")
                                            "XDG_STATE_HOME" (str root "/state")
                                            "KEYLOG_FILE" (str root "/keys.tsv")})}
                        "test-display" "--name" display "exec" "--" "dbus-run-session" "--"
                        (str checkout "/desktop/bin/palette"))
        opened (wait-for 10000 #(str/includes? (dusk-msg "surfaces") "launcher"))]
    (Thread/sleep 300)
    (keys! query)
    (let [rows (launcher-matches 3)]
      (keys! [:key key])
      (deref proc 15000 nil)
      {:opened opened :rows rows})))

(defn- sway-apps []
  (let [tree (json/parse-string (:out (in-display "swaymsg" "-t" "get_tree")) true)]
    (keep :app_id (tree-seq map? #(concat (:nodes %) (:floating_nodes %)) tree))))

(defn- alt-space! [& ks]
  (apply in-display "wtype" "-k" "Shift_L" "-s" "150" "-M" "alt" "-k" "space" "-m" "alt"
         (mapcat (fn [k] ["-s" "150" k]) ks))
  (Thread/sleep 300))

(defn- local-prefix-checks!
  "T208: Alt+Space is the window's local prefix (Hyprland passes it to this
  class), and Cmd+Space's launcher lists the window's commands first and the
  global ones after."
  [s sid root]
  (let [n (count (:windows (view s sid)))]
    (alt-space! "t" "n")
    (check! "Alt+Space t n opens a tab" (wait-for 5000 #(= (inc n) (count (:windows (view s sid)))))
            (count (:windows (view s sid))))
    (let [new-id (:active_window_id (view s sid))]
      (check! "the new tab is last and focused" (= new-id (get-in (view s sid) [:windows n :window_id])))
      (alt-space! "t" "H")
      (check! "Alt+Space t H moves the tab left" (wait-for 3000 #(= new-id (get-in (view s sid) [:windows (dec n) :window_id]))))
      (alt-space! "t" "d")
      (check! "Alt+Space t d closes it" (wait-for 5000 #(= n (count (:windows (view s sid))))))))
  (shot! root "01b-alt-space")
  (let [dusk (start-dusk! root)]
    (try
      (let [before (set (map :window_id (:windows (view s sid))))
            n (count before)
            {:keys [opened rows]} (launch! s root "new tab" "Return")]
        (check! "Cmd+Space opens the launcher" opened)
        (check! "typing filters it to the Rex window's own New tab first, with its keys"
                (= ["New tab" "Alt+Space t n" "Rex"] (first rows)) (pr-str rows))
        (when (check! "choosing it opens a tab (a local command)" (wait-for 5000 #(= (inc n) (count (:windows (view s sid))))))
          (doseq [w (:windows (view s sid)) :when (not (before (:window_id w)))]
            (api s "session.close_window" {:session_id sid :window_id (:window_id w)}))))
      (let [{:keys [rows]} (launch! s root "bb" "Escape")]
        (check! "an old Cmd+Space sequence, b b, is a launcher shortcut"
                (= ["Choose buffer, tab or window" "Cmd+Space b b" "Desktop"] (first (filter #(= "Desktop" (nth % 2)) rows)))
                (pr-str rows)))
      (let [before (count (filter #{"com.mitchellh.ghostty"} (sway-apps)))
            {:keys [rows]} (launch! s root "open ghostty" "Return")]
        (check! "typing filters to the global Open Ghostty" (= "Open Ghostty" (ffirst rows)) (pr-str rows))
        (check! "choosing it opens a Ghostty window (a global command)"
                (wait-for 10000 #(< before (count (filter #{"com.mitchellh.ghostty"} (sway-apps)))))))
      (shot! root "01c-launcher")
      (finally
        (in-display "swaymsg" "[app_id=\"^com.mitchellh.ghostty$\"] kill")
        (dusk-msg "quit")
        (when-not (deref dusk 3000 nil) (p/destroy-tree dusk))))))

(declare switch-checks! term-checks!)

(defn- client-ids [s]
  (set (keep #(when (and (= "ghostty-rex" (get-in % [:info :label])) (not= "lost" (:connection_state %))) (:client_id %))
             (:clients (api s "client.list")))))

(defn- reload-checks!
  "The quick reload (T210): rex -C ghostty-rex do mx.client.reload, which
  make ghostty-rex-reload runs, starts the client anew in the same window on
  the same session."
  [s root sid]
  (let [before (client-ids s)
        {:keys [exit err]} (ts/rex! s "-C" "ghostty-rex" "do" "mx.client.reload")]
    (check! "reload: rex -C ghostty-rex do mx.client.reload is accepted" (zero? exit) err)
    (check! "reload: the client quits to reload"
            (wait-for 8000 #(str/includes? (slurp? (str root "/client.log")) "quit reload")))
    (check! "reload: a new client attaches to the same session in the same window"
            (wait-for 15000 #(let [now (client-ids s)]
                               (and (seq (remove before now))
                                    (str/includes? (pr-str (api s "session.list_clients" {:session_id sid})) "ghostty-rex"))))
            (pr-str (client-ids s)))
    (check! "reload: the old client is gone, so rex -C ghostty-rex stays unambiguous"
            (wait-for 5000 #(= 1 (count (client-ids s)))) (pr-str (client-ids s)))
    (Thread/sleep 1500)))

(defn- cursor-shown?
  "Whether the client's last frame in OUT, its tty output, shows the text
  cursor: rexg-render hides it at a frame's start and shows it at the end
  only when the focused pane's own cursor is visible."
  [out]
  (let [i (str/last-index-of out "\u001b[?25l")]
    (boolean (and i (str/includes? (subs out i) "\u001b[?25h")))))

(defn- cursor-checks!
  "DECTCEM (T210): a pane that hides its cursor with ESC [ ?25 l has no
  cursor drawn; a plain one has."
  [s root config]
  (doseq [[label script want] [["cursor-vis" "printf 'visible here'; exec sleep 100000" true]
                               ["cursor-hid" "printf 'hidden here'; printf '\\033[?25l'; exec sleep 100000" false]]]
    (ts/rex! s "new" label "--keep-open" "--shell" "none" "--" "sh" "-c" script)
    (let [sid (:session_id (session s label))
          t (tty-client! s root config sid)]
      (Thread/sleep 5000)
      (let [shown (cursor-shown? (stop-tty! t))]
        (check! (str "text cursor: " label (if want " shows its cursor" " hides its cursor (ESC [ ?25 l)"))
                (= want shown) (str "shown " shown)))
      (api s "session.destroy" {:session_id sid}))))

(defn e2e!
  "The window checks; returns the run directory."
  [root]
  (let [bin (doto (e2e/stubs! (str root "/stubs")) (desktop-stubs! root))
        s (ts/start! {:root (str root "/srv") :restore true :path-prepend bin})
        config (str root "/ghostty-rex.edn")
        shipped (config! s config)
        labels (mapv :label (get-in shipped [:startup :windows]))]
    (td "--size" "1400x900" "start")
    (try
      ;; A stray session whose shell has exited.
      (ts/rex! s "new" "w242" "--keep-open" "--shell" "none" "--" "sh" "-c" "exit 0")
      (let [win (start-window! s root config)
            main (wait-for 20000 #(session s "main"))
            sid (:session_id main)
            v (wait-for 10000 #(let [v (view s sid)] (when (= (count labels) (count (:windows v))) v)))
            blocks (mapv #(get-in % [:layers 0 :layout :block_id]) (:windows v))
            shell (first blocks)]
        (check! "startup session made beside a stray one" main)
        (check! "startup tabs in Herdr's order" (= labels (mapv :label (:windows v))) (pr-str (mapv :label (:windows v))))
        (doseq [[label block] (map vector labels blocks)
                :let [cmd (some #(when (= label (:label %)) (:command %)) (get-in shipped [:startup :windows]))]]
          (if cmd
            (check! (str "tab " label " runs " (first cmd))
                    (wait-for 15000 #(str/includes? (capture s sid block) (str "STUB " (first cmd)))))
            (check! (str "tab " label " runs " (:shell shipped))
                    (wait-for 15000 #(= (:shell shipped) (foreground s sid block))) (foreground s sid block))))
        (check! "the window is a Ghostty of class dev.engstrand.Rex" (wait-for 15000 window-rect))
        (Thread/sleep 1000)
        (shot! root "01-startup")
        ;; Hyprland's Super+D: workspace-or-herdr-tab runs this.
        (let [{:keys [exit err]} (ts/rex! s "-C" "ghostty-rex" "do" "mx.tab.select_3")]
          (check! "rex -C ghostty-rex do mx.tab.select_3 (Super+D) shows the pi tab"
                  (and (zero? exit) (wait-for 3000 #(= (get-in v [:windows 2 :window_id]) (:active_window_id (view s sid)))))
                  (str exit " " err)))
        (local-prefix-checks! s sid root)
        ;; Before any tty client: they also call themselves ghostty-rex, and
        ;; rex -C ghostty-rex refuses an ambiguous target.
        (reload-checks! s root sid)
        (cursor-checks! s root config)
        ;; Tiny fingers on the shell tab.
        (api s "session.focus_window" {:session_id sid :window_id (get-in v [:windows 0 :window_id])})
        (Thread/sleep 500)
        (ts/rex! s "send" "-s" sid "-b" shell "clear; printf 'see https://example.com/e2e and /tmp/e2e-path\\n'\r")
        (wait-for 5000 #(str/includes? (capture s sid shell) "e2e-path"))
        (Thread/sleep 800)
        (keys! [:alt-space] "s" "l")
        (shot! root "02-tiny-fingers")
        (keys! "a")
        (check! "tiny fingers: a label copies the URL" (wait-for 3000 #(= "https://example.com/e2e" (clipboard root))) (clipboard root))
        (check! "tiny fingers: a web URL opens" (wait-for 3000 #(= "https://example.com/e2e" (slurp? (str root "/opened")))))
        (keys! [:alt-space] "s" "l" "S")
        (check! "tiny fingers: Shift and a label pastes into the pane"
                (wait-for 3000 #(let [l (last (str/split-lines (capture s sid shell)))]
                                  (and (str/ends-with? l "/tmp/e2e-path") (not (str/starts-with? l "see "))))))
        (ts/rex! s "send" "-s" sid "-b" shell "\u0015clear; echo mouse-drag-line\r")
        (wait-for 5000 #(str/includes? (capture s sid shell) "mouse-drag-line"))
        (Thread/sleep 800)
        ;; Mouse: the tabs row, the pane's title, then its first line on row 3.
        (spit (str root "/clipboard") "")
        (let [tty (tty-client! s root config sid)]
          (Thread/sleep 4000)
          (send-bytes! tty "\u001b[<0;1;3M\u001b[<32;20;3M\u001b[<32;40;3M\u001b[<0;40;3m")
          (check! "mouse: a drag copies the line" (wait-for 3000 #(= "mouse-drag-line" (some-> (clipboard root) str/trim))) (clipboard root))
          (ts/rex! s "send" "-s" sid "-b" shell "seq 1 300\r")
          (Thread/sleep 1500)
          (send-bytes! tty "\u001b[<64;10;8M\u001b[<64;10;8M")
          (Thread/sleep 1000)
          (check! "mouse: the wheel scrolls the pane into its history" (str/includes? (stop-tty! tty) "↑ history")))
        (shot! root "03-mouse")
        ;; Rename the tab and split, then move the border.
        (keys! [:alt-space] "t" "r" "logs" [:key "Return"])
        (check! "Alt+Space t r renames the tab" (wait-for 3000 #(= "logs" (get-in (view s sid) [:windows 0 :label]))))
        (ts/rex! s "send" "-s" sid "-b" shell "cd /tmp\r")
        (Thread/sleep 800)
        (keys! [:alt-space] "w" "v")
        (let [after (wait-for 3000 #(get-in (view s sid) [:windows 0 :layers 0 :layout :split :after :block_id]))]
          (check! "a split opens in the focused pane's directory (Herdr's new_cwd follow)"
                  (wait-for 5000 #(= "/tmp" (get-in (api s "block.method" {:session_id sid :block_id after :method "process"}) [:foreground :cwd])))))
        (keys! [:alt-space] "w" "L")
        (check! "Alt+Space w L moves the border"
                (wait-for 3000 #(> (get-in (view s sid) [:windows 0 :layers 0 :layout :split :ratio] 0.5) 0.5))
                (get-in (view s sid) [:windows 0 :layers 0 :layout :split :ratio]))
        ;; Bare Ctrl+h/j/k/l as in Herdr: Ctrl+h moves to the left pane, and
        ;; fish keeps Ctrl+L, so it clears the screen and focus stays.
        (let [focused #(get-in (view s sid) [:windows 0 :focused_block_id])]
          (keys! [:ctrl "h"])
          (check! "Ctrl+h focuses the left pane" (wait-for 5000 #(= shell (focused))) (focused))
          (ts/rex! s "send" "-s" sid "-b" shell "echo before-ctrl-l\r")
          (wait-for 5000 #(some (fn [l] (= "before-ctrl-l" (str/trim l))) (screen s sid shell)))
          (keys! [:ctrl "l"])
          (check! "Ctrl+L clears the shell pane's screen and keeps focus"
                  (wait-for 8000 #(and (not-any? (fn [l] (str/includes? l "before-ctrl-l")) (screen s sid shell))
                                       (= shell (focused))))
                  (pr-str (take 4 (screen s sid shell))))
          ;; Ctrl+b goes to fish, which moves back a character.
          (keys! "echo ctrlb-ab" [:ctrl "b"] "X" [:key "Return"])
          (check! "Ctrl+b reaches the shell (fish moves back a character): ctrlb-aXb"
                  (wait-for 5000 #(some (fn [l] (= "ctrlb-aXb" (str/trim l))) (screen s sid shell)))
                  (pr-str (take-last 4 (remove str/blank? (screen s sid shell))))))
        (shot! root "04-rename-resize")
        ;; Copy mode and search: the scrollback in a stub $EDITOR that
        ;; records its arguments and the file it was given.
        (keys! [:alt-space] "t" "1")
        (Thread/sleep 500)
        (let [focused (get-in (view s sid) [:windows 0 :focused_block_id])]
          (ts/rex! s "send" "-s" sid "-b" focused "echo scrollback-marker\r")
          (wait-for 3000 #(str/includes? (capture s sid focused) "scrollback-marker")))
        (keys! [:alt-space] "e")
        (check! "Alt+Space e opens the scrollback in the editor"
                (wait-for 8000 #(str/includes? (slurp? (str root "/editor-file")) "scrollback-marker"))
                (slurp? (str root "/editor-args")))
        (keys! [:alt-space] "s" "b" "e2e-path" [:key "Return"])
        (check! "Alt+Space s b searches the scrollback"
                (wait-for 8000 #(str/includes? (slurp? (str root "/editor-args")) "e2e-path"))
                (slurp? (str root "/editor-args")))
        ;; Paste: Ghostty's Ctrl+Shift+V pastes the Wayland clipboard.
        (keys! [:alt-space] "d")
        (wait-for 3000 #(nil? (get-in (view s sid) [:windows 0 :layers 0 :layout :split])))
        (in-display "sh" "-c" "printf pasted-from-clipboard | wl-copy >/dev/null 2>&1")
        (Thread/sleep 300)
        (in-display "wtype" "-k" "Shift_L" "-s" "100" "-M" "ctrl" "-M" "shift" "v" "-m" "shift" "-m" "ctrl")
        (check! "Ctrl+Shift+V pastes the clipboard"
                (wait-for 3000 #(some (fn [b] (str/includes? (capture s sid b) "pasted-from-clipboard"))
                                      (map (fn [w] (get-in w [:layers 0 :layout :block_id])) (:windows (view s sid))))))
        (keys! [:ctrl "u"])
        ;; Projects: Alt+Space p p picks a directory and makes its session; Alt+Space TAB ` returns.
        (keys! [:alt-space] "p" "p" "demo" [:key "Return"])
        (let [demo (wait-for 8000 #(session s "demo"))]
          (check! "Alt+Space p p opens a project session with the startup tabs"
                  (and demo (wait-for 5000 #(= labels (mapv :label (:windows (view s (:session_id demo)))))))
                  (some->> demo :session_id (view s) :windows (mapv :label) pr-str))
          (shot! root "05-project")
          (keys! [:alt-space] "p" "p" "demo" [:key "Return"])
          (Thread/sleep 1500)
          (check! "Alt+Space p p on an open project shows it, not a second one"
                  (= 1 (count (filter #(= "demo" (:label %)) (:sessions (api s "session.list"))))))
          (keys! [:alt-space] [:key "Tab"] "`")
          (Thread/sleep 800)
          (keys! [:alt-space] "t" "r" "back" [:key "Return"])
          (check! "Alt+Space TAB ` returns to the previous session"
                  (wait-for 3000 #(= "back" (get-in (view s sid) [:windows 0 :label])))
                  (get-in (view s sid) [:windows 0 :label]))
          (keys! [:alt-space] "t" "r" "logs" [:key "Return"])
          ;; The reboot check wants the first tab split in two.
          (when-not (get-in (view s sid) [:windows 0 :layers 0 :layout :split])
            (keys! [:alt-space] "w" "v")
            (wait-for 3000 #(get-in (view s sid) [:windows 0 :layers 0 :layout :split]))))
        ;; jjui and bbh's inbox float over the panes; Ctrl+C ends each, and its layer closes.
        (doseq [[ks text name] [[["o" "g"] "STUB jjui" "Alt+Space o g floats jjui over the panes"]
                                [["i"] "STUB bbh inbox" "Alt+Space i floats bbh's inbox over the panes"]]]
          (let [before (blocks-of s sid)]
            (apply keys! [:alt-space] ks)
            (let [b (new-block! s sid before text)]
              (check! name b)
              (shot! root (str "07-layer-" (str/join ks)))
              (when b
                (ts/rex! s "send" "-s" sid "-b" b "\u0003")
                (check! (str name ", and closes when it exits") (wait-for 5000 #(not ((blocks-of s sid) b))))))))
        ;; bbh routing: M-x bbh: tell sends bbh say with the rex origin.
        (keys! [:alt "x"] "bbh: tell" [:key "Return"])
        (Thread/sleep 500)
        (keys! "T207 hello from ghostty-rex" [:key "Return"])
        (check! "M-x bbh: tell runs bbh say --from rex"
                (wait-for 5000 #(= ["say" "--from" "rex" "T207 hello from ghostty-rex"] (some-> (slurp? (str root "/bbh-args")) edn/read-string)))
                (slurp? (str root "/bbh-args")))
        ;; Session rename, then back.
        (keys! [:alt-space] [:key "Tab"] "r" "renamed" [:key "Return"])
        (check! "Alt+Space TAB r renames the session" (wait-for 3000 #(= "renamed" (:label (view s sid)))) (:label (view s sid)))
        (keys! [:alt-space] [:key "Tab"] "r" "main" [:key "Return"])
        ;; Notifications: an agent that turns blocked in a tab you are not on.
        (let [before-windows (set (map :window_id (:windows (view s sid))))]
          (api s "session.new_window"
               {:session_id sid :focus false
                :layout {:block {:flavor "com.superlogical.terminal.shell"
                                 :options {:command ["sh" "-c" "sleep 1; printf '\\033]7501;state=blocked:app=pi\\033\\\\'; exec sleep 100000"]}}}})
          (check! "a blocked agent in another tab sends one desktop notification"
                  (wait-for 8000 #(str/includes? (str (slurp? (str root "/notified"))) "pi needs you"))
                  (slurp? (str root "/notified")))
          (Thread/sleep 1500)
          (check! "the notification is sent once" (= 1 (count (re-seq #"needs you" (str (slurp? (str root "/notified")))))))
          (shot! root "08-blocked")
          (when-let [agent-w (first (remove before-windows (map :window_id (:windows (view s sid)))))]
            (api s "session.close_window" {:session_id sid :window_id agent-w})))
        ;; Persistence: the server dies with its programs, as in a reboot.
        (stop-window! win)
        (ts/kill! s)
        (let [s2 (ts/start! {:root (:root s) :restore true :path-prepend bin})
              win2 (start-window! s2 root config)
              v2 (wait-for 15000 #(view s2 sid))
              ;; The client replaces each restored bash block with a new one,
              ;; so block ids change: read the view again each time.
              tab-blocks (fn [i] (->> (get-in (view s2 sid) [:windows i :layers 0 :layout])
                                      (tree-seq map? (comp (juxt :before :after) :split))
                                      (keep :block_id)))
              pi-i (first (keep-indexed #(when (= "pi" (:label %2)) %1) (:windows v2)))]
          (check! "after a reboot the session comes back" (= labels (assoc (mapv :label (:windows v2)) 0 (first labels)))
                  (pr-str (mapv :label (:windows v2))))
          (check! "after a reboot pi resumes with --continue"
                  (wait-for 20000 #(str/includes? (capture s2 sid (first (tab-blocks pi-i))) "STUB pi --continue")))
          (check! "after a reboot a renamed tab's panes, a split too, run the configured shell"
                  (wait-for 15000 #(let [panes (tab-blocks 0)]
                                     (and (= 2 (count panes)) (every? (fn [b] (= (:shell shipped) (foreground s2 sid b))) panes))))
                  (pr-str (map #(foreground s2 sid %) (tab-blocks 0))))
          (Thread/sleep 1500)
          (let [screens (for [i (range (count (:windows v2))) b (tab-blocks i)] (capture s2 sid b))]
            (check! "after a reboot no tab shows a typed exec line (T210)"
                    (not-any? #(re-find #"exec \S*fish" %) screens)
                    (some #(re-find #".*exec \S*fish.*" %) screens)))
          (Thread/sleep 1000)
          (shot! root "06-restored")
          (let [s3 (switch-checks! s2 root sid)]
            (term-checks! root)
            (stop-window! win2)
            (ts/stop! s3))))
      (catch Throwable e
        (check! "no exception" false (ex-message e)))
      (finally
        (td "stop")
        (try (ts/stop! s) (catch Exception _))))))

(defn- client-pids
  "The pids of the e2e window's bb client and its rexg-render, found by the
  REXG_LOG under ROOT in their environment, never a live window's."
  [root]
  (let [mark (str "REXG_LOG=" root "/client.log")]
    (vec (for [d (fs/list-dir "/proc")
               :let [pid (parse-long (str (fs/file-name d)))]
               :when pid
               :let [env (try (slurp (str d "/environ")) (catch Exception _ ""))]
               :when (some #{mark} (str/split env #"\u0000"))]
           pid))))

(defn- alive? [pid] (fs/exists? (str "/proc/" pid)))

(defn- switch-checks!
  "A switch under an open window (T210): its server stops, as when a test or
  a Home activation stopped the live one, and a server from another build,
  the same rex at another store path, takes over. The window says it is not
  connected and comes back on its own, with no kill. Returns the new server."
  [s2 root sid]
  (let [bin2 (str root "/other-build/bin/rex")]
    (fs/create-dirs (fs/parent bin2))
    (fs/copy (:bin s2) bin2 {:replace-existing true})
    (ts/rex! s2 "server" "stop")
    (deref (:proc s2) 5000 nil)
    (check! "switch: with its server gone the window says so and retries"
            (wait-for 8000 #(str/includes? (slurp? (str root "/client.log")) "lost")))
    (Thread/sleep 600)
    (shot! root "09-switch-down")
    (let [s3 (ts/start! {:root (:root s2) :restore true :path-prepend (:path-prepend s2) :bin bin2})
          shell-blocks #(->> (get-in (view s3 sid) [:windows 0 :layers 0 :layout])
                             (tree-seq map? (comp (juxt :before :after) :split))
                             (keep :block_id))]
      (check! "switch: the window reconnects to a server from another build by itself"
              (wait-for 20000 #(str/includes? (pr-str (api s3 "client.list")) "ghostty-rex")))
      (Thread/sleep 1500)
      (api s3 "session.focus_window" {:session_id sid :window_id (get-in (view s3 sid) [:windows 0 :window_id])})
      (Thread/sleep 1500)
      (keys! "echo switch-ok" [:key "Return"])
      (check! "switch: typing reaches the shell again"
              (wait-for 8000 #(some (fn [b] (re-find #"(?m)^switch-ok" (capture s3 sid b))) (shell-blocks))))
      (shot! root "10-switch-back")
      s3)))

(defn- term-checks!
  "SIGTERM ends the window's bb client and rexg-render, so closing it, or a
  pkill without -9, is enough (T210)."
  [root]
  (let [pids (client-pids root)]
    (check! "the e2e window's client and renderer are found" (<= 2 (count pids)) (pr-str pids))
    (doseq [pid pids] (p/sh "kill" "-TERM" (str pid)))
    (check! "SIGTERM ends the client and the renderer, no kill -9"
            (wait-for 5000 #(not-any? alive? pids))
            (pr-str (filter alive? pids)))))

(defn- bombadil-bin []
  (or (some-> (fs/which "bombadil") str)
      (let [{:keys [exit out err]} (p/sh {:dir checkout} "guix" "build" "-L" "modules" "-e" "(@ (engstrand packages bombadil) bombadil)")]
        (when-not (zero? exit) (throw (ex-info (str "building bombadil failed: " err) {})))
        (str (fs/path (last (str/split-lines (str/trim out))) "bin" "bombadil")))))

(defn- fixture!
  "rexmux.ts's session: a shell, a blocked agent (OSC 7501 state=blocked,
  app=pi) in its own tab and a working one, so attention crosses tabs."
  [s]
  (ts/rex! s "new" "work" "--keep-open" "--shell" "none" "--" "sh")
  (doseq [[label state app] [["blocked" "blocked" "pi"] ["busy" "working" "claude"]]]
    (ts/rex! s "api" "call" "-s" "work" "session.new_window"
             (json/generate-string
              {:label label :focus false
               :layout {:block {:flavor "com.superlogical.terminal.shell"
                                :options {:command ["sh" "-c" (str "printf '\\033]7501;state=" state ":app=" app "\\033\\\\'; exec sleep 100000")]}}}}))))

(def ^:private specs
  "rexmux's Bombadil specs, which read the screen model this client shares
  (tabs on row 0, ▶ on the focused pane's title, the status line, the echo
  or minibuffer line). Each: the spec file, its setup, the session to show."
  {"tabs" {:file "rexmux.ts"
           ;; Alt+Space, Rex's only prefix, comes from leader.edn.
           :setup (fn [s config]
                    (fixture! s)
                    (spit config (pr-str {:servers [{:label "test" :address (:address s)}]
                                          :leader-file (str (fs/path checkout "desktop" "configs" "keybindings" "leader.edn"))
                                          :leader-prefix ["alt+space"]}))
                    "work")}
   "startup" {:file "startup.ts"
              :setup (fn [s config]
                       (config! s config)
                       (spit (str (fs/path (:root s) "config" "fish" "config.fish"))
                             "echo 'see https://example.com/shell and /tmp/stub-shell'\n" :append true)
                       nil)}
   ;; startup.ts again on the startup session after a reboot: Rex brings its
   ;; tabs back as bare bash, and no tab may show a typed exec line (T210).
   "restored" {:file "startup.ts"
               :restore true
               :setup (fn [s config]
                        (let [shipped (config! s config)
                              startup (assoc (:startup shipped) :cwd (:root s))]
                          (spit (str (fs/path (:root s) "config" "fish" "config.fish"))
                                "echo 'see https://example.com/shell and /tmp/stub-shell'\n" :append true)
                          (api s "session.create" (model/startup-request startup (str (fs/which (:shell shipped)))))
                          ;; Rex saves each block a moment later; a kill before that
                          ;; restores nothing.
                          (wait-for 20000 #(<= (count (:windows startup))
                                               (count (fs/glob (:root s) "state/**/snapshot/blocks/*"))))
                          (Thread/sleep 1500)
                          (ts/kill! s)
                          (let [s2 (ts/start! {:root (:root s) :restore true :path-prepend (:path-prepend s)})
                                sid (:session_id (wait-for 10000 #(session s2 "main")))
                                fgs #(for [w (:windows (view s2 sid))] (foreground s2 sid (get-in w [:layers 0 :layout :block_id])))]
                            (check! "restored spec: Rex brings the startup tabs back as bare bash"
                                    (wait-for 10000 #(every? #{"bash"} (fgs))) (pr-str (fgs)))
                            ;; No --session: the client finds main as its startup
                            ;; session, which is what revives its tabs.
                            {:session nil :server s2})))}})

(defn bombadil!
  "Bombadil types into the client on a throwaway server for TIME seconds and
  checks SPEC on every screen; true when no property broke."
  [root spec time]
  (let [{:keys [file setup restore]} (specs spec)
        dir (str root "/bombadil-" spec)
        s (ts/start! {:root (str dir "/srv") :restore (boolean restore) :path-prepend (e2e/stubs! (str dir "/stubs"))})
        config (str dir "/ghostty-rex.edn")
        trace (str dir "/trace.bin")
        restarted (volatile! nil)]
    (try
      (let [r (setup s config)
            session (if (map? r) (:session r) r)
            s (if (map? r) (:server r) s)
            _ (when (map? r) (vreset! restarted s))
            ;; Unsynchronized frames: Bombadil 0.8 samples at each finished
            ;; synchronized frame, and the renderer draws pane output on its own
            ;; clock, so a frame can predate bb handling the key.
            env (assoc (client-env s dir config) "REXG_SYNC" "0")
            cmd [(bombadil-bin) "terminal" "test"
                 "--specification" (str (fs/path checkout "desktop" "build" "rexmux" "test" "bombadil" file))
                 "--time-limit" (str time "s") "--exit-on-violation"
                 "--columns" "110" "--rows" "30" "--quiescence-timeout-ms" "150"
                 "--output-path" trace "--output-path-overwrite"
                 "--" "sh" "-c" (str "cd '" here "' && exec bb attach" (when session (str " --session " session)) " 2>>" dir "/client.err")]
            {:keys [exit out err]} @(apply p/process {:env env :out :string :err :string :in nil} cmd)]
        (println (str/join "\n" (take-last 30 (remove #(str/includes? % "\u001b[") (str/split-lines (str out "\n" err))))))
        (check! (str "bombadil " spec " (" file ", " time " s)") (zero? exit) (str "trace " trace))
        (zero? exit))
      (finally (try (ts/stop! (or @restarted s)) (catch Exception _))))))

(defn -main [& args]
  (let [keep? (some #{"--keep"} args)
        time (or (some-> (second (drop-while #(not= "--time" %) args)) parse-long) 60)
        root (str (fs/create-temp-dir {:prefix "ghostty-rex-e2e-"}))]
    (println "e2e run in" root)
    (e2e! root)
    (when-not (some #{"--no-bombadil"} args)
      (doseq [spec ["tabs" "startup" "restored"]]
        (try (bombadil! root spec time)
             (catch Throwable e (check! (str "bombadil " spec) false (ex-message e))))))
    (let [failed (remove :ok @results)]
      (spit (str root "/results.edn") (pr-str @results))
      (println (format "e2e: %d checks, %d failed; screenshots and logs in %s" (count @results) (count failed) root))
      ;; A passing run leaves nothing behind (each Bombadil trace is ~90 MB) unless --keep.
      (when (and (empty? failed) (not keep?)) (fs/delete-tree root) (println "removed" root))
      (System/exit (if (empty? failed) 0 1)))))
