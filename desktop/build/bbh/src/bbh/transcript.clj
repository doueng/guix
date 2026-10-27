(ns bbh.transcript
  "A conversation's append-only message log, and its compactions."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- messages-file [dir]
  (str (fs/path dir "messages.jsonl")))

(defn load-messages [dir]
  (let [f (messages-file dir)]
    (if (fs/exists? f)
      (->> (fs/read-all-lines f) (remove str/blank?) (mapv #(json/parse-string % true)))
      [])))

(defn append! [dir msg]
  (fs/create-dirs dir)
  (spit (messages-file dir) (str (json/generate-string msg) "\n") :append true)
  msg)

(defn- compactions-file [dir]
  (str (fs/path dir "compactions.jsonl")))

(defn load-compaction
  "The latest {:first-kept :summary}, where :first-kept indexes the messages, or nil."
  [dir]
  (let [f (compactions-file dir)]
    (when (fs/exists? f)
      (some-> (->> (fs/read-all-lines f) (remove str/blank?) last) (json/parse-string true)))))

(defn append-compaction! [dir compaction]
  (fs/create-dirs dir)
  (spit (compactions-file dir) (str (json/generate-string compaction) "\n") :append true)
  compaction)

(defn- prompts-file [dir]
  (str (fs/path dir "prompts.jsonl")))

(defn load-prompts
  "Every recorded {:at :time :model :effort :system :tool-description}, oldest
  first, where :at is the message count when the prompt took effect."
  [dir]
  (let [f (prompts-file dir)]
    (if (fs/exists? f)
      (->> (fs/read-all-lines f) (remove str/blank?) (mapv #(json/parse-string % true)))
      [])))

(defn append-prompt! [dir prompt]
  (fs/create-dirs dir)
  (spit (prompts-file dir) (str (json/generate-string prompt) "\n") :append true)
  prompt)
