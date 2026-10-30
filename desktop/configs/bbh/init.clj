(ns bbh.env
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bbh.auth :as auth]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def base-prompt
  "You are a coding agent working in a persistent babashka REPL through the `clojure` tool.

- Each call evaluates Clojure in the `user` namespace and returns printed output and the last value. Batch independent steps into one call, and print or return a vector for intermediate results.
- Definitions persist until the REPL restarts on a deadline (120 s by default), an interrupt or /restart. The tool result says when. The user may share this REPL over nREPL.
- You own this environment. Redefine `bbh.env/manifest` to change your prompt and limits; `bbh.repl/defaults` lists the keys. Changing :system, :tool-description or :effort misses the prompt cache and restarts Claude Code, so do it rarely. Persist changes in ~/.config/bbh/init.clj.
- Keep replies brief and lead with the outcome.")

(def helper-reference
  "# Helpers

`fs`, `p` and `str` are aliased.

- (sh \"cmd string\") returns {:exit :out :err}. A nonzero exit is data. For separate arguments use (p/shell {:out :string :err :string :continue true} \"cmd\" \"arg\").
- (read-lines path) or (read-lines path start count) prints 1-based numbered lines and returns nil. Use slurp when code needs the text.
- (edit! path old new) replaces exactly one occurrence, else throws.
- (inbox tool args) calls the Plannotator Inbox, where you leave the person a decision or finished work and keep going. Replies arrive as your next turn. Tools are \"send_message\" with :body and optionally :subject, :reply_to, :thread and :attachments, \"read_thread\", \"search\" and \"resolve_message\". `plannotator inbox --help` has the question syntax.
- (job cmd) starts a detached command that survives restarts and returns an id. (job-wait id) waits up to 60 s, or :ms, and returns {:done? :exit :tail :log}. Use it for anything that may approach the deadline. Also job-status and jobs.
- (fs/glob root pattern) needs both arguments. \"**\" includes root-level files and \"**/*\" does not. Results include directories.")

(def pi-helpers
  "Skills written for Pi name Pi helpers. Drop `await` and map `tools/jev_classify_task {:task t}` to (jev-classify-task t), `fs/read` to read-lines, `tools/read` to slurp, `fs/list` to fs/glob, `tools/edit` to edit!, and `tools/bash`, `jj/...` and `make/...` to sh. Parallelize with future or pmap.")

;; Skills

(def skill-roots ["~/.pi/agent/skills" "~/.agents/skills" ".agents/skills" ".pi/skills"])

(defn- frontmatter
  "Top-level keys of a SKILL.md YAML header, with folded > and | values joined."
  [text]
  (let [[_ header] (re-find #"(?s)\A---\n(.*?)\n---" text)]
    (loop [[line & more] (some-> header str/split-lines) k nil acc {}]
      (cond (nil? line) acc
            (re-find #"^\s" line) (recur more k (if k (update acc k #(str/trim (str % " " (str/trim line)))) acc))
            :else (let [[_ key value] (re-matches #"([\w-]+):\s*(.*)" line)
                        value (str/replace (str/trim (or value "")) #"^[>|]-?$|^\"|\"$" "")]
                    (if key (recur more (keyword key) (assoc acc (keyword key) value)) (recur more k acc)))))))

(defn skills
  "Every SKILL.md under the skill roots. Hidden skills load only by name."
  []
  (->> skill-roots
       (map #(fs/expand-home %))
       (filter fs/exists?)
       (mapcat #(fs/glob % "**/SKILL.md" {:follow-links true}))
       (filter fs/regular-file?)
       (keep (fn [path]
               (let [{:keys [name description] :as fm} (frontmatter (slurp (str path)))]
                 (when name
                   {:name name :description description :path (str (fs/canonicalize path))
                    :hidden (= "true" (get fm :disable-model-invocation))}))))
       (reduce (fn [acc s] (if (some #(= (:name s) (:name %)) acc) acc (conj acc s))) [])))

(defn- skill-index []
  (let [visible (remove :hidden (skills))]
    (when (seq visible)
      (str "# Skills\n\nWhen a task matches a skill, read its file with slurp before you start.\n"
           (str/join (for [{:keys [name description path]} visible] (str "\n- " name ": " description "\n  " path)))))))

(defn- skill-block [{:keys [name path]}]
  (str "<skill name=\"" name "\" location=\"" path "\">\nReferences are relative to " (fs/parent path) ".\n\n"
       (str/trim (str/replace (slurp path) #"(?s)\A---\n.*?\n---\n" "")) "\n</skill>"))

;; Jev task routing for poteto-mode, after Pi's jev-pstack extension

(def routes
  {:investigation "Read-only understanding, explanation, or diagnosis; no implementation is requested."
   :bug_fix "Existing behavior is defective; reproduce, diagnose, fix, and verify it."
   :feature "New or changed externally observable behavior is requested."
   :refactor "Change implementation structure without intentionally changing behavior."
   :performance "A reported or measured performance problem, or sustained work on one metric."
   :prototype "An exploratory implementation should answer a question cheaply; production quality is not the goal."
   :review "Review existing code, a diff, a design, or an implementation without primarily changing it."
   :large_project "Cross-cutting, multi-stage, or long unattended work that needs decomposition before execution."
   :pr_workflow "Open, babysit, get green, address review comments on, or land a pull request or stack."
   :session_handoff "Resume or take over earlier in-flight work, or pause current work so it can be resumed."
   :skill_work "Write, edit, or evaluate an agent skill, prompt, or workflow instruction."
   :other "No category clearly fits, or the task is too ambiguous to classify."})

(defn jev-classify-task
  "Classify a new poteto-mode task into a pstack route with TypeSafe Jev."
  [task]
  (let [{:keys [status body]} (http/post "https://api.typesafe.ai/v1/systemone"
                                         {:headers {"Authorization" (str "Bearer " (auth/api-key "TYPESAFE_API_KEY" "typesafe"))
                                                    "Content-Type" "application/json"}
                                          :body (json/generate-string
                                                 {:model "jev-latest"
                                                  :state {:task task}
                                                  :questions {:task {:type "choice"
                                                                     :instructions "Which engineering workflow best fits the user's current task? Classify the requested work, not incidental words. If the request is a continuation or does not clearly fit, choose other."
                                                                     :criteria routes}}})
                                          :throw false :timeout 20000})
        _ (when (not= 200 status) (throw (ex-info (str "TypeSafe " status ": " body) {})))
        {:keys [choice probabilities confidence]} (get-in (json/parse-string body true) [:answers :task])
        p (or (get probabilities (keyword choice)) 0.0)
        advice (cond (or (= choice "other") (< p 0.5)) "No strong route. Classify the task yourself with poteto-mode's Route map and label the route as manual."
                     (>= p 0.75) "High-probability route. Confirm it matches the request, then pick a playbook for it from the Route map."
                     :else "Moderate route. Decide yourself whether it fits, then pick a playbook from the Route map.")]
    (str "Jev suggests " choice (format " (probability %.2f; Choice confidence %.2f).\n" p confidence) advice
         "\n\nDistribution:\n"
         (str/join "\n" (for [[k v] (sort-by (comp - val) probabilities)] (format "%s %.2f" (name k) v))))))

(def poteto?
  "New conversations start in poteto-mode until /poteto-mode-off."
  (atom true))

(defn expand-prompt [{:keys [text first?]}]
  (let [[_ skill args] (re-matches #"(?s)/skill:(\S+)\s*(.*)" text)]
    (cond (= text "/new")
          (do (reset! poteto? true)
              {:reply "Fresh start: poteto-mode is re-armed for new conversations. Begin a new conversation in your client to clear this transcript."})

          (= text "/poteto-mode-off")
          (do (reset! poteto? false) {:reply "poteto-mode is off. Start it again with /skill:poteto-mode."})
          skill
          (if-let [s (first (filter #(= skill (:name %)) (skills)))]
            (do (when (= skill "poteto-mode") (reset! poteto? true))
                (str (skill-block s) (when (seq args) (str "\n\n" args))))
            {:reply (str "No skill named " skill ".")})
          (and first? @poteto? (not (str/starts-with? text "/")))
          (expand-prompt {:text (str "/skill:poteto-mode " text)}))))

(defn- instruction-file [dir]
  (some #(let [f (fs/path dir %)] (when (fs/regular-file? f) f)) ["AGENTS.md" "CLAUDE.md"]))

(defn- instruction-files
  "~/.config/bbh/AGENTS.md, then AGENTS.md (or CLAUDE.md) in each directory from
  the repository root down to the working directory, so the nearest comes last."
  []
  (let [cwd (fs/canonicalize (fs/cwd))
        up (take-while some? (iterate fs/parent cwd))
        root (some #(when (or (fs/exists? (fs/path % ".jj")) (fs/exists? (fs/path % ".git"))) %) up)
        dirs (if root (reverse (take-while #(not= % (fs/parent root)) up)) [cwd])
        global (fs/path (fs/home) ".config" "bbh" "AGENTS.md")]
    (cond->> (keep instruction-file dirs)
      (fs/regular-file? global) (cons global))))

(defn- project-instructions []
  (let [files (instruction-files)]
    (case (count files)
      0 nil
      1 (slurp (str (first files)))
      (str/join "\n\n" (for [f files] (str "## " f "\n\n" (str/trim (slurp (str f)))))))))

(defn manifest []
  {:system (str base-prompt
                "\n\n" helper-reference
                "\n\n" pi-helpers
                "\n\nWorking directory: " (fs/cwd)
                (some->> (skill-index) (str "\n\n"))
                (when-let [p (project-instructions)]
                  (str "\n\n# Project instructions\n\n" p)))})

(defn commands
  "Slash commands expand-prompt handles, for agent-shell's completion."
  []
  (into [{:name "new" :description "Start fresh: re-arm poteto-mode for a new conversation."}
         {:name "poteto-mode-off" :description "Turn off poteto-mode for this conversation."}]
        (for [{:keys [name description]} (skills)]
          {:name (str "skill:" name) :description description})))

;; Delegation: a fresh model conversation with one bash tool, for independent verifiers.

(def delegate-system
  "You are a delegated agent working for another agent. Your one tool, bash, runs in the working directory. Do the brief and nothing else. Do not edit tracked files in the working directory. Run every check you report, and never report a check you did not run. End with the report format the brief asks for.")

(def bash-tool
  {:type "function"
   :function {:name "bash"
              :description "Run a bash command in the working directory. Each call is a fresh shell. Returns the exit code, stdout and stderr."
              :parameters {:type "object" :properties {:command {:type "string"}} :required ["command"]}}})

(defn- clip [s]
  (if (> (count s) 20000) (str (subs s 0 20000) "\n[output truncated]") s))

(defn- run-bash [command]
  (clip (try (let [{:keys [exit out err]} (p/shell {:continue true :out :string :err :string :timeout 600000}
                                                   "bash" "-c" command)]
               (str "exit " exit "\n" out err))
             (catch Exception e (str "error: " (ex-message e))))))

(defn- chat! [model messages]
  (let [key (or (auth/api-key "OPENROUTER_API_KEY" "openrouter")
                (throw (ex-info "Set OPENROUTER_API_KEY or log in to OpenRouter in Pi" {})))
        resp (http/post "https://openrouter.ai/api/v1/chat/completions"
                        {:headers {"Authorization" (str "Bearer " key) "Content-Type" "application/json"}
                         :body (json/generate-string {:model model :messages messages :tools [bash-tool]
                                                      :usage {:include true}})
                         :throw false :timeout 300000})]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "OpenRouter " (:status resp) ": " (:body resp)) {})))
    (json/parse-string (:body resp) true)))

(def delegate-model "claude/claude-opus-5-5")

(defn- openrouter-delegate [model brief max-turns]
  (loop [messages [{:role "system" :content delegate-system} {:role "user" :content brief}]
         turn 0
         tokens 0]
    (let [{:keys [choices usage]} (chat! model messages)
          msg (:message (first choices))
          tokens (+ tokens (or (:total_tokens usage) 0))
          messages (conj messages (cond-> {:role "assistant" :content (:content msg)}
                                    (seq (:tool_calls msg)) (assoc :tool_calls (:tool_calls msg))))]
      (cond (empty? (:tool_calls msg)) {:reply (:content msg) :turns turn :total-tokens tokens}
            (>= turn max-turns) {:reply "Stopped at the turn limit." :turns turn :total-tokens tokens}
            :else (recur (into messages
                               (for [{:keys [id function]} (:tool_calls msg)]
                                 {:role "tool" :tool_call_id id
                                  :content (run-bash (:command (json/parse-string (:arguments function) true)))}))
                         (inc turn)
                         tokens)))))

(defn- claude-delegate [model brief]
  (let [{:keys [exit out err]} (p/shell {:continue true :in brief :out :string :err :string :timeout 1200000}
                                        (or (System/getenv "BBH_CLAUDE") "claude")
                                        "-p" "--model" model "--output-format" "json"
                                        "--system-prompt" delegate-system
                                        "--tools" "Bash,Read,Grep,Glob" "--allowedTools" "Bash,Read,Grep,Glob"
                                        "--setting-sources" "" "--strict-mcp-config" "--mcp-config" "{\"mcpServers\":{}}")
        {:keys [result is_error num_turns usage]} (try (json/parse-string out true) (catch Exception _ nil))]
    (when (or (not= 0 exit) is_error (nil? result))
      (throw (ex-info (str "claude delegate failed (exit " exit "): " (or result err out)) {})))
    {:reply result
     :turns num_turns
     :total-tokens (reduce + (map #(or (get usage %) 0)
                                  [:input_tokens :cache_creation_input_tokens :cache_read_input_tokens :output_tokens]))}))

(defn delegate
  "Run BRIEF in a fresh conversation with one bash tool. MODEL is bbh-style: claude/ runs Claude Code, openrouter/ runs
  the bash loop, which stops after MAX-TURNS tool rounds. Returns {:reply :turns :total-tokens}."
  [brief & {:keys [model max-turns] :or {model delegate-model max-turns 40}}]
  (cond (str/starts-with? model "claude/") (claude-delegate (subs model (count "claude/")) brief)
        (str/starts-with? model "openrouter/") (openrouter-delegate (subs model (count "openrouter/")) brief max-turns)
        :else (throw (ex-info (str "Unknown provider in " model "; use claude/ or openrouter/") {}))))

(ns user
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.env :refer [jev-classify-task delegate]]
            [clojure.string :as str]))

(defn sh
  "Run CMD with bash and an empty stdin. Returns {:exit :out :err}."
  [cmd]
  (select-keys (p/shell {:continue true :in "" :out :string :err :string} "bash" "-c" cmd)
               [:exit :out :err]))

(defn read-lines
  "Print PATH with line numbers, N lines from line START."
  ([path] (read-lines path 1 200))
  ([path start n]
   (let [lines (fs/read-all-lines path)]
     (doseq [[i line] (->> lines (map-indexed vector) (drop (dec start)) (take n))]
       (println (format "%5d  %s" (inc i) line)))
     (println (format "[%d of %d lines]" (min n (max 0 (- (count lines) (dec start)))) (count lines))))))

(defn edit!
  "Replace the single occurrence of OLD with NEW in PATH."
  [path old new]
  (let [text (slurp path)
        hits (count (re-seq (re-pattern (java.util.regex.Pattern/quote old)) text))]
    (when (not= 1 hits)
      (throw (ex-info (str "Expected one match in " path ", found " hits) {})))
    (spit path (str/replace-first text old new))
    :ok))

;; Background jobs. State lives in files under the conversation's jobs directory, so a job and its result
;; survive the REPL restart that a deadline causes.
(defn- jobs-dir []
  (str (if-let [port (System/getenv "BBH_PORT_FILE")]
         (fs/path (fs/parent port) "jobs")
         (fs/path (fs/temp-dir) "bbh-jobs"))))

(defn job
  "Start CMD with bash, detached from the REPL, in DIR (default the current directory). Returns the job id."
  [cmd & {:keys [dir]}]
  (let [d (jobs-dir)
        id (str (System/currentTimeMillis) "-" (rand-int 1000))
        file #(str (fs/path d (str id %)))]
    (fs/create-dirs d)
    (spit (file ".cmd") cmd)
    ;; setsid -f forks and returns, so the job is no longer the REPL's descendant and a restart's kill misses it.
    (p/shell {:dir (or dir (str (fs/cwd))) :in "" :out :discard :err :discard
              :extra-env {"BBH_JOB" (str "(" cmd ") >" (pr-str (file ".log")) " 2>&1 </dev/null; echo $? >"
                                         (pr-str (file ".exit")))}}
             "setsid" "-f" "bash" "-c" "eval \"$BBH_JOB\"")
    id))

(defn job-status
  "Job ID as {:id :cmd :done? :exit :log :tail}, with the last LINES (default 20) of output in :tail."
  [id & {:keys [lines] :or {lines 20}}]
  (let [file #(str (fs/path (jobs-dir) (str id %)))
        _ (when-not (fs/exists? (file ".cmd")) (throw (ex-info (str "no job " id) {:id id})))
        exit (when (fs/exists? (file ".exit")) (some-> (slurp (file ".exit")) str/trim not-empty parse-long))]
    {:id id :cmd (slurp (file ".cmd")) :done? (some? exit) :exit exit :log (file ".log")
     :tail (when (fs/exists? (file ".log"))
             (str/join "\n" (take-last lines (str/split-lines (slurp (file ".log"))))))}))

(defn job-wait
  "Wait up to MS (default 60000) for job ID to finish, then return its job-status."
  [id & {:keys [ms lines] :or {ms 60000 lines 20}}]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [st (job-status id :lines lines)]
        (if (or (:done? st) (>= (System/currentTimeMillis) end))
          st
          (do (Thread/sleep 250) (recur)))))))

(defn jobs
  "Every job of this conversation, newest first, without output."
  []
  (let [d (jobs-dir)]
    (if-not (fs/exists? d)
      []
      (->> (fs/glob d "*.cmd") (map #(str/replace (str (fs/file-name %)) #"\.cmd$" ""))
           (sort-by #(parse-long (first (str/split % #"-")))) reverse
           (mapv #(dissoc (job-status % :lines 0) :tail))))))

;; The Plannotator Inbox, under the id the session started this REPL with.
(require '[bbh.inbox :as plannotator-inbox])

(defn inbox
  "Call the Plannotator Inbox tool TOOL, a string such as \"send_message\"."
  ([tool] (inbox tool {}))
  ([tool args]
   (plannotator-inbox/tool! {:session (System/getenv "BBH_INBOX_SESSION") :project (str (fs/cwd))}
                            tool args)))

;; Pstack workflow runs. A run is a journal under ~/.local/state/bbh/workflows; the gates run in this REPL,
;; so a claim alone never advances a phase. Start a run before editing any file.
(require '[bbh.workflow :as wf])

(defn- print-gates [results]
  (doseq [g results]
    (println (str "  [" (cond (:pending? g) "wait" (:pass? g) "ok" :else "FAIL") "] " (wf/describe-gate g)))))

(defn wf-start
  "Start a workflow run before editing. WORKFLOW is a keyword such as :change or :bug-fix. OPTS takes :task :cwd
  :repro :check :bench. Returns the run id."
  [workflow opts]
  (let [id (wf/start! workflow opts)]
    (println (str "run " id " in phase " (name (:phase (wf/status id)))))
    id))

(defn wf-status
  "Status of run ID, or of the latest run."
  ([] (wf-status nil))
  ([id]
   (let [st (wf/status (or id (wf/default-id) (throw (ex-info "no runs for this directory" {}))))]
     (select-keys st [:id :workflow :phase :status :block-reason :brief :evidence]))))

(defn wf-attest
  "Attest the current phase of run ID (default: latest) with CLAIM. The gates run now and print each result."
  ([claim] (wf-attest nil claim))
  ([id claim]
   (let [id (or id (wf/default-id) (throw (ex-info "no runs for this directory" {})))
         {:keys [outcome results]} (wf/attest! id claim)]
     (println (str "attest: " (name outcome)))
     (print-gates results)
     (wf-status id))))

(defn wf-set
  "Give run ID (default: latest) new run commands, a map with any of :repro :check :bench."
  ([cmds] (wf-set nil cmds))
  ([id cmds]
   (let [id (or id (wf/default-id) (throw (ex-info "no runs for this directory" {})))]
     (wf/set-commands! id cmds)
     (wf-status id))))

(defn wf-abandon
  "Retire run ID (default: latest) with REASON, so it is no longer the default run."
  ([reason] (wf-abandon nil reason))
  ([id reason]
   (let [id (or id (wf/default-id) (throw (ex-info "no runs for this directory" {})))]
     (wf/abandon! id reason)
     (select-keys (wf/status id) [:id :workflow :phase :status :abandon-reason]))))

(defn wf-review
  "Run a fresh reviewer on the current verdict gate of run ID (default: latest). Agents never record a human verdict."
  ([] (wf-review nil))
  ([id]
   (let [id (or id (wf/default-id) (throw (ex-info "no runs for this directory" {})))
         {:keys [verdict reviewer]} (wf/review! id)]
     (println (str "review: " (name verdict) " by " reviewer))
     (wf-status id))))

;; Headless browser screenshots via the flatpak Chrome (Guix has no aarch64 chromium substitute), and a
;; Claude delegate that views them, since bbh tool results carry text only.
(def shot-dir "/tmp/bbh-shots")

(defn shot
  "Headless screenshot of URL (default localhost:8787) with flatpak Chrome. Waits WAIT ms of virtual time for JS.
  Returns the png path."
  ([] (shot "http://localhost:8787/"))
  ([url & {:keys [w h name wait] :or {w 1280 h 900 wait 8000}}]
   (fs/create-dirs shot-dir)
   (let [out (str shot-dir "/" (or name (str "shot-" w "x" h "-" (System/currentTimeMillis))) ".png")
         _ (fs/delete-if-exists out)
         r (p/shell {:out :string :err :string :continue true :timeout 60000}
                    "flatpak" "run" (str "--filesystem=" shot-dir) "com.google.Chrome"
                    "--headless=new" "--disable-gpu" "--no-first-run" "--hide-scrollbars"
                    "--user-data-dir=/tmp/bbh-chrome" (str "--virtual-time-budget=" wait)
                    (str "--screenshot=" out) (str "--window-size=" w "," h) url)]
     (if (fs/exists? out) out (throw (ex-info "screenshot failed" {:err (:err r)}))))))

(defn look
  "Have a Claude delegate view the png PATHS and answer QUESTION in text."
  [question & paths]
  (:reply (delegate (str "Use the Read tool to view these screenshots of a web app: " (str/join ", " paths)
                         "\n\n" question
                         "\nBe concrete: layout, spacing, alignment, typography, colors, overflow. Under 250 words."))))
