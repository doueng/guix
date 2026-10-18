(ns bbh.labels
  "Binary good or bad labels with critiques, from the human and from an LLM
  judge, and how well each judge agrees with the human."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.traces :as traces]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def judge-prompt-file
  (str (fs/path (fs/parent (fs/parent (fs/absolutize *file*))) "judge.md")))

(defn- data-dir []
  (or (System/getenv "BBH_EVAL_DATA") (str (fs/expand-home "~/.local/share/bbh/eval"))))

(defn- labels-file [] (str (fs/path (data-dir) "labels.jsonl")))

(defn load-labels
  "The latest label per trace and labeler, as {[dir labeler] label}."
  []
  (let [f (labels-file)]
    (if (fs/exists? f)
      (->> (fs/read-all-lines f) (remove str/blank?) (map #(json/parse-string % true))
           (reduce #(assoc %1 [(:trace %2) (:labeler %2)] %2) {}))
      {})))

(defn add-label! [label]
  (fs/create-dirs (data-dir))
  (spit (labels-file) (str (json/generate-string (assoc label :at (str (java.time.Instant/now)))) "\n") :append true))

(defn- human-labeled [labels]
  (set (for [[[dir labeler]] labels :when (= "human" labeler)] dir)))

;; Human review

(defn- ask [question]
  (print question)
  (flush)
  (some-> (read-line) str/trim))

(defn review!
  "Show each trace under ROOTS the human has not labeled, newest first, and
  record good or bad with a critique."
  [& roots]
  (let [done (human-labeled (load-labels))
        todo (->> (traces/find-traces (or (seq roots) traces/default-roots))
                  (remove #(done (:dir %)))
                  (sort-by #(str (fs/file-name (:dir %))) #(compare %2 %1)))]
    (println (count todo) "unlabeled traces")
    (loop [[t & more] todo]
      (when t
        (println (str "\n" (traces/render t)))
        (let [answer (ask "\n[g]ood, [b]ad, [s]kip or [q]uit? ")]
          (cond (#{"g" "b"} answer)
                (do (add-label! {:trace (:dir t) :labeler "human" :outcome (if (= "g" answer) "good" "bad")
                                 :critique (ask "Why? ")})
                    (recur more))
                (= "s" answer) (recur more)))))))

;; LLM judge

(defn- sha8 [s]
  (subs (format "%064x" (java.math.BigInteger.
                          1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))
        0 8))

(defn parse-verdict
  "The judge's {:critique :outcome} from its reply text."
  [reply]
  (let [m (try (some-> (re-find #"(?s)\{.*\}" (str reply)) (json/parse-string true)) (catch Exception _ nil))]
    (when-not (#{"good" "bad"} (:outcome m))
      (throw (ex-info (str "Judge reply has no good or bad outcome: " reply) {})))
    (select-keys m [:critique :outcome])))

(defn- claude-ask
  "A function sending text to Claude Code without tools and returning its reply."
  [model system]
  (fn [text]
    (let [{:keys [exit out err]} @(p/process {:in text :out :string :err :string
                                              :extra-env {"DISABLE_AUTOUPDATER" "1"}}
                                             (or (System/getenv "BBH_CLAUDE") "claude")
                                             "-p" "--output-format" "json" "--model" model
                                             "--tools" "" "--strict-mcp-config" "--setting-sources" ""
                                             "--system-prompt" system)
          r (try (json/parse-string out true) (catch Exception _ nil))]
      (when (or (not (zero? exit)) (nil? r) (:is_error r))
        (throw (ex-info (str "claude failed: " (or (:result r) err out)) {})))
      (:result r))))

(defn judge-trace
  "Grade TRACE with ASK, a function from prompt text to reply text. The judge
  does not see the task grader's verdict."
  [ask trace]
  (parse-verdict (ask (traces/render (dissoc trace :task :passed)))))

(defn judge!
  "Label traces under ROOTS with the judge, or every human-labeled trace when
  no roots are given. BBH_JUDGE_MODEL picks the Claude model. Editing judge.md
  starts a new labeler, so earlier verdicts stay comparable."
  [& roots]
  (let [model (or (System/getenv "BBH_JUDGE_MODEL") "opus")
        prompt (slurp judge-prompt-file)
        labeler (str "judge:" model ":" (sha8 prompt))
        labels (load-labels)
        todo (->> (if (seq roots)
                    (traces/find-traces roots)
                    (map traces/load-trace (filter fs/exists? (human-labeled labels))))
                  (remove #(contains? labels [(:dir %) labeler])))
        ask (claude-ask model prompt)]
    (println "judging" (count todo) "traces as" labeler)
    (doseq [t todo]
      (try (let [v (judge-trace ask t)]
             (add-label! (merge {:trace (:dir t) :labeler labeler} v))
             (println (:outcome v) (:dir t)))
           (catch Exception e (println "ERROR" (:dir t) (ex-message e)))))))

;; Agreement

(defn- ratio [a b] (when (pos? b) (double (/ a b))))

(defn agreement
  "Each judge's confusion counts against the human, with bad as the positive
  class, and the traces where they disagree."
  [labels]
  (let [human (into {} (for [[[dir labeler] l] labels :when (= "human" labeler)] [dir l]))]
    (into (sorted-map)
          (for [[labeler pairs] (group-by first (for [[[dir labeler] l] labels
                                                      :when (and (not= "human" labeler) (human dir))]
                                                  [labeler (human dir) l]))
                :let [n (fn [h j] (count (filter (fn [[_ hl jl]] (and (= h (:outcome hl)) (= j (:outcome jl)))) pairs)))
                      tp (n "bad" "bad") fp (n "good" "bad") fneg (n "bad" "good") tn (n "good" "good")]]
            [labeler {:n (count pairs) :tp tp :fp fp :fn fneg :tn tn
                      :precision (ratio tp (+ tp fp))
                      :recall (ratio tp (+ tp fneg))
                      :true-negative-rate (ratio tn (+ tn fp))
                      :disagreements (vec (for [[_ h j] pairs :when (not= (:outcome h) (:outcome j))]
                                            {:trace (:trace h) :human h :judge j}))}]))))

(defn agreement! []
  (let [result (agreement (load-labels))]
    (when (empty? result)
      (println "No trace has both a human and a judge label. Run bb eval:review, then bb eval:judge."))
    (doseq [[labeler {:keys [n tp fp tn precision recall true-negative-rate disagreements] fneg :fn}] result]
      (println (format "%s: %d traces, bad caught %d, missed %d, false alarms %d, good agreed %d" labeler n tp fneg fp tn))
      (println (format "  precision %s, recall %s, true negative rate %s"
                       (some->> precision (format "%.2f")) (some->> recall (format "%.2f"))
                       (some->> true-negative-rate (format "%.2f"))))
      (doseq [{:keys [trace human judge]} disagreements]
        (println (str "\n  " trace "\n    human " (:outcome human) ": " (:critique human)
                      "\n    judge " (:outcome judge) ": " (:critique judge)))))))
