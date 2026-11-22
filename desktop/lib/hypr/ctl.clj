(ns hypr.ctl
  "hyprctl against the running Hyprland. A shell can carry a stale
  HYPRLAND_INSTANCE_SIGNATURE, from doom env's snapshot or from a process older
  than this login; then hyprctl prints a plain-text error, not JSON."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [session]))

(defn env
  "Env naming the running Hyprland, or {} while none runs."
  []
  (if-let [sig (session/hypr-signature)]
    {"HYPRLAND_INSTANCE_SIGNATURE" sig}
    {}))

(defn run
  "Run hyprctl ARGS against the running Hyprland; {:exit :out :err}."
  [& args]
  @(p/process (into ["hyprctl"] args) {:out :string :err :string :extra-env (env)}))

(defn json
  "hyprctl -j ARGS, parsed with keyword keys. Throws ex-info with hyprctl's own
  first line when no Hyprland answers or the output is not JSON."
  [& args]
  (when (empty? (env))
    (throw (ex-info (str "no running Hyprland under " (session/runtime-dir) "/hypr") {:args args})))
  (let [{:keys [exit out err]} (apply run "-j" args)
        said (first (str/split-lines (str/trim (str out err))))
        fail #(throw (ex-info (str "hyprctl -j " (str/join " " args) " " % ": " said)
                              {:args args :exit exit}))]
    (when-not (zero? exit) (fail (str "exited " exit)))
    (try (json/parse-string out true)
         (catch Exception _ (fail "printed no JSON")))))
