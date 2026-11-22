#!/usr/bin/env bb
;; make test and make verify: the checks.edn rules covering files changed in a
;; revision, or the rules named with --only, or all with --all; --run runs their
;; checks after lint, --slow adds the slow and live ones.
(load-file (str (babashka.fs/parent *file*) "/common.clj"))
(require '[checks.common :as c] '[clojure.string :as str] '[babashka.process :as p])

(defn slug [name] (str/replace (str/lower-case name) #"\s+" "-"))

(defn named
  "The rules whose dashed name starts with one of the comma-separated NAMES."
  [rules names]
  (let [wanted (map slug (str/split names #","))]
    (filter (fn [r] (some #(str/starts-with? (slug (:name r)) %) wanted)) rules)))

(let [args *command-line-args*
      flag (fn [f] (some #{f} args))
      run? (flag "--run")
      slow? (flag "--slow")
      only (second (drop-while #(not= "--only" %) args))
      rev (or (last (remove #(or (str/starts-with? % "--") (= % only)) args)) "@")
      rules (c/rules)
      changed (when-not (or only (flag "--all")) (c/changed-files rev))
      hits (cond only (named rules only)
                 (flag "--all") rules
                 :else (distinct (mapcat #(c/matching-rules rules %) changed)))
      uncovered (remove #(seq (c/matching-rules rules %)) changed)
      checks (distinct (mapcat :checks hits))
      slow (remove (set checks) (distinct (mapcat :slow hits)))]
  (when (and only (empty? hits))
    (println (str "No checks.edn rule starts with " only ". Rules: " (str/join ", " (map (comp slug :name) rules))))
    (System/exit 2))
  (when (and (not only) (not (flag "--all")) (empty? changed))
    (println (str "No files changed in " rev ". Pass a revision, such as make test REV=@-."))
    (System/exit 0))
  (println (str (if changed (str (count changed) " file(s) changed in " rev "; rules: ") "Rules: ")
                (str/join ", " (map (comp slug :name) hits))))
  (println "Checks:")
  (doseq [cmd checks] (println "  " cmd))
  (when (seq slow)
    (println (if slow? "Slow checks:" "Slow checks (make verify):"))
    (doseq [cmd slow] (println "  " cmd)))
  (when (seq uncovered)
    (println "No rule covers these files; add one to desktop/build/checks/checks.edn if they can break:")
    (doseq [f uncovered] (println "  " f)))
  (when run?
    (let [failed (doall
                  (for [cmd (distinct (concat ["make lint"] checks (when slow? slow)))
                        :let [_ (println (str "\n==> " cmd))
                              {:keys [exit]} (p/shell {:dir c/root :continue true} "sh" "-c" cmd)]
                        :when (not (zero? exit))]
                    cmd))]
      (println)
      (if (seq failed)
        (do (println "FAILED:") (doseq [cmd failed] (println "  " cmd)) (System/exit 1))
        (println "All checks passed.")))))
