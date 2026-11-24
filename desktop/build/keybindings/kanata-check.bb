#!/usr/bin/env bb
;; Run kanata.kbd chord tests in kanata's simulator, built once from the tag that
;; matches the Guix kanata (desktop/build/keybindings/kanata-test.bb).
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def version "1.11.0")
(def guix (str (fs/home) "/.config/guix/current/bin/guix"))
(def src (str (fs/home) "/.cache/kanata-sim/" version))
(def sim (str src "/target/release/kanata_simulated_input"))

(defn die [msg] (binding [*out* *err*] (println msg)) (System/exit 1))

(let [guix-version (some->> (:out (p/sh guix "show" "kanata")) (re-find #"(?m)^version: (\S+)") second)]
  (when (not= guix-version version)
    (die (str "Guix kanata is " guix-version ", the simulator " version "; update version in " *file*))))

(when-not (fs/exists? sim)
  (fs/delete-tree src)
  (p/shell "git" "clone" "--quiet" "--depth" "1" "--branch" (str "v" version) "https://github.com/jtroo/kanata" src)
  (p/shell {:dir src} guix "shell" "rust" "rust:cargo" "gcc-toolchain" "--" "cargo" "build" "--release" "--quiet" "-p" "kanata-sim"))

;; Cargo under guix shell links libgcc_s without a runpath, so the simulator
;; finds it through gcc-toolchain's lib, looked up on each run since that
;; profile is not a GC root.
(def libgcc-dir
  (let [{:keys [exit out]} (p/sh guix "shell" "gcc-toolchain" "--" "gcc" "-print-file-name=libgcc_s.so.1")]
    (when-not (zero? exit) (die "guix shell gcc-toolchain failed: cannot find libgcc_s for the simulator"))
    (str (fs/parent (fs/canonicalize (str/trim out))))))

(System/exit (:exit (p/shell {:continue true :extra-env {"LD_LIBRARY_PATH" libgcc-dir}}
                             "bb" "desktop/build/keybindings/kanata-test.bb" sim)))
