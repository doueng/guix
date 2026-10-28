#!/usr/bin/env bb
;; Presses real keys through kanata on the live desktop. It changes workspaces
;; and opens the Herdr menu briefly, then restores the original workspace.
(require '[babashka.classpath :as classpath]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])
(classpath/add-classpath (str (fs/path (fs/parent (fs/real-path *file*)) "../../lib")))
(require '[hypr.dispatch :as hypr])

(defn run [& args]
  (let [{:keys [exit out err]} @(p/process (vec args) {:out :string :err :string})]
    (when-not (zero? exit) (throw (ex-info "Command failed" {:args args :error err})))
    out))
(defn query [command] (json/parse-string (run "hyprctl" "-j" command) true))
(defn submap [] (str/replace (str/trim (run "hyprctl" "submap")) "\"" ""))
(defn launcher? []
  (boolean (some #(= "dusk-launcher" (:namespace %))
                 (mapcat #(mapcat val (:levels (val %))) (query "layers")))))
(defn wait-for [predicate]
  (loop [attempt 0]
    (cond (predicate) true
          (= attempt 40) false
          :else (do (Thread/sleep 50) (recur (inc attempt))))))
(defn keys! [& codes] (apply run "ydotool" "key" codes))
(def keylog (str/trim (run (str (fs/home) "/.local/bin/keylog") "path")))
(defn recent-log [] (when (fs/exists? keylog) (take-last 5 (fs/read-all-lines keylog))))

(def original (:id (query "activeworkspace")))
(assert (not (launcher?)) "Close the current Dusk menu before verification")
(try
  (assert (str/blank? (run "hyprctl" "configerrors")) "Hyprland has configuration errors")
  (keys! "29:1" "56:1" "11:1" "11:0" "56:0" "29:0")
  (assert (wait-for #(= 10 (:id (query "activeworkspace")))) "Ctrl+Alt+0 did not select workspace 10")
  (keys! "125:1" "57:1" "57:0" "125:0")
  (assert (wait-for #(= "leader" (submap))) "Cmd+Space did not enter the desktop leader")
  (keys! "1:1" "1:0")
  (assert (wait-for #(= "default" (submap))) "Escape did not leave the desktop leader")
  (keys! "125:1" "53:1" "53:0" "125:0")
  (assert (wait-for launcher?) "Cmd+/ did not open the global guide")
  (Thread/sleep 300)
  (keys! "1:1" "1:0")
  (assert (wait-for #(not (launcher?))) "Escape did not close the global guide")
  (keys! "56:1" "57:1" "57:0" "56:0")
  (assert (wait-for #(= "com.mitchellh.ghostty.herdr" (:class (query "activewindow"))))
          "Alt+Space did not focus the Herdr terminal")
  (assert (wait-for #(some (fn [line] (str/includes? line "\tAlt+Space\tForwarded to herdr\tforward\t2")) (recent-log)))
          "Alt+Space did not log forwarding to Herdr")
  (Thread/sleep 700)
  (keys! "1:1" "1:0")
  (println "Verified Ctrl+Alt+0, Cmd+Space, Cmd+/ and Alt+Space on the live desktop")
  (finally
    (when (launcher?) (keys! "1:1" "1:0"))
    (hypr/dispatch! ["focus" {:workspace original}])))
