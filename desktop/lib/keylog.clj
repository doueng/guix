(ns keylog
  "The personal keybinding log: one tab-separated row per binding, never typed text.
  Schema 2 rows are TIME PROGRAM KEYS EFFECT KIND 2. Rows with four fields are
  schema 1, written before 2026-10-26, and parse-line normalises them."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def version 2)

;; action: a binding ran. leader: a leader started waiting for keys.
;; cancel: a leader or menu closed without acting. forward: the key went on to
;; the program that EFFECT names, "Forwarded to fish", which handles it itself.
;; PROGRAM is always the program that logged the row.
(def kinds #{"action" "leader" "cancel" "forward"})

(def ^:dynamic *silent*
  "True while a palette runs its choice, which it has already logged."
  false)

(defn path []
  (or (System/getenv "KEYLOG_FILE")
      (str (or (System/getenv "XDG_STATE_HOME") (str (fs/home) "/.local/state"))
           "/keybindings/keys.tsv")))

;; Linux truncates process names to 15 characters, and Guix's Emacs wrapper
;; shows as .emacs-31.1-rea.
(def program-names [[#"^\.?emacs" "emacs"] [#"^nvim$" "neovim"]])

(defn program-id
  "The stable id for NAME, a program or process name, such as emacs or neovim."
  [name]
  (let [n (str/lower-case (str name))]
    (or (some (fn [[re id]] (when (re-find re n) id)) program-names)
        (-> n (str/replace #"[^a-z0-9-]+" "-") (str/replace #"^-+|-+$" "")))))

(defn line
  "The schema 2 row for KIND, PROGRAM, KEYS and EFFECT at TIME, with a newline."
  [time kind program keys effect]
  (when-not (kinds kind)
    (throw (ex-info (str "Unknown keylog kind: " kind) {:kind kind})))
  (let [clean #(str/replace (str %) #"[\t\n]" " ")]
    (str (str/join "\t" (map clean [time (program-id program) keys effect kind version])) "\n")))

(defn now []
  (str (.truncatedTo (java.time.LocalDateTime/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn log!
  "Append that PROGRAM handled KEYS as KIND with EFFECT."
  [kind program keys effect]
  (when-not *silent*
    (let [p (path)]
      (fs/create-dirs (fs/parent p))
      (spit p (line (now) kind program keys effect) :append true))))

(defn- v1-row
  "Schema 1 had no kind and logged display names. Cmd+/ ran Keyguide,
  Cmd+Space only passed Ctrl+b Space on to Herdr's leader, and Herdr logged keys
  it forwarded under the receiving program."
  [[time program keys effect]]
  (let [program (if (str/starts-with? keys "Cmd+/") "keyguide" (program-id program))]
    (cond
      (= ["hyprland" "Cmd+Space"] [program keys])
      {:time time :program program :keys keys :effect "Forwarded to herdr" :kind "forward" :version 1}
      (str/starts-with? effect "Forwarded to ")
      {:time time :program "herdr" :keys keys :effect (str "Forwarded to " program) :kind "forward" :version 1}
      :else {:time time :program program :keys keys :effect effect :kind "action" :version 1})))

(defn parse-line
  "The row in LINE as a map, or nil when it is neither schema."
  [line]
  (let [fields (str/split line #"\t" -1)]
    (case (count fields)
      4 (v1-row fields)
      6 (let [[time program keys effect kind v] fields]
          (when (and (= (str version) v) (kinds kind))
            {:time time :program program :keys keys :effect effect :kind kind :version version}))
      nil)))
