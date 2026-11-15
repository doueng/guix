(ns bbh.fork
  "Fork replay: resample the next tool call of a recorded Claude Code session under another system prompt and
  tool description, without running it. See eval/README.md."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.repl :as repl]
            [bbh.tool-use :as tool-use]
            [bbh.traces :as traces]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn default-claude
  "Claude Code as argv, through the Guix loader, because the ~/.local/bin wrappers find home as /root under unshare -r."
  []
  [(str (fs/home) "/.guix-home/profile/lib/ld-linux-aarch64.so.1") "--argv0" "claude"
   (str (or (System/getenv "PI_CODING_AGENT_DIR") (str (fs/home) "/.pi/agent"))
        "/npm/node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64/claude")])

(defn mcp-stub!
  "Serve the clojure tool over MCP on stdio with the description in DESC-FILE. Each call's arguments are appended
  to OUT-FILE and answered without running."
  [desc-file out-file]
  (let [desc (slurp desc-file)]
    (doseq [line (line-seq (java.io.BufferedReader. *in*)) :when (seq line)]
      (let [{:keys [id method params]} (json/parse-string line true)
            reply #(do (println (json/generate-string {:jsonrpc "2.0" :id id :result %})) (flush))]
        (case method
          "initialize" (reply {:protocolVersion (:protocolVersion params) :capabilities {:tools {}}
                               :serverInfo {:name "bbh" :version "fork"}})
          "tools/list" (reply {:tools [{:name "clojure" :description desc
                                         :inputSchema {:type "object" :required ["code"]
                                                       :properties {:code {:type "string" :description "Clojure source to evaluate."}}}}]})
          "tools/call" (do (spit out-file (str (json/generate-string (:arguments params)) "\n") :append true)
                           (reply {:content [{:type "text" :text "[fork replay: the call was recorded, not run]"}]}))
          (when id (reply {})))))))

(def default-claude-dir (str (fs/home) "/.claude"))

(defn- project-dir [claude-dir cwd] (str claude-dir "/projects/" (str/replace cwd #"[/.]" "-")))

(defn- tool-use-ids [entry]
  (let [c (get-in entry [:message :content])]
    (when (sequential? c) (keep #(when (= "tool_use" (:type %)) (:id %)) c))))

(defn cut
  "The prefix of session file F before the response that made tool call ID: {:kept lines, :resend the user entry
  that preceded it, :shape the last request shape before it, :assistant the recorded entry}, or nil."
  [f id]
  (let [lines (vec (str/split-lines (slurp f)))
        es (mapv #(try (json/parse-string % true) (catch Exception _ {})) lines)]
    (when-let [hit (first (keep-indexed #(when (some #{id} (tool-use-ids %2)) %1) es))]
      (let [rid (:requestId (es hit))
            start (first (keep-indexed #(when (and (= "assistant" (:type %2)) (= rid (:requestId %2))) %1) es))
            user-i (last (keep-indexed #(when (and (< %1 start) (= "user" (:type %2))) %1) es))]
        {:kept (subvec lines 0 user-i)
         :resend (es user-i)
         :shape (:shape (last (filter #(= "api-request-shape" (:type %)) (subvec es 0 start))))
         :assistant (es hit)}))))

(defn session-file
  "The Claude Code session file under the conversation's project directory that holds tool call ID."
  [claude-dir cwd id]
  (let [d (project-dir claude-dir cwd)]
    (when (fs/directory? d)
      (some #(when (str/includes? (slurp (str %)) id) (str %)) (fs/glob d "*.jsonl")))))

(def ^:private namespace-setup
  "Inside unshare -rm: put the original working directory back, as a git worktree, when it is gone. An overlay
  on its parent keeps the siblings visible, such as the bbh checkout the MCP stub loads, and sends every write to
  SCRATCH, so nothing appears outside the namespace."
  "set -e
cwd=\"$1\"; repo=\"$2\"; scratch=\"$3\"; shift 3
if [ ! -d \"$cwd\" ]; then
  parent=$(dirname \"$cwd\")
  mkdir -p \"$scratch/up\" \"$scratch/work\"
  mount -t overlay overlay -o \"lowerdir=$parent,upperdir=$scratch/up,workdir=$scratch/work\" \"$parent\"
  git clone -q --shared --no-checkout \"$repo\" \"$parent/.fork-repo\"
  git -C \"$parent/.fork-repo\" worktree add -q --no-checkout --detach \"$cwd\" HEAD
fi
cd \"$cwd\"
exec \"$@\"")

(defn fork-once
  "Fork the recorded call ID of session file F under SYSTEM and DESC. Returns {:code :cost :error}, where :code is
  the first tool call the fork made, which was recorded and not run."
  [f id system desc {:keys [claude repo claude-dir] :or {claude-dir default-claude-dir}}]
  (let [{:keys [kept resend assistant]} (cut f id)
        cwd (:cwd resend)
        sid (str (random-uuid))
        tmp (str (fs/create-temp-dir {:prefix "bbh-fork"}))
        proj (project-dir claude-dir cwd)
        out (str tmp "/calls.jsonl")
        dfile (str tmp "/desc.txt")
        resume? (some #(re-find #"\"type\":\"(?:user|assistant)\"" %) kept)
        mcp {:mcpServers {:bbh {:command "bb"
                                :args ["--config" (System/getProperty "babashka.config") "-e"
                                       (str "(require 'bbh.fork) (bbh.fork/mcp-stub! " (pr-str dfile) " " (pr-str out) ")")]}}}]
    (spit dfile desc)
    (when resume?
      (fs/create-dirs proj)
      (spit (str proj "/" sid ".jsonl")
            (str (str/join "\n" (map #(str/replace % (:sessionId resend) sid) kept)) "\n")))
    (let [r (apply p/shell {:in (str (json/generate-string {:type "user" :message (:message resend)}) "\n")
                            :out :string :err :string :continue true
                            :extra-env (cond-> {"DISABLE_AUTOUPDATER" "1" "USE_BUILTIN_RIPGREP" "0"}
                                         (not= claude-dir default-claude-dir) (assoc "CLAUDE_CONFIG_DIR" claude-dir))}
                   "unshare" "-rm" "bash" "-c" namespace-setup "fork" cwd repo (str tmp "/overlay")
                   (concat claude
                           ["-p" "--input-format" "stream-json" "--output-format" "stream-json" "--verbose"
                            "--system-prompt-snapshot" "off" "--tools" "" "--strict-mcp-config" "--mcp-config" (json/generate-string mcp)
                            "--allowedTools" "mcp__bbh__clojure" "--setting-sources" "" "--system-prompt" system
                            "--model" (or (:requestedModel assistant) "claude-opus-5-5")
                            "--effort" (or (:effort assistant) "medium") "--max-turns" "1"]
                           (when resume? ["--resume" sid "--fork-session"])))
          events (keep #(try (json/parse-string % true) (catch Exception _ nil)) (str/split-lines (:out r)))
          code (some #(when (= "assistant" (:type %))
                        (some (fn [b] (when (= "tool_use" (:type b)) (get-in b [:input :code]))) (get-in % [:message :content])))
                     events)]
      (doseq [i (cons sid (distinct (keep :session_id events)))] (fs/delete-if-exists (fs/path proj (str i ".jsonl"))))
      ;; overlayfs leaves its work directory with mode 000.
      (p/shell {:continue true :err :string} "chmod" "-R" "u+rwx" tmp)
      (fs/delete-tree tmp)
      {:code code
       :cost (some #(when (= "result" (:type %)) (:total_cost_usd %)) events)
       :error (when-not code (str "no tool call; exit " (:exit r) ": " (subs (str (:err r)) 0 (min 300 (count (str (:err r)))))))})))

(defn render
  "{:system :tool-description} of INIT's manifest rendered in REPO, as a super Worker when SUPER?."
  [init repo super?]
  (let [out (str (fs/create-temp-file {:suffix ".edn"}))
        env (cond-> (dissoc (into {} (System/getenv)) "BBH_SUPER_TOKEN" "BBH_ROLE")
              super? (assoc "BBH_SUPER_TOKEN" "fork-replay"))]
    (p/shell {:dir repo :env env :out :string :err :string}
             "bb" "--config" (System/getProperty "babashka.config") "-e"
             (str "(load-file " (pr-str (str (fs/absolutize init))) ") (spit " (pr-str out)
                  " (pr-str (select-keys (bbh.env/manifest) [:system :tool-description])))"))
    (merge {:tool-description (:tool-description repl/defaults)} (edn/read-string (slurp out)))))

(defn- choice [code]
  (let [{:keys [shell helpers]} (tool-use/file-work (str code))]
    (cond (nil? code) {:choice :none}
          (seq shell) {:choice :shell :class (first (sort shell))}
          (seq helpers) {:choice :helper :class (first (sort helpers))}
          :else {:choice :other})))

(defn eligible-steps
  "Tool calls in TRACE whose recorded code did file work: [{:trace :id :code :choice :class}]."
  [{:keys [dir messages]}]
  (for [m messages c (:tool_calls m)
        :let [code (:code (try (json/parse-string (get-in c [:function :arguments]) true) (catch Exception _ nil)))
              ch (choice code)]
        :when (#{:shell :helper} (:choice ch))]
    (merge {:trace dir :id (:id c) :code code} ch)))

(defn- pick
  "Up to N steps: the FIRST eligible steps of each trace when given, since a session's own earlier calls prime its
  later ones, else at most PER-TRACE from each trace in a fixed pseudo-random order."
  [steps n per-trace first]
  (let [shuffled (fn [xs] (let [l (java.util.ArrayList. ^java.util.Collection xs)]
                            (java.util.Collections/shuffle l (java.util.Random. 7)) (vec l)))]
    (->> (group-by :trace steps) vals (mapcat #(if first (take first %) (take per-trace (shuffled %)))) shuffled (take n))))

(defn- arm-prompt [arm {:keys [shape]} {:keys [cwd repo super?]} rendered]
  (if (= :recorded arm)
    {:system (str/join "\n\n" (map :text (rest (:system shape))))
     :tool-description (some #(when (= "mcp__bbh__clojure" (:name %)) (:description %)) (:tools shape))}
    (-> (rendered [arm repo super?]) (update :system str/replace repo cwd))))

(defn run!
  "Fork each picked step once per arm and sample, appending results to OUT/forks.jsonl and skipping those already
  there. ARMS maps a name to :recorded or an init file. MATCH, a regex, keeps only steps whose recorded code matches."
  [out {:keys [roots n per-trace first samples arms claude repo claude-dir match]
         :or {n 10 per-trace 2 samples 1 repo "/home/engstrand/guix" claude-dir default-claude-dir}}]
  (fs/create-dirs out)
  (let [file (str (fs/path out "forks.jsonl"))
        done (if (fs/exists? file)
               (set (keep #(let [r (json/parse-string % true)] (when-not (:error r) [(:id r) (:arm r) (:sample r)]))
                          (str/split-lines (slurp file))))
               #{})
        claude (or claude (default-claude))
        rendered (memoize (fn [[arm repo super?]] (render (arms arm) repo super?)))
        steps (pick (for [t (traces/find-traces roots)
                          :let [cwd (some-> (fs/file (:dir t) "agent.json") str (#(when (fs/exists? %) (:project (json/parse-string (slurp %) true)))))]
                          :when cwd
                          s (eligible-steps t)
                          :when (or (nil? match) (re-find (re-pattern match) (str (:code s))))]
                      (assoc s :cwd cwd))
                    n per-trace first)]
    (doseq [{:keys [id cwd] :as step} steps
            :let [f (session-file claude-dir cwd id) c (when f (cut f id))]
            :when c
            arm (keys arms)
            i (range samples)
            :when (not (done [id (name arm) i]))]
      (let [{:keys [system tool-description]} (arm-prompt (if (= :recorded (arms arm)) :recorded arm) c
                                                          {:cwd cwd :repo repo :super? (str/includes? (str (:system (:shape c))) "# Super-agent")}
                                                          rendered)
            r (fork-once f id system tool-description {:claude claude :repo repo :claude-dir claude-dir})
            ch (choice (:code r))]
        (spit file (str (json/generate-string (merge (select-keys step [:trace :id]) {:recorded (select-keys step [:choice :class])
                                                                                       :arm (name arm) :sample i}
                                                     ch (select-keys r [:code :cost :error])))
                        "\n")
              :append true)))
    file))

(defn- choice-cell [{:keys [choice class]}] (str/join " " (keep #(some-> % name) [choice class])))

(defn- step-table
  "Markdown: per forked step, the recorded choice and each arm's, so a habit the session set shows as rows."
  [results]
  (let [arms (sort (distinct (map :arm results)))]
    (str "\n| trace | step | recorded | " (str/join " | " arms) " |\n|" (str/join (repeat (+ 3 (count arms)) "---|")) "\n"
         (str/join (for [[[trace id] rs] (sort-by key (group-by (juxt :trace :id) results))
                         :let [by-arm (group-by :arm rs)]]
                     (str "| " (fs/file-name trace) " | " id " | " (choice-cell (:recorded (first rs))) " | "
                          (str/join " | " (for [a arms] (str/join ", " (map choice-cell (by-arm a))))) " |\n"))))))

(defn report
  "Markdown: per arm, forks, calls made, helper share among file-work choices and list cost. A fork retried
  after an error counts once, as its last result."
  [results]
  (let [results (vals (into {} (map (juxt (juxt :id :arm :sample) identity) results)))]
   (str "| arm | forks | calls | file work | helper share | list cost |\n|---|---|---|---|---|---|\n"
        (str/join (for [[arm rs] (sort-by key (group-by :arm results))
                        :let [work (filter (comp #{"shell" "helper"} :choice) rs)
                              helper (count (filter (comp #{"helper"} :choice) work))]]
                    (format "| %s | %d | %d | %d | %s | $%.2f |\n" arm (count rs) (count (filter :code rs)) (count work)
                            (if (seq work) (format "%d%% (%d/%d)" (Math/round (* 100.0 (/ helper (count work)))) helper (count work)) "-")
                            (double (reduce + (keep :cost rs))))))
        (step-table results))))

(defn- parse-args [args]
  (loop [[a v & more :as as] args opts {:arms {} :roots []}]
    (cond (empty? as) opts
          (= "--n" a) (recur more (assoc opts :n (parse-long v)))
          (= "--per-trace" a) (recur more (assoc opts :per-trace (parse-long v)))
          (= "--first" a) (recur more (assoc opts :first (parse-long v)))
          (= "--samples" a) (recur more (assoc opts :samples (parse-long v)))
          (= "--repo" a) (recur more (assoc opts :repo v))
          (= "--match" a) (recur more (assoc opts :match v))
          (= "--arm" a) (let [[k init] (str/split v #"=" 2)]
                          (recur more (assoc-in opts [:arms (keyword k)] (if (= "recorded" init) :recorded init))))
          :else (recur (rest as) (update opts :roots conj a)))))

(defn -main [out & args]
  (let [opts (parse-args args)
        opts (cond-> opts (empty? (:arms opts)) (assoc :arms {:recorded :recorded}))
        file (run! out opts)]
    (println (report (map #(json/parse-string % true) (str/split-lines (slurp file)))))))
