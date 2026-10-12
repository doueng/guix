(ns herdr.client
  "Run the Herdr CLI from desktop/bin scripts. Set HERDR_BIN_PATH to use a
  different herdr executable."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def bin (or (System/getenv "HERDR_BIN_PATH") "herdr"))

(defn eprintln [& values]
  (binding [*out* *err*]
    (apply println values)))

(defn fail! [message & {:keys [exit] :or {exit 1}}]
  (eprintln message)
  (System/exit exit))

(defn run!
  "Run argv, or exit with its status after printing its stderr."
  [argv & [options]]
  (let [result (try
                 @(process/process argv (merge {:out :string :err :string} options))
                 (catch Exception error
                   {:exit 127 :out "" :err (.getMessage error)}))]
    (if (zero? (:exit result))
      result
      (do
        (when (seq (:err result))
          (eprintln (:err result)))
        (System/exit (:exit result))))))

(defn call!
  "Run herdr with args and return the :result of its JSON reply."
  [& args]
  (let [argv (into [bin] args)
        output (:out (run! argv))]
    (try
      (:result (json/parse-string output true))
      (catch Exception error
        (fail! (str "invalid JSON from " (str/join " " argv) ": " (.getMessage error)))))))

(defn focused-workspace-id []
  (or (System/getenv "HERDR_ACTIVE_WORKSPACE_ID")
      (some (fn [{:keys [focused workspace_id]}]
              (when focused workspace_id))
            (:workspaces (call! "workspace" "list")))))

(defn focus-tab!
  "Focus the tab at position number (1-based) in the focused workspace."
  [number]
  (let [workspace-id (or (focused-workspace-id)
                         (fail! "no focused Herdr workspace"))
        ;; Herdr's public tab numbers remain stable after tabs are closed or
        ;; moved, so use the ordered tab list here rather than TabInfo.number.
        tab-id (nth (map :tab_id (:tabs (call! "tab" "list" "--workspace" workspace-id)))
                    (dec number) nil)]
    (when-not tab-id
      (fail! (str "no Herdr tab numbered " number " in workspace " workspace-id)))
    (run! [bin "tab" "focus" tab-id])))
