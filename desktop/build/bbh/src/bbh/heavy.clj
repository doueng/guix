(ns bbh.heavy
  "The machine-wide heavy-job slot. Test suites, guix builds, TLC, browsers
  and eval replays each take a few hundred MB to a few GB, and several at once
  froze the laptop (T108). A heavy command runs under flock(1) on one lock
  file, so the next waits, printing who holds the slot, and gives up after a
  timeout with exit 75. The lock dies with its holder, so a crash frees it.

  A command that runs inside a held slot gets BBH_HEAVY_SLOT in its
  environment, so a heavy command it starts, such as bb test under make
  verify, runs at once instead of waiting for its own parent."
  (:require [babashka.fs :as fs]
            [bbh.sysenv :as sysenv]
            [clojure.string :as str]))

(def held-var "BBH_HEAVY_SLOT")

(defn default-timeout-s
  "How long a heavy command waits for the slot before it gives up:
  BBH_HEAVY_TIMEOUT_S, else 30 minutes."
  []
  (or (some-> (sysenv/getenv "BBH_HEAVY_TIMEOUT_S") parse-long) 1800))

(def timeout-exit
  "The exit status of a command that timed out waiting, EX_TEMPFAIL."
  75)

(def ^:private heavy-re
  (re-pattern
   (str "(?x)"
        "(^|[\\s;&|(])("
        "bb\\s+(test(:[\\w-]+)?|verify:tla|eval:(replay|fork|tools|contracts|review|judge|graders|agreement))"
        "|make\\s+(\\S+\\s+)*?(verify|check|build|dry-run|home-build|repro-build|switch|home-apply|doom-check|bbh-tla-check|pi-sci-test|dusk-test|dusk-verify|keybindings-check|hypr-nested-check)"
        "|guix\\s+(build|system|home|pull|package|time-machine)"
        "|doom\\s+(sync|doctor|build|upgrade)"
        "|tlc|tla2tools|chrome|chromium|com\\.google\\.Chrome|playwright"
        "|(\\S*/)?emacs-throwaway"
        ")(?=$|[\\s;&|)])")))

(defn heavy?
  "Whether shell command CMD runs something heavy: a test suite, a guix or
  make build, TLC, a browser or an eval replay."
  [cmd]
  (boolean (and (string? cmd) (re-find heavy-re cmd))))

(defn held?
  "Whether this process already runs inside the slot."
  []
  (some? (sysenv/getenv held-var)))

(defn lock-file
  "The slot's lock: BBH_HEAVY_LOCK, else heavy.lock in the bbh super directory."
  []
  (or (sysenv/getenv "BBH_HEAVY_LOCK")
      (str (fs/path (or (sysenv/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh" "super" "heavy.lock"))))

(defn holder-file [lock] (str lock ".holder"))

(defn who
  "Who is asking, for the waiting message: the Worker (W108) or conversation
  of this bbh session, else the pid."
  []
  (let [conv (some-> (sysenv/getenv "BBH_PORT_FILE") fs/parent fs/file-name str)]
    (cond (nil? conv) (str "pid " (.pid (java.lang.ProcessHandle/current)))
          (str/starts-with? conv "super-") (subs conv 6)
          :else conv)))

(defn- q
  "S quoted for bash."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn label
  "The first line of CMD, cut to 80 characters, for the holder file."
  [cmd]
  (let [l (str/trim (first (str/split-lines (str cmd " "))))]
    (if (> (count l) 80) (str (subs l 0 77) "...") l)))

(defn wrap
  "A bash script that runs the bash script CMD in the heavy-job slot: at once
  when free, else after printing who holds it and waiting up to TIMEOUT-S
  seconds, exiting 75 if it stays taken. The exit status is CMD's. The lock's
  descriptor is closed for CMD, so a process CMD leaves behind cannot hold the
  slot."
  [cmd & {:keys [who timeout-s lock]}]
  (let [timeout-s (or timeout-s (default-timeout-s))
        lock (or lock (lock-file))
        holder (holder-file lock)
        who (or who (bbh.heavy/who))]
    (str "mkdir -p " (q (str (fs/parent lock))) "\n"
         "exec 9>>" (q lock) "\n"
         "if ! flock -n 9; then\n"
         "  echo \"bbh: waiting for the heavy-job slot held by $(cat " (q holder) " 2>/dev/null || echo another process); one heavy command runs at a time (timeout " timeout-s " s)\" >&2\n"
         "  if ! flock -w " timeout-s " 9; then\n"
         "    echo \"bbh: gave up after " timeout-s " s waiting for the heavy-job slot held by $(cat " (q holder) " 2>/dev/null || echo another process)\" >&2\n"
         "    exit " timeout-exit "\n"
         "  fi\n"
         "fi\n"
         "printf '%s\\n' " (q (str who ": " (label cmd))) "\" since $(date +%H:%M:%S), pid $$\" > " (q holder) "\n"
         "export " held-var "=1\n"
         "bash -c " (q cmd) " bash 9>&-\n")))

(defn command
  "CMD as it should run: wrapped in the slot when heavy? and not already held?."
  [cmd & opts]
  (if (and (heavy? cmd) (not (held?))) (apply wrap cmd opts) cmd))

(defn reexec-held!
  "Unless this process already holds the slot, replace it with ARGV run in the
  slot, so a bb task such as bb test takes the slot however it was started.
  Returns only when the slot is held."
  [argv]
  (when-not (held?)
    ((requiring-resolve 'babashka.process/exec) "bash" "-c" (wrap (str/join " " (map q argv))))))
