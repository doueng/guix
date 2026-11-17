#!/usr/bin/env bb
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
     (for [t (sort (remove targets (into phony help)))]
       (str "Makefile: " t " is in .PHONY or make help but has no rule"))
     (for [cmd (distinct cmds)
           :let [t (c/make-target-of cmd)]
           :when (and t (not (targets t)))]
       (str "`" cmd "` names a missing Makefile target")))))

(defn rule-problems []
  (for [{:keys [name paths]} (c/rules)
        glob paths
        :when (not-any? (c/matcher glob) files)]
    (str "checks.edn rule " name ": " glob " matches no tracked file")))

(defn sha256 [& parts]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [p parts] (.update md (if (string? p) (.getBytes ^String p "UTF-8") ^bytes p)))
    (format "%064x" (BigInteger. 1 (.digest md)))))

(defn scheme-problems []
  (let [scm (sort (filter #(str/ends-with? % ".scm") files))
        key (apply sha256 (str (fs/real-path guix)) (slurp (str c/root "/desktop/build/checks/scheme.scm"))
                   (mapcat (fn [f] [f (sha256 (fs/read-all-bytes (str c/root "/" f)))])
                           (sort (distinct (concat scm (filter #(str/starts-with? % "modules/") files))))))
        stamp (fs/path (or (System/getenv "XDG_CACHE_HOME") (str (fs/home) "/.cache")) "guix-lint" key)]
    (when-not (fs/exists? stamp)
      (let [{:keys [exit out err]} (apply c/sh guix "repl" "-L" "modules" "--" "desktop/build/checks/scheme.scm" scm)]
        (if (zero? exit)
          (do (fs/create-dirs (fs/parent stamp))
              (spit (str stamp) "")
              (doseq [old (drop 20 (sort-by #(- (.toMillis (fs/last-modified-time %))) (fs/list-dir (fs/parent stamp))))]
                (fs/delete-if-exists old))
              nil)
          [(str/trim (str err out))])))))

(defn tested
  "A problem naming WHAT when test command ARGV, run with options OPTS, fails, or
  nil. It reruns only when a file in OWN changed since it last passed; the stamp is
  PREFIX-sha256(SEED, OWN)."
  [what prefix seed own opts argv]
  (let [key (apply sha256 seed (mapcat (fn [f] [f (sha256 (fs/read-all-bytes (str c/root "/" f)))]) (sort own)))
        stamp (fs/path (or (System/getenv "XDG_CACHE_HOME") (str (fs/home) "/.cache")) "guix-lint" (str prefix "-" key))]
    (when-not (fs/exists? stamp)
      (let [{:keys [exit out err]} (apply c/sh opts argv)]
        (if (zero? exit)
          (do (fs/create-dirs (fs/parent stamp)) (spit (str stamp) "") nil)
          (str what " failed:\n" (str/join "\n" (take-last 30 (c/lines (str out err))))))))))

(defn chrome-problems []
  (let [exts (sort (distinct (keep #(second (re-find #"^desktop/build/chrome/([^/]+)/bb\.edn$" %)) files)))]
    (doall
     (for [ext exts
           :let [dir (str "desktop/build/chrome/" ext "/")
                 problem (tested (str dir " bb test") "chrome" ext (filter #(str/starts-with? % dir) files)
                                 {:dir (str c/root "/" dir)} ["bb" "test"])]
           :when problem]
       problem))))

(defn md-view-problems []
  (keep identity [(tested "make md-view-test" "md-view" "md-view" (filter #(or (= % "desktop/bin/md-view") (str/starts-with? % "desktop/build/md-view/")) files)
                          {:dir c/root} ["bb" "desktop/build/md-view/test.bb"])]))

(defn ext-reload-problems
  "Each extension ext-reload serves must reload itself when its manifest.json changed: ext-reload's disable and
  enable keep the manifest Chrome first loaded, so without it a new command never gets its key (T197's Cmd+T)."
  []
  (for [ext (sort (distinct (keep #(second (re-find #"^desktop/build/chrome/([^/]+)/bb\.edn$" %)) files)))
        :let [dir (str "desktop/build/chrome/" ext "/")]
        :when (some #{(str dir "ext/manifest.json")} files)
        :when (not-any? #(let [s (slurp (str c/root "/" %))]
                           (and (str/includes? s "(reread-manifest!)") (str/includes? s "\"manifest.json\"")
                                (str/includes? s "js/chrome.runtime.reload")))
                        (filter #(and (str/starts-with? % (str dir "src/")) (re-find #"\.clj[sc]?$" %)) files))]
   (str dir ": its background never calls reread-manifest! (see vimium-cljs bg/main.cljs),"
        " so after ext-reload Chrome keeps running its old manifest.json")))

(def steps
  [["scheme" scheme-problems]
   ["chrome" chrome-problems]
   ["ext-reload" ext-reload-problems]
   ["md-view" md-view-problems]
   ["fish" #(run-each (fn [f] ["fish" "--no-execute" f]) (filter (fn [f] (str/ends-with? f ".fish")) files))]
   ["babashka" #(parse-each (fn [s] (edamame.core/parse-string-all s {:all true :auto-resolve name :readers (fn [_] identity)}))
                            (filter (fn [f] (or (str/ends-with? f ".bb") (re-find #"^#!.*\bbb\b" (first-line f)))) files))]
   ["no-shell" #(for [f shell-scripts] (str f ": shell script; write it in babashka (#!/usr/bin/env bb)"))]
   ["json" #(parse-each (fn [s] ; parse-string ignores text after the first value
                         (let [vs (doall (json/parsed-seq (java.io.StringReader. s)))]
                           (when (not= 1 (count vs)) (throw (Exception. (str (count vs) " top-level values"))))))
                       (filter (fn [f] (str/ends-with? f ".json")) files))]
   ["edn" #(parse-each (fn [s] (edn/read-string {:default tagged-literal} (str "[" s "]")))
                       (filter (fn [f] (str/ends-with? f ".edn")) files))]
   ["parinfer" #(let [{:keys [exit out err]} (c/sh "bb" "desktop/build/checks/parinfer.bb")]
                   (when-not (zero? exit) [(str/trim (str out err))]))]
   ["makefile" makefile-problems]
   ["checks.edn" rule-problems]])

(let [results (->> steps (map (fn [[n f]] [n (future (doall (f)))])) (mapv (fn [[n f]] [n @f])))
      failed (filter (comp seq second) results)]
  (doseq [[n problems] results]
    (println (format "%-10s %s" n (if (seq problems) "FAIL" "ok")))
    (doseq [p problems] (println (str "  " (str/replace p "\n" "\n  ")))))
  (System/exit (if (seq failed) 1 0)))
