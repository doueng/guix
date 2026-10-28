#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      run #(apply p/shell {:dir here} %&)]
  (run "./build.bb")
  (run "npm" "ci" "--ignore-scripts")
  (run "npm" "run" "check")
  (run "npm" "run" "lint")
  (run "npm" "test")
  (p/shell {:dir (str here "/../bbh")} "bb" "test:workflow")
  (run "npm" "audit" "--audit-level=high"))
