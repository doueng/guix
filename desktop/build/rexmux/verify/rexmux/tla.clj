(ns rexmux.tla
  "Model-check verify/tla with TLC, through bbh's runner (one rule per run,
  shortest counterexample each)."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]))

(def models-dir (str (fs/path (fs/parent (fs/parent (fs/absolutize *file*))) "tla")))
(cp/add-classpath (str (fs/path models-dir ".." ".." ".." "bbh" "verify")))
(require 'bbh.tla)

(defn -main [& args]
  (with-redefs [bbh.tla/models-dir models-dir]
    (apply (resolve 'bbh.tla/-main) args)))
