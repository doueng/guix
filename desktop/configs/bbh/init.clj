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
- Definitions persist across turns. A deadline (120 s by default), an interrupt or /restart kills the REPL; it then reloads init.clj and replays your definitions from a checkpoint, so `defn`s and `def`s of readable values come back, while atoms, open resources and failed forms do not. The tool result says what was restored. The checkpoint is a plain file under the conversation directory, so keep secrets out of `def`s and read them inside functions instead. When you will repeat work, such as parsing the same kind of file each turn, define a `defn` helper once and call it after. The user may share this REPL over nREPL.
- You own this environment. Redefine `bbh.env/manifest` to change your prompt and limits; `bbh.repl/defaults` lists the keys. Changing :system, :tool-description or :effort misses the prompt cache and restarts Claude Code, so do it rarely. Persist changes in ~/.config/bbh/init.clj.
- Keep replies brief and lead with the outcome.")

(def helper-reference
  "# Helpers

`fs`, `p` and `str` are aliased.

- (sh \"cmd string\") returns {:exit :out :err}. A nonzero exit is data. If the command outlasts the evaluation's budget it keeps running and sh returns {:timeout true :job id}. (sh \"cmd\" \"arg\" ...) runs one argv, each word quoted.
- (read-lines path) or (read-lines path start count) prints a line-count header and 1-based numbered lines (200 by default) and returns nil. It refuses binary files. Use slurp when code needs the text.
- (edit! path old new) replaces exactly one occurrence, or every one with `:all true`, else throws. It tolerates whitespace and indentation drift in old, keeps BOM and CRLF, and returns a summary with a diff.
- (job cmd) starts a detached command that survives restarts and returns an id. (job-wait id) waits for it to finish, up to 10 min or :ms, and returns {:done? :exit :tail :log}. Waiting does not count against the evaluation's budget, so one call covers a long command. p/shell, http and Thread/sleep do count, so use a job for anything that may approach the deadline. Also job-status and jobs.
- (shot url) saves a headless Chrome screenshot and returns its path. (look question & paths) has a Claude delegate describe images, since tool results carry text only. (delegate brief) runs a fresh agent, for an independent check.
- (fs/glob root pattern) needs both arguments. \"**\" includes root-level files and \"**/*\" does not. Results include directories.")

(def super-helpers
  "# Super-agent

This session is a Worker of bbh super: a thread's work, with a controller that relays your asks to the person and their replies back as mailbox messages. Call these instead of waiting in the conversation.

- (super-ask! {:kind :question :text \"...\" :options [{:id \"no\" :label \"No\"} ...] :recommended \"no\" :stopped true}) files a question (or :action, or :review with :revision, the commit id to approve from `jj log -r @- --no-graph -T commit_id`) and returns its id. An approval names that revision and the wf evidence as it was when you asked, so ask for review only once the evidence is final. With :stopped true, end your turn after it; the reply arrives as a new prompt. Omit :stopped when you can go on meanwhile.
- (super-answered! \"reply-id\") after you have acted on a reply, naming the id the mailbox message gave.
- (super-note! \"text\") updates the thread's summary line; (super-milestone! \"text\") marks progress worth the person's notice.
- (super-done!) when the wf run is done, (super-failed! \"why\") when it cannot finish. Both end the Worker.")

(def router-helpers
  "# Router

You are the Router of bbh super: one long-lived conversation that reads what the person says and the events no thread owns. Each prompt is a batch of messages, oldest first, each tagged with its origin. Each message arrives with Jev's triage, such as `(Jev: request 0.98)`, and messages Jev was sure were noise never reach you, so do not classify again. For every message: a question or a request for information gets a one-line answer from what (super-threads) and (super-thread id) show; a feature, fix or chore becomes a thread with (super-thread! title), whose returned handle you bind and reuse (also when a later call fails; never look a new thread up), and a Worker with (super-start-worker! {:for \"o12\" :thread id :workflow :change :brief text :repo \"/home/engstrand/guix\" :check \"make lint\"}), where :for is the id that starts the message's line, so the Worker gets that origin's capabilities (:investigation for an open question, :bug-fix with :repro for a failure; :check is the command that proves the change, `make lint` for this repository unless the person names one); noise gets no reply. A `[routed to T12 W3 from herdr] ...` line was already delivered to that Worker by its handle or a linked resource; take it as context and do nothing. Reply in one line per message, such as \"Started T15 (change workflow). I'll ask if it needs you.\" Your tool evaluates in a sandbox: pure Clojure plus jev-classify-task, (super-threads), (super-thread id), (super-worker id), (super-ask id), (super-reply id) (each names the ids it links, so follow them), (super-events {:thread id :after seq}), (super-thread! title), (super-link! id resource), (super-start-worker! spec), (super-steer! worker text), (super-cancel! worker reason), (super-note! id text) and (super-milestone! id text). It has no shell, files or Java: Workers do that.")

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
    (doseq [suffix [".cmd" ".log" ".err" ".exit" ".pid" ".start"]]
      (fs/delete-if-exists (str/replace f #"\.pid$" suffix))))
  (reset! live-sh #{})
  nil)

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
  {:system (if (= "router" (System/getenv "BBH_ROLE"))
             router-helpers
             (str base-prompt
                  "\n\n" helper-reference
                  "\n\n" pi-helpers
                  (when (System/getenv "BBH_SUPER_TOKEN") (str "\n\n" super-helpers))
                  "\n\nWorking directory: " (fs/cwd)
                  (some->> (skill-index) (str "\n\n"))
                  (when-let [p (project-instructions)]
                    (str "\n\n# Project instructions\n\n" p))))})

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

(defn read-lines
  "Print N lines of PATH from line START, numbered, under a header with the file's line count."
  ([path] (read-lines path 1 200))
  ([path start n]
   (let [bs (read-bytes path)
         _ (when (binary? path bs) (throw (ex-info (str "Cannot read binary file: " path) {})))
         lines (text-lines (str/replace-first (String. ^bytes bs "UTF-8") #"^\uFEFF" ""))
         total (count lines)
         offset (dec (max 1 start))
         end (min (+ offset n) total)
         width (count (str total))
         clip #(if (> (count %) 16384) (str (subs % 0 16384) " …[line truncated]") %)]
     (println
      (cond (zero? total) "(empty file)"
            (>= offset total) (format "(%d lines total; offset %d is past end of file — nothing to show)" total (inc offset))
            :else (str (format "(%d lines total, showing lines %d-%d)\n\n" total (inc offset) end)
                       (str/join "\n" (for [i (range offset end)]
                                         (format (str "%" width "d: %s") (inc i) (clip (lines i)))))))))))

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

(defn edit!
  "Replace OLD with NEW in PATH and return a summary with a diff. OLD must match once, or pass :all true.
  Whitespace and indentation drift in OLD is tolerated, and the summary says when it was."
  [path old new & {:keys [all]}]
  (when (empty? old) (throw (ex-info "OLD must not be empty. Provide the exact text to replace." {})))
  (let [{:keys [content] :as src} (decode-for-edit path)
        old (str/replace old "\r\n" "\n")
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
          result (reduce (fn [s [b e]] (str (subs s 0 b) new (subs s e))) content (reverse ranges))
          {:keys [text note]} (reencode src result)
          tmp (fs/create-temp-file {:dir (fs/parent (fs/absolutize path)) :prefix ".edit-"})
          [b e] (first ranges)
          matched (subs content b e)
          delta (reduce + (for [[b e] ranges] (- (line-count new) (line-count (subs content b e)))))]
      (spit (str tmp) text)
      (fs/set-posix-file-permissions tmp (fs/posix-file-permissions path))
      (fs/move tmp path {:replace-existing true :atomic-move true})
      (str "Applied edit" (when all (str " (" (count ranges) " replacements)"))
           (when fallback (str " (matched via " fallback " fallback — exact text not found; whitespace/indent tolerated)"))
           (when note (str "\n" note))
           (when-not (zero? delta) (format " (%+d lines)" delta))
           (when (and (<= (line-count matched) 200) (<= (line-count new) 200))
             (show-diff path content b matched new))))))

;; Background jobs. State lives in files under the conversation's jobs directory, so a job and its result
;; survive the REPL restart that a deadline causes.
(defn- jobs-dir []
  (str (if-let [port (System/getenv "BBH_PORT_FILE")]
         (fs/path (fs/parent port) "jobs")
         (fs/path (fs/temp-dir) "bbh-jobs"))))

(defn job
  "Start CMD with bash, detached from the REPL, in DIR (default the current directory). Returns the job id.
  SPLIT-ERR? keeps stderr in its own file."
  [cmd & {:keys [dir split-err?]}]
  (let [d (jobs-dir)
        id (str (System/currentTimeMillis) "-" (rand-int 1000))
        file #(str (fs/path d (str id %)))]
    (fs/create-dirs d)
    (spit (file ".cmd") cmd)
    ;; setsid -f forks and returns, so the job is no longer the REPL's descendant and a restart's kill misses it.
    ;; The command runs from its file because a syntax error in this eval string would lose the exit code.
    (p/shell {:dir (or dir (str (fs/cwd))) :in "" :out :discard :err :discard
              :extra-env {"BBH_JOB" (str "echo $$ >" (pr-str (file ".pid"))
                                           "; stat=$(cat /proc/$$/stat); rest=${stat##*) }; set -- $rest; shift 19; echo $1 >" (pr-str (file ".start"))
                                           "; bash " (pr-str (file ".cmd")) " >" (pr-str (file ".log")) " "
                                           (if split-err? (str "2>" (pr-str (file ".err"))) "2>&1")
                                           " </dev/null; echo $? >" (pr-str (file ".exit")))}}
             "setsid" "-f" "bash" "-c" "eval \"$BBH_JOB\"")
    id))

(defn- job-file [id suffix] (str (fs/path (jobs-dir) (str id suffix))))

(defn- job-exit [id]
  (let [f (job-file id ".exit")]
    (when (fs/exists? f) (some-> (slurp f) str/trim not-empty parse-long))))

(defn- slurp-if [f] (if (fs/exists? f) (slurp f) ""))

(defn job-status
  "Job ID as {:id :cmd :done? :exit :log :tail}, with the last LINES (default 20) of output in :tail. A sh command
  that outlived its budget also has :err, the end of its stderr."
  [id & {:keys [lines] :or {lines 20}}]
  (when-not (fs/exists? (job-file id ".cmd")) (throw (ex-info (str "no job " id) {:id id})))
  (let [end #(str/join "\n" (take-last lines (str/split-lines %)))
        exit (job-exit id)]
    (cond-> {:id id :cmd (slurp (job-file id ".cmd")) :done? (some? exit) :exit exit :log (job-file id ".log")
             :tail (when (fs/exists? (job-file id ".log")) (end (slurp (job-file id ".log"))))}
      (fs/exists? (job-file id ".err")) (assoc :err (end (slurp (job-file id ".err")))))))

(def ^:private max-wait-ms 600000)

(defn- deadline-file
  "Where a helper asks the bbh session to move the running evaluation's deadline. Absent outside bbh."
  []
  (some-> (System/getenv "BBH_PORT_FILE") fs/parent (fs/path "deadline") str))

(defn- move-deadline! [until]
  (spit (deadline-file) (str until))
  (reset! bbh.env/eval-deadline until))

(defn job-wait
  "Wait for job ID to finish, up to MS (default and most 600000), then return its job-status. Inside a bbh evaluation
  the wait moves the evaluation's deadline, so it does not count against the budget. (job-wait id ms) also works."
  [id & args]
  (let [[ms & opts] (if (number? (first args)) args (cons nil args))
        {:keys [lines] :or {lines 20} :as opts} (apply hash-map opts)
        ms (min (or ms (:ms opts) max-wait-ms) max-wait-ms)
        now (System/currentTimeMillis)
        margin @#'bbh.env/deadline-margin-ms
        before @bbh.env/eval-deadline
        move? (and (deadline-file) before (> before now))
        end (+ now (if move? ms (min ms (bbh.env/budget-ms))))]
    (when move? (move-deadline! (max before (+ end margin))))
    (try
      (loop []
        (let [st (job-status id :lines lines)]
          (if (or (:done? st) (>= (System/currentTimeMillis) end))
            st
            (do (Thread/sleep 250) (recur)))))
      (finally
        (when move? (move-deadline! (max before (+ (System/currentTimeMillis) margin))))))))

(defn- shell-quote [s]
  (if (re-matches #"[\w@%+=:,./-]+" s) s (str "'" (str/replace s "'" "'\\''") "'")))

;; Super-agent Worker helpers, over the controller's socket with this Worker's token.

(defn- super-call! [command args]
  (let [{:keys [ok error message data]} ((requiring-resolve 'bbh.super.client/call!) {:command command :args args})]
    (if error (throw (ex-info (str "bbh super: " (name error) (when message (str ", " message))) (or data {}))) ok)))

(defn super-ask! "File ASK ({:kind :text :options :recommended :stopped :holds-up :supersedes}) and return its id." [ask] (super-call! :ask! ask))
(defn super-answered! "Record that reply ID has been acted on." [id] (super-call! :answered! {:reply id}))
(defn super-note! "Set the thread's summary line." [text] (super-call! :note! {:text text}))
(defn super-milestone! "Mark progress worth the person's notice." [text] (super-call! :milestone! {:text text}))
(defn super-done! "End this Worker as done." [] (super-call! :finish-worker! {:state-after :done}))
(defn super-failed! "End this Worker as failed, saying WHY." [why] (super-call! :finish-worker! {:state-after :failed :reason why}))

(defn super-threads "Every thread, by id." [] (super-call! :threads {}))
(defn super-thread "Thread ID with its asks, replies and Workers." [id] (super-call! :thread {:id id}))
(defn super-thread! "A new thread titled TITLE; returns its handle." [title] (super-call! :thread! {:title title}))
(defn super-link! "Link RESOURCE (such as \"repo:engstrand/guix\") to thread ID." [id resource] (super-call! :link! {:thread id :resource resource}))
(defn super-start-worker!
  "Start a Worker: {:thread :workflow :brief :repo :check :locks :for}. :for is
  the id that opens the message you act on, such as \"o12\"; the Worker gets the
  capabilities of where that message came from. A :repo that does not exist is
  made as a new jj repository. Returns its id and state."
  [spec] (super-call! :start-worker! spec))

(defn pwd "The working directory." [] (str (fs/cwd)))

(defn sh
  "Run CMD with bash and an empty stdin. Returns {:exit :out :err}. With ARGS, CMD and ARGS are one argv, each word
  quoted for bash. A command that outlasts the evaluation's budget keeps running as a job, and the result is
  {:exit nil :timeout true :job id :out :err} with the output so far."
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
            (doseq [suffix [".cmd" ".log" ".err" ".exit" ".pid" ".start"]] (fs/delete-if-exists (job-file id suffix)))
            result)
          (if (>= (System/currentTimeMillis) end)
            (assoc (output) :exit nil :timeout true :job id)
            (do (Thread/sleep pause) (recur (min 100 (* 2 pause)))))))
      (finally (swap! bbh.env/live-sh disj pid)))))

(defn jobs
  "Every job of this conversation, newest first, without output."
  []
  (let [d (jobs-dir)]
    (if-not (fs/exists? d)
      []
      (->> (fs/glob d "*.cmd") (map #(str/replace (str (fs/file-name %)) #"\.cmd$" ""))
           (sort-by #(parse-long (first (str/split % #"-")))) reverse
           (mapv #(dissoc (job-status % :lines 0) :tail))))))

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
         ;; One profile per call: Chrome refuses a profile another instance holds,
         ;; and several sessions take screenshots at once.
         profile (str "/tmp/bbh-chrome-" (random-uuid))
         r (p/shell {:out :string :err :string :continue true :timeout 60000}
                    "flatpak" "run" (str "--filesystem=" shot-dir) "com.google.Chrome"
                    "--headless=new" "--disable-gpu" "--no-first-run" "--hide-scrollbars"
                    (str "--user-data-dir=" profile) (str "--virtual-time-budget=" wait)
                    (str "--screenshot=" out) (str "--window-size=" w "," h) url)]
     (fs/delete-tree profile)
     (if (fs/exists? out) out (throw (ex-info "screenshot failed" {:err (:err r)}))))))

(defn look
  "Have a Claude delegate view the png PATHS and answer QUESTION in text."
  [question & paths]
  (:reply (delegate (str "Use the Read tool to view these screenshots of a web app: " (str/join ", " paths)
                         "\n\n" question
                         "\nBe concrete: layout, spacing, alignment, typography, colors, overflow. Under 250 words."))))
