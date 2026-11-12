#!/usr/bin/env bb
;; Smoke test: main.ts highlights a small git diff, and browser.ts still
;; bundles to the tracked bbh highlights.js.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def here (str (fs/parent (fs/absolutize *file*))))
(def diff "diff --git a/x.clj b/x.clj
index 1111111..2222222 100644
--- a/x.clj
+++ b/x.clj
@@ -1,2 +1,2 @@
 (ns x)
-(def a 1)
+(def a 2)
")

(defn fail [msg] (binding [*out* *err*] (println "pierre-diff-ansi:" msg)) (System/exit 1))

(let [out (:out (p/shell {:dir here :in diff :out :string} "bun" "main.ts" "--width" "60"))
      plain (str/replace out #"\x1b\[[0-9;]*m" "")]
  (when-not (str/includes? plain "x.clj") (fail (str "no file header in " (pr-str plain))))
  (when-not (re-find #"(?m)^\+.*\(def a 2\)" plain) (fail (str "no added line in " (pr-str plain))))
  (when-not (re-find #"\x1b\[38;2;" out) (fail "no colour in the output")))

(let [tmp (str (fs/create-temp-file {:suffix ".js"}))]
  (try
    (p/shell {:dir here :out :string :err :string}
             "bun" "build" "browser.ts" "--target=browser" "--format=iife" "--minify" (str "--outfile=" tmp))
    (when-not (= (slurp tmp) (slurp (str here "/../bbh/src/bbh/web/highlights.js")))
      (fail "desktop/build/bbh/src/bbh/web/highlights.js is stale; run make bbh-web-highlights"))
    (finally (fs/delete-if-exists tmp))))
(println "pierre-diff-ansi: highlighted diff and bundle ok")
