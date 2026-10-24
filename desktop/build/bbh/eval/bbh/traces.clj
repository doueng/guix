(ns bbh.traces
  "bbh conversations as evaluation traces: loading, Level 1 assertions and
  a domain-specific rendering for reading them."
  (:require [babashka.fs :as fs]
            [bbh.transcript :as transcript]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-roots
  [(str (fs/expand-home "~/.local/share/bbh/conversations"))
   (str (fs/expand-home "~/.cache/bbh-eval"))])

(defn- ancestor-with [dir file]
  (loop [d (fs/parent dir)]
    (cond (nil? d) nil
          (fs/exists? (fs/path d file)) (str d)
          :else (recur (fs/parent d)))))

(defn- error-of
  "The exception class in a rendered tool result, or :restart or :skipped."
  [text]
  (cond (str/includes? text "The REPL process was killed and restarted") :restart
        (str/starts-with? text "Skipped because the user interrupted") :skipped
        :else (some->> (re-find #"(?s)(?:^|\n)err:\n(.*?)(?:\n=> |\z)" text)
                       second
                       (re-find #"(?m)^((?:[a-z]\w*\.)+[A-Z]\w*(?:Exception|Error)\w*)")
                       second)))

(defn- steps
  "Each tool call paired with its result, numbered from 1."
  [messages]
  (let [results (into {} (for [m messages :when (= "tool" (:role m))] [(:tool_call_id m) (:content m)]))]
    (->> (for [m messages c (:tool_calls m)
               :let [result (get results (:id c) "")]]
           {:code (or (:code (try (json/parse-string (get-in c [:function :arguments]) true)
                                  (catch Exception _ nil)))
                      (get-in c [:function :arguments]))
            :result result
            :error (error-of result)})
         (map-indexed #(assoc %2 :n (inc %1)))
         vec)))

(defn- turns
  "The messages after each user message, up to the next one."
  [messages]
  (->> (partition-by #(= "user" (:role %)) messages)
       (drop-while #(not= "user" (:role (first %))))
       (partition-all 2)
       (mapv second)))

(defn load-trace [dir]
  (let [dir (str (fs/canonicalize dir))
        messages (transcript/load-messages dir)
        trial (ancestor-with dir "result.json")
        run (ancestor-with dir "manifest.json")
        result (when trial (json/parse-string (slurp (str (fs/path trial "result.json"))) true))
        run-name (some->> run fs/components (take-last 2) (str/join "/"))]
    {:dir dir
     :source (if trial :eval :real)
     :task (:task result)
     :profile (some-> (:profile result) name)
     :passed (:passed result)
     :cohort (if trial
               (str run-name " " (name (:profile result)))
               (let [[_ y m d] (re-find #"^(\d{4})(\d{2})(\d{2})" (str (fs/file-name dir)))]
                 (str "real " y "-" m "-" d)))
     :messages messages
     :steps (steps messages)
     :turns (turns messages)}))

(defn find-traces
  "Every conversation with messages under ROOTS, which may be conversation directories."
  [roots]
  (->> roots
       (filter fs/exists?)
       (mapcat #(fs/glob % "**" {:hidden true}))
       (filter #(and (= "messages.jsonl" (str (fs/file-name %))) (pos? (fs/size %))))
       (map (comp str fs/canonicalize fs/parent))
       distinct
       sort
       (mapv load-trace)))

;; Level 1 assertions. Each check returns the places a trace breaks it, or nothing.

(defn- step-check [pred]
  (fn [{:keys [steps]}] (for [s steps :when (pred s)] (str "step " (:n s)))))

(def assertions
  [{:id :guessed-arity
    :doc "A helper was called with the wrong number of arguments, usually a guessed API."
    :check (step-check #(some-> (:error %) str (str/ends-with? "ArityException")))}
   {:id :unresolved-symbol
    :doc "The code named a function or namespace that does not exist in the REPL."
    :check (step-check #(re-find #"Could not resolve symbol|Could not find namespace|No such namespace" (:result %)))}
   {:id :pi-idiom
    :doc "The code used Pi codemode conventions such as await, tools/..., fs/read or fs/list."
    :check (step-check #(re-find #"\(await\b|\(tools/|\(fs/(?:read|list|read-json|read-jsonl)[\s)]" (:code %)))}
   {:id :root-blind-glob
    :doc "A **/ glob, which omits root-level files in babashka."
    :check (step-check #(re-find #"\"\*\*/" (:code %)))}
   {:id :repl-restart
    :doc "An evaluation timed out or died and the REPL lost its definitions."
    :check (step-check #(= :restart (:error %)))}
   {:id :error-streak
    :doc "Three or more failed evaluations in a row."
    :check (fn [{:keys [steps]}]
             (for [run (partition-by (comp some? :error) steps)
                   :when (and (:error (first run)) (<= 3 (count run)))]
               (str "steps " (:n (first run)) "-" (:n (last run)))))}
   {:id :no-final-answer
    :doc "A turn ended without assistant text after its last tool call."
    :check (fn [{:keys [turns]}]
             (for [[i turn] (map-indexed vector turns)
                   :let [last-msg (last turn)]
                   :when (not (and (= "assistant" (:role last-msg))
                                   (not (str/blank? (:content last-msg)))
                                   (empty? (:tool_calls last-msg))))]
               (str "turn " (inc i))))}])

(defn check
  "The assertions TRACE breaks, as {assertion-id [place ...]}."
  [trace]
  (into (sorted-map)
        (for [{:keys [id check]} assertions
              :let [hits (vec (check trace))]
              :when (seq hits)]
          [id hits])))

;; Rendering

(defn- elide-skills [text]
  (str/replace (str text) #"(?s)<skill name=\"([^\"]+)\".*?</skill>" "[skill $1 expanded here]"))

(defn- cap [s n]
  (if (<= (count s) n) s (str (subs s 0 n) "\n[... " (- (count s) n) " more chars]")))

(defn- flags [by-place place]
  (when-let [ids (by-place place)] (str "  [" (str/join ", " ids) "]")))

(defn- render-step [by-place {:keys [n code result error]}]
  (str "\n### Step " n (when error (str "  FAILED " (name error))) (flags by-place (str "step " n))
       "\n\n```clojure\n" (str/trim (str code)) "\n```\n\n```\n" (cap (str/trim result) 2000) "\n```\n"))

(defn render
  "TRACE as Markdown, with grade, assertion hits and steps inline."
  [{:keys [dir source task profile passed messages steps turns] :as trace}]
  (let [hits (check trace)
        by-place (reduce-kv (fn [acc id places] (reduce #(update %1 %2 (fnil conj []) (name id)) acc places)) {} hits)
        users (filterv #(= "user" (:role %)) messages)
        remaining (atom steps)
        take-steps! (fn [k] (let [s (take k @remaining)] (swap! remaining #(drop k %)) s))]
    (str "# " dir "\n\n"
         "- source: " (name source)
         (when task (str ", task " task ", profile " profile ", grader " (if passed "PASS" "FAIL")))
         "\n- " (count steps) " steps, " (count (filter :error steps)) " failed"
         "\n- assertions: " (if (seq hits)
                               (str/join "; " (for [[id places] hits] (str (name id) " at " (str/join ", " places))))
                               "none")
         "\n"
         (str/join
          (for [[i user turn] (map vector (range 1 ##Inf) users turns)]
            (str "\n## Turn " i (flags by-place (str "turn " i))
                 "\n\n> " (str/replace (cap (str/trim (elide-skills (:content user))) 3000) "\n" "\n> ") "\n"
                 (str/join
                  (for [m turn :when (= "assistant" (:role m))]
                    (str (when-not (str/blank? (:content m)) (str "\n" (str/trim (:content m)) "\n"))
                         (str/join (map #(render-step by-place %) (take-steps! (count (:tool_calls m))))))))))))))

;; Lint report

(defn report
  "A Markdown table of graded passes and assertion hits per cohort, with example traces."
  [traces]
  (let [checked (map #(assoc % :hits (check %)) traces)
        ids (mapv :id assertions)]
    (str "| cohort | traces | graded pass | " (str/join " | " (map name ids)) " |\n"
         "|---|---|---|" (str/join (repeat (count ids) "---|")) "\n"
         (str/join
          (for [[cohort ts] (sort-by key (group-by :cohort checked))
                :let [graded (filter #(some? (:passed %)) ts)]]
            (str "| " cohort " | " (count ts) " | "
                 (if (seq graded) (str (count (filter :passed graded)) "/" (count graded)) "-") " | "
                 (str/join " | " (for [id ids] (count (filter #(contains? (:hits %) id) ts)))) " |\n")))
         "\nCells count traces with at least one hit.\n"
         (str/join
          (for [{:keys [id doc]} assertions
                :let [examples (take 3 (filter #(contains? (:hits %) id) checked))]
                :when (seq examples)]
            (str "\n" (name id) ": " doc "\n" (str/join (for [t examples] (str "  " (:dir t) "\n")))))))))

(defn lint! [& roots]
  (let [traces (find-traces (or (seq roots) default-roots))]
    (println (str (count traces) " traces\n"))
    (print (report traces))
    (flush)))

(defn show! [dir]
  (print (render (load-trace dir)))
  (flush))
