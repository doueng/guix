#!/usr/bin/env bb
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def repo (str (fs/canonicalize ".")))
(def work (str (fs/create-temp-dir {:prefix "hypr-nested"})))
(def cfg (str work "/cfg"))
(def keylog (str work "/keys.tsv"))

(defn sh [env & args]
  (let [{:keys [out err]} @(p/process (vec args) {:out :string :err :string :extra-env env})]
    (str/trim (str out err))))

(defn instances []
  (set (map :instance (json/parse-string (sh {} "hyprctl" "-j" "instances") true))))

;; The user's live Hyprland. The inherited signature goes stale when Hyprland restarts.
(def user-env
  (let [live (filter #(fs/exists? (str "/proc/" (:pid %)))
                     (json/parse-string (sh {} "hyprctl" "-j" "instances") true))]
    (if-let [i (first (sort-by :time live))]
      {"HYPRLAND_INSTANCE_SIGNATURE" (:instance i) "WAYLAND_DISPLAY" (:wl_socket i)}
      {})))

(defn outer [& args] (apply sh user-env args))
(defn user-focus [] (:address (json/parse-string (outer "hyprctl" "-j" "activewindow") true)))

(def failures (atom []))
(defn check [ok? what]
  (println (if ok? "PASS" "FAIL") what)
  (when-not ok? (swap! failures conj what)))

(defn wait-for [pred]
  (loop [n 0] (cond (pred) true (= n 50) false :else (do (Thread/sleep 100) (recur (inc n))))))

(fs/create-dirs cfg)
(doseq [f (fs/glob (str repo "/desktop/configs/hypr") "*.lua")]
  (fs/copy f (str cfg "/" (fs/file-name f))))
(spit (str cfg "/startup.lua") "-- startup disabled in the nested check\n")
(spit (str cfg "/hyprland.lua")
      (str (slurp (str cfg "/hyprland.lua"))
           ;; wtype invents keycodes; match its keys by name like a real keymap.
           "\nhl.device({ name = \"hl-virtual-keyboard-wtype\", resolve_binds_by_sym = true })\n"))

(def user-focus-before (user-focus))
;; Aquamarine nests only in Hyprland, which cannot run headless, so its window opens in
;; the user's Hyprland: this rule sends it to a special workspace without focus, before
;; it maps. Other live tests use desktop/bin/test-display instead.
(outer "hyprctl" "eval" (str "hl.window_rule({ name = \"test-display-nested-hyprland\", match = { class = \"^aquamarine$\" }, "
                               "workspace = \"special:test-display silent\", no_initial_focus = true })"))
(def before (instances))
(def nested (p/process ["Hyprland" "-c" (str cfg "/hyprland.lua")]
                       {:out :discard :err :discard :extra-env (assoc user-env "KEYLOG_FILE" keylog)}))
