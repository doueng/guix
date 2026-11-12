#!/usr/bin/env bb
;; Tests the built dist/; make pi-sci-test builds it first when a source changed.
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      run #(apply p/shell {:dir here} %&)]
  (run "npm" "ci" "--ignore-scripts")
  (run "npm" "run" "check")
  (run "npm" "run" "lint")
  (run "npm" "test")
  (p/shell {:dir (str here "/../bbh")} "bb" "test:workflow")
  (run "npm" "audit" "--audit-level=high"))
