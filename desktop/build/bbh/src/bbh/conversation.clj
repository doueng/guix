(ns bbh.conversation
  "A conversation's directory: where it lives, the names of the files in it,
  and the chain of rollovers. A session that fills its context continues in a
  new conversation beside it: the old one names the new one in continued-in,
  and the new one names the first of the chain, its root, in inbox-session.
  The root's mailbox/ and agent.json serve the whole chain, so a message from
  the website still finds the session after a rollover.

  Only this namespace spells the file names. agent-shell.el knows some of
  them too, and has to agree."
  (:require [babashka.fs :as fs]
            [bbh.sysenv :as sysenv]
            [clojure.string :as str]))

(defn base
  "The directory that holds every conversation."
  []
  (str (fs/path (or (sysenv/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh" "conversations")))

(defn dir
  "The directory of conversation ID, under BASE or the usual one."
  ([id] (dir (base) id))
  ([base id] (str (fs/path base id))))

(def ^:private names
  {:messages "messages.jsonl"
   :prompts "prompts.jsonl"
   :mailbox "mailbox"
   :agent "agent.json"
   :images "images"
   :repl-port "repl.port"
   :repl-log "repl.log"
   :wf-run "workflow-run"
   :jobs "jobs"
   :outputs "outputs"
   :checkpoint "checkpoint.clj"
   :deadline "deadline"
   :runs "runs.jsonl"
   :claude-session "claude-session"
   :claude-seen "claude-seen"
   :claude-log "claude.log"
   :root "inbox-session"
   :continued-in "continued-in"})

(defn file
  "The path of conversation DIR's file K, a key of the table above."
  [dir k]
  (str (fs/path dir (or (names k) (throw (ex-info (str "no conversation file " k) {:file k}))))))

(def bookkeeping
  "The files a session writes before anyone talks to it. A folder holding only
  these is not a conversation."
  (into #{} (map names) [:repl-log :repl-port :wf-run :claude-log :claude-session :claude-seen :root]))

(defn saved?
  "Whether DIR holds a conversation with a transcript."
  [dir]
  (fs/exists? (file dir :messages)))

(defn saved
  "The directories of every saved conversation under BASE, sorted, so oldest first."
  [base]
  (if (fs/exists? base) (->> (fs/list-dir base) (filter saved?) (map str) sort) []))

(defn- read-id [f]
  (when (fs/exists? f) (str/trim (slurp f))))

(defn root-id
  "The id of the first conversation of DIR's rollovers."
  [dir]
  (or (read-id (file dir :root)) (str (fs/file-name dir))))

(defn root
  "The directory of the first conversation of DIR's rollovers, which holds the
  chain's mailbox and agent.json."
  [dir]
  (str (fs/path (fs/parent dir) (root-id dir))))

(defn continue!
  "Point conversation DIR at the conversation ID beside it. Returns ID's directory."
  [dir id]
  (let [next-dir (str (fs/path (fs/parent dir) id))]
    (fs/create-dirs next-dir)
    (spit (file next-dir :root) (root-id dir))
    (spit (file dir :continued-in) id)
    next-dir))

(defn- next-dir [dir]
  (some->> (read-id (file dir :continued-in)) (fs/path (fs/parent dir)) str))

(defn continued?
  "Whether conversation DIR continued in another."
  [dir]
  (some? (next-dir dir)))

(defn continuation
  "The newest conversation DIR continued into, or DIR itself."
  [dir]
  (if-let [n (next-dir dir)] (recur n) dir))

(defn chain
  "Conversation DIR and every conversation it continued into, oldest first."
  [dir]
  (loop [dir dir dirs [dir]]
    (if-let [n (next-dir dir)] (recur n (conj dirs n)) dirs)))
