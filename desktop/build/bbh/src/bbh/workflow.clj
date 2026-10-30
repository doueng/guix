(ns bbh.workflow
  "Runs pstack workflows as state machines. Gates run here, so a claim alone never advances a phase."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [bbh.models :as models]
            [bbh.session :as session]
            [clojure.string :as str]))

(def default-max-visits 3)

(defn state-root []
  (str (fs/path (or (System/getenv "XDG_STATE_HOME") (fs/expand-home "~/.local/state"))
                "bbh" "workflows")))

(defn session-run-file
  "The file that holds the run this bbh conversation started. It sits beside the conversation's repl.port.
  Nil outside a bbh conversation."
  []
  (when-let [port (System/getenv "BBH_PORT_FILE")]
    (str (fs/path (fs/parent port) "workflow-run"))))

(defn session-run-id
  "The run this bbh conversation started, or nil when it has none."
  []
  (when-let [file (session-run-file)]
    (when (fs/exists? file) (str/trim (slurp file)))))

(defn- run-dir [id] (str (fs/path (state-root) id)))
(defn- journal-file [id] (str (fs/path (run-dir id) "journal.edn")))
(defn- evidence-dir [id] (str (fs/path (run-dir id) "evidence")))

(def gate-kinds #{:command :artifact :verdict})

(def principles-dir
  "The pstack principle skills, found from this source file so the wf binary and the tests read the same copy."
  (str (fs/normalize (fs/path (fs/parent (str (fs/file (io/resource "bbh/workflow.clj"))))
                              ".." ".." ".." ".." "pi" "agent" "skills" "pstack"))))

(defn principle-file [principle]
  (str (fs/path principles-dir (str "principle-" principle) "SKILL.md")))

(defn principles-text
  "The full text of each named principle skill, without its YAML header and with absolute links,
  so a phase brief carries the rules it names wherever the agent runs."
  [principles]
  (str/join "\n\n"
            (for [p principles]
              (str "<principle name=\"" p "\">\n"
                   (-> (slurp (principle-file p))
                       (str/replace #"(?s)\A---\n.*?\n---\n" "")
                       (str/replace "](../" (str "](" principles-dir "/"))
                       str/trim)
                   "\n</principle>"))))

(def run-commands #{:repro :check :bench})

(defn workflow-problems
  "Why a workflow definition cannot run, as strings. Empty when it can.
  A reference to a missing phase would otherwise enter a phase with no gates and finish the run."
  [wf]
  (let [phases (:phases wf)
        ref-problem (fn [where target]
                      (when-not (contains? phases target)
                        (str where " names missing phase " (pr-str target))))]
    (vec
     (concat
      (when-not (and (map? phases) (seq phases)) ["no :phases"])
      (keep identity [(ref-problem ":start" (:start wf))])
      (for [r (:requires wf) :when (not (#{:check :bench} r))]
        (str ":requires names unknown flag " (pr-str r)))
      (for [[p spec] phases
            problem (concat
                     [(when (some? (:next spec)) (ref-problem (str p " :next") (:next spec)))
                      (when (some? (:on-fail spec)) (ref-problem (str p " :on-fail") (:on-fail spec)))]
                     (let [ps (:principles spec)]
                       (cond (nil? ps) nil
                             (not (and (vector? ps) (every? string? ps)))
                             [(str p " :principles is not a vector of strings")]
                             :else (for [pr ps :when (not (fs/exists? (principle-file pr)))]
                                     (str p " names missing principle " (pr-str pr)))))
                     (map-indexed
                      (fn [i {:keys [kind cmd path] :as g}]
                        (let [at (str p " gate " i)]
                          (cond
                            (not (gate-kinds kind)) (str at " has unknown :kind " (pr-str kind))
                            (and (= :command kind) (not (or (run-commands cmd) (string? cmd))))
                            (str at " has :cmd " (pr-str cmd) ", not a string or one of " (pr-str run-commands))
                            (and (= :artifact kind) (not (string? path))) (str at " has no string :path")
                            (and (= :verdict kind) (not (string? (:criteria g)))) (str at " has no string :criteria"))))
                      (:gates spec)))
            :when problem]
        problem)))))

(defn validate-workflow [wf]
  (when-let [problems (seq (workflow-problems wf))]
    (throw (ex-info (str "invalid workflow " (some-> (:workflow wf) name) ": " (str/join "; " problems))
                    {:workflow (:workflow wf) :problems (vec problems)})))
  wf)

(defn load-workflow [wf-name]
  (if-let [res (io/resource (str "bbh/workflows/" (name wf-name) ".edn"))]
    (validate-workflow (edn/read-string (slurp res)))
    (throw (ex-info (str "unknown workflow " (name wf-name)) {:workflow wf-name}))))

(defn run-workflow
  "The definition a run started with, so editing a workflow file never changes a run in progress.
  Runs started before definitions were journaled fall back to the current file."
  [st]
  (or (:definition st) (load-workflow (:workflow st))))

;; The journal is the source of truth. State is folded from it.

(defn fold-event [st {:keys [type] :as e}]
  (case type
    :start   (merge st (select-keys e [:id :workflow :definition :cwd :task :repro :check :bench :phase])
                    {:status :active :visits {(:phase e) 1} :verdicts []})
    :verdict (update st :verdicts conj e)
    :attest  (assoc st :last-attest e)
    :enter   (-> st
                 (assoc :phase (:phase e))
                 (assoc-in [:visits (:phase e)] (:visit e)))
    :set     (merge st (select-keys e [:repro :check :bench]))
    :abandon (assoc st :status :abandoned :abandon-reason (:reason e))
    :done    (assoc st :status :done)
    :block   (assoc st :status :blocked :block-reason (:reason e))))

(defn replay [events] (reduce fold-event {} events))

(defn- verdict-for [st phase visit]
  (last (filter #(and (= phase (:phase %)) (= visit (:visit %))) (:verdicts st))))

(defn- enter [wf st phase]
  (let [visit (inc (get-in st [:visits phase] 0))
        cap (get (get-in wf [:phases phase]) :max-visits default-max-visits)]
    (cond
      (not (contains? (:phases wf) phase))
      [{:type :block :phase phase :reason (str "unknown phase " (pr-str phase))}]
      (> visit cap)
      [{:type :block :phase phase :reason (str "phase " (name phase) " exceeded " cap " visits")}]
      :else [{:type :enter :phase phase :visit visit}])))

(defn- failed? [g] (and (not (:pass? g)) (not (:pending? g))))

(defn outcome
  "A failed gate outranks a pending one, so missing evidence never hides behind a waiting verdict."
  [results]
  (cond (some failed? results) :fail
        (some :pending? results) :pending
        :else :pass))

(defn decide
  "Events that follow one attest. Pure: the phase spec and the gate results decide."
  [wf st results]
  (let [phase (:phase st)
        spec (get-in wf [:phases phase])]
    (cond
      (= :pending (outcome results)) []
      (= :pass (outcome results)) (if-let [nxt (:next spec)]
                                (enter wf st nxt)
                                [{:type :done}])
      :else (enter wf st (or (:on-fail spec) phase)))))

(defn- sha256 [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn- command-text [st cmd]
  (if (keyword? cmd) (get st cmd) cmd))

(def default-timeout-seconds 600)

(defn- run-command [id st phase visit i gate]
  (if-let [cmd (command-text st (:cmd gate))]
    (let [limit (get gate :timeout default-timeout-seconds)
          {:keys [exit out err]} (p/shell {:dir (:cwd st) :out :string :err :string :continue true}
                                          "timeout" (str limit) "sh" "-c" cmd)
          timed-out? (= 124 exit)
          text (str out err (when timed-out? (str "\ntimed out after " limit "s")))
          evidence (str (evidence-dir id) "/" (name phase) "-" visit "-" i ".txt")]
      (spit evidence (str "$ " cmd "\n" text "\nexit " exit "\n"))
      {:index i :kind :command :cmd cmd :exit exit :evidence evidence :sha256 (sha256 text)
       :timed-out? timed-out?
       :pass? (and (not timed-out?)
                   (if (= :zero (get gate :expect :zero)) (zero? exit) (not (zero? exit))))})
    (if (:optional gate)
      {:index i :kind :command :skipped? true :pass? true}
      {:index i :kind :command :pass? false
       :detail (str "no " (name (:cmd gate)) " command was given; add one with wf set --" (name (:cmd gate)) " CMD")})))

(defn- run-artifact [id i gate]
  (let [f (fs/path (evidence-dir id) (:path gate))]
    {:index i :kind :artifact :path (str f)
     :pass? (boolean (and (fs/exists? f) (pos? (fs/size f))))}))

(defn- run-verdict [st phase visit i gate]
  (if-let [v (verdict-for st phase visit)]
    {:index i :kind :verdict :criteria (:criteria gate) :reviewer (:reviewer v)
     :pass? (= :pass (:verdict v))}
    {:index i :kind :verdict :criteria (:criteria gate) :pending? true}))

(defn- run-gates [wf id st]
  (let [phase (:phase st)
        visit (get-in st [:visits phase])]
    (vec (map-indexed
          (fn [i gate]
            (case (:kind gate)
              :command  (run-command id st phase visit i gate)
              :artifact (run-artifact id i gate)
              :verdict  (run-verdict st phase visit i gate)))
          (get-in wf [:phases phase :gates])))))

(defn- read-journal
  "A torn last line is what a crash mid-write leaves. It was never committed, so it is dropped and the file is
  rewritten without it. A bad line anywhere else means corruption, and reading stops."
  [id]
  (let [lines (vec (remove str/blank? (str/split-lines (slurp (journal-file id)))))
        parsed (mapv #(try (let [v (edn/read-string %)] (if (and (map? v) (:type v)) v ::torn))
                           (catch Exception _ ::torn))
                     lines)
        torn (keep-indexed (fn [i v] (when (= ::torn v) i)) parsed)]
    (cond
      (empty? torn) parsed
      (= [(dec (count lines))] (vec torn))
      (do (spit (journal-file id) (apply str (map #(str % "\n") (butlast lines))))
          (binding [*out* *err*] (println "wf: dropped a torn last journal line in run" id))
          (vec (butlast parsed)))
      :else (throw (ex-info (str "journal corrupt at line " (inc (first torn)) " of run " id) {:id id})))))

(defn- current [id]
  (when-not (fs/exists? (journal-file id))
    (throw (ex-info (str "no run named " id) {:id id})))
  (replay (read-journal id)))

(defn- append! [id event]
  (let [e (assoc event :at (str (java.time.Instant/now)))]
    (spit (journal-file id) (str (pr-str e) "\n") :append true)
    e))

(def ^:private required-flag-help
  {:check "a command that proves the change"
   :bench "a command that measures the metric"})

(defn start! [wf-name {:keys [cwd task repro check bench]}]
  (let [wf (load-workflow wf-name)
        given {:check check :bench bench}]
    (doseq [req (:requires wf)]
      (when (nil? (get given req))
        (throw (ex-info (str (name wf-name) " needs --" (name req) ", " (required-flag-help req))
                        {:workflow wf-name}))))
    (let [id (str (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS"))
                  "-" (name wf-name))]
      (fs/create-dirs (evidence-dir id))
      (append! id {:type :start :id id :workflow wf-name :definition wf
                   :cwd (str (fs/canonicalize (or cwd ".")))
                   :task task :repro repro :check check :bench bench :phase (:start wf)})
      (when-let [file (session-run-file)] (spit file id))
      id)))

(defn set-commands!
  "Gives active run ID new run commands, a map with any of :repro :check :bench. The next attest uses them."
  [id cmds]
  (let [st (current id)
        cmds (into {} (filter (comp some? val)) (select-keys cmds [:repro :check :bench]))]
    (when-not (= :active (:status st))
      (throw (ex-info (str "run is " (name (:status st))) {:id id})))
    (when (empty? cmds)
      (throw (ex-info "set needs --repro, --check or --bench" {:id id})))
    (append! id (merge {:type :set} cmds))))

(defn abandon!
  "Retires run ID so it no longer counts as the default run. Done runs stay done."
  [id reason]
  (let [st (current id)]
    (when-not (#{:active :blocked} (:status st))
      (throw (ex-info (str "run is " (name (:status st)) ", nothing to abandon") {:id id})))
    (append! id {:type :abandon :reason reason})))

(defn- abandoned? [id]
  (try (= :abandoned (:status (current id))) (catch Exception _ false)))

(defn- live? [id]
  (and (fs/exists? (journal-file id)) (not (abandoned? id))))

(defn status [id]
  (let [st (current id)]
    (assoc st :brief (get-in (run-workflow st) [:phases (:phase st) :brief])
              :principles (get-in (run-workflow st) [:phases (:phase st) :principles])
              :evidence (evidence-dir id))))

(defn attest! [id claim]
  (let [st (current id)]
    (when-not (= :active (:status st))
      (throw (ex-info (str "run is " (name (:status st))) {:id id})))
    (let [wf (run-workflow st)
          results (run-gates wf id st)
          result (outcome results)
          attested (append! id {:type :attest :phase (:phase st)
                                :visit (get-in st [:visits (:phase st)])
                                :claim claim :outcome result :gates results})]
      (doseq [e (decide wf (fold-event st attested) results)]
        (append! id e))
      {:outcome result :results results})))

(defn verdict!
  "Records a verdict from a named human. Agents use review!, which runs a reviewer they do not control."
  [id reviewer verdict note]
  (let [st (current id)]
    (when (str/blank? reviewer)
      (throw (ex-info "verdict needs --human NAME" {})))
    (when-not (#{:pass :fail} verdict)
      (throw (ex-info "verdict must be pass or fail" {:verdict verdict})))
    (append! id {:type :verdict :phase (:phase st) :visit (get-in st [:visits (:phase st)])
                 :reviewer reviewer :source :human :verdict verdict :note note})))

(defn- last-assistant-text [messages]
  (let [c (:content (last (filter #(= "assistant" (:role %)) messages)))]
    (cond (string? c) c
          (sequential? c) (str/join "\n" (keep :text c))
          :else "")))

(defn- parse-verdict [text]
  (when-let [m (last (re-seq #"(?m)^\s*VERDICT:\s*(pass|fail)\b" text))]
    (keyword (second m))))

(def default-review-model
  "A reviewer must be at least as strong as the agent it judges. Haiku accepted a sloppy mechanism note that Sonnet
  rejected, and the agents doing the work include Opus."
  "claude/opus")

(def ^:private verdict-nudges
  "Follow-ups asking for the missing VERDICT line. A turn that ends in text is a report, not always the end of the work."
  2)

(defn review!
  "Runs a fresh bbh conversation that judges the current verdict gate, then records its verdict.
  The reviewer shares no context with the agent that did the work."
  [id & {:keys [model]}]
  (let [st (current id)
        phase (:phase st)
        visit (get-in st [:visits phase])
        wf (run-workflow st)
        gate (first (filter #(= :verdict (:kind %)) (get-in wf [:phases phase :gates])))
        _ (when-not gate (throw (ex-info (str "phase " (name phase) " has no review gate") {})))
        dir (str (run-dir id) "/reviews/" (name phase) "-" visit "-" (System/currentTimeMillis))
        brief (str "You are an independent reviewer. Judge one claim. Do not change any files.\n\n"
                   "Criteria: " (:criteria gate) "\n"
                   "Task: " (:task st) "\n"
                   "Repo: " (:cwd st) "\n"
                   "Evidence directory: " (evidence-dir id) "\n\n"
                   (when-let [ps (seq (get-in wf [:phases phase :principles]))]
                     (str "Judge against these principles too:\n\n" (principles-text ps) "\n\n"))
                   "Read the evidence and the repo as needed. Finish with one line, VERDICT: pass or VERDICT: fail, then a short reason.")
        s (session/new-session {:dir dir :init nil :cwd (:cwd st)
                                :model (or model (System/getenv "WF_REVIEW_MODEL") default-review-model)
                                :make-engine models/engine :emit (fn [_])})]
    (try
      (session/start-repl! s)
      (session/prompt! s brief)
      (loop [n 0]
        (when (and (< n verdict-nudges) (not (parse-verdict (last-assistant-text (:messages @s)))))
          (session/prompt! s "Your reply has no VERDICT line yet. Finish the review, then end with one line, VERDICT: pass or VERDICT: fail, and a short reason.")
          (recur (inc n))))
      (let [text (last-assistant-text (:messages @s))
            v (parse-verdict text)]
        (when-not v
          (throw (ex-info "reviewer gave no VERDICT line" {:reply text})))
        (append! id {:type :verdict :phase phase :visit visit :reviewer (str "bbh-review/" (fs/file-name dir))
                     :source :review :verdict v :note text})
        {:verdict v :reviewer (str "bbh-review/" (fs/file-name dir)) :reply text})
      (finally (session/close! s)))))

(defn- run-cwd [id]
  (try (some-> (journal-file id) slurp str/split-lines first edn/read-string :cwd)
       (catch Exception _ nil)))

(defn latest-id
  "The newest run started for DIR or a directory containing it, or the newest run of all without DIR."
  ([] (latest-id nil))
  ([dir]
   (when (fs/exists? (state-root))
     (let [here (when dir (str (fs/canonicalize dir)))
           mine? (fn [id] (or (nil? here)
                              (when-let [cwd (run-cwd id)]
                                (or (= here cwd) (str/starts-with? here (str cwd "/"))))))]
       (->> (fs/list-dir (state-root)) (map (comp str fs/file-name)) sort reverse
            (filter mine?) (remove abandoned?) first)))))

(defn default-id
  "The run a command means when it names none: $WF_RUN, else the run this bbh conversation started, else the
  newest run for the current directory. Other agents' runs in other repositories never qualify. Abandoned runs
  only qualify through $WF_RUN, and a session run missing from this state root is skipped."
  []
  (or (System/getenv "WF_RUN")
      (some-> (session-run-id) (as-> id (when (live? id) id)))
      (latest-id (fs/cwd))))

(defn- configured-session-dir [files]
  (reduce (fn [acc file]
            (if-not (fs/exists? file)
              acc
              (let [v (:sessionDir (json/parse-string (slurp (str file)) true))]
                (cond (nil? v) acc
                      (or (not (string? v)) (str/blank? v))
                      (throw (ex-info (str "Invalid sessionDir in " file) {:file (str file)}))
                      :else v))))
          nil files))

(defn session-dir
  "Pi's session directory for a workspace. Resolution order matches the Pi settings docs."
  ([workspace] (session-dir workspace nil))
  ([workspace explicit]
   (let [env (System/getenv)
         cwd (str (fs/normalize (fs/absolutize (or workspace "."))))
         home (or (get env "HOME") (System/getProperty "user.home"))
         resolve-path (fn [value]
                        (let [v (cond (= value "~") home
                                      (str/starts-with? value "~/") (str home (subs value 1))
                                      :else value)]
                          (str (fs/normalize (fs/path cwd v)))))
         agent (resolve-path (or (get env "PI_CODING_AGENT_DIR") (str home "/.pi/agent")))]
     (if-let [override (or explicit (get env "PI_CODING_AGENT_SESSION_DIR"))]
       (resolve-path override)
       (if-let [configured (configured-session-dir [(fs/path agent "settings.json")
                                                    (fs/path cwd ".pi" "settings.json")])]
         (resolve-path configured)
         (let [slug (-> cwd
                        (str/replace #"^[/\\]" "")
                        (str/replace #"[/\\:]" "-"))]
           (str (fs/path agent "sessions" (str "--" slug "--")))))))))

(defn describe-gate [g]
  (case (:kind g)
    :command (cond (:skipped? g) "optional check skipped, none given at start"
                   (:detail g) (:detail g)
                   (:timed-out? g) (str "`" (:cmd g) "` timed out, output in " (:evidence g))
                   :else (str "`" (:cmd g) "` exited " (:exit g) ", output in " (:evidence g)))
    :artifact (str "artifact " (:path g))
    :verdict (if (:pending? g)
               (str "verdict pending: " (:criteria g))
               (str "verdict " (if (:pass? g) "pass" "fail") " by " (:reviewer g)))))

(defn- print-attest [{:keys [outcome results]}]
  (println (str "attest: " (name outcome)))
  (doseq [g results]
    (println (str "  [" (cond (:pending? g) "wait" (:pass? g) "ok" :else "FAIL") "] " (describe-gate g)))))

(defn- phase-order
  "Phases in the order a run takes them: from :start along :next, then any phase that walk never reaches."
  [wf]
  (let [walk (loop [p (:start wf), acc []]
               (if (or (nil? p) (some #{p} acc))
                 acc
                 (recur (get-in wf [:phases p :next]) (conj acc p))))]
    (vec (concat walk (remove (set walk) (keys (:phases wf)))))))

(defn- spec-gate-text
  "How the panel describes a gate in the spec. Commands show the run's own text, such as its check."
  [st gate]
  (case (:kind gate)
    :artifact (str "artifact " (:path gate))
    :command (str "command `" (get st (:cmd gate)) "`")
    :verdict (str "verdict: " (:criteria gate))
    (str (name (:kind gate)))))

(defn status-json
  "Read-only view of a run for other programs, such as the Emacs panel. Writes nothing."
  [st]
  (let [wf (run-workflow st)]
    {:run (:id st)
     :workflow (name (:workflow st))
     :status (name (:status st))
     :phase (name (:phase st))
     :visit (get-in st [:visits (:phase st)])
     :block_reason (:block-reason st)
     :phases (mapv (fn [p]
                     (let [spec (get-in wf [:phases p])]
                       {:name (name p)
                        :brief (:brief spec)
                        :principles (vec (:principles spec))
                        :visits (get-in st [:visits p] 0)
                        :gates (mapv #(spec-gate-text st %) (:gates spec))}))
                   (phase-order wf))}))

(defn- print-status
  "Prints run ST. FULL? adds the text of the phase's principles, which an agent needs on entering a phase. A retry
  and a status check get their names and paths, because repeating the text costs context on every call."
  [st & {:keys [full?]}]
  (println (str "run " (:id st)))
  (println (str "workflow " (name (:workflow st)) "  phase " (name (:phase st))
                "  visit " (get-in st [:visits (:phase st)]) "  status " (name (:status st))))
  (when (:block-reason st) (println (str "blocked: " (:block-reason st))))
  (when (= :abandoned (:status st)) (println (str "abandoned: " (or (:abandon-reason st) "no reason given"))))
  (when-not (= :done (:status st))
    (println (str "brief: " (:brief st)))
    (when-let [ps (seq (:principles st))]
      (if full?
        (do (println (str "principles: " (str/join ", " ps) ". Apply each where it bears on this phase."))
            (println (principles-text ps)))
        (println (str "principles: " (str/join ", " (map #(str % " (" (principle-file %) ")") ps)))))))
  (println (str "evidence: " (:evidence st))))

(def ^:private usage
  "usage:
  wf start <workflow> [--task T] [--repro CMD] [--check CMD] [--bench CMD] [--cwd DIR]
  wf status [RUN] [--json]
  wf attest [RUN] --claim TEXT
  wf set [RUN] [--repro CMD] [--check CMD] [--bench CMD]   replace run commands; the next attest uses them
  wf abandon [RUN] [--reason T]         retire a run so it is no longer the default
  wf review [RUN]                       fresh reviewer judges the current verdict gate
  wf verdict [RUN] --human NAME --verdict pass|fail [--note TEXT]
  wf session-dir [WORKSPACE] [DIR]
RUN defaults to $WF_RUN, else the newest run started for this directory or one containing it.")

(defn- parse-args [args]
  (loop [xs args, pos [], flags {}]
    (cond
      (empty? xs) {:pos pos :flags flags}
      (str/starts-with? (first xs) "--")
      (recur (drop 2 xs) pos (assoc flags (keyword (subs (first xs) 2)) (second xs)))
      :else (recur (rest xs) (conj pos (first xs)) flags))))

(defn -main [& argv]
  (let [json? (boolean (some #{"--json"} argv))
        argv (remove #{"--json"} argv)
        [cmd & more] argv
        {:keys [pos flags]} (parse-args more)
        run-id (fn [] (or (first pos) (default-id) (throw (ex-info "no runs for this directory; pass RUN or set WF_RUN" {}))))]
    (try
      (case cmd
        "start" (print-status (status (start! (keyword (first pos))
                                              (select-keys flags [:cwd :task :repro :check :bench])))
                              :full? true)
        "status" (if json?
                    (println (json/generate-string (status-json (status (run-id)))))
                    (print-status (status (run-id))))
        "attest" (let [id (run-id)
                         before (:phase (status id))]
                     (print-attest (attest! id (or (:claim flags)
                                                   (throw (ex-info "attest needs --claim" {})))))
                     (let [st (status id)]
                       (print-status st :full? (not= before (:phase st)))))
        "set" (let [id (run-id)]
                (set-commands! id (select-keys flags [:repro :check :bench]))
                (print-status (status id)))
        "abandon" (let [id (run-id)]
                    (abandon! id (:reason flags))
                    (print-status (status id)))
        "session-dir" (println (session-dir (first pos) (second pos)))
        "review" (let [id (run-id)
                       {:keys [verdict reviewer reply]} (review! id)]
                   (println (str "review: " (name verdict) " by " reviewer))
                   (println (str/trim reply))
                   (print-status (status id)))
        "verdict" (let [id (run-id)]
                    (when-not (contains? flags :human)
                      (throw (ex-info "agents run wf review; wf verdict --human NAME is for a person" {})))
                    (when-not (System/console)
                      (throw (ex-info "wf verdict needs a terminal; agents have none, so they run wf review" {})))
                    (print (str "Type the run id " id " to confirm: ")) (flush)
                    (when-not (= id (str/trim (or (read-line) "")))
                      (throw (ex-info "confirmation did not match the run id; no verdict recorded" {})))
                    (verdict! id (:human flags) (keyword (:verdict flags)) (:note flags))
                    (print-status (status id)))
        (do (println usage) (System/exit 2)))
      (catch clojure.lang.ExceptionInfo e
        (if json?
          (println (json/generate-string {:error (ex-message e)}))
          (binding [*out* *err*] (println (ex-message e))))
        (System/exit 1)))))
