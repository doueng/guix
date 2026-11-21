#!/usr/bin/env bb
;; Cmd+Space o n (Open Chrome incognito) end to end on the hidden test display,
;; the way the launcher runs it on the real desktop: `palette run` with the
;; environment Hyprland gives the launcher (its PATH has no ~/.local/bin), the
;; leader.edn row's argv, and the flatpak Chrome against the document portal
;; as the user's session has it right now, which T208 found stale.
;;
;; 1. Chrome closed: the row opens an incognito window.
;; 2. Chrome running (a normal window): the row hands off to it, the new
;;    process exits, and the running Chrome opens an incognito window.
;;
;; Chrome gets a throwaway profile through CHROME_UNIFIED_ARGS, so the hand-off
;; goes to the test's Chrome and never to yours.
;;
;;   bb desktop/build/keybindings/chrome-launch-e2e.bb [--script PATH] [--keep]
;;
;; --script runs another chrome-unified, such as the one before the fix.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def root (str (fs/canonicalize (fs/path (fs/parent *file*) "../../.."))))
(def opts (loop [[a b & more :as args] *command-line-args* m {}]
            (cond (empty? args) m
                  (= a "--script") (recur more (assoc m :script b))
                  (= a "--keep") (recur (cons b more) (assoc m :keep true))
                  :else (recur (cons b more) m))))
(def script (str (fs/absolutize (or (:script opts) (str root "/desktop/bin/chrome-unified")))))
(def display (str "chrome-launch-" (.pid (java.lang.ProcessHandle/current))))
(def home (System/getProperty "user.home"))
;; Under ~/.var/app so the sandbox sees it without an extra --filesystem.
(def profile (str home "/.var/app/com.google.Chrome/chrome-launch-e2e-" (.pid (java.lang.ProcessHandle/current))))
(def tmp (str (fs/create-temp-dir {:prefix "chrome-launch-e2e-"})))
(def failures (atom 0))

(defn check [name ok & [detail]]
  (when-not ok (swap! failures inc))
  (println (str (if ok "PASS " "FAIL ") name (when detail (str " - " detail))))
  ok)

(defn sh [& args] (apply p/sh args))
(defn td [& args] (apply sh "test-display" "--name" display args))

;; The pure core of chrome-unified.
(load-file (str root "/desktop/bin/chrome-unified"))
(check "a healthy portal runs Chrome as usual" (= :ok (portal-plan {:docs-mounted? true :instances ["1"]})))
(check "a stale portal with no flatpak app running is restarted" (= :restart (portal-plan {:docs-mounted? false :instances []})))
(check "a stale portal under a running app is skipped, not restarted" (= :skip (portal-plan {:docs-mounted? false :instances ["1"]})))
(check "skipping it runs flatpak without the document portal"
       (= ["flatpak" "run" "--no-documents-portal" "com.google.Chrome"] (take 4 (command ["--incognito"] {:plan :skip}))))
(check "--incognito reaches Chrome" (= "--incognito" (last (command ["--incognito"] {:plan :ok}))))

;; The row as the launcher runs it.
(def row (some #(when (= ["o" "n"] (:keys %)) %)
               (:bindings (edn/read-string (slurp (str root "/desktop/configs/keybindings/leader.edn"))))))
(check "leader.edn's o n row runs ~/.local/bin/chrome-unified --incognito"
       (= ["~/.local/bin/chrome-unified" "--incognito"] (:desktop-command row)) (pr-str (:desktop-command row)))
(let [link (str home "/.local/bin/chrome-unified")]
  (check "~/.local/bin/chrome-unified is the repo's script"
         (and (fs/exists? link) (str/ends-with? (str (fs/real-path link)) "/desktop/bin/chrome-unified"))
         (when (fs/exists? link) (str (fs/real-path link)))))
;; The same row, pointed at the script under test.
(def leader (str tmp "/leader.edn"))
(fs/copy (str root "/desktop/configs/keybindings/palette.edn") tmp)
(spit leader (str/replace (slurp (str root "/desktop/configs/keybindings/leader.edn"))
                          "\"~/.local/bin/chrome-unified\" \"--incognito\""
                          (str (pr-str script) " \"--incognito\"")))

