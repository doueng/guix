(ns rexmux.quint
  "The Quint port of the control connection, verify/quint/connect.qnt.
  check (bb verify:quint, in make test): typecheck, the run tests and a
  fixed-seed simulation of the safety rules, a few seconds. model-check (bb
  verify:quint-mc, in make verify): TLC over every state with the liveness
  rules, seconds, then Apalache's bounded check to STEPS (default 8, about two
  minutes; each step past 8 costs several times the last)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def root (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))
(def spec (str (fs/path root "verify" "quint" "connect.qnt")))
(def modules (str (fs/normalize (fs/path root ".." ".." ".." "modules"))))

(defn- quint
  "quint from Home, else this repo's package through guix shell."
  []
  (if (fs/which "quint")
    ["quint"]
    ["guix" "shell" "-L" modules "-e" "(@ (engstrand packages quint) quint)" "--" "quint"]))

(defn- run!
  "Run quint ARGS in DIR; verify writes _apalache-out there."
  [dir args]
  (println "== quint" (str/join " " args))
  (:exit @(p/process (concat (quint) args) {:inherit true :continue true :dir dir})))

(defn- all! [checks]
  (let [dir (str (fs/create-temp-dir {:prefix "quint"}))
        ok (every? zero? (mapv #(run! dir %) checks))]
    (fs/delete-tree dir)
    (System/exit (if ok 0 1))))

(defn check [& _]
  (all! [["typecheck" spec]
         ["test" "--main" "connect3" spec]
         ["run" "--main" "connect3" "--invariant" "safe" "--seed" "0x1"
          "--max-samples" "3000" "--max-steps" "40" "--verbosity" "1" spec]]))

(defn model-check [& args]
  (all! [["verify" "--backend" "tlc" "--main" "connect3" "--invariant" "safe"
          "--temporal" "localAttaches,serverUpAttaches" spec]
         ["verify" "--main" "connect3" "--invariant" "safe"
          "--max-steps" (or (first args) "8") spec]]))
