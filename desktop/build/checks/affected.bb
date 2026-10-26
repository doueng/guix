#!/usr/bin/env bb
;; bb affected.bb [--run] [--slow] [REV]
;; Print the checks that cover the files changed in REV (default @), from
;; checks.edn, and the changed files no rule covers. With --run, run the checks
;; and exit 1 if any fails; --run always runs make lint first, and --slow
;; also runs the slow ones.
(load-file (str (babashka.fs/parent *file*) "/common.clj"))
(require '[checks.common :as c] '[clojure.string :as str] '[babashka.process :as p])

(let [args *command-line-args*
      run? (some #{"--run"} args)
      slow? (some #{"--slow"} args)
      rev (or (first (remove #(str/starts-with? % "--") args)) "@")
      rules (c/rules)
      changed (c/changed-files rev)
      hits (mapcat #(c/matching-rules rules %) changed)
      uncovered (remove #(seq (c/matching-rules rules %)) changed)
      checks (distinct (mapcat :checks hits))
      slow (remove (set checks) (distinct (mapcat :slow hits)))]
  (when (empty? changed)
    (println (str "No files changed in " rev ". Pass a revision, such as make affected REV=@-."))
    (System/exit 0))
  (println (str (count changed) " file(s) changed in " rev "; rules: "
                (str/join ", " (distinct (map :name hits)))))
  (println "Checks:")
  (doseq [cmd checks] (println "  " cmd))
  (when (seq slow)
    (println "Slow checks (make verify SLOW=1):")
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
        (println "All affected checks passed.")))))
