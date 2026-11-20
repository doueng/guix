#!/usr/bin/env bb
;; make ghostty-rex: compile rexg-render into target/ against the repo's
;; libghostty-vt package, for working on the C half. The installed client
;; uses the rexg-render Guix package instead (modules/engstrand/packages/ghostty.scm).
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      repo (str (fs/canonicalize (fs/path here ".." ".." "..")))]
  (fs/create-dirs (str here "/target"))
  (p/shell {:dir repo} "guix" "shell" "-L" "modules" "gcc-toolchain"
           "-e" "(@ (engstrand packages ghostty) libghostty-vt)" "--"
           "gcc" "-O2" "-Wall" "-Wextra" "-o" (str here "/target/rexg-render")
           (str here "/csrc/rexg-render.c") "-lghostty-vt" "-lpthread")
  (println (str "built " here "/target/rexg-render")))
