(ns bbh.bombadil
  "Bombadil drives the bbh Inbox in a real terminal (T174). A throwaway
  controller in a temp root churns fake Workers, a capped `emacs -Q -nw`
  shows its split Inbox, and Bombadil types into that PTY and checks the
  properties in test/bombadil/inbox.ts on every screen.

    bb test:bombadil [--time 120] [--seed N] [--reproduce DIR] [--keep]

  Nothing here reaches your live stack: HOME and the XDG dirs are a temp root
  (T75), BBH_ and WF_ session variables are dropped, and BBH_EMACS_SERVER
  names a daemon that does not exist (T163). A failure prints the trace
  directory and the seed, and --reproduce replays that trace's keys against
  a fixture churned from the same seed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [clojure.string :as str]))

(def ^:private here (fs/parent (fs/parent (fs/absolutize *file*))))
(def ^:private checkout (str (fs/canonicalize (fs/path here ".." ".." ".." ".."))))
(def ^:private inside-var "BBH_BOMBADIL_ROOT")

;; ---- options --------------------------------------------------------------

(defn- parse [args]
  (loop [[a v & more :as args] args
         o {:time 120 :seed (rand-int 1000000) :keep false :rows 40 :columns 140}]
    (case a
      nil o
      "--time" (recur more (assoc o :time (parse-long v)))
      "--seed" (recur more (assoc o :seed (parse-long v)))
      "--reproduce" (recur more (assoc o :reproduce (str (fs/absolutize v))))
      "--keep" (recur (cons v more) (assoc o :keep true))
      (throw (ex-info (str "bb test:bombadil: unknown argument " a) {})))))

