(ns mux
  "Which terminal multiplexer the desktop uses, Herdr or Rex (T207), and how
  a program finds the Rex pane it runs in.

  The choice is one word in ~/.config/engstrand/mux, written by Guix Home from
  %terminal-mux in modules/engstrand/config.scm: herdr (the default, also when
  the file is missing), rex (the rexmux window) or ghostty-rex (Ghostty with
  the bb ghostty-rex client). Hyprland, notify.clj and the launch scripts read
  it; a program inside a pane follows the pane it is in (REX_BLOCK or
  HERDR_PANE_ID), whatever the file says."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def backends #{:herdr :rex :ghostty-rex})

(defn config-file []
  (str (fs/path (or (System/getenv "XDG_CONFIG_HOME") (fs/path (fs/home) ".config")) "engstrand" "mux")))

(defn parse
  "The backend named by TEXT, the mux file's contents; :herdr for anything else."
  [text]
  (let [k (keyword (str/trim (str text)))]
    (if (backends k) k :herdr)))

(defn backend
  "The configured backend: MUX in the environment, else the mux file, else :herdr."
  []
  (parse (or (not-empty (System/getenv "MUX"))
             (let [f (config-file)] (when (fs/exists? f) (slurp f))))))

(def window-class
  "The Hyprland class of each backend's terminal window."
  {:herdr "com.mitchellh.ghostty.herdr" :rex "rexmux" :ghostty-rex "dev.engstrand.Rex"})

(def rex-client
  "The client label each Rex backend connects with, for rex -C LABEL do."
  {:rex "rexmux" :ghostty-rex "ghostty-rex"})

(defn rex-block
  "The Rex block the caller runs in: REX_BLOCK, when TERM_PROGRAM says Rex is
  the innermost terminal (a Herdr inside Rex, or the reverse, sets its own)."
  []
  (when (= "rex" (System/getenv "TERM_PROGRAM"))
    (not-empty (System/getenv "REX_BLOCK"))))
(defn rex-session [] (not-empty (System/getenv "REX_SESSION")))

(defn rex-call!
  "Call Rex API METHOD on the caller's session with PARAMS (a map); returns
  the parsed result. Throws with Rex's message on failure."
  [method params]
  (let [argv (cond-> ["rex" "api" "call"]
               (rex-session) (into ["-s" (rex-session)])
               true (into [method (json/generate-string params)]))
        {:keys [exit out err]} @(p/process argv {:out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "rex " method ": " (str/trim (str err))) {:method method :exit exit})))
    (json/parse-string out true)))

(defn rex-view [] (rex-call! "session.view" {}))

(defn active-window [view]
  (let [ws (:windows view)]
    (or (some #(when (= (:active_window_id view) (:window_id %)) %) ws) (first ws))))

(defn rex-foreground
  "The name of the program in the foreground of BLOCK."
  [block]
  (get-in (rex-call! "block.method" {:block_id block :method "process"}) [:foreground :name]))

(defn rex-focus!
  "Move focus DIRECTION from the focused block. True when it moved; false at
  the layout's edge or when the window is zoomed, where Rex leaves the
  revision as it was."
  [direction]
  (let [view (rex-view)]
    (if (:zoomed_block_id (active-window view))
      false
      (let [after (rex-call! "session.focus_direction" {:direction direction})]
        (> (long (:revision after 0)) (long (:revision view 0)))))))

(defn rex-send-key! [block chord]
  (let [argv (cond-> ["rex" "send-key" "-b" block] (rex-session) (into ["-s" (rex-session)]) true (conj chord))
        {:keys [exit err]} @(p/process argv {:out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "rex send-key: " (str/trim (str err))) {:block block :chord chord})))))
