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
    (cond (= text "/poteto-mode-off")
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
  (into [{:name "poteto-mode-off" :description "Turn off poteto-mode for this conversation."}]
        (for [{:keys [name description]} (skills)]
          {:name (str "skill:" name) :description description})))

(ns user
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.env :refer [jev-classify-task]]
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
