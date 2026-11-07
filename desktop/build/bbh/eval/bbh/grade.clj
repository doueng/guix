(ns bbh.grade
  "Grade a full replay against its original: metrics from both transcripts, the wf outcome, and a pairwise judge."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.labels :as labels]
            [bbh.tool-use :as tool-use]
            [bbh.traces :as traces]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- jsonl [f] (if (fs/exists? f) (keep #(try (json/parse-string % true) (catch Exception _ nil)) (str/split-lines (slurp (str f)))) []))

(defn metrics
  "A conversation's cost and tool use: {:calls :sh :shell-share [n d] :errors :input :output :minutes :final}."
  [dir]
  (when (fs/exists? (fs/file dir "messages.jsonl"))
    (let [trace (traces/load-trace dir)
          s (tool-use/summarize trace)
          runs (jsonl (fs/file dir "runs.jsonl"))
          llm (filter #(= "llm" (:kind %)) runs)]
      {:calls (:calls s)
       :sh (:shell-commands s)
       :shell-share (:with-search (tool-use/shares s))
       :errors (count (filter #(and (:tool-call-id %) (= "error" (:status %))) runs))
       :input (reduce + (map #(get-in % [:usage :input] 0) llm))
       :output (reduce + (map #(get-in % [:usage :output] 0) llm))
       :minutes (/ (Math/round (/ (reduce + (keep #(when (or (:tool-call-id %) (= "llm" (:kind %))) (:ms %)) runs)) 6000.0)) 10.0)
       :final (some #(when (and (= "assistant" (:role %)) (seq (:content %))) (:content %)) (reverse (:messages trace)))})))

(defn wf-outcome
  "\"done\", \"abandoned\" or \"<phase> (active)\" from a wf journal."
  [journal]
  (when (fs/exists? journal)
    (let [forms (map edn/read-string (remove str/blank? (str/split-lines (slurp (str journal)))))]
      (cond (some #(= :done (:type %)) forms) "done"
            (some #(= :abandon (:type %)) forms) "abandoned"
            :else (str (some-> (last (keep :phase forms)) name) " (active)")))))

(def judge-prompt (str (fs/path (fs/parent *file*) ".." "replay-judge.md")))

(defn parse-verdict [text]
  {:verdict (some-> (re-find #"(?m)^VERDICT: (better|same|worse)\s*$" (str text)) second keyword)
   :critique (str/trim (str/replace (str text) #"(?m)^VERDICT:.*$" ""))})

(defn- show [label m]
  (str "## " label "\n\nMetrics: " (pr-str (dissoc m :final)) "\n\nFinal report:\n\n" (or (:final m) "(none)") "\n"))

(defn judge
  "Compare ORIGINAL and REPLAY metrics for BRIEF with Claude Code on the subscription, without tools. The
  :labeler names the model and a hash of the judge prompt, as labels.clj does."
  [brief original replay]
  (let [model (or (System/getenv "BBH_JUDGE_MODEL") "opus")
        system (slurp judge-prompt)
        r (p/shell {:in (str "# Brief\n\n" brief "\n\n" (show "ORIGINAL" original) "\n" (show "REPLAY" replay))
                    :out :string :err :string :continue true}
                   "claude" "-p" "--output-format" "json" "--tools" "" "--setting-sources" ""
                   "--system-prompt" system "--model" model)]
    (assoc (parse-verdict (:result (try (json/parse-string (:out r) true) (catch Exception _ {}))))
           :labeler (str "replay-judge:" model ":" (labels/sha8 system)))))

(defn- state-root [scratch] (str scratch "/up/.local/state/bbh/workflows"))

(defn grade
  "Grade a finished item's DATA against its recorded original. {:judge false} skips the judge."
  [{:keys [out scratch result trace prompt old-run blocked super]} {judge? :judge :or {judge? true}}]
  (let [replay (metrics (str out "/conversation"))
        original (metrics trace)]
    {:replay replay
     :original original
     :wf (some->> (:run result) (fs/path (state-root scratch)) (#(fs/file % "journal.edn")) wf-outcome)
     :original-wf (some->> old-run (fs/path (System/getProperty "user.home") ".local/state/bbh/workflows") (#(fs/file % "journal.edn")) wf-outcome)
     :blocked (count blocked)
     :super (frequencies (map :command super))
     :judge (when (and judge? replay original) (judge prompt original replay))}))

(defn- share [[n d]] (if (and d (pos? d)) (format "%d%% (%d/%d)" (Math/round (* 100.0 (/ n d))) n d) "-"))

(defn- row [item arm state wf m blocked verdict]
  (str "| " (str/join " | " [item arm (some-> state name) wf (:calls m) (:sh m) (share (:shell-share m)) (:errors m)
                               (some-> (:input m) (quot 1000) (str "k")) (some-> (:output m) (quot 1000) (str "k")) (:minutes m) blocked (some-> verdict name)])
       " |\n"))

(defn report
  "Markdown: a row per original and per replay item, then a per-arm summary."
  [states]
  (let [items (sort-by key states)
        originals (distinct (keep (fn [[_ {:keys [data]}]] (when-let [g (:original data)] [(fs/file-name (:trace data)) g (:original-wf data)])) items))
        graded (filter #(= :graded (:state (val %))) items)]
    (str "| item | arm | state | wf | calls | sh | shell share | errors | input | output | min | blocked | verdict |\n|"
         (str/join (repeat 13 "---|")) "\n"
         (str/join (for [[t g wf] originals] (row t "original" nil wf g nil nil)))
         (str/join (for [[item {:keys [state data]}] items]
                     (row item (:arm data) state (:wf data) (:replay data) (:blocked data) (get-in data [:judge :verdict]))))
         "\n| arm | graded | shell share | calls | input | verdicts |\n|---|---|---|---|---|---|\n"
         (str/join (for [[arm xs] (sort-by key (group-by #(get-in (val %) [:data :arm]) graded))
                         :let [ms (keep #(get-in (val %) [:data :replay]) xs)
                               [n d] (reduce (fn [[a b] m] (let [[x y] (:shell-share m)] [(+ a (or x 0)) (+ b (or y 0))])) [0 0] ms)]]
                     (str "| " arm " | " (count xs) " | " (share [n d]) " | " (reduce + (keep :calls ms)) " | "
                          (quot (reduce + (keep :input ms)) 1000) "k | "
                          (pr-str (frequencies (keep #(get-in (val %) [:data :judge :verdict]) xs))) " |\n"))))))