(defn hyprland-env
  "The environment Hyprland gives what it starts, read from a live child of
  Hyprland, else this one's."
  []
  (let [hypr (some-> (sh "pgrep" "-x" "Hyprland") :out str/split-lines first str/trim not-empty)
        child (when hypr (some-> (sh "pgrep" "-P" hypr) :out str/split-lines first str/trim not-empty))
        f (when child (fs/file "/proc" child "environ"))]
    (if (and f (fs/readable? f))
      (into {} (keep #(let [[k v] (str/split % #"=" 2)] (when v [k v])) (str/split (slurp f) #"\u0000")))
      (into {} (System/getenv)))))

(defn display-env []
  (into {} (keep #(let [[k v] (str/split % #"=" 2)] (when v [k v]))
                 (str/split-lines (:out (td "exec" "--" "env"))))))

(defn windows
  "The test display's windows as [app_id title]."
  []
  (let [tree (json/parse-string (:out (td "exec" "--" "swaymsg" "-t" "get_tree")) true)]
    (->> (tree-seq map? #(concat (:nodes %) (:floating_nodes %)) tree)
         (filter :app_id)
         (mapv (juxt :app_id :name)))))

(defn incognito? [[_ title]] (boolean (re-find #"(?i)incognito" (str title))))

(defn wait-for [pred ms]
  (loop [left ms]
    (let [v (pred)]
      (cond v v
            (pos? left) (do (Thread/sleep 250) (recur (- left 250)))
            :else nil))))

(defn instances []
  (set (remove str/blank? (str/split-lines (:out (sh "flatpak" "ps" "--columns=instance,child-pid"))))))

(def before (instances))

(defn kill-test-chrome!
  "Stop the test profile's Chrome. Its sandbox's bwrap, PID 1 there, ignores
  SIGTERM from outside, so the signal goes to Chrome itself."
  []
  (wait-for #(do (sh "pkill" "-TERM" "-f" (str "^/app/extra/chrome .*--user-data-dir=" profile))
                 (Thread/sleep 1000)
                 (and (empty? (windows)) (= before (instances))))
            20000))

(def log (str tmp "/palette.log"))
(try
  (when-not (zero? (:exit (td "--size" "1280x800" "start")))
    (throw (ex-info "test-display would not start" {})))
  (let [denv (display-env)
        env (-> (hyprland-env)
                (dissoc "DISPLAY" "HYPRLAND_INSTANCE_SIGNATURE" "HYPRLAND_CMD" "SWAYSOCK")
                (merge (select-keys denv ["WAYLAND_DISPLAY" "XDG_SESSION_TYPE"]))
                (assoc "CHROME_UNIFIED_ARGS" (str "--user-data-dir=" profile " --no-first-run --no-default-browser-check --disable-sync")
                       "KEYLEADER_CONFIG" leader
                       "KEYGUIDE_CONFIG" (str root "/desktop/configs/keybindings/bindings.edn")
                       "PALETTE_WINDOW" (json/generate-string {:class "test"})
                       "XDG_STATE_HOME" (str tmp "/state")
                       "KEYLOG_FILE" "/dev/null"))
        _ (check "the launcher's PATH has no ~/.local/bin, as under Hyprland"
                 (not (str/includes? (get env "PATH" "") "/.local/bin")) (get env "PATH"))
        ;; Output to a file: Chrome inherits it and would hold a pipe open.
        choose-o-n! #(deref (p/process {:env env :dir home :out :append :out-file (fs/file log) :err :append :err-file (fs/file log)}
                                       "bb" (str root "/desktop/bin/palette") "run" "desktop:leader:o n"))
        doc-dir (str (or (get env "XDG_RUNTIME_DIR") "/run/user/1000") "/doc/by-app/com.google.Chrome")]
    (println (str "document portal for Chrome: " (if (fs/exists? doc-dir) "mounted" "missing (stale portal)")))

    ;; 1. Chrome closed.
    (let [r (choose-o-n!)
          w (wait-for #(seq (filter incognito? (windows))) 20000)]
      (check "Chrome closed: o n opens an incognito window" w (pr-str (windows)))
      (when-not w (println (slurp log))))
    (check "the test Chrome stops before the next case" (kill-test-chrome!) (pr-str (windows)))

    ;; 2. Chrome running with a normal window.
    (let [_ (p/process {:env env :dir home :out :append :out-file (fs/file log) :err :append :err-file (fs/file log)} script "about:blank")
          w (wait-for #(seq (windows)) 20000)]
      (check "Chrome running: a normal window is up first" (and w (not-any? incognito? (windows))) (pr-str (windows)))
      (let [running (instances)
            t0 (System/currentTimeMillis)
            r (choose-o-n!)
            w (wait-for #(seq (filter incognito? (windows))) 20000)
            ws (windows)]
        (check "Chrome running: o n opens an incognito window in it" w (pr-str ws))
        (check "Chrome running: the normal window stays" (some (complement incognito?) ws) (pr-str ws))
        (check "Chrome running: the new process hands off and leaves no sandbox of its own"
               (wait-for #(= running (instances)) 10000)
               (str (count (remove running (instances))) " extra flatpak instances after " (- (System/currentTimeMillis) t0) " ms"))
        (check "the launcher exited cleanly" (zero? (:exit r)) (str "exit " (:exit r)))
        (when-not w (println (slurp log))))))
  (finally
    (kill-test-chrome!)
    (when-not (:keep opts)
      (td "stop")
      (fs/delete-tree profile)
      (fs/delete-tree tmp))))

(println (if (zero? @failures) "chrome-launch-e2e: all passed" (str "chrome-launch-e2e: " @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
