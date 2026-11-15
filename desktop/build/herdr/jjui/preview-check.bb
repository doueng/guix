#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])
(load-file (str (fs/parent *file*) "/jjui.bb"))

(let [repo (or (first *command-line-args*) ".")
      herdr-path (->> (str/split (System/getenv "PATH") #":") (remove #(str/includes? % "/.local/bin")) (str/join ":"))
      env ((or (some-> (resolve 'jjui/pane-env) deref) identity) {"HOME" (str (fs/home)) "TERM" "xterm-256color" "PATH" herdr-path})
      {:keys [exit out err]} (p/shell {:dir repo :env env :out :string :err :string :continue true}
                                      "jj" "jjui-show-fancy" "@-")]
  (if (and (zero? exit) (not (str/blank? out)) (str/blank? err))
    (println "ok: preview has" (count (str/split-lines out)) "lines")
    (do (println "preview failed, exit" exit) (println err) (System/exit 1))))
