#!/usr/bin/env bb
(ns second-brain-builder
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def home (or (System/getenv "BRAIN_HOME") "/var/lib/second-brain"))
(def db (str home "/data/brain.db"))
(def lock (str home "/jobs.lock"))
(def port "8790")
(def max-bytes (* 64 1024 1024))

(defn die [& msg] (binding [*out* *err*] (apply println msg)) (System/exit 1))

(defn owner-login
  "The login that owns this node, from `tailscale status --json', or nil."
  [status]
  (let [uid (get-in status ["Self" "UserID"])]
    (get-in status ["User" (str uid) "LoginName"])))

(defn tailnet-owner []
  (try (owner-login (json/parse-string (:out (p/sh "tailscale" "status" "--json"))))
       (catch Exception _ nil)))

(defn serve []
  (let [file (str home "/owner")
        owner (or (some (fn [_] (or (tailnet-owner) (do (Thread/sleep 1000) nil))) (range 30))
                  (when (fs/exists? file) (not-empty (str/trim (slurp file)))))]
    (when-not owner (die "second-brain: no tailnet owner known; refusing to serve"))
    (spit file owner)
    (println "second-brain: serving to the builder's tailnet owner")
    (p/exec {:extra-env {"BRAIN_OWNER" owner "BRAIN_DB" db}}
            "second-brain" "serve" "--port" port)))

(def kinds
  {"chrome" {:file "chrome.json" :args ["import-chrome"]}
   "html" {:file "bookmarks.html" :args ["import-html"] :source? true}
   "x-likes" {:file "like.js" :args ["import-x" "likes"]}
   "x-export" {:file "x-export.json" :args ["import-x" "export"]}})

(defn parse-command
  "The import that SSH_ORIGINAL_COMMAND asks for, or nil: {:file :args}, with
  the file last in :args."
  [command]
  (let [[kind source & more] (str/split (str/trim (str command)) #"\s+")
        {:keys [file args source?]} (kinds kind)]
    (when (and file (empty? more)
               (if source? (and source (re-matches #"[a-z0-9][a-z0-9-]{0,31}" source)) (nil? source)))
      (let [path (str home "/inbox/" file)]
        {:file path :args (cond-> (conj args path) source? (conj source))}))))

(defn read-capped [in]
  (let [out (java.io.ByteArrayOutputStream.)
        buf (byte-array 65536)]
    (loop [total 0]
      (let [n (.read in buf)]
        (cond (neg? n) (.toByteArray out)
              (> (+ total n) max-bytes) (die "second-brain: export larger than" max-bytes "bytes")
              :else (do (.write out buf 0 n) (recur (+ total n))))))))

(defn receive []
  (let [{:keys [file args]} (or (parse-command (System/getenv "SSH_ORIGINAL_COMMAND"))
                                (die "usage: ssh second-brain@builder chrome | html SOURCE | x-likes | x-export < FILE"))
        bytes (read-capped System/in)]
    (when (zero? (count bytes)) (die "second-brain: empty export"))
    (fs/create-dirs (fs/parent file))
    (fs/write-bytes (str file ".new") bytes)
    (fs/move (str file ".new") file {:replace-existing true :atomic-move true})
    (let [r @(apply p/process {:out :inherit :err :inherit :extra-env {"BRAIN_DB" db}}
                        (concat ["flock" "-w" "600" lock "nice" "-n" "10" "second-brain"] args))]
      (System/exit (:exit r)))))

(defn step [& args]
  (println (str (java.time.Instant/now)) (str/join " " args))
  (:exit @(apply p/process {:out :inherit :err :inherit :extra-env {"BRAIN_DB" db}} args)))

(defn nightly []
  (let [heavy ["flock" "-w" "600" lock "nice" "-n" "19" "ionice" "-c" "3"
               "timeout" "3h" "second-brain"]
        enrich (apply step (concat heavy ["enrich" "--limit" "1500"]))
        export (apply step (concat heavy ["export"]))
        backup (str home "/backups/brain-" (.getValue (.getDayOfWeek (java.time.LocalDate/now))) ".db")
        saved (step "flock" "-w" "600" lock "sqlite3" db (str ".backup " backup))]
    (System/exit (if (every? zero? [enrich export saved]) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (case (first *command-line-args*)
    "serve" (serve)
    "receive" (receive)
    "nightly" (nightly)
    (die "usage: builder.bb serve | receive | nightly")))
