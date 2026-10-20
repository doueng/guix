(ns hypr.dispatch
  "Hyprland dispatchers as data, e.g. [\"focus\" {:direction \"left\"}] or
  [\"window.close\"]. The same value renders into the Lua config and into
  `hyprctl dispatch`."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn lua-value [v]
  (cond
    (map? v) (str "{ " (str/join ", " (for [[k x] (sort-by (comp name key) v)]
                                          (str (name k) " = " (lua-value x)))) " }")
    (string? v) (str "\"" (str/escape v {\" "\\\"" \\ "\\\\" \newline "\\n"}) "\"")
    (keyword? v) (lua-value (name v))
    (boolean? v) (str v)
    (number? v) (str v)
    :else (throw (ex-info "Cannot render as Lua" {:value v}))))

(defn lua
  "The Lua expression for dispatcher D."
  [[path & args]]
  (str "hl.dsp." path "(" (str/join ", " (map lua-value args)) ")"))

(defn valid? [d]
  (and (vector? d) (string? (first d)) (re-matches #"[a-z_]+(\.[a-z_]+)?" (first d))
       (<= (count d) 2)
       (try (lua d) true (catch Exception _ false))))

(defn- hyprctl-dispatch [args]
  (let [{:keys [out err]} @(p/process (into ["hyprctl" "dispatch"] args) {:out :string :err :string})]
    (str/trim (str out err))))

(defn dispatch!
  "Run dispatcher D in the running Hyprland. Throws when Hyprland refuses it."
  [d]
  (let [out (hyprctl-dispatch [(lua d)])]
    (when-not (= "ok" out)
      (throw (ex-info (str "Hyprland refused " (lua d) ": " out) {:dispatcher d})))))
