(ns checks.common
  "Shared by lint.bb and affected.bb. Paths are relative to the repo root."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.nio.file FileSystems)))

(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))))

(defn sh [& args]
  (apply p/shell {:dir root :out :string :err :string :continue true} args))

(defn lines [s] (remove str/blank? (str/split-lines (or s ""))))

(defn tracked-files []
  (let [{:keys [exit out err]} (sh "jj" "file" "list")]
    (when-not (zero? exit) (throw (ex-info (str "jj file list failed: " err) {})))
    (lines out)))

(defn changed-files [rev]
  (let [{:keys [exit out err]} (sh "jj" "diff" "--name-only" "-r" rev)]
    (when-not (zero? exit) (throw (ex-info (str "jj diff failed: " err) {})))
    (lines out)))

(defn matcher [glob]
  (let [m (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob))]
    (fn [path] (.matches m (fs/path path)))))

(defn rules []
  (:rules (edn/read-string (slurp (str root "/desktop/build/checks/checks.edn")))))

(defn matching-rules [rules path]
  (filter (fn [{:keys [paths]}] (some #((matcher %) path) paths)) rules))

(defn make-targets
  "Targets defined in the Makefile, explicit rules only."
  []
  (->> (str/split-lines (slurp (str root "/Makefile")))
       (keep #(second (re-find #"^([A-Za-z][A-Za-z0-9_-]*)\s*:(?!=)" %)))
       set))

(defn make-target-of
  "The target in a check command such as `make lint`, or nil."
  [cmd]
  (second (re-find #"^make ([A-Za-z0-9_-]+)" cmd)))
