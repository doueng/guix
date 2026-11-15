(ns bbh.bombadil
  "Bombadil drives the bbh Emacs UI in a real terminal (T174). A throwaway
  controller in a temp root churns Workers, a capped `emacs -Q -nw` shows a
  view of it, and Bombadil types into that PTY and checks the properties of
  test/bombadil/<view>.ts on every screen.

    bb test:bombadil [--view inbox|worker] [--time 120] [--seed N]
                     [--reproduce DIR] [--keep] [--step-ms 1000]

  The inbox view (inbox.ts, inbox.el) is the split Inbox over fake Workers.
  The worker view (worker.ts, worker.el) is the Router's and Workers'
  transcript and composer: the controller opens real `bbh acp` sessions on
  the script model in that Emacs through its own server socket, the first
  Worker's transcript is a several-MB replay of anonymized real
  conversations, and a watchdog fails the run when Emacs stops answering.

  Nothing here reaches your live stack: HOME and the XDG dirs are a temp root
  (T75), BBH_ and WF_ session variables are dropped, and BBH_EMACS_SERVER
  names a daemon that does not exist (inbox) or this run's own socket under
  the temp root (worker), never bbh-super (T163). A failure prints the trace
  directory and the seed, and --reproduce replays that trace's keys against
  a fixture churned from the same seed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private here (fs/parent (fs/parent (fs/absolutize *file*))))
(def ^:private checkout (str (fs/canonicalize (fs/path here ".." ".." ".." ".."))))
(def ^:private inside-var "BBH_BOMBADIL_ROOT")

