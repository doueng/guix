(ns bbh.super.emacs
  "The super-agent's surfaces live in one terminal Emacs daemon, `bbh-super`:
  a buffer per Worker, the Router and the Inbox. The controller reaches it with
  emacsclient. There is no other place to put them, so a daemon that is not
  running is an error that says how to start it."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(defn server
  "The Emacs server name the super-agent's daemon runs under: bbh-super, or
  BBH_EMACS_SERVER, which the test runner points at a daemon that does not
  exist so no test reaches yours. Read from the process environment, which
  the test fixture's hermetic getenv does not cover."
  []
  (or (System/getenv "BBH_EMACS_SERVER") "bbh-super"))

(def ^:dynamic *emacsclient* "emacsclient")

(def timeout-seconds
  "How long a call waits for the daemon. A daemon that does not answer, such
  as one stuck on a prompt, is then an error rather than a stuck controller."
  30)

(defn- elisp-string [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn elisp
  "Clojure data as an elisp form: strings, keywords as :keywords, symbols,
  numbers, nil, sequential collections as lists, maps as alists of strings."
  [x]
  (cond (nil? x) "nil"
        (true? x) "t"
        (string? x) (elisp-string x)
        (keyword? x) (str ":" (name x))
        (symbol? x) (str x)
        (number? x) (str x)
        (map? x) (str "'(" (str/join " " (for [[k v] x] (str "(" (elisp-string (name k)) " . " (elisp-string v) ")"))) ")")
        (sequential? x) (str "(" (str/join " " (map elisp x)) ")")
        :else (throw (ex-info (str "No elisp for " (pr-str x)) {}))))

(defn call!
  "Call elisp function F with ARGS in the daemon and return what it printed.
  Throws {:error :emacs} with emacsclient's message when the daemon is not
  running or the call signals."
  [f & args]
  (let [form (str "(" f (when (seq args) " ") (str/join " " (map elisp args)) ")")
        {:keys [exit out err]} (p/shell {:out :string :err :string :continue true}
                                        *emacsclient* "-s" (server) (str "--timeout=" timeout-seconds) "--eval" form)]
    (when-not (zero? exit)
      (throw (ex-info (str "Emacs daemon " (server) ": " (str/trim (str err out))
                           (when (re-find #"(?i)socket|server" (str err))
                             (str ". Start it with `herd start bbh-emacs`, or by hand with `~/.local/bin/emacs --daemon=" (server) "`.")))
                      {:error :emacs :form form})))
    (str/trim out)))

(defn alive?
  "Whether the daemon answers within a few seconds."
  []
  (try (zero? (:exit (p/shell {:out :string :err :string :continue true}
                              *emacsclient* "-s" (server) "--timeout=5" "--eval" "t")))
       (catch Exception _ false)))

(defn read-string*
  "An elisp string printed by emacsclient, unquoted."
  [s]
  (if (and (str/starts-with? s "\"") (str/ends-with? s "\""))
    (-> (subs s 1 (dec (count s))) (str/replace "\\\"" "\"") (str/replace "\\\\" "\\"))
    s))

(defn open!
  "Open the bbh session of conversation CONVERSATION in the daemon, in DIR,
  as buffer TITLE, with ENV (a map of environment variables) added to its
  process. A live session of that conversation is kept. Returns the buffer
  name."
  [{:keys [conversation dir title env]}]
  (read-string* (call! "eng/bbh-super-open" conversation dir title env)))

(defn attach!
  "Replace this process with a terminal Emacs frame on the daemon running FORM."
  [form]
  (p/exec *emacsclient* "-s" (server) "-t" "--eval" form))
