#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def plain ["--no-lisp-vline-symbols" "--no-lisp-block-comments" "--no-guile-block-comments"
            "--no-scheme-sexp-comments" "--no-janet-long-strings"])
(def scheme ["--lisp-vline-symbols" "--lisp-block-comments" "--scheme-sexp-comments"
             "--no-guile-block-comments" "--no-janet-long-strings"])

(defn first-line [f]
  (with-open [r (clojure.java.io/reader (str f))] (or (.readLine r) "")))

(defn options
  "parinfer-rust flags for path, or nil when it is not Lisp."
  [path]
  (let [ext (second (re-find #"\.([a-z]+)$" (str (fs/file-name path))))]
    (cond (#{"clj" "cljs" "cljc" "bb" "edn" "el" "fnl" "kbd"} ext) plain
          (#{"scm" "sls" "ss"} ext) scheme
          (and (nil? ext) (fs/regular-file? path) (re-find #"^#!.*\bbb\b" (first-line path))) plain)))

(defn parinfer [text flags mode]
  (let [{:keys [exit out err]} (apply p/shell {:in text :out :string :err :string :continue true}
                                      "parinfer-rust" "-m" mode flags)]
    (if (zero? exit) out (throw (ex-info (str/trim (str err out)) {})))))

(defn drop-emptied-lines
  "Paren mode moves a line of closing parens up to the line before and leaves
  its indentation behind; drop such lines."
  [before after]
  (let [a (str/split-lines before) b (str/split-lines after)]
    (if (not= (count a) (count b))
      after
      (str (str/join "\n" (keep (fn [[x y]] (when-not (and (str/blank? y) (not (str/blank? x))) y))
                                (map vector a b)))
           (when (str/ends-with? after "\n") "\n")))))

(defn problem
  "nil when path is parinfer-stable, else why not."
  [path]
  (let [flags (options path) text (slurp (str path))]
    (try (cond (not= text (parinfer text flags "paren")) "paren mode would reindent it"
               (not= text (parinfer text flags "indent")) "indent mode would move its parens")
         (catch Exception e (.getMessage e)))))

(defn fix! [path]
  (let [flags (options path) text (slurp (str path))
        fixed (drop-emptied-lines text (parinfer text flags "paren"))]
    (spit (str path) fixed)
    (problem path)))

(defn tracked [root]
  (let [jj (p/shell {:dir root :out :string :err :string :continue true} "jj" "file" "list")
        {:keys [out]} (if (zero? (:exit jj)) jj
                          (p/shell {:dir root :out :string :err :string} "git" "ls-files"))]
    (map #(str (fs/path root %)) (remove str/blank? (str/split-lines out)))))

(let [args *command-line-args*
      fix? (some #{"--fix"} args)
      root (or (second (drop-while #(not= "--root" %) args))
               (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))))
      named (remove #(or (#{"--fix" "--root"} %) (= % root)) args)
      files (filter #(and (fs/regular-file? %) (options %)) (or (seq named) (tracked root)))
      bad (->> files (pmap (fn [f] [f (problem f)])) (filter second) doall)
      left (if fix? (doall (keep (fn [[f _]] (when-let [why (fix! f)] [f why])) bad)) bad)]
  (doseq [[f why] bad]
    (println (str (let [a (fs/absolutize f)] (if (fs/starts-with? a root) (fs/relativize root a) a)) ": " why
                  (when (and fix? (not (some #{f} (map first left)))) " (fixed)"))))
  (when (and (seq left) (not fix?))
    (println (str "Fix with: bb " *file* " --fix"
                  (when (some #{"--root"} args) (str " --root " root))
                  (when (seq named) " FILE..."))))
  (System/exit (if (seq left) 1 0)))
