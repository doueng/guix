#!/usr/bin/env bb
;; doom doctor exits 0 despite missing tools, so compare its warnings with a
;; baseline. --accept writes the current warnings as the new baseline.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def baseline "desktop/build/doom/doctor-baseline.txt")
(def doom (or (System/getenv "DOOM") (str (fs/home) "/.config/emacs/bin/doom")))

(defn warnings
  "Each `! warning` line, prefixed with the module heading above it. Doom's CLI
  reads piped stdin to EOF, so it gets an empty one."
  []
  (let [{:keys [exit out err]} (p/sh {:dir (str (fs/home)) :in "" :continue true} doom "doctor")
        text (str out err)]
    (when-not (zero? exit) (binding [*out* *err*] (println text)) (System/exit 1))
    (->> (str/split-lines text)
         (reduce (fn [[module acc] line]
                   (if-let [[_ a b] (re-find #"^ *> :(\S+) (\S+)" line)]
                     [(str ":" a " " b ": ") acc]
                     (if-let [[_ w] (re-find #"^ *! (.*)" line)]
                       [module (conj acc (str module w))]
                       [module acc])))
                 ["" []])
         second sort distinct)))

(let [ws (warnings)]
  (if (= "--accept" (first *command-line-args*))
    (spit baseline (str (str/join "\n" ws) "\n"))
    (let [new (remove (set (str/split-lines (slurp baseline))) ws)]
      (if (seq new)
        (do (println "New doom doctor warnings (accept with bb desktop/build/doom/doctor.bb --accept):")
            (run! println new)
            (System/exit 1))
        (println "doom doctor: no new warnings")))))
