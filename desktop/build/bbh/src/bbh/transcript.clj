(ns bbh.transcript
  "A conversation's append-only message log and its prompts."
  (:require [babashka.fs :as fs]
            [bbh.conversation :as conversation]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- messages-file [dir] (conversation/file dir :messages))

(defn load-messages [dir]
  (let [f (messages-file dir)]
    (if (fs/exists? f)
      (->> (fs/read-all-lines f) (remove str/blank?) (mapv #(json/parse-string % true)))
      [])))

(defn append! [dir msg]
  (fs/create-dirs dir)
  (spit (messages-file dir) (str (json/generate-string msg) "\n") :append true)
  msg)

(defn new-id
  "A new conversation's id, its start time."
  []
  (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS")))

(defn- prompts-file [dir] (conversation/file dir :prompts))

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
