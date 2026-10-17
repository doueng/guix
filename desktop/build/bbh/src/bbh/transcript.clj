(ns bbh.transcript
  "A conversation's append-only message log."
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
