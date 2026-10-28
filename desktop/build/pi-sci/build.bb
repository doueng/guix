#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p])
(let [here (str (fs/parent (fs/absolutize *file*)))
      run #(apply p/shell {:dir here} %&)]
  (fs/create-dirs (str here "/dist"))
  (when-not (fs/directory? (str here "/node_modules/@earendil-works/pi-coding-agent"))
    (run "npm" "ci" "--ignore-scripts"))
  (run "node" "scripts/prepare-search.mjs")
  (run "clojure" "-M" "-m" "pi.prepare")
  (run "clojure" "-J-Xmx2g" "-M:compile" "-m" "cljs.main" "-co" "compiler.edn" "-c" "pi.sci")
  (fs/move (str here "/dist/sci.pending.js") (str here "/dist/sci.js") {:replace-existing true}))