(defn- mem-available-mb []
  (some->> (str/join "\n" (fs/read-all-lines "/proc/meminfo")) (re-find #"MemAvailable:\s+(\d+) kB") second parse-long (#(quot % 1024))))

(def ^:private emacs-mem-mb 1536)

;; ---- the binary -----------------------------------------------------------

(defn- bombadil-bin
  "Bombadil from PATH, else the pinned Guix package built from modules/."
  []
  (or (some-> (fs/which "bombadil") str)
      (let [{:keys [exit out err]} (p/shell {:out :string :err :string :continue true :dir checkout}
                                            "guix" "build" "-L" "modules" "-e" "(@ (engstrand packages bombadil) bombadil)")]
        (when-not (zero? exit) (throw (ex-info (str "building bombadil failed: " err) {})))
        (str (fs/path (last (str/split-lines (str/trim out))) "bin" "bombadil")))))

;; ---- the fixture: a controller and fake Workers ---------------------------

(def ^:private words
  ["alder" "birch" "cedar" "dogwood" "elm" "fir" "ginkgo" "hazel" "ivy" "juniper" "larch" "maple"])

(defn- fixture!
  "Start a controller on ROOT's data dir with a stub launch, then churn fake
  Workers from SEED until the returned stop fn runs. Every ask's text is
  unique, so the screen says which ask it is."
  [root seed log]
  (let [super (requiring-resolve 'bbh.super/start!)
        stop (requiring-resolve 'bbh.super/stop!)
        call (requiring-resolve 'bbh.super.client/call!)
        dir (str (fs/path root ".local" "share" "bbh" "super"))
        conversations (str (fs/path root ".local" "share" "bbh" "conversations"))
        tokens (atom {})
        c (super {:dir dir :conversations conversations :scan-ms 50
                  :launch {:workspace! (fn [_ w] (str (fs/create-dirs (fs/path root "ws" (str (:thread w) "-" (:generation w))))))
                           :open! (fn [spec] (swap! tokens assoc (:conversation spec) (:token spec)) "buf")
                           :land! (fn [_] {:landed nil}) :retire! (constantly nil) :release! (constantly nil)}})
        rng (java.util.Random. seed)
        pick #(nth % (.nextInt rng (count %)))
        asks (atom 0)
        running (atom true)
        say (fn [& xs] (spit log (str (str/join " " xs) "\n") :append true))
        owner (fn [cmd args] (call dir {:command cmd :args args}))
        token-of (fn [w] (get @tokens (str "super-" w)))
        worker (fn [w cmd args] (call dir {:token (token-of w) :command cmd :args args}))
        live (fn [] (->> (:ok (owner :threads {}))
                         (keep (fn [t] (when (#{:working :waiting} (:execution t)) (:worker t))))
                         (filter token-of) vec))
        new-thread! (fn []
                      (let [rt (owner :thread! {:title (str "Fixture thread about " (pick words))})
                            t (:ok rt)
                            rw (owner :start-worker! {:thread t :workflow :investigation :brief "churn" :repo root})]
                        (say "thread" (pr-str rt) (pr-str rw))))
        ask! (fn [w]
               (let [n (swap! asks inc)
                     text (format "q%03d %s?" n (pick words))]
                 (say "ask" w text (worker w :ask! {:kind :question :text text :stopped (.nextBoolean rng)
                                                    :options [{:id "y" :label "Yes"} {:id "n" :label "No"}]
                                                    :recommended "y"}))))
        take-mail! (fn []
                     ;; A Worker's session takes its mailbox; a reply in it has arrived.
                     (doseq [f (fs/glob conversations "super-W*/mailbox/*.json")] (fs/delete-if-exists f)))
        tick! (fn []
                (take-mail!)
                (let [ws (live) r (.nextInt rng 10)]
                  (cond (or (< (count ws) 3) (and (< (count ws) 6) (< r 2))) (new-thread!)
                        (< r 6) (ask! (pick ws))
                        (< r 8) (let [w (pick ws)] (say "note" w (worker w :note! {:text (str "looking at " (pick words))})))
                        :else (let [w (pick ws)] (say "finish" w (worker w :finish-worker! {:state-after :done :reason "fixture"}))))))
        churn (doto (Thread. #(do (dotimes [_ 3] (try (tick!) (catch Exception e (say "error" (ex-message e)))))
                                  (while @running
                                    (try (tick!) (catch Exception e (say "error" (ex-message e))))
                                    (Thread/sleep (+ 800 (.nextInt rng 1700))))))
                (.setDaemon true) (.start))]
    (fn []
      (reset! running false)
      (.join churn 5000)
      (stop c))))

;; ---- the run --------------------------------------------------------------

(defn- run-bombadil! [{:keys [time seed reproduce keep rows columns]} root]
  (let [log (str (fs/path root "fixture.log"))
        trace (str (fs/path root "trace"))
        emacs (System/getenv "BBH_BOMBADIL_EMACS")
        stop (fixture! root seed log)]
    (try
      (let [cmd (concat [(System/getenv "BBH_BOMBADIL_BIN") "terminal" "test"
                         "--specification" (str (fs/path here "bombadil" "inbox.ts"))
                         "--time-limit" (str time "s") "--exit-on-violation"
                         "--columns" (str columns) "--rows" (str rows)
                         "--quiescence-timeout-ms" "100"
                         "--output-path" trace "--output-path-overwrite"]
                        (when reproduce ["--reproduce" reproduce])
                        ;; A soft cap: Emacs gets an allocation error past it, while the bbh it
                         ;; runs lifts it again, since babashka's isolate cannot start under it.
                         ["--" "prlimit" (str "--as=" (* emacs-mem-mb 1024 1024) ":unlimited") "timeout" (str (+ time 60))
                         emacs "-Q" "-nw" "-l" (str (fs/path here "bombadil" "inbox.el"))])
            started (System/currentTimeMillis)
            {:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true :in nil} cmd)
            secs (quot (- (System/currentTimeMillis) started) 1000)
            text (str out "\n" err)
            _ (spit (str (fs/path root "bombadil.log")) text)
            keys (some->> (fs/glob trace "**trace.jsonl") first str
                          (#(with-open [r (clojure.java.io/reader %)]
                              (mapv (fn [line] (second (re-find #"^\{[^{]*\"action\":\{\"TypeText\":\{\"text\":\"((?:[^\"\\]|\\.)*)\"" line)))
                                    (line-seq r)))))
            states (count keys)]
        ;; Bombadil draws the screen in color after each state; keep the words.
        (println (str/join "\n" (take-last 120 (remove #(str/includes? % "\u001b[") (str/split-lines (str/trim text))))))

        (println (format "bombadil: exit %d after %d s, %s states, seed %d" exit secs (or states "?") seed))
        ;; Which keys the generators reached, so a property whose key never came is visible.
        (println "keys:" (->> (frequencies (clojure.core/keep #(case %
                                                      nil nil "\\u000e" "C-n" "\\u0003\\u0003" "C-c C-c" "\\u001b" "ESC"
                                                      "\\u0007" "C-g" "\\t" "TAB" " " "SPC"
                                                      (if (re-matches #"[a-z]{3,6}" %) "word" %))
                                                   keys))
                                (sort-by (comp - val)) (map (fn [[k n]] (str k " " n))) (str/join ", ")))
        (when (or keep (not (zero? exit)))
          (println "fixture log:" log)
          (println "trace:" trace))
        (when-not (zero? exit)
          (println (str "reproduce: bb test:bombadil --seed " seed " --reproduce " trace " --keep")))
        exit)
      (finally (stop)))))

(defn- outside!
  "Make the temp root and run this namespace again in it, with hermetic HOME
  and XDG dirs, no session variables and a dead Emacs server name."
  [args]
  (let [o (parse args)
        available (mem-available-mb)
        _ (when (and available (< available (+ emacs-mem-mb 1024)))
            (binding [*out* *err*] (println "bb test:bombadil: only" available "MB available; wait for other checks"))
            (System/exit 75))
        root (str (fs/create-temp-dir {:prefix "bbh-bombadil"}))
        home (System/getProperty "user.home")
        sqlite (str (fs/path home ".guix-home/profile/lib/libsqlite3.so.0"))
        env (merge (into {} (remove (fn [[k _]] (re-find #"^(BBH_|WF_|PI_CODING_AGENT_|EMACS_SOCKET_NAME|INSIDE_EMACS)" k))) (System/getenv))
                   (hermetic/dirs root)
                   {inside-var root
                    "BBH_EMACS_SERVER" (str "bbh-bombadil-none-" (fs/file-name root))
                    "TERM" "xterm-256color"
                    "HERDR_ENV" ""
                    "BBH_BOMBADIL_BBH" (str (fs/path checkout "desktop" "bin" "bbh"))
                    "BBH_ELISP_BUILD" (or (System/getenv "BBH_ELISP_BUILD")
                                          (str home "/.config/emacs/.local/straight/build-31.1"))
                    "BBH_DOOM_AGENT_SHELL" (str (fs/path checkout "desktop" "configs" "doom" "agent-shell.el"))
                    "BBH_BOMBADIL_BIN" (bombadil-bin)
                    ;; The profile's emacs: desktop/bin/emacs is babashka, which prlimit's cap stops.
                    "BBH_BOMBADIL_EMACS" (let [e (fs/path home ".guix-home/profile/bin/emacs")] (if (fs/executable? e) (str e) "emacs"))
                    "PATH" (str (fs/path home ".guix-home/profile/bin") ":" (System/getenv "PATH"))}
                   (when (fs/exists? sqlite) {"BBH_SQLITE_LIB" sqlite})
                   (when (System/getenv "BBH_HEAVY_SLOT") {"BBH_HEAVY_SLOT" (System/getenv "BBH_HEAVY_SLOT")}))]
    (println "bb test:bombadil: root" root "seed" (:seed o))
    (apply p/exec {:env env :dir (str here "/..")}
           "bb" "--config" (str (fs/path here ".." "bb.edn")) (str "-Duser.home=" root)
           (str "-Djava.io.tmpdir=" root) "-m" "bbh.bombadil"
           "--seed" (str (:seed o)) "--time" (str (:time o))
           (concat (when (:reproduce o) ["--reproduce" (:reproduce o)])
                   (when (:keep o) ["--keep"])))))

(defn- inside! [args]
  (let [root (System/getenv inside-var)
        o (parse args)]
    ;; The run's own guards: every bbh path must sit under the temp root.
    (doseq [k ["HOME" "XDG_DATA_HOME" "XDG_STATE_HOME" "XDG_CONFIG_HOME"]]
      (when-not (str/starts-with? (str (System/getenv k)) root)
        (throw (ex-info (str k " is not under the temp root " root) {}))))
    (when (#{"bbh-super" "server" nil} (System/getenv "BBH_EMACS_SERVER"))
      (throw (ex-info "BBH_EMACS_SERVER would reach a live daemon" {})))
    (let [exit (run-bombadil! o root)]
      (when (and (zero? exit) (not (:keep o))) (fs/delete-tree root))
      (System/exit (if (zero? exit) 0 1)))))

(defn -main [& args]
  (if (System/getenv inside-var) (inside! args) (outside! args)))

(defn -main-args [args] (apply -main args))
