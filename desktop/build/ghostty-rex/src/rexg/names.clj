(ns rexg.names
  "Readable names for what the client shows, in place of Rex's IDs (T210).
  A pane is named from what runs in it, as Herdr and tmux name tabs: a shell
  by its directory, ssh by its host, anything else by its program. INFO is
  what Rex told about one block (rexg.model's :procs entry):
  {:fg {:name :argv0 :script :cwd :pid} :title s :title-owner {:pid :name}
   :pwd path :pwd-owner {..} :home dir}, each part from block.method process,
  title and pwd, then kept current by the process_changed, title_changed and
  pwd_changed events; and :app, the app an agent in it reported (OSC 7501)."
  (:require [clojure.string :as str]))

(def shells #{"fish" "bash" "zsh" "sh" "dash" "nu" "ksh" "tcsh" "elvish" "xonsh"})

(defn- basename [p] (last (str/split (str p) #"/")))

(defn program
  "The name of the foreground program FG, as a person calls it: the script an
  interpreter runs, else argv0, else the process name, without a login
  shell's -, Guix's .NAME-real wrapper or a version (.emacs-31.1-real is
  emacs), or an extension (cli.js is cli). emacsclient is emacs."
  [fg]
  (when fg
    (let [raw (some #(when-not (str/blank? %) %) [(some-> (:script fg) basename) (some-> (:argv0 fg) basename) (:name fg)])
          n (-> (str raw)
                (str/replace #"^-+" "")
                (str/replace #"^\." "")
                (str/replace #"-(real|wrapped)$" "")
                (str/replace #"-[0-9][0-9.]*$" "")
                (str/replace #"\.(js|mjs|cjs|py|rb|pl|bb|clj)$" ""))]
      (cond (str/blank? n) nil
            (= n "emacsclient") "emacs"
            :else n))))

(def interpreters
  "Programs that run another's code: a pane running one is named by the
  script, the agent's app or the command the shell titled it with."
  #{"node" "bun" "deno" "python" "python3" "ruby" "perl" "bb" "java" "ld-linux-aarch64.so.1"})

(defn- typed-command
  "The command a shell put first in the title it set while running it, as
  fish's pi ~/guix, else nil."
  [info]
  (when (shells (some-> (:name (:title-owner info)) (str/replace #"^-" "")))
    (let [w (first (str/split (str/trim (str (:title info))) #"\s+"))]
      (when-not (or (str/blank? w) (str/starts-with? w "~") (str/starts-with? w "/") (str/includes? w "…"))
        (basename w)))))

(defn- shown-program
  "The program a pane is named by: an interpreter's script, else the app its
  agent reported or the command its shell titled it with."
  [info]
  (let [prog (program (:fg info))]
    (if (and (interpreters prog) (nil? (:script (:fg info))))
      (or (not-empty (:app info)) (typed-command info) prog)
      prog)))

(defn path-of
  "The path in an OSC 7 report, file://HOST/PATH with %XX escapes, or a
  plain path."
  [pwd]
  (when-not (str/blank? pwd)
    (if-let [[_ p] (re-matches #"file://[^/]*(/.*)" pwd)]
      (try (java.net.URLDecoder/decode (str/replace p "+" "%2B") "UTF-8") (catch Exception _ p))
      pwd)))

(defn cwd
  "The pane's directory: what the shell last reported with OSC 7 when the
  foreground process made the report, else the foreground process's own."
  [info]
  (let [fg (:fg info)]
    (or (when (and (:pwd info) (or (nil? (:pwd-owner info)) (= (:pid (:pwd-owner info)) (:pid fg))))
          (path-of (:pwd info)))
        (:cwd fg)
        (path-of (:pwd info)))))

(defn tilde [path home]
  (cond (str/blank? path) nil
        (and (seq home) (= path home)) "~"
        (and (seq home) (str/starts-with? path (str home "/"))) (str "~" (subs path (count home)))
        :else path))

(defn dir-name
  "The short name of directory PATH: its last part, ~ for HOME, / for the root."
  [path home]
  (let [t (tilde path home)]
    (cond (nil? t) nil
          (#{"~" "/"} t) t
          :else (basename (str/replace t #"/+$" "")))))

(defn short-dir
  "PATH for a title row: ~ for HOME, and only the last two parts of a deep one."
  [path home]
  (when-let [t (tilde path home)]
    (let [parts (str/split t #"/")]
      (if (> (count parts) 3) (str "…/" (str/join "/" (take-last 2 parts))) t))))

(defn ssh-host
  "The host of an ssh pane, from the title the far side set (user@host:
  ~/dir, or host alone), else nil."
  [title]
  (when-not (str/blank? title)
    (or (second (re-find #"@([A-Za-z0-9._-]+)" title))
        (when (re-matches #"[A-Za-z0-9._-]+" title) title))))

(defn- fit [s n] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(def max-name 16)

(defn pane-name
  "A pane's short name for its tab: a shell's directory, ssh's host, else the
  program. Nil when Rex has told nothing yet."
  [info home]
  (let [prog (shown-program info)
        n (cond (nil? prog) (dir-name (cwd info) home)
                (shells prog) (or (dir-name (cwd info) home) prog)
                (= "ssh" prog) (or (ssh-host (:title info)) "ssh")
                :else prog)]
    (some-> n (fit max-name))))

(defn pane-title
  "A pane's title row and status-line name: its program and directory, as
  fish ~/guix, or ssh's host."
  [info home]
  (let [prog (shown-program info)
        dir (short-dir (cwd info) home)]
    (cond (= "ssh" prog) (str "ssh " (or (ssh-host (:title info)) ""))
          (and prog dir) (str prog " " dir)
          :else (or prog dir))))

(defn rex-default-label?
  "Whether LABEL is the one Rex gives a window made without one: Window and
  the last 8 hex digits of its ID."
  [label]
  (boolean (re-matches #"Window [0-9a-f]{8}" (str label))))

(defn manual-label?
  "Whether window LABEL is a name the user gave: not blank, not Rex's
  default and not the label of a :startup tab in STARTUP, which a revive
  matches tabs by."
  [label startup]
  (not (or (str/blank? label) (rex-default-label? label) (some #(= label (:label %)) startup))))

(defn tab-name
  "The name tab WIN shows: the user's label; a startup tab's label while
  its own program runs (bbh, pi), since it says more than the program's;
  else the name of the tab's focused pane, from INFO; else the startup label
  or nil, the tab number alone. STARTUP is config.edn's :startup :windows."
  [win info home startup]
  (let [l (:label win)
        spec (some #(when (= l (:label %)) %) startup)
        prog (program (:fg info))]
    (cond (manual-label? l startup) l
          (and spec (:command spec) (not (shells prog))) l
          :else (or (when info (pane-name info home)) (when spec l)))))

(defn session-name
  "A session's name: its label, else its place in SESSIONS ([id label]) as
  session N, else session."
  [id label sessions]
  (if-not (str/blank? label)
    label
    (if-let [i (first (keep-indexed #(when (= id (first %2)) %1) sessions))]
      (str "session " (inc i))
      "session")))
