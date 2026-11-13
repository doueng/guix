;; The graphical session's environment for programs that Shepherd starts at
;; login, before or apart from Hyprland: they inherit the D-Bus address but
;; not WAYLAND_DISPLAY or HYPRLAND_INSTANCE_SIGNATURE.
(ns session
  (:require [babashka.fs :as fs]))

(defn runtime-dir []
  (or (not-empty (System/getenv "XDG_RUNTIME_DIR"))
      (str "/run/user/" (.. (java.lang.ProcessHandle/current) info user (orElse "")))))

(defn- newest [paths]
  (some->> (seq paths) (sort-by #(fs/last-modified-time %)) last fs/file-name str))

(defn wayland-env
  "WAYLAND_DISPLAY and HYPRLAND_INSTANCE_SIGNATURE of the running session, as
  an env map, or nil while no compositor runs. A variable already set wins
  while its socket exists; a stale one from an earlier session does not."
  []
  (let [dir (runtime-dir)
        live-display? #(and % (fs/exists? (fs/path dir %)))
        live-hypr? #(and % (fs/exists? (fs/path dir "hypr" % ".socket.sock")))
        display (some #(when (live-display? %) %)
                      [(not-empty (System/getenv "WAYLAND_DISPLAY"))
                       (newest (remove #(re-find #"\.lock$" (str %))
                                       (fs/glob dir "wayland-[0-9]*")))])
        hypr (some #(when (live-hypr? %) %)
                   [(not-empty (System/getenv "HYPRLAND_INSTANCE_SIGNATURE"))
                    (newest (filter #(live-hypr? (str (fs/file-name %))) (fs/glob dir "hypr/*")))])]
    (when display
      (cond-> {"WAYLAND_DISPLAY" display "XDG_RUNTIME_DIR" dir}
        hypr (assoc "HYPRLAND_INSTANCE_SIGNATURE" hypr)))))

(defn await-wayland-env
  "wayland-env, polling every few seconds until a compositor runs."
  []
  (loop []
    (or (wayland-env) (do (Thread/sleep 3000) (recur)))))