(assert (wait-for #(seq (remove before (instances)))) "nested Hyprland did not start")
(def sig (first (remove before (instances))))
(def socket (:wl_socket (first (filter #(= sig (:instance %))
                                       (json/parse-string (outer "hyprctl" "-j" "instances") true)))))
(def nested-pid (:pid (first (filter #(= sig (:instance %))
                                     (json/parse-string (outer "hyprctl" "-j" "instances") true)))))
(def env {"HYPRLAND_INSTANCE_SIGNATURE" sig "WAYLAND_DISPLAY" socket})

(defn hypr [& args] (apply sh env "hyprctl" args))
(defn submap [] (str/replace (hypr "submap") "\"" ""))
(defn active [] (:class (json/parse-string (hypr "-j" "activewindow") true)))
(defn type! [& args]
  (apply sh env "guix" "shell" "wtype" "--" "wtype" args)
  (Thread/sleep 400))
(defn logged [] (if (fs/exists? keylog) (map #(vec (rest (str/split % #"\t"))) (fs/read-all-lines keylog)) []))
(defn no-herdr-window!
  "Wait until no Herdr-class window is left, so the next one client! starts is
  the only target. Herdr handlers would otherwise start a real Herdr terminal."
  []
  (wait-for #(not-any? (fn [c] (= "com.mitchellh.ghostty.herdr" (:class c)))
                       (json/parse-string (hypr "-j" "clients") true))))
(defn row [kind keys effect] ["hyprland" keys effect kind "2"])
(defn client! [class & cmd]
  (p/process (concat ["ghostty" "--gtk-single-instance=false"]
                     (when class [(str "--class=" class)])
                     ["-e"] cmd)
             {:out :discard :err :discard :extra-env env})
  (let [class (or class "com.mitchellh.ghostty")]
    (wait-for #(some (fn [c] (= class (:class c))) (json/parse-string (hypr "-j" "clients") true)))))

(try
  (check (str/blank? (hypr "configerrors")) "config loads without errors")
  (check (= user-focus-before (user-focus)) "the nested session did not take the user's focus")
  (client! "kt.left" "sleep" "300")
  (client! "kt.right" "sleep" "300")
  (check (= "kt.right" (active)) "the second window has focus")

  (check (some #(and (= "" (:submap %)) (= 64 (:modmask %)) (= "SPACE" (:key %)))
               (json/parse-string (hypr "-j" "binds") true))
         "Cmd+Space (SUPER + SPACE), the launcher, is bound at the top level")
  (check (some #(and (= "" (:submap %)) (= 8 (:modmask %)) (= "SPACE" (:key %)))
               (json/parse-string (hypr "-j" "binds") true))
         "Alt+Space, the local prefix, is bound at the top level")
  (type! "-M" "ctrl" "-M" "alt" "-k" "h" "-m" "alt" "-m" "ctrl")
  (check (= "kt.left" (active)) "Ctrl+Alt+h focuses left")
  (type! "-M" "ctrl" "-M" "alt" "-k" "l" "-m" "alt" "-m" "ctrl")
  ;; Alt+Space by window class (leader.edn :apps). Only the Herdr window gets
  ;; Ctrl+b Space; a plain Ghostty gets Alt+Space itself; Chrome enters its submap.
  (client! "com.mitchellh.ghostty.herdr" "sh" "-c" (str "stty raw -echo; head -c 2 > " work "/received"))
  (type! "-M" "alt" "-k" "space" "-m" "alt")
  (let [f (str work "/received")
        got (when (wait-for #(and (fs/exists? f) (= 2 (fs/size f)))) (slurp f))]
    (check (= "\u0002 " got) (str "Alt+Space sends Ctrl+b Space to the Herdr terminal, got " (pr-str got))))
  (client! nil "sh" "-c" (str "stty raw -echo; head -c 2 > " work "/plain"))
  (check (= "com.mitchellh.ghostty" (active)) (str "a plain Ghostty window has focus, got " (active)))
  (type! "-M" "alt" "-k" "space" "-m" "alt")
  (let [f (str work "/plain")
        got (when (wait-for #(and (fs/exists? f) (= 2 (fs/size f)))) (slurp f))]
    (check (= "\u001b " got) (str "a plain Ghostty, where Emacs's M-SPC is Doom's leader, gets Alt+Space itself, got " (pr-str got))))
  (client! "google-chrome" "sh" "-c" (str "stty raw -echo; head -c 1 > " work "/chrome"))
  (check (= "google-chrome" (active)) "a window of Chrome's class has focus")
  (type! "-M" "alt" "-k" "space" "-m" "alt")
  (check (= "local-chrome" (submap)) "Alt+Space in Chrome enters its local submap")
  (type! "-k" "Escape")
  (check (= "default" (submap)) "Escape leaves it")
  (type! "-M" "alt" "-k" "space" "-m" "alt")
  (type! "-k" "t")
  (check (= "local-chrome-t" (submap)) "t enters the Tabs group")
  (type! "-k" "w")
  (let [f (str work "/chrome")
        got (when (wait-for #(and (fs/exists? f) (= 1 (fs/size f)))) (slurp f))]
    (check (and (= "\u0017" got) (= "default" (submap)))
           (str "Alt+Space t d sends Ctrl+W and leaves the submap, got " (pr-str got))))
  (wait-for #(= 7 (count (logged))))
  (check (= (frequencies [(row "action" "CTRL+ALT+H" "Focus left")
                          (row "action" "CTRL+ALT+L" "Focus right")
                          (row "forward" "Alt+Space" "Forwarded to com.mitchellh.ghostty.herdr")
                          (row "leader" "Alt+Space" "Local leader menu")
                          (row "cancel" "Alt+Space" "Cancelled with Escape")
                          (row "leader" "Alt+Space" "Local leader menu")
                          (row "action" "Alt+Space t d" "Close tab")])
            (frequencies (logged)))
         (str "the keybinding log has one row per leader, action, cancel and forward, got " (pr-str (logged))))
  (no-herdr-window!)
  (client! "com.mitchellh.ghostty.herdr" "sh" "-c" (str "stty raw -echo; head -c 2 > " work "/yank"))
  (wait-for #(= "com.mitchellh.ghostty.herdr" (active)))
  (check (= "ok" (hypr "eval" "require('keys').herdr_yank()")) "the Cmd+Y handler runs")
  (let [f (str work "/yank")
        got (when (wait-for #(and (fs/exists? f) (= 2 (fs/size f)))) (slurp f))]
    (check (= "\u0002f" got) (str "Cmd+Y sends Ctrl+b f, tiny-fingers, to the Herdr terminal, got " (pr-str got))))
  (check (some #(and (= "" (:submap %)) (= 64 (:modmask %)) (= "Y" (:key %)))
               (json/parse-string (hypr "-j" "binds") true))
         "Cmd+Y (SUPER + Y) is bound at the top level")
  (check (= "ok" (hypr "dispatch" "hl.dsp.focus({ workspace = 3 })")) "hyprctl accepts Lua dispatchers")
  (check (= user-focus-before (user-focus)) "the user's focus never moved during the run")
  (let [out (sh (assoc env "KEYLEADER_CONFIG" (str repo "/desktop/configs/keybindings/leader.edn"))
                (str repo "/desktop/bin/keyleader") "--verify-hypr")]
    (check (str/starts-with? out "Hyprland has loaded all") (str "keyleader --verify-hypr: " out)))
  (let [out (sh (assoc env "HERDR_PANE_ID" "" "HERDR_ACTIVE_PANE_ID" "" "KEYLOG_FILE" keylog)
                (str repo "/desktop/bin/keyleader") "nav" "left")]
    (check (str/blank? out) (str "keyleader dispatches in Lua mode" (when-not (str/blank? out) (str ": " out)))))
  (finally
    (p/destroy-tree nested)
    (when nested-pid (outer "kill" (str nested-pid)))
    (wait-for #(not (contains? (instances) sig)))
    (fs/delete-tree work)))

(if (seq @failures)
  (do (println (count @failures) "failed") (System/exit 1))
  (println "All nested Hyprland checks passed"))
