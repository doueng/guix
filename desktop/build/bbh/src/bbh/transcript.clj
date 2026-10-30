(ns bbh.transcript
  "A conversation's append-only message log, and its links to its continuations."
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

(defn new-id
  "A new conversation's id, its start time."
  []
  (.format (java.time.LocalDateTime/now) (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS")))

(defn inbox-id
  "The name conversation DIR goes by in the Plannotator Inbox, the root of its
  rollovers, so a reply still finds the session after a rollover."
  [dir]
  (let [f (fs/path dir "inbox-session")]
    (if (fs/exists? f) (str/trim (slurp (str f))) (str (fs/file-name dir)))))

(defn continue!
  "Point conversation DIR at the conversation ID beside it. Returns ID's directory."
  [dir id]
  (let [next-dir (str (fs/path (fs/parent dir) id))]
    (fs/create-dirs next-dir)
    (spit (str (fs/path next-dir "inbox-session")) (inbox-id dir))
    (spit (str (fs/path dir "continued-in")) id)
    next-dir))

(defn continuation
  "The newest conversation DIR continued into, or DIR itself."
  [dir]
  (let [f (fs/path dir "continued-in")]
    (if (fs/exists? f)
      (recur (str (fs/path (fs/parent dir) (str/trim (slurp (str f))))))
      dir)))

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
