#!/usr/bin/env bb
;; Runs desktop/configs/hypr/hyprland.lua in a nested Hyprland and types keys
;; into it with wtype. The nested window waits on a hidden special workspace,
;; startup programs are disabled, and the keybinding log goes to a temp file.
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

(defn outer [& args] (apply sh {} args))

(defn instances []
  (set (map :instance (json/parse-string (outer "hyprctl" "-j" "instances") true))))

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

(def before (instances))
(def nested (p/process ["Hyprland" "-c" (str cfg "/hyprland.lua")]
                       {:out :discard :err :discard :extra-env {"KEYLOG_FILE" keylog}}))
(assert (wait-for #(seq (remove before (instances)))) "nested Hyprland did not start")
(def sig (first (remove before (instances))))
(def socket (:wl_socket (first (filter #(= sig (:instance %))
                                       (json/parse-string (outer "hyprctl" "-j" "instances") true)))))
(def nested-pid (:pid (first (filter #(= sig (:instance %))
                                     (json/parse-string (outer "hyprctl" "-j" "instances") true)))))
(def env {"HYPRLAND_INSTANCE_SIGNATURE" sig "WAYLAND_DISPLAY" socket})

(defn hypr [& args] (apply sh env "hyprctl" args))
(defn hide-window! []
  (let [lua (outer "hyprctl" "dispatch" "hl.dsp.window.move({ workspace = \"special:keytest\", follow = false, window = \"class:aquamarine\" })")]
    (when-not (= "ok" lua)
      (outer "hyprctl" "dispatch" "movetoworkspacesilent" "special:keytest,class:aquamarine"))))
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
(defn enter-leader!
  "Run the Cmd+Space handler, which wtype cannot press."
  []
  (hypr "eval" "require('keys').enter('leader', 'Cmd+Space')()")
  (Thread/sleep 200))
(defn row [kind keys effect] ["hyprland" keys effect kind "2"])
(defn client! [class & cmd]
  (p/process (concat ["ghostty" "--gtk-single-instance=false"]
                     (when class [(str "--class=" class)])
                     ["-e"] cmd)
             {:out :discard :err :discard :extra-env env})
  (let [class (or class "com.mitchellh.ghostty")]
    (wait-for #(some (fn [c] (= class (:class c))) (json/parse-string (hypr "-j" "clients") true)))))

(try
  (hide-window!)
  (check (str/blank? (hypr "configerrors")) "config loads without errors")
  (client! "kt.left" "sleep" "300")
  (client! "kt.right" "sleep" "300")
  (check (= "kt.right" (active)) "the second window has focus")

  ;; wtype's keymap does not map its logo modifier to Super, so check the
  ;; Cmd+Space bind and run the desktop leader's handler directly.
  (check (some #(and (= "" (:submap %)) (= 64 (:modmask %)) (= "SPACE" (:key %)))
               (json/parse-string (hypr "-j" "binds") true))
         "Cmd+Space (SUPER + SPACE) is bound at the top level")
  (enter-leader!)
  (check (= "leader" (submap)) "Cmd+Space enters the desktop leader")
  (type! "-k" "Escape")
  (check (= "default" (submap)) "Escape leaves the leader")
  (enter-leader!)
  (type! "-k" "w" "-s" "150" "-k" "h")
  (check (and (= "kt.left" (active)) (= "default" (submap))) "Cmd+Space w h focuses left and leaves the leader")
  (enter-leader!)
  (type! "-k" "w" "-s" "150" "-k" "l")
  (check (= "kt.right" (active)) "Cmd+Space w l focuses right")
  (enter-leader!)
  (type! "-k" "w" "-s" "150" "-k" "z")
  (check (and (= "kt.right" (active)) (= "default" (submap))) "an unbound key cancels without acting")
  (type! "-M" "ctrl" "-M" "alt" "-k" "h" "-m" "alt" "-m" "ctrl")
  (check (= "kt.left" (active)) "Ctrl+Alt+h focuses left")
  (type! "-M" "ctrl" "-M" "alt" "-k" "l" "-m" "alt" "-m" "ctrl")
  (client! "com.mitchellh.ghostty.herdr" "sh" "-c" (str "stty raw -echo; head -c 2 > " work "/received"))
  (client! nil "sh" "-c" (str "stty raw -echo; head -c 2 > " work "/plain"))
  (check (= "com.mitchellh.ghostty" (active)) (str "a Ghostty window without Herdr has focus before Alt+Space, got " (active)))
  (check (= "ok" (hypr "eval" "require('keys').herdr_menu()")) "the Alt+Space handler runs")
  (check (= "com.mitchellh.ghostty.herdr" (active)) "the Alt+Space handler focuses the Herdr terminal")
  (let [f (str work "/received")
        got (when (wait-for #(and (fs/exists? f) (= 2 (fs/size f)))) (slurp f))]
    (check (= "\u0002 " got) (str "Alt+Space sends Ctrl+b Space to the Herdr terminal, got " (pr-str got))))
  (let [f (str work "/plain")]
    (check (not (and (fs/exists? f) (pos? (fs/size f))))
           (str "the other Ghostty window receives nothing, got " (pr-str (when (fs/exists? f) (slurp f))))))
  ;; Now press the chord itself. Keys Hyprland sends while wtype still holds
  ;; Alt arrive garbled, though they arrive intact through kanata, so this
  ;; checks the bind and the focus only.
  (no-herdr-window!)
  (client! "com.mitchellh.ghostty.herdr" "sleep" "30")
  (hypr "dispatch" "hl.dsp.focus({ window = \"class:^com\\\\.mitchellh\\\\.ghostty$\" })")
  (wait-for #(= "com.mitchellh.ghostty" (active)))
  (type! "-M" "alt" "-k" "space" "-m" "alt")
  (wait-for #(= "com.mitchellh.ghostty.herdr" (active)))
  (check (= "com.mitchellh.ghostty.herdr" (active)) "Alt+Space focuses the Herdr terminal")
  (hypr "dispatch" "hl.dsp.window.close()")
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
  ;; Each row is its own keylog process, so rows within one wtype call may land
  ;; in either order.
  (wait-for #(= 13 (count (logged))))
  (check (= (frequencies [(row "leader" "Cmd+Space" "Desktop leader menu")
                          (row "cancel" "Cmd+Space" "Cancelled with Escape")
                          (row "leader" "Cmd+Space" "Desktop leader menu")
                          (row "action" "Cmd+Space w h" "Focus left")
                          (row "leader" "Cmd+Space" "Desktop leader menu")
                          (row "action" "Cmd+Space w l" "Focus right")
                          (row "leader" "Cmd+Space" "Desktop leader menu")
                          (row "cancel" "Cmd+Space w" "Cancelled by an unbound key")
                          (row "action" "CTRL+ALT+H" "Focus left")
                          (row "action" "CTRL+ALT+L" "Focus right")
                          (row "forward" "Alt+Space" "Forwarded to herdr")
                          (row "forward" "Alt+Space" "Forwarded to herdr")
                          (row "action" "Cmd+Y" "Copy visible text by hint")])
            (frequencies (logged)))
         (str "the keybinding log has one row per leader, action, cancel and forward, got " (pr-str (logged))))
  (check (= "ok" (hypr "dispatch" "hl.dsp.focus({ workspace = 3 })")) "hyprctl accepts Lua dispatchers")
  (let [out (sh env (str repo "/desktop/bin/keyleader") "--verify-hypr")]
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
