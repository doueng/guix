(ns bbh.auth
  "Credentials shared with Pi and Claude Code."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- read-json [path]
  (let [f (str (fs/expand-home path))]
    (when (fs/exists? f)
      (json/parse-string (slurp f) true))))

(defn pi-entry
  "PROVIDER's entry in Pi's auth.json."
  [provider]
  (get (read-json "~/.pi/agent/auth.json") (keyword provider)))

(defn resolve-key
  "Pi's convention: !CMD runs CMD, an environment variable name reads that
  variable, and anything else is the key itself."
  [v]
  (cond (str/starts-with? v "!") (str/trim (:out (p/shell {:out :string} "bash" "-c" (subs v 1))))
        (System/getenv v) (System/getenv v)
        :else v))

(defn api-key
  "ENV-VAR, or PROVIDER's key from Pi's auth.json."
  [env-var provider]
  (or (System/getenv env-var)
      (some-> (pi-entry provider) :key resolve-key)))

(defn oauth-token
  "PROVIDER's unexpired OAuth access token from Pi's auth.json."
  [provider]
  (let [{:keys [access expires]} (pi-entry provider)]
    (when (and access (> (or expires 0) (System/currentTimeMillis)))
      access)))

(defn claude-oauth
  "Claude Code's subscription login, refreshed by Claude Code itself."
  []
  (:claudeAiOauth (read-json "~/.claude/.credentials.json")))
