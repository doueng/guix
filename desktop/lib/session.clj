(ns session
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn runtime-dir []
  (or (not-empty (System/getenv "XDG_RUNTIME_DIR"))
      (str "/run/user/" (.. (java.lang.ProcessHandle/current) info user (orElse "")))))

(defn- newest [paths]
  (some->> (seq paths) (sort-by #(fs/last-modified-time %)) last fs/file-name str))

(defn- live-hypr?
  "Whether Hyprland instance SIG under DIR still runs: its socket exists and the
  pid on the first line of its hyprland.lock is alive. A crashed compositor can
  leave both files behind."
  [dir sig]
  (let [instance (fs/path dir "hypr" sig)
        lock (fs/path instance "hyprland.lock")
        pid (try (some-> (slurp (str lock)) str/split-lines first str/trim parse-long)
                 (catch java.io.IOException _ nil))]
    (boolean (and (fs/exists? (fs/path instance ".socket.sock"))
                  (or (nil? pid) (.isPresent (java.lang.ProcessHandle/of pid)))))))

(defn hypr-signature
  "The HYPRLAND_INSTANCE_SIGNATURE of a running Hyprland, or nil. The inherited
  one wins while it runs. A stale one, saved by doom env or inherited by a
  process older than this login, gives way to the newest running instance."
  []
  (let [dir (runtime-dir)
        own (not-empty (System/getenv "HYPRLAND_INSTANCE_SIGNATURE"))]
    (if (and own (live-hypr? dir own))
      own
      (newest (filter #(live-hypr? dir (str (fs/file-name %))) (fs/glob dir "hypr/*"))))))

(defn wayland-env
  "WAYLAND_DISPLAY and HYPRLAND_INSTANCE_SIGNATURE of the running session, as
  an env map, or nil while no compositor runs. A variable already set wins
  while its socket exists; a stale one from an earlier session does not."
  []
  (let [dir (runtime-dir)
        live-display? #(and % (fs/exists? (fs/path dir %)))
        display (some #(when (live-display? %) %)
                      [(not-empty (System/getenv "WAYLAND_DISPLAY"))
                       (newest (remove #(re-find #"\.lock$" (str %))
                                       (fs/glob dir "wayland-[0-9]*")))])
        hypr (hypr-signature)]
    (when display
      (cond-> {"WAYLAND_DISPLAY" display "XDG_RUNTIME_DIR" dir}
        hypr (assoc "HYPRLAND_INSTANCE_SIGNATURE" hypr)))))

(defn await-wayland-env
  "wayland-env, polling every few seconds until a compositor runs."
  []
  (loop []
    (or (wayland-env) (do (Thread/sleep 3000) (recur)))))
