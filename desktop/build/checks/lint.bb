#!/usr/bin/env bb
;; Fast checks that run on every change: Scheme compiles without warnings,
;; scripts and data files parse, no shell scripts are tracked, and the Makefile,
;; AGENTS.md and checks.edn agree with each other.
(load-file (str (babashka.fs/parent *file*) "/common.clj"))
(require '[checks.common :as c] '[babashka.fs :as fs] '[cheshire.core :as json]
         '[clojure.edn :as edn] '[clojure.string :as str] 'edamame.core)

(def guix (or (System/getenv "GUIX") (str (fs/home) "/.config/guix/current/bin/guix")))
(def files (filter #(fs/regular-file? (str c/root "/" %)) (c/tracked-files)))

(defn first-line [f]
  (with-open [r (clojure.java.io/reader (str c/root "/" f))] (or (.readLine r) "")))

(def shell-scripts
  (filter #(or (re-find #"\.(sh|bash)$" %) (re-find #"^#!.*\b(sh|bash|dash|zsh|ksh)\b" (first-line %))) files))

(defn run-each
  "Problems from running (cmd file) over files: [file output] where it exits non-zero."
  [cmd fs]
  (for [f fs
        :let [{:keys [exit out err]} (apply c/sh (cmd f))]
        :when (not (zero? exit))]
    (str f ": " (str/trim (str out err)))))

(defn parse-each [parse fs]
  (for [f fs
        :let [problem (try (parse (slurp (str c/root "/" f))) nil
                           (catch Exception e (.getMessage e)))]
        :when problem]
    (str f ": " problem)))

(defn makefile-problems []
  (let [mk (slurp (str c/root "/Makefile"))
        phony (-> (re-find #"(?s)\.PHONY:(.*?)(?<!\\)\n" mk) second
                  (str/replace "\\" " ") str/trim (str/split #"\s+") set)
        help (set (map second (re-seq #"@echo '([a-z0-9-]+) " mk)))
        targets (c/make-targets)
        agents (slurp (str c/root "/AGENTS.md"))
        cmds (concat (map second (re-seq #"`(make [a-z0-9-]+)" agents))
                     (mapcat #(concat (:checks %) (:slow %)) (c/rules)))]
    (concat
     (for [t (sort (disj phony "help")) :when (not (help t))]
       (str "Makefile: .PHONY target " t " has no line in make help"))
     (for [t (sort (remove phony (filter help targets)))]
       (str "Makefile: " t " is in make help but not .PHONY"))
     (for [cmd (distinct cmds)
           :let [t (c/make-target-of cmd)]
           :when (and t (not (targets t)))]
       (str "`" cmd "` names a missing Makefile target")))))

(defn rule-problems []
  (for [{:keys [name paths]} (c/rules)
        glob paths
        :when (not-any? (c/matcher glob) files)]
    (str "checks.edn rule " name ": " glob " matches no tracked file")))

(def steps
  [["scheme" #(let [{:keys [exit out err]}
                     (apply c/sh guix "repl" "-L" "modules" "--" "desktop/build/checks/scheme.scm"
                           (filter (fn [f] (str/ends-with? f ".scm")) files))]
                 (when-not (zero? exit) [(str/trim (str err out))]))]
   ["fish" #(run-each (fn [f] ["fish" "--no-execute" f]) (filter (fn [f] (str/ends-with? f ".fish")) files))]
   ;; Scripts are babashka: `#!/usr/bin/env bb`, with babashka.process/exec for wrappers.
   ["babashka" #(parse-each (fn [s] (edamame.core/parse-string-all s {:all true :auto-resolve name :readers (fn [_] identity)}))
                            (filter (fn [f] (or (str/ends-with? f ".bb") (re-find #"^#!.*\bbb\b" (first-line f)))) files))]
   ["no-shell" #(for [f shell-scripts] (str f ": shell script; write it in babashka (#!/usr/bin/env bb)"))]
   ["json" #(parse-each (fn [s] ; parse-string ignores text after the first value
                         (let [vs (doall (json/parsed-seq (java.io.StringReader. s)))]
                           (when (not= 1 (count vs)) (throw (Exception. (str (count vs) " top-level values"))))))
                       (filter (fn [f] (str/ends-with? f ".json")) files))]
   ["edn" #(parse-each (fn [s] (edn/read-string {:default tagged-literal} (str "[" s "]")))
                       (filter (fn [f] (str/ends-with? f ".edn")) files))]
   ["makefile" makefile-problems]
   ["checks.edn" rule-problems]])

(let [results (->> steps (map (fn [[n f]] [n (future (doall (f)))])) (mapv (fn [[n f]] [n @f])))
      failed (filter (comp seq second) results)]
  (doseq [[n problems] results]
    (println (format "%-10s %s" n (if (seq problems) "FAIL" "ok")))
    (doseq [p problems] (println (str "  " (str/replace p "\n" "\n  ")))))
  (System/exit (if (seq failed) 1 0)))
