(ns bbh.env
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [bbh.auth :as auth]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def copyable-refs
  "The person opens paths and URLs by selecting them in Emacs or on the phone, and any attached character breaks the link. So write each file path in full from / and each URL in full, set off by a space or line break on each side. A sentence that ends with a path ends without a period. Nothing is attached: no backticks, quotes, brackets, markdown links, ~ or trailing comma or period. Put a line number in its own words after the path, as in /home/engstrand/guix/Makefile line 12.")

(def base-prompt
  "You are a coding agent working in a persistent babashka REPL through the `clojure` tool.

- Each call evaluates Clojure in the `user` namespace and returns printed output and the last value. Batch independent steps into one call, and print or return a vector for intermediate results.
- Definitions persist across turns. A deadline (120 s by default), an interrupt or /restart kills the REPL; it then reloads init.clj and replays your definitions from a checkpoint, so `defn`s and `def`s of readable values come back, while atoms and open resources do not. An evaluation that throws is not checkpointed, so its definitions stay live but are lost at the next restart; put `defn`s in a call of their own. The tool result says what was restored. The checkpoint is a plain file under the conversation directory, so keep secrets out of `def`s and read them inside functions instead. When you will repeat work, such as parsing the same kind of file each turn, define a `defn` helper once and call it after. Slurp a file into a var once and search it in Clojure; read it again only after it changes. The user may share this REPL over nREPL.
- You own this environment. Redefine `bbh.env/manifest` to change your prompt and limits; `bbh.repl/defaults` lists the keys. Changing :system, :tool-description or :effort misses the prompt cache and restarts Claude Code, so do it rarely. Persist changes in /home/engstrand/.config/bbh/init.clj.
- Keep replies brief and lead with the outcome.")

(def helper-reference
  "# Helpers

`fs`, `p` and `str` are aliased.

- (sh \"cmd string\") runs the string with bash, so it needs no `bash -c` wrapper, and returns {:exit :out :err}. A nonzero exit is data. If the command outlasts the evaluation's budget it keeps running and sh returns {:timeout true :job id}. (sh \"cmd\" \"arg\" ...) runs one argv, each word quoted. ($ \"cmd\") prints stdout, stderr and a nonzero exit, and returns nil; use it when you only want to see the result. Both throw on a command that only reads, searches or lists files, naming the helper call to use instead; end the command with `# confirm` when the shell is really needed, such as for a flag no helper has.
- (grep pattern & paths) prints `path:line:text` hits for an rg regex (smart case, alternation a|b), skipping gitignored and binary files. Paths are files, directories or shell-style globs (`*` stays in one directory, `**` recurses). End with {:context n}, {:files true}, {:fixed true} or {:max n}. (ls & paths) prints a directory's entries, a glob's files or a file, with line counts.
- (read-lines path) or (read-lines path start count) prints a line-count header and 1-based numbered lines (200 by default, fewer when they would pass about 7000 characters, ending with the `next:` call) and returns nil. It refuses binary files. Use slurp when code needs the text.
- (edit! path old new) replaces exactly one occurrence, or every one with `:all true`, else throws. (edit! path [[old new] ...]) applies several in order and writes only if all match. It tolerates whitespace and indentation drift in old, keeps BOM and CRLF, and returns a summary with a diff. It refuses a .clj, .bb or .edn result that would not read, unless the file already failed or you pass :force true.
- (write! path text) writes a whole file, creating directories, and returns line counts and a diff. It refuses unreadable Clojure like edit!.
- (job cmd) starts a detached command that survives restarts and returns an id. (job-wait id) waits for it to finish, up to 10 min or :ms, and returns {:done? :exit :tail :log}; pass options as (job-wait id :ms 600000) or a map. Run `wf attest` and `wf review` as (job-wait (job \"wf attest ...\")), since they can outlast sh's budget. Waiting does not count against the evaluation's budget, so one call covers a long command. p/shell, http and Thread/sleep do count, so use a job for anything that may approach the deadline. Also job-status and jobs.
- (shot url) saves a headless Chrome screenshot and returns its path. (look question & paths) has a Claude delegate describe images, since tool results carry text only. (delegate brief) runs a fresh agent, for an independent check.
- (fs/glob root pattern) needs both arguments. \"**\" includes root-level files and \"**/*\" does not. Results include directories.")

(def super-helpers
  "# Super-agent

This session is a Worker of bbh super: a thread's work, with a controller that relays your asks to the person and their replies back as mailbox messages. Call these instead of waiting in the conversation. A message without a tool call ends your turn, and your work stops until a mailbox message arrives. So do not end a turn by announcing the next step, by offering to continue, or by listing decisions that do not block the rest of the work. Put status in (super-note! ...) and carry on. End a turn only after super-done!, super-failed!, or a super-ask! with :stopped true for something that cannot move without the person. Risky or destructive actions still need an ask first. Your first message on a task restates, in your own words, the person's goal, the problem and the smallest change you expect, without waiting for confirmation; your final report opens with the goal.

- (super-ask! {:kind :question :text \"...\" :options [{:id \"no\" :label \"No\"} ...] :recommended \"no\" :details \"...\" :evidence \"/abs/path\" :stopped true}) files a question (or :action, or :review with :revision, the commit id to approve from `jj log -r @- --no-graph -T commit_id`) and returns its id. Keep :text to the question in one line (the controller refuses a longer one) and each option :label to a few words. Put the context they need to decide, your pick and why, the evidence and the risk in :details, which the inbox folds, or in a file named by :evidence. The person can answer by picking an option or in their own words. An approval names that revision and the wf evidence as it was when you asked, so ask for review only once the evidence is final. With :stopped true, end your turn after it; the reply arrives as a new prompt. Omit :stopped when you can go on meanwhile.
- (super-answered! \"reply-id\") after you have acted on a reply, naming the id the mailbox message gave.
- Never evaluate in, attach to or benchmark the live bbh-super Emacs: it runs every session, and Workers froze it twice. Your emacsclient refuses it. Run Emacs checks in batch Emacs, one at a time after free -m, or with emacs-throwaway, which caps memory and can start a daemon of your own.
- (super-note! \"text\") updates the thread's summary line; (super-milestone! \"text\") marks progress worth the person's notice.
- An explanation you write for the person, such as an answer, plan, audit, review, comparison or design, is one self-contained HTML file, not markdown: inline CSS and SVG, no network resources, readable at phone width. (explainer! path {:title :description :thread \"T12\" :body html}) writes one in the shared style. Its <meta name=\"bbh-doc\" content=\"T<thread>\"> and <meta name=\"description\"> list it in the phone's Docs when it sits in ~/guix/docs, /tmp or your wf evidence directory. Your reply names the file by its full path.
- (super-done!) when the wf run is done, (super-failed! \"why\") when it cannot finish. Both end the Worker. super-done! first rebases your revision onto the tip of the person's line, then removes your workspace, and refuses, saying why, while your working copy has uncommitted changes or the line has two tips; then ask which, and call (super-done! {:onto \"<commit id>\"}). When the rebase leaves conflicts it returns {:conflicts :todo} and keeps you running: resolve them yourself, then call super-done! again.")

(def router-helpers
  "# Router

You are the Router of bbh super: one long-lived conversation that reads what the person says and the events no thread owns. Each prompt is a batch of messages, oldest first, each tagged with its origin. Some messages are noise, such as thanks or a stray yes; they get no reply and no action. For every message: a question or a request for information gets a one-line answer from what (super-threads) and (super-thread id) show, except that a request to catch up on what happened or what waits on the person gets one sentence naming what waits, one line per thing they asked for, then each open ask with its id, your pick, the evidence and the risk; a feature, fix or chore becomes a thread with (super-thread! title), whose returned handle you bind and reuse (also when a later call fails; never look a new thread up), and a Worker with (super-start! id text :for \"o12\" :workflow :change :project \"guix\"), where :for is the id that starts the message's line, so the Worker knows where it came from (:investigation for an open question, :bug-fix with :repro for a failure). The brief opens with the person's goal and the problem in your own words, then the request. :project names one of (super-projects), which lists each repository with its :about and the :check that proves a change there; pick the one the message names or plainly means, and pass :check only when the person names another. A new project, or a path the person gives that is not listed, goes in :repo instead, with a :check you choose. When no project fits and no path is given, start nothing and ask in your one-line reply which repository the person means; never default to guix. When a message asks you to cancel, steer, continue or restart a thread's work, act only on what it asks and pass its id, which logs it as the authority. Every verb takes the thread id, never a Worker id: (super-cancel! id reason :for \"o66\"), (super-steer! id text :for \"o66\"), which also resumes a Worker whose session ended, and (super-start! id brief :for \"o66\") to continue a thread with a new Worker. That start keeps the workflow, repo and check of the thread's last Worker unless you pass others, reopens an archived thread and replaces a Worker whose session ended; a live Worker is replaced by cancelling it and then starting one. Pass :model \"claude/claude-fable-5-2\" (Fable 5.2) only when the person's message explicitly asks for Fable for that work, and then say in your reply that the Worker runs on Fable; never choose it otherwise. A `[routed to T12 W3 from herdr] ...` line was already delivered to that Worker by its handle; take it as context and do nothing. Reply in one line per message, such as \"Started T15 (change workflow). I'll ask if it needs you.\" Your tool evaluates in a sandbox: pure Clojure plus jev-classify-task, (super-threads), (super-thread id), (super-projects), (super-worker id), (super-ask id), (super-reply id) (each names the ids it links, so follow them), (super-events {:thread id :after seq}), (super-thread! title), (super-start! thread brief & opts), (super-steer! thread text & opts), (super-cancel! thread reason & opts), (super-note! id text) and (super-milestone! id text). It has no shell, files or Java: Workers do that. Explanations for the person (answers, plans, audits, reviews, comparisons) are self-contained HTML files that Workers write and the phone's Docs list shows, never markdown; when a reply mentions one, it names the file's full path.")

(def pi-helpers
  "Skills written for Pi name Pi helpers. Drop `await` and map `tools/jev_classify_task {:task t}` to (jev-classify-task t), `fs/read` to read-lines, `tools/read` to slurp, `fs/list` to fs/glob, `tools/edit` to edit!, and `tools/bash`, `jj/...` and `make/...` to sh. Parallelize with future or pmap.")

;; The evaluation budget. The session sets eval-deadline before each evaluation, so helpers can return before
;; the deadline kills the REPL.

(def eval-deadline (atom nil))

(def ^:private deadline-margin-ms 10000)

(def ^:private idle-budget-ms
  "The budget when no evaluation deadline is live, as for a form sent from CIDER."
  90000)

(defn budget-ms
  "Milliseconds a helper may block in this evaluation."
  []
  (let [left (some-> @eval-deadline (- (System/currentTimeMillis)))]
    (if (and left (pos? left))
      (max 0 (- left deadline-margin-ms))
      idle-budget-ms)))

(def live-sh
  "Pid files of sh commands in flight. They run detached, so an interrupt has to stop them by hand."
  (atom #{}))

(defn kill-running!
  "Stop every sh command in flight, each with its process group."
  []
  (doseq [f @live-sh :when (fs/exists? f)
          :let [pid (str/trim (slurp f))] :when (seq pid)]
    (p/shell {:continue true :out :discard :err :discard} "kill" "-TERM" "--" (str "-" pid))
    (doseq [suffix [".cmd" ".run" ".log" ".err" ".exit" ".pid" ".start" ".boot"]]
      (fs/delete-if-exists (str/replace f #"\.pid$" suffix))))
  (reset! live-sh #{})
  nil)

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

(defn- distance
  "Levenshtein distance between strings A and B."
  [a b]
  (peek (reduce (fn [prev ca]
                  (reduce (fn [row [j cb]]
                            (conj row (min (inc (peek row)) (inc (prev (inc j))) (+ (prev j) (if (= ca cb) 0 1)))))
                          [(inc (first prev))] (map-indexed vector b)))
                (vec (range (inc (count b)))) a)))

(defn similar-names
  "Up to three names close to the unresolved symbol named SYM: from user's namespace map, or from the publics of the
  namespace an alias names for a/b."
  [sym]
  (let [[alias nm] (if-let [[_ a n] (re-matches #"(.+)/(.+)" sym)] [a n] [nil sym])
        target (when alias (or (get (ns-aliases 'user) (symbol alias)) (find-ns (symbol alias))))
        names (map str (keys (if alias (some-> target ns-publics) (ns-map 'user))))
        close? #(or (str/includes? % nm) (and (>= (count %) 4) (str/includes? nm %)))]
    (->> names
         (remove #{nm})
         (map (juxt #(distance nm %) identity))
         (filter (fn [[d n]] (or (close? n) (<= d (max 1 (quot (count nm) 3))))))
         sort (take 3) (map second)
         (map #(if alias (str alias "/" %) %)))))

(defn- skill-index []
  (let [visible (remove :hidden (skills))]
    (when (seq visible)
      (str "# Skills\n\nWhen a task matches a skill, read its file with slurp before you start.\n"
           (str/join (for [{:keys [name description path]} visible] (str "\n- " name ": " description "\n  " path)))))))

(defn- skill-block [{:keys [name path]}]
  (str "<skill name=\"" name "\" location=\"" path "\">\nReferences are relative to " (fs/parent path) ".\n\n"
       (str/trim (str/replace (slurp path) #"(?s)\A---\n.*?\n---\n" "")) "\n</skill>"))

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

(def tool-description
  (str "Evaluate Clojure in the persistent babashka REPL. Returns printed output and the last value.
Prefer these helpers to shell commands for files: their output is capped and paged, lines are numbered, and arguments need no shell quoting.
- Read a file: (read-lines path) or (read-lines path start count), not cat, head, tail or sed -n.
- Search contents: (grep pattern & paths), not grep or rg. Paths may be globs such as \"src/**/*.clj\".
- List files or count lines: (ls & paths-or-globs), not ls, find or wc -l.
- Change a file: (edit! path old new); it refuses Clojure or EDN that would not read. Write a whole file: (write! path text), not spit; it creates directories and returns a diff.
- For data in code, use slurp and fs/glob.
- Everything else, such as jj, wf, builds and tests: ($ \"cmd\") to see the result, (sh \"cmd\") when code needs {:exit :out :err}, (job-wait (job \"cmd\")) when it may be slow.
sh and $ throw on cat, head, tail, sed -n, grep, rg, ls, find or wc -l, naming the helper call instead; end the command with # confirm to run it anyway."))

(defn manifest []
  (cond-> {:system (if (= "router" (System/getenv "BBH_ROLE"))
             (str router-helpers "\n\n" copyable-refs)
             (str base-prompt "\n- " copyable-refs
                  "\n\n" helper-reference
                  "\n\n" pi-helpers
                  (when (System/getenv "BBH_SUPER_TOKEN") (str "\n\n" super-helpers))
                  "\n\nWorking directory: " (fs/cwd)
                  (some->> (skill-index) (str "\n\n"))
                  (when-let [p (project-instructions)]
                    (str "\n\n# Project instructions\n\n" p))))}
    (not= "router" (System/getenv "BBH_ROLE")) (assoc :tool-description tool-description)))

(defn commands
  "Slash commands expand-prompt handles, for agent-shell's completion."
  []
  (into [{:name "new" :description "Start fresh: re-arm poteto-mode for a new conversation."}
         {:name "poteto-mode-off" :description "Turn off poteto-mode for this conversation."}]
        (for [{:keys [name description]} (skills)]
          {:name (str "skill:" name) :description description})))

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
            [bbh.explainer :as explainer]
            [clojure.string :as str]))

(def ^:private binary-extensions
  #{"zip" "tar" "gz" "tgz" "bz2" "xz" "7z" "rar" "exe" "dll" "so" "dylib" "class" "jar" "war" "wasm"
    "doc" "docx" "xls" "xlsx" "ppt" "pptx" "odt" "ods" "odp" "pdf" "bin" "dat" "obj" "o" "a" "lib"
    "pyc" "pyo" "png" "jpg" "jpeg" "gif" "webp" "bmp" "ico" "mp3" "mp4" "mov" "avi" "ogg" "wav" "flac"})

(defn- binary? [path ^bytes bs]
  (let [sample (take 4096 bs)
        control (count (filter #(or (< -1 % 9) (< 13 % 32)) sample))]
    (or (contains? binary-extensions (some-> (re-find #"\.([^./]+)$" (str/lower-case (str path))) second))
        (some zero? sample)
        (and (seq sample) (> (quot (* 100 control) (count sample)) 30)))))

(defn- text-lines
  "Lines of S as Rust's str::lines splits them: on LF or CRLF, with no empty line after a final newline."
  [s]
  (let [ls (str/split s #"\r?\n" -1)]
    (if (= "" (peek ls)) (pop ls) ls)))

(defn- distance [a b]
  (peek (reduce (fn [prev [i ca]]
                  (reduce (fn [row [j cb]]
                            (conj row (min (inc (peek row)) (inc (prev (inc j)))
                                           (+ (prev j) (if (= ca cb) 0 1)))))
                          [(inc i)] (map-indexed vector b)))
                (vec (range (inc (count b)))) (map-indexed vector a))))

(defn- nearby-path [path]
  (let [f (fs/path path) dir (or (fs/parent f) (fs/path ".")) target (str (fs/file-name f))]
    (when (fs/directory? dir)
      (when-let [[d near] (first (sort (for [c (fs/list-dir dir) :let [n (str (fs/file-name c))]]
                                         [(distance (str/lower-case target) (str/lower-case n)) n])))]
        (when (and (not= near target) (<= d (max 2 (quot (count target) 3))))
          (str (if (fs/parent f) (fs/path (fs/parent f) near) near)))))))

(defn- read-bytes [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "File not found: " path (some->> (nearby-path path) (format ". Did you mean `%s`?"))) {})))
  (fs/read-all-bytes path))

(def ^:private read-lines-chars
  "How much read-lines prints, under the session's 8000-character output cap so one call is never cut short."
  7000)

(defn read-lines
  "Print N lines of PATH from line START, numbered, under a header with the file's line count. It stops early
  rather than print more than read-lines-chars, and then ends with the call that shows the next line."
  ([path] (read-lines path 1 200))
  ([path start n]
   (let [bs (read-bytes path)
         _ (when (binary? path bs) (throw (ex-info (str "Cannot read binary file: " path) {})))
         lines (text-lines (str/replace-first (String. ^bytes bs "UTF-8") #"^\uFEFF" ""))
         total (count lines)
         offset (dec (max 1 start))
         width (count (str total))
         clip #(if (> (count %) 3000) (str (subs % 0 3000) " …[line truncated]") %)
         numbered (loop [acc [] chars 0 [i & is] (range offset (min (+ offset n) total))]
                    (let [l (some->> i lines clip (format (str "%" width "d: %s") (inc i)))]
                      (if (or (nil? l) (and (seq acc) (> (+ chars (count l)) read-lines-chars)))
                        acc
                        (recur (conj acc l) (+ chars (count l) 1) is))))
         end (+ offset (count numbered))]
     (println
      (cond (zero? total) "(empty file)"
            (>= offset total) (format "(%d lines total; offset %d is past end of file — nothing to show)" total (inc offset))
            :else (str (format "(%d lines total, showing lines %d-%d)\n\n" total (inc offset) end)
                       (str/join "\n" numbered)
                       (when (< end total) (format "\n\nnext: (read-lines %s %d)" (pr-str (str path)) (inc end)))))))))

(defn- decode-for-edit
  "PATH's text with any BOM removed and CRLF turned into LF, plus what restores both on write."
  [path]
  (let [bs (read-bytes path)
        text (try (str (.decode (.newDecoder java.nio.charset.StandardCharsets/UTF_8) (java.nio.ByteBuffer/wrap bs)))
                  (catch java.nio.charset.CharacterCodingException _
                    (throw (ex-info "file is not valid UTF-8; refusing to edit so its other bytes are not corrupted. Use sh with sed or perl." {}))))
        bom? (str/starts-with? text "\uFEFF")
        text (cond-> text bom? (subs 1))
        crlf (count (re-seq #"\r\n" text))
        lf (- (count (re-seq #"\n" text)) crlf)]
    {:content (str/replace text "\r\n" "\n") :bom? bom? :crlf crlf :lf lf}))

(defn- reencode [{:keys [bom? crlf lf]} content]
  (let [crlf? (and (pos? crlf) (>= crlf lf))]
    {:text (str (when bom? "\uFEFF") (cond-> content crlf? (str/replace "\n" "\r\n")))
     :note (when (and (pos? crlf) (pos? lf))
             (format "note: file had mixed line endings (%d CRLF, %d LF); normalized to %s." crlf lf (if crlf? "CRLF" "LF")))}))

(defn- block-matches
  "[start end) character ranges of the blocks of CONTENT's lines, sized SIZES, that SAME? accepts."
  [content sizes same?]
  (let [lines (str/split content #"\n" -1)
        starts (vec (reductions + 0 (map #(inc (count %)) lines)))]
    (for [i (range (count lines))
          :let [size (first (filter #(and (<= (+ i %) (count lines)) (same? (subvec lines i (+ i %)))) sizes))]
          :when size]
      [(starts i) (+ (starts i) (dec (- (starts (+ i size)) (starts i))))])))

(defn- strip-min-indent [ls]
  (let [indent (or (some->> (remove str/blank? ls) seq (map #(count (re-find #"^\s*" %))) (apply min)) 0)]
    (mapv #(if (str/blank? %) % (subs % indent)) ls)))

(defn- find-matches
  "Ranges where OLD occurs in CONTENT and the fallback that found them, trying exact, line-trimmed,
  whitespace-normalized and indentation-flexible matching in turn."
  [content old]
  (let [olds (str/split old #"\n" -1)
        n (count olds)
        squash #(str/trim (str/replace (str/join "\n" %) #"\s+" " "))]
    (or (when-let [hits (seq (loop [from 0 acc []]
                               (if-let [i (str/index-of content old from)]
                                 (recur (+ i (count old)) (conj acc [i (+ i (count old))]))
                                 acc)))]
          [hits nil])
        (some (fn [[label sizes same?]]
                (when-let [hits (seq (block-matches content sizes same?))] [hits label]))
              [["line-trimmed" [n] #(= (map str/trim %) (map str/trim olds))]
               ["whitespace-normalized" (range n (+ n 6)) (let [want (squash olds)] #(and (seq want) (= want (squash %))))]
               ["indentation-flexible" [n] #(= (strip-min-indent %) (strip-min-indent olds))]]))))

(defn- disjoint [ranges]
  (reduce (fn [kept [s e]] (if (and (seq kept) (< s (second (peek kept)))) kept (conj kept [s e])))
          [] (sort ranges)))

(defn- line-count [s] (count (text-lines s)))

(defn- show-diff [path content pos old new]
  (let [lines (text-lines content)
        at (count (filter #{\newline} (subs content 0 pos)))
        from (max 0 (- at 3))
        after (min (+ at (line-count old)) (count lines))
        until (min (+ after 3) (count lines))
        ctx (+ (- at from) (- until after))]
    (str "\n--- a/" path "\n+++ b/" path "\n"
         (format "@@ -%d,%d +%d,%d @@\n" (inc from) (+ (line-count old) ctx) (inc from) (+ (line-count new) ctx))
         (str/join (concat (map #(str " " % "\n") (subvec lines from at))
                           (map #(str "-" % "\n") (text-lines old))
                           (map #(str "+" % "\n") (text-lines new))
                           (map #(str " " % "\n") (subvec lines after until)))))))

(defn- apply-edit
  "CONTENT with OLD replaced by NEW, as {:content :summary}, or an exception that names PATH."
  [path content old new all]
  (when (empty? old) (throw (ex-info "OLD must not be empty. Provide the exact text to replace." {})))
  (let [old (str/replace old "\r\n" "\n")
        new (str/replace new "\r\n" "\n")
        [found fallback] (find-matches content old)
        ranges (disjoint found)
        line-of #(inc (count (filter #{\newline} (subs content 0 %))))]
    (when (empty? ranges)
      (throw (ex-info (str "OLD not found in '" path "'.\nEnsure the exact text matches including whitespace and line endings. "
                           "Tried exact match, line-trimmed match, whitespace-normalized match, and indentation-flexible match.") {})))
    (when (and (next ranges) (not all))
      (throw (ex-info (str "OLD matched " (count ranges) " times in " path ":\n"
                           (str/join "\n" (for [[s] (take 20 ranges)]
                                              (let [ln (line-of s)]
                                                (str "  Line " ln ": " (str/join (take 100 (nth (str/split content #"\n" -1) (dec ln))))))))
                           (when (> (count ranges) 20)
                             (let [more (- (count ranges) 20)] (str "\n  ... and " more " more match" (when (> more 1) "es"))))
                           "\n\nPass :all true to replace every occurrence, or include more surrounding context in OLD.") {})))
    (let [ranges (if all ranges [(first ranges)])
          [b e] (first ranges)
          matched (subs content b e)
          delta (reduce + (for [[b e] ranges] (- (line-count new) (line-count (subs content b e)))))]
      {:content (reduce (fn [s [b e]] (str (subs s 0 b) new (subs s e))) content (reverse ranges))
       :summary (str "Applied edit" (when all (str " (" (count ranges) " replacements)"))
                     (when fallback (str " (matched via " fallback " fallback — exact text not found; whitespace/indent tolerated)"))
                     (when-not (zero? delta) (format " (%+d lines)" delta))
                     (let [ls (inc (or (str/last-index-of content "\n" (dec b)) -1))
                           nl-end? (and (> e b) (= \newline (.charAt content (dec e))))
                           le (if nl-end? (dec e) (or (str/index-of content "\n" e) (count content)))
                           old-lines (subs content ls le)
                           new-lines (str (subs content ls b)
                                          (if (and nl-end? (str/ends-with? new "\n")) (subs new 0 (dec (count new))) new)
                                          (when-not nl-end? (subs content e le)))]
                       (when (and (<= (line-count old-lines) 200) (<= (line-count new-lines) 200))
                         (show-diff path content ls old-lines new-lines))))})))

(defn- parse-error
  "Why TEXT does not read as Clojure or EDN, with its position, when PATH has such an extension; else nil."
  [path text]
  (when (re-find #"\.(clj|cljc|cljs|bb|edn)$" (str path))
    (try (edamame.core/parse-string-all text {:all true :auto-resolve (fn [_] 'x) :readers (fn [_] identity)
                                               :features #{:clj :bb} :read-cond :allow})
         nil
         (catch Exception e
           (let [m (ex-message e) {:keys [row col]} (ex-data e)]
             (if (and row (not (str/includes? m " at ["))) (str m " at [" row "," col "]") m))))))

(defn- syntax-gate!
  "Throw when NEW-TEXT fails to read for PATH while OLD-TEXT (nil for a new file) did not."
  [path old-text new-text force]
  (when-not force
    (when-let [err (parse-error path new-text)]
      (when (or (nil? old-text) (nil? (parse-error path old-text)))
        (throw (ex-info (str "Refusing to write " path ": the result would not read: " err
                             ". The file is unchanged. Fix the text, or pass :force true to write it anyway.") {}))))))

(defn- replace-file! [path text]
  (let [tmp (fs/create-temp-file {:dir (fs/parent (fs/absolutize path)) :prefix ".edit-"})]
    (spit (str tmp) text)
    (when (fs/exists? path) (fs/set-posix-file-permissions tmp (fs/posix-file-permissions path)))
    (fs/move tmp path {:replace-existing true :atomic-move true})))

(defn write!
  "Write TEXT to PATH, creating parent directories, and return a summary with a diff against the old file (capped at
  60 lines). A .clj, .cljc, .cljs, .bb or .edn result that would not read is refused unless the old file already failed,
  or :force true."
  [path text & {:keys [force]}]
  (let [text (str text)
        old (when (fs/exists? path) (slurp (str path)))]
    (syntax-gate! path old text force)
    (fs/create-dirs (fs/parent (fs/absolutize path)))
    (replace-file! path text)
    (str "Wrote " path " (" (line-count text) " lines"
         (if old (str ", was " (line-count old) ")") ", new file)")
         (when (and old (not= old text))
           (let [d (:out (p/shell {:out :string :err :string :continue true :in old}
                                  "diff" "-u" "--label" (str "a/" path) "--label" (str "b/" path) "-" (str path)))
                 ls (str/split-lines d)]
             (str "\n" (str/join "\n" (take 60 ls))
                  (when (> (count ls) 60) (str "\n... " (- (count ls) 60) " more diff lines"))))))))

(defn edit!
  "Replace OLD with NEW in PATH and return a summary with a diff. OLD must match once, or pass :all true.
  Whitespace and indentation drift in OLD is tolerated, and the summary says when it was. (edit! path [[old new] ...])
  applies each pair in order and writes the file only when every pair matched. A Clojure or EDN file that would stop
  reading is left unchanged unless :force true."
  [path old & more]
  (let [[edits {:keys [all force]}] (if (string? old)
                                [[[old (first more)]] (apply hash-map (rest more))]
                                [old (apply hash-map more)])
        {:keys [content] :as src} (decode-for-edit path)
        [result summaries] (reduce (fn [[c acc] [i [o n]]]
                                     (let [{c :content s :summary}
                                           (try (apply-edit path c o n all)
                                                (catch clojure.lang.ExceptionInfo e
                                                  (throw (if (next edits)
                                                           (ex-info (str "Edit " (inc i) " of " (count edits) " failed, so " path " is unchanged. " (ex-message e)) {})
                                                           e))))]
                                       [c (conj acc s)]))
                                   [content []] (map-indexed vector edits))
        _ (syntax-gate! path content result force)
        {:keys [text note]} (reencode src result)]
    (replace-file! path text)
    (str (str/join "\n" summaries) (when note (str "\n" note)))))

(def ^:private conversation-file (requiring-resolve 'bbh.conversation/file))

;; Background jobs. State lives in files under the conversation's jobs directory, so a job and its result
;; survive the REPL restart that a deadline causes.
(defn- jobs-dir []
  (if-let [port (System/getenv "BBH_PORT_FILE")]
    (conversation-file (fs/parent port) :jobs)
    (str (fs/path (fs/temp-dir) "bbh-jobs"))))

(defn- heavy-command
  "CMD wrapped in bbh's machine-wide heavy-job slot when it is heavy (a test suite, a build, TLC, a browser, an eval
  replay), so one runs at a time across Workers. CMD itself without bbh.heavy on the classpath."
  [cmd & opts]
  (if-let [command (try (requiring-resolve 'bbh.heavy/command) (catch Exception _ nil))]
    (apply command cmd opts)
    cmd))

(defn job
  "Start CMD with bash, detached from the REPL, in DIR (default the current directory). Returns the job id.
  SPLIT-ERR? keeps stderr in its own file. A heavy CMD, such as bb test, a guix build or a browser, waits for the
  machine-wide heavy-job slot first and says who holds it."
  [cmd & {:keys [dir split-err?]}]
  (let [d (jobs-dir)
        id (str (System/currentTimeMillis) "-" (rand-int 1000))
        file #(str (fs/path d (str id %)))
        run (heavy-command cmd)]
    (fs/create-dirs d)
    (spit (file ".cmd") cmd)
    (when-not (= run cmd) (spit (file ".run") run))
    ;; setsid -f forks and returns, so the job is no longer the REPL's descendant and a restart's kill misses it.
    ;; The command runs from its file because a syntax error in this eval string would lose the exit code.
    (p/shell {:dir (or dir (str (fs/cwd))) :in "" :out :discard :err :discard
              :extra-env {"BBH_JOB" (str "echo $$ >" (pr-str (file ".pid"))
                                           "; stat=$(cat /proc/$$/stat); rest=${stat##*) }; set -- $rest; shift 19; echo $1 >" (pr-str (file ".start"))
                                           "; cat /proc/sys/kernel/random/boot_id >" (pr-str (file ".boot"))
                                           "; bash " (pr-str (file (if (= run cmd) ".cmd" ".run"))) " >" (pr-str (file ".log")) " "
                                           (if split-err? (str "2>" (pr-str (file ".err"))) "2>&1")
                                           " </dev/null; echo $? >" (pr-str (file ".exit")))}}
             "setsid" "-f" "bash" "-c" "eval \"$BBH_JOB\"")
    id))

(defn- job-file [id suffix] (str (fs/path (jobs-dir) (str id suffix))))

(defn- job-exit [id]
  (let [f (job-file id ".exit")]
    (when (fs/exists? f) (some-> (slurp f) str/trim not-empty parse-long))))

(defn- slurp-if [f] (if (fs/exists? f) (slurp f) ""))

(defn- lost-in-reboot?
  "Whether job ID never finished because the machine rebooted under it."
  [id]
  (let [b (job-file id ".boot")]
    (and (fs/exists? b) (not= (str/trim (slurp b)) (str/trim (slurp "/proc/sys/kernel/random/boot_id"))))))

(defn job-status
  "Job ID as {:id :cmd :done? :exit :log :tail}, with the last LINES (default 20) of output in :tail. A sh command
  that outlived its budget also has :err, the end of its stderr. A job the machine rebooted under has :lost? true."
  [id & {:keys [lines] :or {lines 20}}]
  (when-not (fs/exists? (job-file id ".cmd")) (throw (ex-info (str "no job " id) {:id id})))
  (let [end #(str/join "\n" (take-last lines (str/split-lines %)))
        exit (job-exit id)]
    ;; The exit is now read, so bbh super sends no [Job ... exited] message for it.
    (when (some? exit) (spit (job-file id ".seen") ""))
    (cond-> {:id id :cmd (slurp (job-file id ".cmd")) :done? (some? exit) :exit exit :log (job-file id ".log")
             :tail (when (fs/exists? (job-file id ".log")) (end (slurp (job-file id ".log"))))}
      (fs/exists? (job-file id ".err")) (assoc :err (end (slurp (job-file id ".err"))))
      (and (nil? exit) (lost-in-reboot? id)) (assoc :lost? true))))

(def ^:private max-wait-ms 600000)

(defn- deadline-file
  "Where a helper asks the bbh session to move the running evaluation's deadline. Absent outside bbh."
  []
  (some-> (System/getenv "BBH_PORT_FILE") fs/parent (conversation-file :deadline)))

(defn- move-deadline! [until]
  (spit (deadline-file) (str until))
  (reset! bbh.env/eval-deadline until))

(defn job-wait
  "Wait for job ID to finish, up to MS (default and most 600000), then return its job-status. Inside a bbh evaluation
  the wait moves the evaluation's deadline, so it does not count against the budget. (job-wait id ms) and
  (job-wait id {:ms ms}) also work."
  [id & args]
  (let [[ms & opts] (if (number? (first args)) args (cons nil args))
        {:keys [lines] :or {lines 20} :as opts} (if (map? (first opts)) (first opts) (apply hash-map opts))
        ms (min (or ms (:ms opts) max-wait-ms) max-wait-ms)
        now (System/currentTimeMillis)
        margin @#'bbh.env/deadline-margin-ms
        before @bbh.env/eval-deadline
        move? (and (deadline-file) before (> before now))
        end (+ now (if move? ms (min ms (bbh.env/budget-ms))))]
    (when move? (move-deadline! (max before (+ end margin))))
    ;; Until END this wait reports the exit, so bbh super holds its message back.
    (spit (job-file id ".waiting") (str end))
    (try
      (loop []
        (let [st (job-status id :lines lines)]
          (if (or (:done? st) (:lost? st) (>= (System/currentTimeMillis) end))
            st
            (do (Thread/sleep 250) (recur)))))
      (finally
        (fs/delete-if-exists (job-file id ".waiting"))
        (when move? (move-deadline! (max before (+ (System/currentTimeMillis) margin))))))))

(defn- shell-quote [s]
  (if (re-matches #"[\w@%+=:,./-]+" s) s (str "'" (str/replace s "'" "'\\''") "'")))

(defn- super-call! [command args]
  (let [{:keys [ok error message data]} ((requiring-resolve 'bbh.super.client/call!) {:command command :args args})]
    (if error (throw (ex-info (str "bbh super: " (name error) (when message (str ", " message))) (or data {}))) ok)))

(let [table @(requiring-resolve 'bbh.super.verbs/worker)
      usage (requiring-resolve 'bbh.super.verbs/usage)]
  (doseq [[sym f] ((requiring-resolve 'bbh.super.verbs/bind) super-call! table)]
    (intern *ns* (with-meta sym {:doc (str (usage sym (table sym)) "\n  " (:doc (table sym)))}) f)))

(defn pwd "The working directory." [] (str (fs/cwd)))

(defn- shell-tokens
  "CMD as bash words and the operators ; && || | and newline, with quotes removed, or nil when it has
  substitutions, redirections, heredocs or backgrounding the guard should not judge."
  [cmd]
  (when-not (re-find #"\$\(|`|[<>]|&(?!&)|\\\n" (str/replace cmd #"&&|\|\||\s2>(&1|/dev/null)" " "))
    (loop [[c & more :as cs] (seq (str/replace cmd #"\s2>(&1|/dev/null)" " ")) word nil quote nil out []]
      (let [flush #(if word (conj % word) %)]
        (cond (nil? cs) (when-not quote (flush out))
              quote (if (= c quote) (recur more (or word "") nil out) (recur more (str word c) quote out))
              (#{\' \"} c) (recur more (or word "") c out)
              (= c \\) (recur (rest more) (str word (first more)) nil out)
              (= c \#) (if word (recur more (str word c) nil out) (flush out))
              (Character/isWhitespace ^char c) (if (= c \newline) (recur more nil nil (conj (flush out) ";")) (recur more nil nil (flush out)))
              (and (#{\& \|} c) (= c (first more))) (recur (rest more) nil nil (conj (flush out) (str c c)))
              (#{\; \|} c) (recur more nil nil (conj (flush out) (str c)))
              :else (recur more (str word c) nil out))))))

(defn- stages
  "CMD as a vector of pipelines, each a vector of commands, each a vector of words; nil when shell-tokens is."
  [cmd]
  (when-let [ts (shell-tokens cmd)]
    (->> (partition-by #{";" "&&" "||"} ts) (remove (comp #{";" "&&" "||"} first))
         (mapv #(->> (partition-by #{"|"} %) (remove (comp #{"|"} first)) (mapv vec))))))

(defn- under [dir path]
  (let [home #(str/replace % #"^~(?=/|$)" (System/getProperty "user.home"))
        path (home path)]
    (cond (or (nil? dir) (str/starts-with? path "/")) path
          (= "." path) (home dir)
          :else (str (str/replace (home dir) #"/$" "") "/" (str/replace path #"^\./" "")))))

(defn- bre->ere
  "grep's basic regex PATTERN as the extended regex rg reads: \\( \\) \\| \\{ \\} \\+ \\? become operators and the bare
  characters literals."
  [pattern]
  (str/replace pattern #"\\.|[(){}|+?]" #(if (= 2 (count %)) (if (re-find #"[(){}|+?]" (subs % 1)) (subs % 1) %) (str "\\" %))))

(defn- flag-value
  "The numeric value of a short option such as -n 5, -n5 or -5 in WORDS, and WORDS without it."
  [words letter]
  (loop [[w & more :as ws] words acc []]
    (cond (empty? ws) [nil acc]
          (and (= (str "-" letter) w) (some->> (first more) (re-matches #"\d+"))) [(parse-long (first more)) (into acc (rest more))]
          (re-matches (re-pattern (str "-" letter "(\\d+)")) w) [(parse-long (subs w 2)) (into acc more)]
          (re-matches #"-(\d+)" w) [(parse-long (subs w 1)) (into acc more)]
          :else (recur more (conj acc w)))))

(defn- grep-call [bre? dir args]
  (loop [[w & more :as ws] args pattern nil paths [] opts {} include nil]
    (cond
      (empty? ws)
      (when pattern
        (let [pattern (if (and bre? (not (:ere opts)) (not (:fixed opts))) (bre->ere pattern) pattern)
              opts (dissoc opts :ere)
              pattern (if (and (:i opts) (re-find #"[A-Z]" pattern)) (str "(?i)" pattern) pattern)
              opts (dissoc opts :i)
              paths (map #(under dir %) (if (seq paths) paths (when dir ["."])))
              paths (if include (map #(str (str/replace % #"/$" "") "/**/" include) (or (seq paths) ["."])) paths)]
          (str "(grep " (pr-str pattern) (apply str (map #(str " " (pr-str %)) paths))
               (when (seq opts) (str " " (pr-str opts))) ")")))
      (#{"-e" "--regexp"} w) (recur (rest more) (first more) paths opts include)
      (#{"-C" "-A" "-B" "--context"} w) (recur (rest more) pattern paths (assoc opts :context (parse-long (first more))) include)
      (re-matches #"-[CAB]\d+" w) (recur more pattern paths (assoc opts :context (parse-long (subs w 2))) include)
      (str/starts-with? w "--include=") (when-not include (recur more pattern paths opts (subs w 10)))
      (#{"-g" "--glob" "--include"} w) (when-not (or include (str/starts-with? (str (first more)) "!"))
                                            (recur (rest more) pattern paths opts (first more)))
      (or (#{"-t" "--type" "-m" "--max-count" "--count" "--files" "--invert-match" "--only-matching" "--files-without-match"} w)
          (re-find #"^-[a-zA-Z]*[cvoLqPwx]" w)) nil
      (#{"--max-columns" "-M" "--color" "--colour" "--sort"} w) (recur (rest more) pattern paths opts include)
      (and (str/starts-with? w "--")
           (not (#{"--no-heading" "--heading" "--with-filename" "--line-number" "--recursive" "--ignore-case" "--smart-case"
                   "--fixed-strings" "--files-with-matches" "--extended-regexp" "--hidden" "--no-messages"} w))
           (not (re-find #"^--(color|colour|sort|max-columns)=" w)))
      nil
      (str/starts-with? w "-")
      (recur more pattern paths (cond-> opts
                                  (re-find #"^-[a-zA-Z]*l" w) (assoc :files true)
                                  (or (re-find #"^-[a-zA-Z]*i" w) (= "--ignore-case" w)) (assoc :i true)
                                  (or (re-find #"^-[a-zA-Z]*E" w) (= "--extended-regexp" w)) (assoc :ere true)
                                  (or (= "--files-with-matches" w)) (assoc :files true)
                                  (or (re-find #"^-[a-zA-Z]*F" w) (= "--fixed-strings" w)) (assoc :fixed true))
             include)
      (nil? pattern) (recur more w paths opts include)
      :else (recur more pattern (conj paths w) opts include))))

(defn- helper-call
  "The helper call that does what the simple command WORDS does, run in DIR, or nil when there is none."
  [dir [w & args]]
  (let [files (remove #(str/starts-with? % "-") args)
        read (fn [f & more] (str "(read-lines " (pr-str (under dir f)) (apply str (map #(str " " %) more)) ")"))]
    (case w
      "cat" (when (and (seq files) (every? #(not= "-" %) files) (= files args)) (str/join " " (map read files)))
      "head" (let [[n ws] (flag-value args "n") fs (remove #(str/starts-with? % "-") ws)]
               (when (and (= 1 (count fs)) (= fs ws)) (read (first fs) 1 (or n 10))))
      "tail" (let [[n ws] (flag-value args "n") fs (remove #(str/starts-with? % "-") ws) f (first fs)]
               (when (and (= 1 (count fs)) (= fs ws) (fs/regular-file? (under dir f)))
                 (let [total (count (text-lines (slurp (under dir f))))]
                   (read f (max 1 (inc (- total (or n 10)))) (or n 10)))))
      "sed" (when-let [[_ a b f] (and (= "-n" (first args)) (= 3 (count args))
                                       (re-matches #"(\d+)(?:,(\d+))?p (.+)" (str/join " " (rest args))))]
              (read f a (if b (inc (- (parse-long b) (parse-long a))) 1)))
      "grep" (grep-call true dir args)
      ("rg" "egrep") (grep-call false dir args)
      "ls" (when-not (some #(re-find #"^-[a-zA-Z]*[tSRr]" %) args) (str "(ls" (apply str (map #(str " " (pr-str (under dir %))) (or (seq files) (when dir ["."])))) ")"))
      "wc" (when (and (#{["-l"]} (vec (filter #(str/starts-with? % "-") args))) (seq files))
             (str "(ls" (apply str (map #(str " " (pr-str (under dir %))) files)) ")"))
      ("find" "fd") (let [[root & opts] (if (and (= w "find") (seq args) (not (str/starts-with? (first args) "-"))) args (cons "." args))
                         opts (vec opts)
                         nm (some (fn [[k v]] (when (#{"-name" "-iname"} k) v)) (partition 2 1 opts))
                         others (remove #{"-name" "-iname" nm "-type" "f" "d"} opts)]
                     (when (and (= w "find") (empty? others))
                       (str "(ls " (pr-str (str (str/replace (under dir root) #"/$" "") "/**" (when nm (str "/" nm)))) ")")))
      nil)))

(defn- guard-suggestion
  "Helper calls that replace CMD when every command in it is a file read, search or listing, each optionally piped
  into head or tail, or nil when the shell is the right tool."
  [cmd]
  (when-not (re-find #"#\s*confirm\s*$" cmd)
    (when-let [ss (stages cmd)]
      (loop [[pipeline & more] ss dir nil calls []]
        (cond (nil? pipeline) (when (seq calls) (str/join " " calls))
              (= "cd" (ffirst pipeline)) (when (and (= 1 (count pipeline)) (= 2 (count (first pipeline))))
                                              (recur more (under dir (second (first pipeline))) calls))
              (and (= 2 (count pipeline)) (= "cat" (ffirst pipeline)) (= 2 (count (first pipeline))) (= "head" (first (second pipeline))))
              (when-let [c (helper-call dir (conj (second pipeline) (second (first pipeline))))] (recur more dir (conj calls c)))
              (every? #(#{"head" "tail"} (first %)) (rest pipeline))
              (when-let [c (helper-call dir (first pipeline))] (recur more dir (conj calls c)))
              :else nil)))))

(def sh-guard
  "When true, sh and $ refuse a command that only reads, searches or lists files and name the helper instead."
  (atom true))

(defn- guard! [cmd]
  (when @sh-guard
    (when-let [calls (guard-suggestion cmd)]
      (when-let [port (System/getenv "BBH_PORT_FILE")]
        (try (spit (str (fs/path (fs/parent port) "sh-guard.jsonl")) (str (json/generate-string {:at (str (java.time.Instant/now)) :cmd cmd :helper calls}) "\n") :append true)
             (catch Exception _ nil)))
      (throw (ex-info (str "sh: use " calls " instead; the helpers cap output, number lines and need no quoting. "
                           "Append # confirm to the command to run it in the shell anyway.")
                      {:cmd cmd :helper calls})))))

(defn- run-sh
  "sh without the guard, for the helpers that call rg themselves."
  [cmd & args]
  (let [cmd (if (seq args) (str/join " " (map (comp shell-quote str) (cons cmd args))) cmd)
        end (+ (System/currentTimeMillis) (bbh.env/budget-ms))
        id (job cmd :split-err? true)
        pid (job-file id ".pid")
        output (fn [] {:out (slurp-if (job-file id ".log")) :err (slurp-if (job-file id ".err"))})]
    (swap! bbh.env/live-sh conj pid)
    (try
      (loop [pause 2]
        (if-let [exit (job-exit id)]
          (let [result (assoc (output) :exit exit)]
            (doseq [suffix [".cmd" ".run" ".log" ".err" ".exit" ".pid" ".start" ".boot"]] (fs/delete-if-exists (job-file id suffix)))
            result)
          (if (>= (System/currentTimeMillis) end)
            (assoc (output) :exit nil :timeout true :job id)
            (do (Thread/sleep pause) (recur (min 100 (* 2 pause)))))))
      (finally (swap! bbh.env/live-sh disj pid)))))

(defn sh
  "Run CMD with bash and an empty stdin. Returns {:exit :out :err}. With ARGS, CMD and ARGS are one argv, each word
  quoted for bash. A command that outlasts the evaluation's budget keeps running as a job, and the result is
  {:exit nil :timeout true :job id :out :err} with the output so far. A CMD string that only reads, searches or
  lists files throws with the helper call to use instead, unless it ends with # confirm."
  [cmd & args]
  (when (empty? args) (guard! cmd))
  (apply run-sh cmd args))

(defn $
  "Run sh and print what it returned: stdout, then stderr, the exit code when it is nonzero, and the job id when
  the command outlasted the budget. Returns nil."
  [cmd & args]
  (let [{:keys [out err exit timeout job]} (apply sh cmd args)]
    (when (seq out) (print out) (when-not (str/ends-with? out "\n") (println)))
    (when (seq err) (println "stderr:") (print err) (when-not (str/ends-with? err "\n") (println)))
    (cond timeout (println (str "[still running as job " job "; (job-wait " (pr-str job) ")]"))
          (not (zero? exit)) (println (str "[exit " exit "]")))
    (flush)))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn- glob? [s] (boolean (re-find #"[*?\[{]" (str s))))

(defn- glob-parts
  "[base glob] for a shell-style glob: its literal leading directories, and the rest anchored to them for rg."
  [g]
  (let [segs (str/split g #"/" -1)
        lit (take-while (complement glob?) (butlast segs))
        base (str/join "/" lit)]
    [(cond (= [""] lit) "/" (seq lit) base :else ".")
     (str "/" (str/join "/" (drop (count lit) segs)))]))

(defn- rg-in
  "Run rg with ARGV, whose last word is `.`, inside DIR. Returns sh's map plus :lines, its output lines with
  paths as seen from the working directory."
  [dir argv]
  (let [r (run-sh (str (when (not= dir ".") (str "cd " (shell-quote dir) " && "))
                   (str/join " " (map shell-quote (cons "rg" argv)))))
        prefix (if (= dir ".") "" (str (str/replace dir #"/$" "") "/"))]
    (assoc r :lines (vec (for [l (str/split-lines (:out r)) :when (seq l)]
                           (if (= "--" l) l (str prefix (str/replace l #"^\./" ""))))))))

(defn- print-capped
  "Print LINES, a lazy seq of TOTAL lines, up to MAX lines and about 6000 characters, then what was left out."
  [lines total max hint]
  (loop [[l & more :as ls] lines shown 0 chars 0]
    (if (and (seq ls) (< shown max) (< (+ chars (count l)) 6000))
      (do (println l) (recur more (inc shown) (+ chars 1 (count l))))
      (when (< shown total) (println (str "[" (- total shown) " more; " hint "]"))))))

(defn grep
  "Search file contents for the rg regex PATTERN and print `path:line:text` hits, capped. ARGS are files,
  directories or shell-style globs (default `.`), then an optional map {:context n :files true :fixed true :max n}."
  [pattern & args]
  (let [[opts paths] (if (map? (last args)) [(last args) (butlast args)] [{} args])
        {:keys [context files fixed max] :or {max 100}} opts
        flags (concat ["--no-heading" "--with-filename" "--line-number" "--color" "never" "--smart-case" "--sort" "path"
                       "--max-columns" "300" "--max-columns-preview"]
                      (when files ["--files-with-matches"])
                      (when fixed ["--fixed-strings"])
                      (when context ["--context" (str context)]))
        plain (remove glob? paths)
        runs (concat (when (or (seq plain) (empty? paths))
                       [["." (concat flags ["-e" pattern "--"] (or (seq plain) ["."]))]])
                     (for [g (filter glob? paths) :let [[base gl] (glob-parts g)]]
                       [base (concat flags ["--glob" gl "-e" pattern "--" "."])]))
        results (mapv (fn [[dir argv]] (rg-in dir argv)) runs)
        lines (vec (mapcat :lines results))
        hits (if files lines (keep #(second (re-find #"^(.+?):\d+:" %)) lines))]
    (when-let [bad (first (filter #(and (= 2 (:exit %)) (empty? (:lines %))) results))]
      (throw (ex-info (str "grep: " (str/trim (:err bad)) "\nEscape regex characters, or pass {:fixed true} to match the text literally.")
                      {:pattern pattern :paths paths})))
    (if (empty? lines)
      (println (str "No matches for " (pr-str pattern) " in " (str/join " " (or (seq paths) ["."]))
                    (when (str/includes? pattern "\\|") ". rg writes alternation as a|b, not a\\|b.")))
      (do (println (if files (plural (count hits) "file" "files") (str (plural (count hits) "match" "matches") " in " (plural (count (distinct hits)) "file" "files"))))
          (print-capped lines (count lines) max "narrow the pattern or paths, or pass {:max n}")))
    (doseq [r results :when (seq (:err r))] (println (str "stderr: " (first (str/split-lines (:err r))))))
    (flush)))

(defn- file-note [f]
  (let [n (fs/size f)]
    (if (> n 1000000)
      (format "%.1f MB" (/ n 1e6))
      (let [bs (fs/read-all-bytes f)]
        (if (binary? f bs)
          (plural n "byte" "bytes")
          (plural (line-count (String. ^bytes bs "UTF-8")) "line" "lines"))))))

(defn ls
  "Print each PATH (default `.`): a directory's entries one level deep, the files matching a shell-style glob
  (gitignored files left out), or a file, each with its line count. Capped at 200 entries per path."
  [& paths]
  (doseq [p (or (seq paths) ["."])]
    (let [[names render]
          (cond (glob? p)
                (let [[base g] (glob-parts p)]
                  [(sort (:lines (rg-in base ["--files" "--color" "never" "--glob" g "."]))) #(str % "  (" (file-note %) ")")])
                (fs/directory? p)
                [(sort-by (juxt (complement fs/directory?) #(str (fs/file-name %)))
                          (remove #(str/starts-with? (str (fs/file-name %)) ".") (fs/list-dir p)))
                 #(let [n (str (fs/file-name %))]
                    (if (fs/directory? %) (str n "/  (" (plural (count (fs/list-dir %)) "entry" "entries") ")") (str n "  (" (file-note %) ")")))]
                (fs/exists? p) [[p] #(str % "  (" (file-note %) ")")]
                :else (throw (ex-info (str "Not found: " p (some->> (nearby-path p) (format ". Did you mean `%s`?"))) {})))]
      (when (next paths) (println (str p ":")))
      (if (empty? names) (println "(none)") (print-capped (map render names) (count names) 200 "pass a narrower path or glob"))))
  (flush))

(defn jobs
  "Every job of this conversation, newest first, without output."
  []
  (let [d (jobs-dir)]
    (if-not (fs/exists? d)
      []
      (->> (fs/glob d "*.cmd") (map #(str/replace (str (fs/file-name %)) #"\.cmd$" ""))
           (sort-by #(parse-long (first (str/split % #"-")))) reverse
           (mapv #(dissoc (job-status % :lines 0) :tail))))))

;; The flatpak Chrome, since Guix has no aarch64 chromium substitute.
(def shot-dir "/tmp/bbh-shots")

(defn shot
  "Headless screenshot of URL (default localhost:8787) with flatpak Chrome. Waits WAIT ms of virtual time for JS.
  Returns the png path."
  ([] (shot "http://localhost:8787/"))
  ([url & {:keys [w h name wait] :or {w 1280 h 900 wait 8000}}]
   (fs/create-dirs shot-dir)
   (let [out (str shot-dir "/" (or name (str "shot-" w "x" h "-" (System/currentTimeMillis))) ".png")
         _ (fs/delete-if-exists out)
         ;; One profile per call: Chrome refuses a profile another instance holds,
         ;; and several sessions take screenshots at once.
         profile (str "/tmp/bbh-chrome-" (random-uuid))
         limit (min 60000 (bbh.env/budget-ms))
         argv ["flatpak" "run" (str "--filesystem=" shot-dir) "com.google.Chrome"
               "--headless=new" "--disable-gpu" "--no-first-run" "--hide-scrollbars"
               (str "--user-data-dir=" profile) (str "--virtual-time-budget=" wait)
               (str "--screenshot=" out) (str "--window-size=" w "," h) url]
         ;; Chrome takes the machine-wide heavy-job slot, waiting at most half the limit, so screenshots wait for a running test suite.
         ;; stderr goes to a file, so the wait ends when Chrome exits, not when a child that kept the pipe does.
         err-file (str profile ".err")
         proc (p/process {:out :discard :err (fs/file err-file)}
                         "bash" "-c" (heavy-command (str/join " " (map shell-quote argv)) :timeout-s (max 1 (quot limit 2000))))
         ;; Always SIGKILL the tree, even after a clean exit or an interrupt: Chrome can leave its sandbox behind,
         ;; and SIGTERM strands flatpak's bwrap with Chrome inside it.
         r (try (deref proc limit nil)
                (finally ((requiring-resolve 'bbh.repl/kill-tree!) proc)
                         ;; A Chrome process that left the tree still names its unique profile.
                         (p/shell {:continue true :out :discard :err :discard} "pkill" "-KILL" "-f" profile)
                         (fs/delete-tree profile)))
         err (str/join "\n" (take-last 5 (str/split-lines (str (when (fs/exists? err-file) (slurp err-file))))))]
     (fs/delete-if-exists err-file)
     (cond (fs/exists? out) out
           (nil? r) (throw (ex-info (str "screenshot of " url " timed out after " limit " ms") {}))
           :else (throw (ex-info (str "screenshot of " url " failed, exit " (:exit r) (when (seq err) (str ":\n" err))) {}))))))

(defn explainer!
  "Write an explanation for the person to PATH as one self-contained HTML page with the shared explainer style
  and return PATH. OPTS: :title, :description, :thread (such as \"T12\", which lists it in the phone's Docs), :body
  (the HTML under the title, with inline SVG for diagrams) and :style (extra CSS)."
  [path {:keys [title description] :as opts}]
  (when (or (str/blank? title) (str/blank? description))
    (throw (ex-info "explainer! needs a :title and a :description" {})))
  (fs/create-dirs (fs/parent (fs/absolutize path)))
  (spit (str path) (explainer/page opts))
  (str (fs/absolutize path)))

(defn look
  "Have a Claude delegate view the png PATHS and answer QUESTION in text."
  [question & paths]
  (when-let [bad (seq (remove #(and (string? %) (fs/exists? %)) paths))]
    (throw (ex-info (str "look needs existing png paths; not found: " (str/join ", " (map pr-str bad))) {})))
  (:reply (delegate (str "Use the Read tool to view these screenshots of a web app: " (str/join ", " paths)
                         "\n\n" question
                         "\nBe concrete: layout, spacing, alignment, typography, colors, overflow. Under 250 words."))))