(defn- parse [args]
  (loop [[a v & more :as args] args
         o {:time 120 :seed (rand-int 1000000) :keep false :rows 40 :columns 140 :view "inbox" :step-ms 1000}]
    (case a
      nil o
      "--time" (recur more (assoc o :time (parse-long v)))
      "--seed" (recur more (assoc o :seed (parse-long v)))
      "--view" (recur more (assoc o :view (or (#{"inbox" "worker"} v) (throw (ex-info (str "bb test:bombadil: --view inbox or worker, not " v) {})))))
      "--step-ms" (recur more (assoc o :step-ms (parse-long v)))
      "--reproduce" (recur more (assoc o :reproduce (str (fs/absolutize v))))
      "--keep" (recur (cons v more) (assoc o :keep true))
      (throw (ex-info (str "bb test:bombadil: unknown argument " a) {})))))

(defn- mem-available-mb []
  (some->> (str/join "\n" (fs/read-all-lines "/proc/meminfo")) (re-find #"MemAvailable:\s+(\d+) kB") second parse-long (#(quot % 1024))))

(def ^:private emacs-mem-mb 1536)

(defn- bombadil-bin
  "Bombadil from PATH, else the pinned Guix package built from modules/."
  []
  (or (some-> (fs/which "bombadil") str)
      (let [{:keys [exit out err]} (p/shell {:out :string :err :string :continue true :dir checkout}
                                            "guix" "build" "-L" "modules" "-e" "(@ (engstrand packages bombadil) bombadil)")]
        (when-not (zero? exit) (throw (ex-info (str "building bombadil failed: " err) {})))
        (str (fs/path (last (str/split-lines (str/trim out))) "bin" "bombadil")))))

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

(def ^:private plain-words
  ;; No q or z, so a typed zq-word never occurs in generated or anonymized text.
  ["alder" "birch" "cedar" "dogwood" "elm" "fir" "ginkgo" "hazel" "ivy" "juniper" "larch" "maple"
   "the" "a" "of" "to" "and" "in" "that" "it" "with" "for" "on" "is" "was" "this" "by" "from"
   "session" "buffer" "window" "point" "transcript" "render" "block" "result" "thread" "worker"])

(defn- gen-text
  "Markdown as a model writes it, from RNG: paragraphs, headings, lists,
  tables, links, very long lines and fenced code, sometimes left unclosed."
  [^java.util.Random rng]
  (let [pick #(nth % (.nextInt rng (count %)))
        words (fn [n] (str/join " " (repeatedly n #(pick plain-words))))
        part (fn []
               (case (int (.nextInt rng 9))
                 0 (str "## " (str/capitalize (words 3)))
                 1 (str/join "\n" (repeatedly (+ 2 (.nextInt rng 5)) #(str "- " (words (+ 3 (.nextInt rng 9))) " `" (pick plain-words) "`")))
                 2 (str "| key | value |\n|---|---|\n" (str/join "\n" (repeatedly (+ 2 (.nextInt rng 6)) #(str "| " (pick plain-words) " | " (words 4) " |"))))
                 3 (str "See [" (words 2) "](https://example.org/" (pick plain-words) ") and **" (words 2) "**.")
                 4 (words (+ 300 (.nextInt rng 500)))
                 5 (str "```clojure\n" (str/join "\n" (repeatedly (+ 3 (.nextInt rng 40)) #(str "(" (pick plain-words) " " (words 3) ")"))) "\n```")
                 6 (str "```json\n{\"k\": [[\"a\\\\\\\"b\\\\\\\"\" " (str/join " " (repeat (+ 20 (.nextInt rng 200)) "\\\"x\\\"")) "]]}\n```")
                 7 (str "```\n" (str/join "\n" (repeatedly (+ 2 (.nextInt rng 20)) #(words (+ 1 (.nextInt rng 30))))) "\n```")
                 (words (+ 10 (.nextInt rng 60)))))
        parts (vec (repeatedly (+ 1 (.nextInt rng 7)) part))]
    (str (str/join "\n\n" parts)
         (when (zero? (.nextInt rng 15)) (str "\n\n```clojure\n(" (words 4))))))

(def ^:private gen-codes
  ["(Thread/sleep 1500)"
   "(println (apply str (repeat 1500 \"ab \")))"
   "(doseq [i (range 80)] (println i (apply str (repeat (mod (* i 7) 120) \"-\"))))"
   "(println \"{\\\"k\\\": [[\\\"a\\\\\\\\\\\"b\\\"]]}\")"
   "(+ 40 2)"
   "(throw (ex-info \"a failing step\" {}))"])

(defn- script!
  "Write a script model's steps for TURNS turns to PATH: each turn some tool
  calls, then a reply streamed in chunks."
  [path ^java.util.Random rng turns]
  (let [pick #(nth % (.nextInt rng (count %)))]
    (spit path (pr-str (vec (mapcat (fn [_]
                                      (concat (repeatedly (.nextInt rng 3) (fn [] {:code (pick gen-codes)}))
                                              [{:text (gen-text rng) :chunks (+ 2 (.nextInt rng 12)) :chunk-ms (+ 60 (.nextInt rng 200))}]))
                                    (range turns)))))))

(defn- shuffle-with [^java.util.Random rng coll]
  (let [a (java.util.ArrayList. ^java.util.Collection coll)]
    (java.util.Collections/shuffle a rng)
    (vec a)))

(defn- anonymize-string
  "S with every word replaced by a stable pseudo-word of the same length and
  every digit by another, so shapes survive and content does not. SALT is
  fresh per run and never stored. Words in KEEP, such as fence languages and
  markup, stay."
  [salt s]
  (let [keep #{"clojure" "json" "edn" "bash" "sh" "elisp" "emacs" "lisp" "diff" "html" "js" "ts" "begin" "end" "src" "example" "quote" "result" "Error" "Usage"}
        letters "abcdefghijklmnoprstuvwxy"]
    (-> s
        (str/replace #"\p{L}+"
                     (fn [w]
                       (if (keep w)
                         w
                         (let [r (java.util.Random. (hash [salt w]))]
                           (apply str (for [ch w]
                                        (let [c (nth letters (.nextInt r (count letters)))]
                                          (if (Character/isUpperCase ^char ch) (Character/toUpperCase c) c))))))))
        (str/replace #"\d" (fn [_] (str (rand-int 10)))))))

(defn- anonymize-message [salt m]
  (let [anon (partial anonymize-string salt)
        content (fn content [c] (cond (string? c) (anon c)
                                      (sequential? c) (mapv content c)
                                      (map? c) (cond-> c (string? (:text c)) (update :text anon)
                                                         (some? (:content c)) (update :content content))
                                      :else c))]
    (cond-> (update m :content content)
      (:tool_calls m) (update :tool_calls (partial mapv (fn [call]
                                                          (update-in call [:function :arguments]
                                                                     (fn [a] (try (json/generate-string (update (json/parse-string a true) :code #(some-> % anon)))
                                                                                  (catch Exception _ "{}"))))))))))

(defn- seed-conversation!
  "Fill conversation ID under CONVERSATIONS with at least TARGET bytes of
  messages: anonymized real ones from REAL, the person's conversations
  directory, picked by RNG, else generated ones. Returns its source and size."
  [conversations id real ^java.util.Random rng target]
  (let [out (fs/path conversations id "messages.jsonl")
        files (when (and real (fs/directory? real))
                (->> (fs/glob real "*/messages.jsonl") (filter #(> (fs/size %) 200000)) (map str) sort vec))
        salt (str (java.util.UUID/randomUUID))]
    (fs/create-dirs (fs/parent out))
    (with-open [w (clojure.java.io/writer (str out))]
      (if (seq files)
        (loop [order (shuffle-with rng files) written 0]
          (when (and (seq order) (< written target))
            (let [n (with-open [r (clojure.java.io/reader (first order))]
                      (reduce (fn [n line]
                                (if-let [m (try (json/parse-string line true) (catch Exception _ nil))]
                                  (let [s (json/generate-string (anonymize-message salt m))]
                                    (.write w s) (.write w "\n") (+ n (count s) 1))
                                  n))
                              0 (line-seq r)))]
              (recur (rest order) (+ written n)))))
        (loop [written 0 i 0]
          (when (< written target)
            (let [lines [(json/generate-string {:role "user" :content (str "Generated prompt " i ": " (gen-text rng))})
                         (json/generate-string {:role "assistant" :content (gen-text rng)})]]
              (doseq [l lines] (.write w l) (.write w "\n"))
              (recur (+ written (reduce + (map count lines))) (inc i)))))))
    {:source (if (seq files) "anonymized real conversations" "generated") :bytes (fs/size out)}))

(defn- environ [pid]
  (try (String. (fs/read-all-bytes (str "/proc/" pid "/environ"))) (catch Exception _ "")))

(defn- ours?
  "Whether process PID runs in this run's temp ROOT, as its HOME says."
  [root pid]
  (str/includes? (environ pid) (str "\u0000HOME=" root "\u0000")))

(defn- wrapper!
  "ROOT/bin/NAME runs this checkout's desktop/bin/NAME without Emacs's
  address-space cap, which babashka cannot start under."
  [root name]
  (let [f (fs/path root "bin" name)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str "#!/bin/sh\nexec prlimit --as=unlimited:unlimited " (fs/path checkout "desktop" "bin" name) " \"$@\"\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")))

(defn- worker-fixture!
  "Start a controller on ROOT's data dir whose launch opens real sessions in
  the run's Emacs, then churn: mail to Workers and the Router, asks, notes,
  killed sessions and finished Workers, from SEED, until the returned stop
  fn runs."
  [root seed log]
  (let [super (requiring-resolve 'bbh.super/start!)
        stop (requiring-resolve 'bbh.super/stop!)
        open! (requiring-resolve 'bbh.super/open!)
        alive? (requiring-resolve 'bbh.super.emacs/alive?)
        live (requiring-resolve 'bbh.super.sessions/live)
        kill! (requiring-resolve 'bbh.super.sessions/kill!)
        call (requiring-resolve 'bbh.super.client/call!)
        dir (str (fs/path root ".local" "share" "bbh" "super"))
        conversations (str (fs/path root ".local" "share" "bbh" "conversations"))
        rng (java.util.Random. seed)
        pick #(nth % (.nextInt rng (count %)))
        tokens (atom {})
        say (fn [& xs] (spit log (str (str/join " " xs) "\n") :append true))
        mine (fn [] (filterv #(ours? root (:pid %)) (live)))
        c (super {:dir dir :conversations conversations :scan-ms 200 :health-ms 5000 :manage-router true
                  :launch {:workspace! (fn [_ w] (str (fs/create-dirs (fs/path root "ws" (str (:thread w) "-" (:generation w))))))
                           :open! (fn [spec] (swap! tokens assoc (:conversation spec) (:token spec)) (open! spec))
                           :emacs-up? alive?
                           :land! (fn [_] {:landed nil}) :retire! (constantly nil)}
                  ;; Only this run's sessions are ever seen, so none other is reaped.
                  :sessions {:live mine}})
        owner (fn [cmd args] (call dir {:command cmd :args args}))
        worker (fn [w cmd args] (call dir {:token (get @tokens (str "super-" w)) :command cmd :args args}))
        threads (fn [] (:ok (owner :threads {})))
        live-workers (fn [] (->> (threads) (keep (fn [t] (when (#{:working :waiting} (:execution t)) [(:id t) (:worker t)]))) vec))
        asks (atom 0)
        running (atom true)
        new-thread! (fn []
                      (let [t (:ok (owner :thread! {:title (str "Fixture thread about " (pick plain-words))}))
                            w (owner :start-worker! {:thread t :workflow :investigation :brief "churn" :repo root})]
                        (say "thread" t (pr-str w))))
        tick! (fn []
                (let [ws (live-workers) r (.nextInt rng 20)]
                  (cond (< (count ws) 2) (new-thread!)
                        (< r 8) (let [[t] (pick ws)] (say "mail" t (owner :say! {:origin :emacs :text (str t " please look at " (pick plain-words))})))
                        (< r 10) (say "router" (owner :say! {:origin :emacs :text (str "router, what about " (pick plain-words) "?")}))
                        (< r 14) (let [[_ w] (pick ws) n (swap! asks inc)]
                                   (say "ask" w (worker w :ask! {:kind :question :text (format "q%03d %s?" n (pick plain-words)) :stopped (.nextBoolean rng)
                                                                 :options [{:id "y" :label "Yes"} {:id "n" :label "No"}] :recommended "y"})))
                        (< r 16) (let [[_ w] (pick ws)] (say "note" w (worker w :note! {:text (str "looking at " (pick plain-words))})))
                        (< r 19) (let [[t w] (pick ws)
                                       s (first (filter #(= (str "super-" w) (:conversation %)) (mine)))]
                                   (say "kill" t w (pr-str s) (when s (kill! s :grace-ms 0))))
                        :else (let [[t w] (pick ws)]
                                (when (> (count ws) 2)
                                  (say "finish" t w (worker w :finish-worker! {:state-after :done :reason "fixture"})))))))
        churn (doto (Thread. #(do
                                (loop [n 0] (when (and @running (< n 600) (not (alive?))) (Thread/sleep 200) (recur (inc n))))
                                (say "emacs" (alive?))
                                (dotimes [_ 2] (try (new-thread!) (catch Exception e (say "error" (ex-message e)))))
                                (while @running
                                  (Thread/sleep (+ 2500 (.nextInt rng 4000)))
                                  (try (tick!) (catch Exception e (say "error" (ex-message e)))))))
                (.setDaemon true) (.start))]
    (fn []
      (reset! running false)
      (.join churn 10000)
      (stop c)
      (doseq [s (mine)] (kill! s :grace-ms 1000)))))

(defn- emacs-pid [root]
  (some (fn [d] (let [pid (parse-long (str (fs/file-name d)))]
                  (when (and pid (ours? root pid)
                             (str/includes? (or (try (String. (fs/read-all-bytes (str d "/cmdline"))) (catch Exception _ nil)) "") "worker.el")
                             (str/includes? (or (try (slurp (str d "/comm")) (catch Exception _ nil)) "") "emacs"))
                    pid)))
        (fs/list-dir "/proc")))

(defn- watchdog!
  "Watch ROOT/heartbeat, which the Emacs timer writes twice a second, once
  ROOT/ready says the view is up. When it is older than HANG-MS, ask Emacs for a backtrace with SIGUSR2, note the hang
  and call ON-HANG. Returns a stop fn."
  [root hang-ms on-hang]
  (let [beat (fs/path root "heartbeat")
        ready (fs/path root "ready")
        running (atom true)
        t (doto (Thread. #(loop []
                            (when @running
                              (Thread/sleep 1000)
                              (let [age (when (and (fs/exists? ready) (fs/exists? beat)) (- (System/currentTimeMillis) (.toMillis (fs/last-modified-time beat))))]
                                (if (and age (> age hang-ms))
                                  (let [pid (emacs-pid root)]
                                    (spit (str (fs/path root "watchdog.log"))
                                          (format "Emacs (pid %s) did not run its timers for %d ms; SIGUSR2 asked for a backtrace in hang.log\n" pid age))
                                    (when pid (p/shell {:continue true} "kill" "-USR2" (str pid)))
                                    (Thread/sleep 3000)
                                    (on-hang))
                                  (recur))))))
            (.setDaemon true) (.start))]
    (fn [] (reset! running false))))

(defn- percentile [xs q]
  (when (seq xs) (nth (vec (sort xs)) (min (dec (count xs)) (int (* q (count xs)))))))

(defn timing-summary
  "Per kind of step in ROOT/steps.log: count, median, p95 and max ms, plus
  the one-off timings and the checks that failed."
  [root]
  (let [rows (when (fs/exists? (fs/path root "steps.log"))
               (for [l (fs/read-all-lines (fs/path root "steps.log"))
                     :let [[_ ms cmd keys kind size] (re-matches #"\S+ (\d+)\t([^\t]*)\t([^\t]*)\t([^\t]*)\t(\d+)(?:\t.*)?" l)]
                     :when ms]
                 {:ms (parse-long ms) :cmd cmd :keys keys :kind kind :size (parse-long size)}))
        group (fn [r] (cond (= "output" (:cmd r)) "output arriving"
                            (= "stall" (:cmd r)) "stall between commands, over 750"
                            (re-find #"scroll|goto-line|goto-first|next-line|previous-line|output-page" (:cmd r)) (str "scroll " (:cmd r))
                            :else (str (:kind r) " " (:cmd r))))]
    {:steps (->> rows (group-by group)
                 (map (fn [[g rs]] (let [ms (map :ms rs)]
                                     {:step g :n (count rs) :p50 (percentile ms 0.5) :p95 (percentile ms 0.95) :max (apply max ms)
                                      :max-size (apply max (map :size rs))})))
                 (sort-by (comp - :max)) vec)
     :timings (when (fs/exists? (fs/path root "timings.log")) (vec (fs/read-all-lines (fs/path root "timings.log"))))
     :checks (when (fs/exists? (fs/path root "checks.log")) (vec (fs/read-all-lines (fs/path root "checks.log"))))
     :faults (if (fs/exists? (fs/path root "faults.log")) (count (fs/read-all-lines (fs/path root "faults.log"))) 0)
     :hang (when (fs/exists? (fs/path root "watchdog.log")) (slurp (str (fs/path root "watchdog.log"))))}))

(defn- trace-keys [trace]
  (some->> (fs/glob trace "**trace.jsonl") first str
           (#(with-open [r (clojure.java.io/reader %)]
               (mapv (fn [line] (second (re-find #"^\{[^{]*\"action\":\{\"TypeText\":\{\"text\":\"((?:[^\"\\]|\\.)*)\"" line)))
                     (line-seq r))))))

(defn- key-name [k]
  (case k
    nil nil "\\u000e" "C-n" "\\u0003\\u0003" "C-c C-c" "\\u001b" "ESC" "\\u0007" "C-g" "\\t" "TAB" " " "SPC"
    "\\u0015" "C-u" "\\u0004" "C-d" "\\u0006" "C-f" "\\u0002" "C-b" "\\u0017k" "C-w k" "\\u0017j" "C-w j"
    "\\u001b[5~" "PgUp" "\\u001b[6~" "PgDn" "\\u001e" "WAIT" "\\u001bk" "M-k" "\\u001bj" "M-j"
    "\\u0003i" "C-c i" "\\u0003r" "C-c r" "\\r" "RET"
    (if (re-matches #"[a-z]{3,7}" k) "word" k)))

(defn- run-bombadil! [{:keys [time seed reproduce keep rows columns view]} root]
  (let [log (str (fs/path root "fixture.log"))
        trace (str (fs/path root "trace"))
        emacs (System/getenv "BBH_BOMBADIL_EMACS")
        worker? (= "worker" view)
        stop (if worker? (worker-fixture! root seed log) (fixture! root seed log))]
    (try
      (let [cmd (concat [(System/getenv "BBH_BOMBADIL_BIN") "terminal" "test"
                         "--specification" (str (fs/path here "bombadil" (str view ".ts")))
                         "--time-limit" (str time "s") "--exit-on-violation"
                         "--columns" (str columns) "--rows" (str rows)
                         "--quiescence-timeout-ms" "100"
                         "--output-path" trace "--output-path-overwrite"]
                        (when reproduce ["--reproduce" reproduce])
                        ;; Soft cap for Emacs; bbh lifts it again since babashka's isolate cannot start under it.
                        ["--" "prlimit" (str "--as=" (* emacs-mem-mb 1024 1024) ":unlimited") "timeout" (str (+ time 60))
                         emacs "-Q" "-nw" "-l" (str (fs/path here "bombadil" (str view ".el")))])
            started (System/currentTimeMillis)
            proc (apply p/process {:out :string :err :string :in nil} cmd)
            hung (atom false)
            stop-watch (if worker?
                         (watchdog! root 15000 (fn [] (reset! hung true) (p/destroy-tree proc)))
                         (constantly nil))
            {:keys [exit out err]} @proc
            _ (stop-watch)
            exit (if @hung 2 exit)
            secs (quot (- (System/currentTimeMillis) started) 1000)
            text (str out "\n" err)
            _ (spit (str (fs/path root "bombadil.log")) text)
            keys (trace-keys trace)
            summary (when worker? (timing-summary root))
            exit (if (and (zero? exit) (seq (:checks summary))) 3 exit)]
        (println (str/join "\n" (take-last 120 (remove #(str/includes? % "\u001b[") (str/split-lines (str/trim text))))))
        (println (format "bombadil: exit %d after %d s, %s states, seed %d" exit secs (or (count keys) "?") seed))
        (println "keys:" (->> (frequencies (clojure.core/keep key-name keys))
                              (sort-by (comp - val)) (map (fn [[k n]] (str k " " n))) (str/join ", ")))
        (when summary
          (spit (str (fs/path root "summary.edn")) (pr-str summary))
          (println "timings (ms):")
          (doseq [{:keys [step n p50 p95 max max-size]} (:steps summary)]
            (println (format "  %-44s n %4d  p50 %5d  p95 %5d  max %5d  (buffer up to %d chars)" step n p50 p95 max max-size)))
          (doseq [t (:timings summary)] (println " " t))
          (println "turn ends that signalled (T163 fault):" (:faults summary))
          (when (:hang summary) (println "HANG:" (:hang summary) "backtrace:" (str (fs/path root "hang.log"))))
          (doseq [c (:checks summary)] (println "CHECK:" c)))
        (when (or keep (not (zero? exit)))
          (println "fixture log:" log)
          (println "trace:" trace))
        (when-not (zero? exit)
          (println (str "reproduce: bb test:bombadil --view " view " --seed " seed " --reproduce " trace " --keep")))
        exit)
      (finally (stop)))))

(defn- outside!
  "Make the temp root and run this namespace again in it, with hermetic HOME
  and XDG dirs, no session variables and an Emacs server name of its own."
  [args]
  (let [o (parse args)
        worker? (= "worker" (:view o))
        need (+ emacs-mem-mb 1024 (if worker? 1536 0))
        available (mem-available-mb)
        _ (when (and available (< available need))
            (binding [*out* *err*] (println "bb test:bombadil: only" available "MB available of" need "needed; wait for other checks"))
            (System/exit 75))
        root (str (fs/create-temp-dir {:prefix "bbh-bombadil"}))
        home (System/getProperty "user.home")
        sqlite (str (fs/path home ".guix-home/profile/lib/libsqlite3.so.0"))
        env (merge (into {} (remove (fn [[k _]] (re-find #"^(BBH_|WF_|PI_CODING_AGENT_|EMACS_SOCKET_NAME|INSIDE_EMACS)" k))) (System/getenv))
                   (hermetic/dirs root)
                   {inside-var root
                    "BBH_EMACS_SERVER" (if worker?
                                         (str (fs/path root "emacs-server" "bombadil"))
                                         (str "bbh-bombadil-none-" (fs/file-name root)))
                    "TERM" "xterm-256color"
                    "HERDR_ENV" ""
                    "BBH_BOMBADIL_BBH" (str (fs/path checkout "desktop" "bin" "bbh"))
                    "BBH_BOMBADIL_SEED" (str (:seed o))
                    "BBH_BOMBADIL_STEP_MS" (str (:step-ms o))
                    "BBH_ELISP_BUILD" (or (System/getenv "BBH_ELISP_BUILD")
                                          (str home "/.config/emacs/.local/straight/build-31.1"))
                    "BBH_DOOM_AGENT_SHELL" (str (fs/path checkout "desktop" "configs" "doom" "agent-shell.el"))
                    "BBH_BOMBADIL_BIN" (bombadil-bin)
                    ;; Not desktop/bin/emacs: that is babashka, which prlimit's cap stops.
                    "BBH_BOMBADIL_EMACS" (let [e (fs/path home ".guix-home/profile/bin/emacs")] (if (fs/executable? e) (str e) "emacs"))
                    "PATH" (str (when worker? (str (fs/path root "bin") ":")) (fs/path home ".guix-home/profile/bin") ":" (System/getenv "PATH"))}
                   (when worker?
                     {"BBH_MODEL" (str "script/" (fs/path root "scripts" "default.edn"))
                      "BBH_BOMBADIL_SEED_CONVERSATIONS" "seed-big,seed-mid"
                      "BBH_BOMBADIL_REAL_CONVERSATIONS" (str (fs/path home ".local" "share" "bbh" "conversations"))})
                   (when (fs/exists? sqlite) {"BBH_SQLITE_LIB" sqlite})
                   (when (System/getenv "BBH_HEAVY_SLOT") {"BBH_HEAVY_SLOT" (System/getenv "BBH_HEAVY_SLOT")}))]
    (println "bb test:bombadil: view" (:view o) "root" root "seed" (:seed o))
    (apply p/exec {:env env :dir (str here "/..")}
           "bb" "--config" (str (fs/path here ".." "bb.edn")) (str "-Duser.home=" root)
           (str "-Djava.io.tmpdir=" root) "-m" "bbh.bombadil"
           "--seed" (str (:seed o)) "--time" (str (:time o)) "--view" (:view o) "--step-ms" (str (:step-ms o))
           (concat (when (:reproduce o) ["--reproduce" (:reproduce o)])
                   (when (:keep o) ["--keep"])))))

(defn- prepare-worker!
  "The worker view's files: bbh and wf wrappers, the default script and the
  large seed conversation, whose size and source go to timings.log."
  [root seed]
  (wrapper! root "bbh")
  (wrapper! root "wf")
  (script! (str (fs/create-dirs (fs/path root "scripts")) "/default.edn") (java.util.Random. seed) 400)
  (let [conversations (fs/path root ".local" "share" "bbh" "conversations")
        real (System/getenv "BBH_BOMBADIL_REAL_CONVERSATIONS")]
    (doseq [[id mb n] [["seed-big" 5 0] ["seed-mid" 2.5 1]]]
      (let [{:keys [source bytes]} (seed-conversation! conversations id real (java.util.Random. (+ seed n)) (long (* mb 1024 1024)))]
        (spit (str (fs/path root "timings.log")) (format "seed %s\t%s\t%d bytes of messages\n" id source bytes) :append true)))))

(defn- inside! [args]
  (let [root (System/getenv inside-var)
        o (parse args)]
    (doseq [k ["HOME" "XDG_DATA_HOME" "XDG_STATE_HOME" "XDG_CONFIG_HOME"]]
      (when-not (str/starts-with? (str (System/getenv k)) root)
        (throw (ex-info (str k " is not under the temp root " root) {}))))
    (let [server (System/getenv "BBH_EMACS_SERVER")]
      (when (or (#{"bbh-super" "server" nil} server)
                (and (= "worker" (:view o)) (not (str/starts-with? server root))))
        (throw (ex-info "BBH_EMACS_SERVER would reach a live daemon" {}))))
    (when (= "worker" (:view o)) (prepare-worker! root (:seed o)))
    (let [exit (run-bombadil! o root)]
      (when (and (zero? exit) (not (:keep o))) (fs/delete-tree root))
      (System/exit (if (zero? exit) 0 1)))))

(defn -main [& args]
  (if (System/getenv inside-var) (inside! args) (outside! args)))

(defn -main-args [args] (apply -main args))
