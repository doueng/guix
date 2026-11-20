#!/usr/bin/env bb
;; make mux MUX=rex|ghostty-rex|herdr: choose the terminal multiplexer (T207). It sets
;; %terminal-mux in modules/engstrand/config.scm, so the choice is part of the
;; Home configuration; the Makefile then runs make home-apply, which writes
;; ~/.config/engstrand/mux, and reloads Hyprland so its keys follow. Herdr and
;; both Rex clients stay installed: MUX=herdr is the rollback, and so is
;; guix home roll-back.
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def config (str (fs/path (fs/parent (fs/absolutize *file*)) ".." ".." ".." "modules" "engstrand" "config.scm")))
(def line-re #"(?m)^\(define %terminal-mux '([\w-]+)\)$")

(defn set-mux
  "TEXT with %terminal-mux set to MUX, or throws when the definition is missing."
  [text mux]
  (when-not (re-find line-re text)
    (throw (ex-info "no (define %terminal-mux '...) line in config.scm" {})))
  (str/replace text line-re (str "(define %terminal-mux '" mux ")")))

(defn -main [mux]
  (when-not (#{"herdr" "rex" "ghostty-rex"} mux)
    (binding [*out* *err*] (println "usage: make mux MUX=rex|ghostty-rex|herdr"))
    (System/exit 2))
  (let [text (slurp config)
        [_ was] (re-find line-re text)]
    (spit config (set-mux text mux))
    (println (str "terminal mux: " was " -> " mux " in " (fs/normalize config)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
