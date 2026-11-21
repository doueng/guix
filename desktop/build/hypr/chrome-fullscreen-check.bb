#!/usr/bin/env bb
;; Page fullscreen in Chrome fills the whole screen, and Esc puts the window back in its tile.
;; Runs the repo's Hyprland config nested, with a throwaway Flatpak Chrome profile, and presses
;; F on a page that calls video.requestFullscreen() the way YouTube does.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def repo (str (fs/canonicalize ".")))
(def work (str (fs/create-temp-dir {:prefix "chrome-fullscreen"})))
(def cfg (str work "/cfg"))
;; Inside the Flatpak's own data directory, which its sandbox can always read.
(def prof (str (fs/create-temp-dir {:dir (str (fs/home) "/.var/app/com.google.Chrome") :prefix "fullscreen-check"})))

(defn sh [env & args]
  (let [{:keys [out err]} @(p/process (vec args) {:out :string :err :string :extra-env env})]
    (str/trim (str out err))))
(defn instances [] (json/parse-string (sh {} "hyprctl" "-j" "instances") true))
(defn wait-for [pred n] (loop [i 0] (cond (pred) true (= i n) false :else (do (Thread/sleep 100) (recur (inc i))))))

(def user-env
  (let [i (first (sort-by :time (filter #(fs/exists? (str "/proc/" (:pid %))) (instances))))]
    (if i {"HYPRLAND_INSTANCE_SIGNATURE" (:instance i) "WAYLAND_DISPLAY" (:wl_socket i)} {})))

(def failures (atom []))
(defn check [ok? what]
  (println (if ok? "PASS" "FAIL") what)
  (when-not ok? (swap! failures conj what)))

(fs/create-dirs cfg)
(doseq [f (fs/glob (str repo "/desktop/configs/hypr") "*.lua")]
  (fs/copy f (str cfg "/" (fs/file-name f))))
(spit (str cfg "/startup.lua") "-- startup disabled in the nested check\n")
(spit (str cfg "/hyprland.lua")
      (str (slurp (str cfg "/hyprland.lua"))
           "\nhl.device({ name = \"hl-virtual-keyboard-wtype\", resolve_binds_by_sym = true })\n"))
(spit (str prof "/page.html")
      (str "<!doctype html><body style='margin:0'><video id=v style='width:320px;height:180px;background:#0a0' muted></video><script>"
           "const t=()=>document.title='fs'+(document.fullscreenElement?1:0);"
           "document.addEventListener('fullscreenchange',t);"
           "document.addEventListener('keydown',e=>{if(e.key==='f')document.getElementById('v').requestFullscreen();});t();"
           "</script></body>"))

;; The nested Hyprland's window opens in the user's Hyprland, so this rule hides it on a special
;; workspace without focus. render_unfocused keeps its frame callbacks coming while hidden: without
;; them Chrome never acks a configure and fullscreen hangs, a false failure.
(sh user-env "hyprctl" "eval"
    (str "hl.window_rule({ name = \"test-display-nested-hyprland\", match = { class = \"^aquamarine$\" }, "
         "workspace = \"special:test-display silent\", no_initial_focus = true, render_unfocused = true })"))
(def nested (p/process ["Hyprland" "-c" (str cfg "/hyprland.lua")] {:out :discard :err :discard :extra-env user-env}))
;; Match by pid, since another run may start a nested Hyprland at the same time.
(defn own-instance [] (first (filter #(= (.pid (:proc nested)) (:pid %)) (instances))))
(assert (wait-for own-instance 100) "nested Hyprland did not start")
(def inst (own-instance))
(def env {"HYPRLAND_INSTANCE_SIGNATURE" (:instance inst) "WAYLAND_DISPLAY" (:wl_socket inst)})
(defn hypr [& args] (apply sh env "hyprctl" args))
(defn client [cls] (first (filter #(= cls (:class %)) (json/parse-string (hypr "-j" "clients") true))))
(defn chrome [] (select-keys (client "google-chrome") [:fullscreen :fullscreenClient :at :size :title]))
(defn press! [k] (sh env "wtype" "-k" k) (Thread/sleep 1500))

(def term (p/process ["ghostty" "--gtk-single-instance=false" "--class=fullscreen-check.term" "-e" "sleep" "300"]
                     {:out :discard :err :discard :extra-env env}))
(def browser (p/process ["flatpak" "run" "com.google.Chrome" "--ozone-platform=wayland" (str "--user-data-dir=" prof "/profile")
                         "--no-first-run" "--no-default-browser-check" "--disable-sync" "--new-window" (str "file://" prof "/page.html")]
                        {:out :discard :err :discard :extra-env env}))

(try
  (check (str/blank? (hypr "configerrors")) "config loads without errors")
  ;; Flatpak Chrome can take a minute to start when memory is short.
  (check (wait-for #(client "fullscreen-check.term") 600) "a terminal opened")
  (check (wait-for #(client "google-chrome") 1800) "Chrome opened")
  (wait-for #(str/starts-with? (str (:title (chrome))) "fs0") 100)
  (Thread/sleep 2000)
  (let [m (first (json/parse-string (hypr "-j" "monitors") true))
        screen [(Math/round (/ (:width m) (:scale m))) (Math/round (/ (:height m) (:scale m)))]
        tiled (chrome)]
    (check (and (= 0 (:fullscreen tiled) (:fullscreenClient tiled)) (< (first (:size tiled)) (first screen)))
           (str "Chrome opens tiled beside the terminal: " tiled))
    (doseq [round [1 2 3]]
      (press! "f")
      (let [s (chrome)]
        (check (and (= 2 (:fullscreen s) (:fullscreenClient s)) (= screen (:size s)) (= [0 0] (:at s))
                    (str/starts-with? (:title s) "fs1"))
               (str "round " round ": F fills the whole " screen " screen: " s)))
      (press! "Escape")
      (let [s (chrome)]
        (check (and (= 0 (:fullscreen s) (:fullscreenClient s)) (= (:size tiled) (:size s)) (= (:at tiled) (:at s))
                    (str/starts-with? (:title s) "fs0"))
               (str "round " round ": Esc returns to the tile, not maximized: " s)))))
  (finally
    ;; flatpak run may still be starting Chrome, so kill until none of this profile's is left.
    (p/destroy-tree browser)
    (wait-for #(do (sh {} "pkill" "-f" (str "user-data-dir=" prof))
                   (str/blank? (sh {} "pgrep" "-f" (str "user-data-dir=" prof))))
              300)
    (p/destroy-tree term)
    (p/destroy-tree nested)
    (sh user-env "kill" (str (:pid inst)))
    (wait-for #(not-any? (fn [i] (= (:instance inst) (:instance i))) (instances)) 50)
    (fs/delete-tree work)
    (fs/delete-tree prof)))

(if (seq @failures)
  (do (println (count @failures) "failed") (System/exit 1))
  (println "all passed"))
