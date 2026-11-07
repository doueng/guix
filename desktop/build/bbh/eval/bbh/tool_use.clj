(ns bbh.tool-use
  "How a trace's code does file work: through shell commands in sh or through the REPL helpers. Counts come
  from parsing each step's code, not from regexes over it, so a helper name inside a string does not count."
  (:require [babashka.fs :as fs]
            [bbh.traces :as traces]
            [cheshire.core :as json]
            [clojure.string :as str]
            [edamame.core :as e]))

(def ^:private shell-fns '#{sh $ job})

(defn- forms [code]
  (try (e/parse-string-all code {:all true :read-cond :allow :features #{:clj} :auto-resolve name})
       (catch Exception _ [])))

(defn- literal
  "The text of a command argument: a string, or a (str …) of strings with ? for each hole."
  [x]
  (cond (string? x) x
        (and (seq? x) (= 'str (first x))) (str/join (map #(if (string? %) % "?") (rest x)))))

(defn shell-commands
  "Every sh, $ and job call in CODE whose command is literal: [{:fn sym :cmd text}]."
  [code]
  (for [f (mapcat #(tree-seq coll? seq %) (forms code))
        :when (and (seq? f) (contains? shell-fns (first f)))
        :let [args (remove map? (rest f))
              cmd (if (and (not= 'job (first f)) (next args) (every? string? args))
                    (str/join " " args)
                    (some literal args))]
        :when cmd]
    {:fn (first f) :cmd cmd}))

(def ^:private skipped #{"cd" "export" "source" "set" "echo" "true" "printf"})

(def ^:private prefixes
  "Words that run the command after them."
  #{"env" "time" "sudo" "timeout" "nice" "command" "exec"})

(defn- words [cmd]
  (for [seg (str/split cmd #"\|\||&&|;|\||\n|\$\(|`")
        :let [w (->> (str/split (str/trim seg) #"\s+")
                     (remove #(re-matches #"[A-Za-z_]+=.*|[({!]|\d+[smh]?|-\S+" %))
                     (remove prefixes)
                     first)]
        :when (seq w)]
    (str/replace w #"^.*/|[\"')]" "")))

(defn classify
  "{:primary word :class kw :bash-c bool} for shell command CMD. :class is nil when no helper covers it."
  [cmd]
  (let [primary (first (remove skipped (words cmd)))]
    {:primary primary
     :bash-c (boolean (re-find #"^\s*bash\s+-l?c\b" cmd))
     :class (case primary
              "cat" (if (re-find #"cat\s*>|<<" cmd) :write :read)
              ("head" "tail") :read
              "sed" (if (re-find #"\bsed\s+(-\w+\s+)*-i" cmd) :edit :read)
              "perl" (when (re-find #"\bperl\s+(-\w+\s+)*-\w*i" cmd) :edit)
              "tee" :write
              ("grep" "rg") :search
              ("ls" "find" "fd") :list
              "wc" :count
              nil)}))

(def helper-class
  "Which file operation each helper does."
  '{read-lines :read slurp :read grep :search ls :list fs/glob :list fs/list-dir :list edit! :edit spit :edit})

(defn- helper-calls [code]
  (keep #(when (seq? %) (helper-class (first %))) (mapcat #(tree-seq coll? seq %) (forms code))))

(defn file-work
  "The file operations CODE does: {:shell #{class} :helpers #{class}}, shell counting sh and $ only."
  [code]
  {:shell (set (keep #(when (not= 'job (:fn %)) (:class (classify (:cmd %)))) (shell-commands code)))
   :helpers (set (helper-calls code))})

(defn- first-day [dir]
  (let [f (fs/file dir "runs.jsonl")]
    (when (fs/exists? f)
      (with-open [r (clojure.java.io/reader f)]
        (some #(some-> (try (json/parse-string % true) (catch Exception _ nil)) :start (subs 0 10)) (line-seq r))))))

(defn summarize
  "One trace's file work: calls, shell commands by class, helper calls by class, leading words and bash -c wrappers."
  [{:keys [dir steps cohort]}]
  (let [codes (map :code steps)
        shell (for [c codes, s (shell-commands (str c)) :when (not= 'job (:fn s))] (classify (:cmd s)))]
    {:dir dir
     :cohort (if (str/starts-with? (str cohort) "real") (str "real " (or (first-day dir) "undated")) cohort)
     :calls (count steps)
     :shell-commands (count shell)
     :shell (frequencies (keep :class shell))
     :helpers (frequencies (mapcat (comp helper-calls str) codes))
     :primary (frequencies (keep :primary shell))
     :bash-c (count (filter :bash-c shell))}))

(def ^:private classes [:read :search :list :count :edit])

(defn shares
  "Shell share of eligible file operations in summed summary S: [shell total] without and with :search."
  [s]
  (let [tally (fn [cs] [(reduce + (map #(get (:shell s) % 0) cs))
                        (reduce + (map #(+ (get (:shell s) % 0) (get (:helpers s) % 0)) cs))])]
    {:without-search (tally (remove #{:search} classes)) :with-search (tally classes)}))

(defn- add [a b] (merge-with #(if (map? %1) (merge-with + %1 %2) (+ %1 %2)) a b))

(defn- pct [[n d]] (if (pos? d) (format "%d%% (%d/%d)" (Math/round (* 100.0 (/ n d))) n d) "-"))

(defn report
  "A Markdown table per cohort of shell against helper file operations, then the leading shell words."
  [summaries]
  (let [sum #(reduce add (map (fn [s] (select-keys s [:calls :shell-commands :shell :helpers :primary :bash-c])) %))
        cell (fn [m k] (get m k 0))
        rows (for [[cohort ss] (sort-by key (group-by :cohort summaries)) :let [s (sum ss)]] [cohort (count ss) s])
        total (sum summaries)]
    (str "| cohort | traces | calls | sh cmds | bash -c | "
         (str/join " | " (for [c classes] (str (name c) " sh/helper")))
         " | shell share | with search |\n|" (str/join (repeat (+ 7 (count classes)) "---|")) "\n"
         (str/join (for [[cohort n s] (concat rows [["all" (count summaries) total]])
                         :let [{:keys [without-search with-search]} (shares s)]]
                     (str "| " cohort " | " n " | " (:calls s) " | " (:shell-commands s) " | " (:bash-c s) " | "
                          (str/join " | " (for [c classes] (str (cell (:shell s) c) "/" (cell (:helpers s) c))))
                          " | " (pct without-search) " | " (pct with-search) " |\n")))
         "\nLeading shell words: "
         (str/join ", " (for [[w n] (take 20 (sort-by (comp - val) (:primary total)))] (str w " " n))) "\n")))

(defn tools!
  "Print the tool-use report for the traces under ROOTS, default the real conversations and eval runs."
  [& roots]
  (println (report (map summarize (traces/find-traces (or (seq roots) traces/default-roots))))))
