#!/usr/bin/env bb
(ns sesh
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def plugin-id "fullerzz.sesh")
(defn env [key] (System/getenv key))
(defn home [] (System/getProperty "user.home"))
(defn herdr [] (or (env "HERDR_BIN_PATH") "herdr"))
(defn state-dir [] (or (env "HERDR_PLUGIN_STATE_DIR") (str (home) "/.local/state/herdr-sesh")))
(defn config-path []
  (some #(when (and % (fs/exists? %)) %)
        [(env "HERDR_SESH_CONFIG")
         (some-> (env "HERDR_PLUGIN_CONFIG_DIR") (str "/sesh.toml"))
         (str (home) "/.config/herdr/sesh.toml")]))

(defn run [argv & [options]]
  (let [result @(process/process argv (merge {:out :string :err :string} options))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str (str/join " " argv) ": " (str/trim (:err result)))
                      {:exit (:exit result)})))
    (:out result)))

(defn herdr-json [& args]
  (let [response (json/parse-string (run (into [(herdr)] args)) true)]
    (when-let [error (:error response)] (throw (ex-info (str "Herdr: " (:message error)) error)))
    (:result response)))

(defn workspaces [] (:workspaces (herdr-json "workspace" "list")))
(defn current-id [items]
  (or (env "HERDR_WORKSPACE_ID")
      (some #(when (:focused %) (:workspace_id %)) items)))
(defn workspace-id [response] (or (get-in response [:workspace :workspace_id])
                                   (get-in response [:workspace :id])))
(defn pane-id [response] (or (get-in response [:root_pane :pane_id])
                              (get-in response [:root_pane :id])))

;; Only the settings used by this local plugin are read from sesh.toml. Keep
;; config in Herdr's plugin directory so home activation can link it as before.
(defn toml-string [line key]
  (when-let [[_ value] (re-matches (re-pattern (str "\\s*" key "\\s*=\\s*\"(.*)\"\\s*")) line)]
    (-> value (str/replace "\\\"" "\"") (str/replace "\\\\" "\\"))))
(defn settings [text]
  (reduce (fn [cfg line]
            (let [line (str/trim line)
                  section (cond (= line "[[window]]") :window
                                (= line "[[wildcard]]") :wildcard
                                (str/starts-with? line "[") :other
                                :else (:section cfg))
                  value (or (toml-string line "name") (toml-string line "startup_script")
                            (toml-string line "pattern"))
                  key (cond (toml-string line "name") :name
                            (toml-string line "startup_script") :startup-script
                            (toml-string line "pattern") :pattern)]
              (cond-> (assoc cfg :section section)
                (= line "[[window]]") (update :windows conj {})
                (= line "[[wildcard]]") (update :wildcards conj {})
                (and key (= section :window)) (update :windows (fn [xs] (update xs (dec (count xs)) assoc key value)))
                (and key (= section :wildcard)) (update :wildcards (fn [xs] (update xs (dec (count xs)) assoc key value)))
                (and (= section :wildcard) (str/starts-with? line "windows ="))
                (update :wildcards (fn [xs] (update xs (dec (count xs)) assoc :windows
                                                    (mapv second (re-seq #"\"([^\"]+)\"" line))))))))
          {:section nil :windows [] :wildcards []} (str/split-lines text)))
(defn config [] (settings (if-let [path (config-path)] (slurp path) "")))
(defn startup-windows [cfg path]
  (let [names (->> (:wildcards cfg)
                   (filter #(or (= "/**" (:pattern %)) (= path (:pattern %))))
                   (mapcat :windows) set)]
    (filter #(contains? names (:name %)) (:windows cfg))))

(defn history-file [] (str (state-dir) "/previous-workspace"))
(defn previous-id []
  (try (str/trim (slurp (history-file))) (catch Exception _ nil)))
(defn record-switch! [from to]
  (when (and (seq from) (seq to) (not= from to))
    (fs/create-dirs (state-dir))
    (spit (history-file) from)))
(defn focus! [items id]
  (when (and (seq id) (not= id (current-id items)))
    (herdr-json "workspace" "focus" id)
    (record-switch! (current-id items) id)))
(defn last! []
  (let [items (workspaces) id (previous-id)]
    (when (some #(= id (:workspace_id %)) items)
      (focus! items id))))

(defn zoxide-paths []
  (try (->> (str/split-lines (run ["zoxide" "query" "-l" "-s"]))
            (keep (fn [line] (second (re-matches #"\s*[\d.]+\s+(.+)" line)))))
       (catch Exception _ [])))
(defn sessions [items]
  (let [open (mapv (fn [w] {:source :herdr :id (:workspace_id w) :name (:label w)}) items)
        names (set (map :name open))]
    (into open (for [path (zoxide-paths)
                     :when (and (fs/directory? path) (not (contains? names (str (fs/file-name path)))))]
                 {:source :zoxide :path path :name (str (fs/file-name path))}))))
(defn escape-row [s] (str/replace (str s) #"[\t\r\n]" " "))
(defn picker-choice [rows]
  (let [input (str/join "\n" (map-indexed (fn [i {:keys [source name path]}]
                                            (str i "\t" (escape-row (format "%-8s %-24s %s" (clojure.core/name source) name (or path ""))))) rows))
        result @(process/process ["fzf" "--border" "--layout" "reverse" "--no-multi"
                                  "--no-sort" "--delimiter" "\t" "--with-nth" "2"
                                  "--prompt" "Sesh> " ]
                                 {:in input :out :string :err :inherit})]
    (when (zero? (:exit result))
      (when-let [index (some-> (:out result) (str/split #"\t" 2) first parse-long)]
        (get rows index)))))

(defn connect! [target]
  (let [items (workspaces)
        choices (sessions items)
        match (some #(when (or (= target (:id %)) (= target (:name %)) (= target (:path %))) %) choices)]
    (if (and match (:id match))
      (focus! items (:id match))
      (let [path (or (:path match) (when (fs/directory? target) (str (fs/absolutize target))))]
        (when-not (and path (fs/directory? path))
          (throw (ex-info (str "No session or directory: " target) {})))
        (let [name (or (:name match) (str (fs/file-name path)))
              created (herdr-json "workspace" "create" "--cwd" path "--label" name "--no-focus")
              id (workspace-id created)]
          (when-not id (throw (ex-info "Workspace create returned no ID" created)))
          (try (run ["zoxide" "add" path]) (catch Exception _ nil))
          (doseq [[index {:keys [name startup-script]}] (map-indexed vector (startup-windows (config) path))]
            (let [tab (herdr-json "tab" "create" "--workspace" id "--cwd" path
                                  "--label" name (if (zero? index) "--focus" "--no-focus"))]
              (when (seq startup-script)
                (when-not (pane-id tab) (throw (ex-info "Tab create returned no pane ID" tab)))
                (herdr-json "pane" "run" (pane-id tab) startup-script))))
          (focus! items id))))))

(defn picker! []
  (let [rows (sessions (workspaces))]
    (when-let [choice (and (seq rows) (picker-choice rows))]
      (connect! (or (:id choice) (:path choice))))))
(defn main [args]
  (case (first args)
    "plugin" (if (= (second args) "open-picker")
               (herdr-json "plugin" "pane" "open" "--plugin" plugin-id
                           "--entrypoint" "picker" "--placement" "overlay" "--focus")
               (throw (ex-info "Usage: herdr-sesh plugin open-picker" {})))
    "picker" (picker!)
    "last" (last!)
    "connect" (if-let [target (second args)] (connect! target)
                   (throw (ex-info "Usage: herdr-sesh connect TARGET" {})))
    (throw (ex-info "Usage: herdr-sesh plugin open-picker|picker|last|connect TARGET" {}))))

(when (= *file* (System/getProperty "babashka.file"))
  (try (main *command-line-args*)
       (catch Exception e
         (binding [*out* *err*] (println "sesh:" (.getMessage e)))
         (System/exit 1))))
