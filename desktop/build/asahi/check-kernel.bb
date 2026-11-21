#!/usr/bin/env bb
;; Compare linux-asahi (modules/engstrand/asahi/kernel.scm) with the booted
;; kernel: its .config against /proc/config.gz and its module list against
;; /run/booted-system/kernel.  Exits 1 when they differ, which is expected
;; after a deliberate config change or bump until you switch and reboot.
;; Usage: bb desktop/build/asahi/check-kernel.bb [KERNEL-STORE-PATH]
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.set :as set]
         '[clojure.string :as str])

(defn out [& args]
  (str/trim (:out (apply shell {:out :string :err :inherit} args))))

(def kernel
  (or (first *command-line-args*)
      (last (str/split-lines
             (out "guix" "build" "-L" "modules" "-e"
                  "(@ (engstrand asahi kernel) linux-asahi)")))))

(defn options [text]
  (set (filter #(re-find #"^(CONFIG_|# CONFIG_\w+ is not set)" %)
               (str/split-lines text))))

(defn modules [dir]
  (set (for [f (fs/glob dir "lib/modules/*/kernel/**.ko*")]
         (str/replace (str f) #".*/lib/modules/[^/]+/" ""))))

(def booted "/run/booted-system/kernel")
(def config-new (options (slurp (str kernel "/.config"))))
(def config-old (options (out "zcat" "/proc/config.gz")))
(def mods-new (modules kernel))
(def mods-old (modules (str (fs/real-path booted))))

(defn report [label old new]
  (let [gone (sort (set/difference old new))
        added (sort (set/difference new old))]
    (println (format "%-8s %d booted, %d built, %d only booted, %d only built"
                     label (count old) (count new) (count gone) (count added)))
    (doseq [l gone] (println "  -" l))
    (doseq [l added] (println "  +" l))
    (and (empty? gone) (empty? added))))

(println "kernel" kernel)
(let [same-config (report "config" config-old config-new)
      same-modules (report "modules" mods-old mods-new)]
  (when-not (and same-config same-modules)
    (System/exit 1)))
