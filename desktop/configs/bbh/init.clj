(ns bbh.env
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def base-prompt
  "You are a coding agent working inside a persistent babashka REPL through the `clojure` tool.

- Each call evaluates Clojure in the user namespace and returns printed output and the last value. Prefer one call that does several steps over many small calls.
- Definitions persist for the whole conversation until the REPL process restarts. A restart happens when an evaluation exceeds its deadline, when the user interrupts an evaluation, or on /restart. The tool result tells you when it happened.
- You own this environment. Require any namespace bb ships, define helpers, and change how you are prompted by redefining `bbh.env/manifest`: a map, or a function returning one, with optional keys :system, :tool-description, :deadline-ms and :max-output-chars. It is read before every model request. `bbh.env/expand-prompt` rewrites user input before you see it.
- To keep a change across restarts and conversations, edit ~/.config/bbh/init.clj, which every new REPL loads.
- Run host programs with `sh`, or babashka.process directly. Read files with `read-lines` and edit them with `edit!`.
- The user may be connected to this same REPL from their editor over nREPL.")

(def helper-reference
  "# REPL API quick reference

These are Babashka APIs, not Pi's fs helpers. The aliases fs, p and str are already available in user.

- (sh command-string) returns {:exit integer :out string :err string}. Example: (sh \"node --test\"). Pass one shell-command string, not separate arguments. A nonzero exit is data; inspect :exit.
- (p/shell {:out :string :err :string :continue true} \"node\" \"--test\") is the separate-arguments alternative. Options come first. It returns a process result with :exit, :out and :err.
- (fs/list-dir \".\") returns directory entries as Path objects. Use (mapv str (fs/list-dir \".\")) for printable names. There is no fs/list function here.
- (fs/glob root pattern) takes two required arguments and an optional third options map. To enumerate all files including root-level files: (->> (fs/glob \".\" \"**\" {:hidden true}) (filter fs/regular-file?) (mapv str)). Glob results include directories. The pattern \"**/*\" omits root-level files such as README.md and package.json. Do not use Pi's one-argument fs/glob calling convention.
- (slurp path) returns the complete file as a string. (spit path text) writes it and returns nil. Create parent directories first with (fs/create-dirs directory).
- (read-lines path) or (read-lines path start-line line-count) prints numbered lines and returns nil. start-line is 1-based. Use slurp, not read-lines, when your code needs the file contents.
- (edit! path old-text new-text) replaces exactly one occurrence and returns :ok. It throws if old-text is absent or repeated. Example: (edit! \"src/app.clj\" \"old-name\" \"new-name\").
- For JSON, (require '[cheshire.core :as json]) then (json/parse-string (slurp path) true) returns keyword-keyed data.

Only the final expression's value is returned. Print intermediate results with println or prn, or return a vector/map containing all results. Batch independent reads and an edit plus its verification when the dependencies allow it.")

(def pi-helpers
  "Skills written for Pi's codemode name Pi helpers. Here every call blocks, so drop `await`, and use these instead: `(tools/jev_classify_task {:task t})` is `(jev-classify-task t)`; `fs/read`, `tools/read` and `fs/list` are `read-lines`, `slurp` and `fs/glob`; `tools/bash` is `sh`; `tools/edit` is `edit!`; `jj/...` and `make/...` are `sh` with jj or make. Run tool calls in parallel with `pmap` or `future`.")

(defn- resolve-key
  "Pi's auth.json convention: !CMD runs CMD, anything else is the key."
  [v]
  (if (str/starts-with? v "!")
    (str/trim (:out (p/shell {:out :string} "bash" "-c" (subs v 1))))
    v))

(defn- pi-key [provider]
  (some-> (fs/expand-home "~/.pi/agent/auth.json") str slurp (json/parse-string true) (get-in [(keyword provider) :key]) resolve-key))

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
                                         {:headers {"Authorization" (str "Bearer " (pi-key "typesafe"))
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
        p (get probabilities (keyword choice))
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

(defn- project-instructions []
  (some->> ["AGENTS.md" "CLAUDE.md"] (filter fs/exists?) first slurp))

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
(def
 delegate-system
 "You are a delegated agent working for another agent. Your one tool, bash, runs in the working directory. Do the brief and nothing else. Do not edit tracked files in the working directory. Run every check you report, and never report a check you did not run. End with the report format the brief asks for.")

(def
 bash-tool
 {:type "function",
  :function
  {:name "bash",
   :description
   "Run a bash command in the working directory. Each call is a fresh shell. Returns the exit code, stdout and stderr.",
   :parameters
   {:type "object",
    :properties {:command {:type "string"}},
    :required ["command"]}}})

(defn-
 clip
 [s]
 (if
  (> (count s) 20000)
  (str (subs s 0 20000) "\n[output truncated]")
  s))

(defn-
 run-bash
 [command]
 (clip
  (try
   (let
    [{:keys [exit out err]}
     (p/shell
      {:continue true, :out :string, :err :string, :timeout 600000}
      "bash"
      "-c"
      command)]
    (str "exit " exit "\n" out err))
   (catch Exception e (str "error: " (ex-message e))))))

(defn-
 chat!
 [model messages]
 (let
  [key
   (or
    (System/getenv "OPENROUTER_API_KEY")
    (pi-key "openrouter")
    (throw
     (ex-info
      "Set OPENROUTER_API_KEY or log in to OpenRouter in Pi"
      {})))
   resp
   (http/post
    "https://openrouter.ai/api/v1/chat/completions"
    {:headers
     {"Authorization" (str "Bearer " key),
      "Content-Type" "application/json"},
     :body
     (json/generate-string
      {:model model,
       :messages messages,
       :tools [bash-tool],
       :usage {:include true}}),
     :throw false,
     :timeout 300000})]
  (when-not
   (= 200 (:status resp))
   (throw
    (ex-info (str "OpenRouter " (:status resp) ": " (:body resp)) {})))
  (json/parse-string (:body resp) true)))

(def delegate-model "claude/claude-opus-5-5")

(defn-
 openrouter-delegate
 [model brief max-turns]
 (loop
  [messages
   [{:role "system", :content delegate-system}
    {:role "user", :content brief}]
   turn
   0
   tokens
   0]
  (let
   [{:keys [choices usage]}
    (chat! model messages)
    msg
    (:message (first choices))
    tokens
    (+ tokens (or (:total_tokens usage) 0))
    messages
    (conj
     messages
     (cond->
      {:role "assistant", :content (:content msg)}
      (seq (:tool_calls msg))
      (assoc :tool_calls (:tool_calls msg))))]
   (cond
    (empty? (:tool_calls msg))
    {:reply (:content msg), :turns turn, :total-tokens tokens}
    (>= turn max-turns)
    {:reply "Stopped at the turn limit.",
     :turns turn,
     :total-tokens tokens}
    :else
    (recur
     (into
      messages
      (for
       [{:keys [id function]} (:tool_calls msg)]
       {:role "tool",
        :tool_call_id id,
        :content
        (run-bash
         (:command (json/parse-string (:arguments function) true)))}))
     (inc turn)
     tokens)))))

(defn-
 claude-delegate
 [model brief]
 (let
  [{:keys [exit out err]}
   (p/shell
    {:continue true,
     :in brief,
     :out :string,
     :err :string,
     :timeout 1200000}
    (or (System/getenv "BBH_CLAUDE") "claude")
    "-p"
    "--model"
    model
    "--output-format"
    "json"
    "--system-prompt"
    delegate-system
    "--tools"
    "Bash,Read,Grep,Glob"
    "--allowedTools"
    "Bash,Read,Grep,Glob"
    "--setting-sources"
    ""
    "--strict-mcp-config"
    "--mcp-config"
    "{\"mcpServers\":{}}")
   {:keys [result is_error num_turns usage]}
   (try (json/parse-string out true) (catch Exception _ nil))]
  (when
   (or (not= 0 exit) is_error (nil? result))
   (throw
    (ex-info
     (str
      "claude delegate failed (exit "
      exit
      "): "
      (or result err out))
     {})))
  {:reply result,
   :turns num_turns,
   :total-tokens
   (reduce
    +
    (map
     (fn* [%1#] (or (get usage %1#) 0))
     [:input_tokens
      :cache_creation_input_tokens
      :cache_read_input_tokens
      :output_tokens]))}))

(defn
 delegate
 "Run BRIEF in a fresh conversation with one bash tool. MODEL is bbh-style: claude/ runs Claude Code, openrouter/ runs the bash loop, which stops after MAX-TURNS tool rounds. Returns {:reply :turns :total-tokens}."
 [brief
  &
  {:keys [model max-turns], :or {model delegate-model, max-turns 40}}]
 (cond
  (str/starts-with? model "claude/")
  (claude-delegate (subs model (count "claude/")) brief)
  (str/starts-with? model "openrouter/")
  (openrouter-delegate
   (subs model (count "openrouter/"))
   brief
   max-turns)
  :else
  (throw
   (ex-info
    (str "Unknown provider in " model "; use claude/ or openrouter/")
    {}))))

(defn
 pre-ship-verdict
 "Verify GitHub PR NUMBER independently before it ships. A fresh agent checks the head out in a new directory, runs the checks and exercises the surface. :verdict is PASS, PASS+NOTES, FAIL, or nil when no verdict line came back."
 [number]
 (let
  [brief
   (str/join
    \newline
    [(str
      "You verify GitHub PR #"
      number
      " independently before it lands. You did not write this change, so judge it from its artifacts.")
     ""
     (str
      "1. Run `gh pr view "
      number
      " --json headRefOid,baseRefName,title,body` and `gh pr diff "
      number
      "`. Record the head SHA.")
     "2. Create a new empty directory outside the working copy and check out the head SHA there from origin. Do not touch the working copy."
     "3. Run the build and tests that AGENTS.md or the README names for this change."
     "4. Exercise the real surface the change affects the way a user would, and record what you saw. Passing tests alone do not count."
     "5. Compare the diff with the PR body and flag any mismatch."
     ""
     "Finish with a report that gives the head SHA, each check with its command and result, and what you could not verify. The last line must be exactly one of `VERDICT: PASS`, `VERDICT: PASS+NOTES` or `VERDICT: FAIL`. Use PASS+NOTES only for non-blocking issues you list. Use FAIL when a check fails or the surface cannot be exercised."])
   result
   (delegate brief)
   [_ verdict]
   (re-find
    #"VERDICT:[ ]*(PASS[+]NOTES|PASS|FAIL)"
    (or (:reply result) ""))]
  (assoc result :verdict verdict)))


(ns user
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.env :refer [jev-classify-task delegate pre-ship-verdict]]
            [clojure.string :as str]))

(defn sh
  "Run CMD with bash. Returns {:exit :out :err}."
  [cmd]
  (select-keys (p/shell {:continue true :out :string :err :string} "bash" "-c" cmd)
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
