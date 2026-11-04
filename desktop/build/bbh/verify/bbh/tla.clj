(ns bbh.tla
  "Model-check the TLA+ models in verify/tla with TLC. A config M.NAME.cfg
  checks M.tla. TLC stops at the first broken rule, so each broken rule is
  reported with its shortest counterexample and dropped, and TLC runs again
  on the rest until none breaks."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def models-dir (str (fs/path (fs/parent (fs/parent *file*)) "tla")))

(def ^:private rule-sections #{"INVARIANT" "INVARIANTS" "PROPERTY" "PROPERTIES"})

(defn- tlc-command []
  (if (fs/which "tlc2") ["tlc2"] ["guix" "shell" "tla2tools" "--" "tlc2"]))

(defn parse-cfg
  "{:base text-without-rules :rules [[section name] ...]} of CFG text."
  [cfg]
  (loop [[line & more] (str/split-lines cfg) section nil base [] rules []]
    (if (nil? line)
      {:base (str/join "\n" base) :rules rules}
      (let [[head & words] (remove str/blank? (str/split (str/trim line) #"\s+"))]
        (cond (rule-sections head) (recur more head base (into rules (map #(vector head %) words)))
              (and head (re-matches #"[A-Z_]+" head)) (recur more nil (conj base line) rules)
              section (recur more section base (into rules (map #(vector section %) (cons head words))))
              :else (recur more nil (conj base line) rules))))))

(defn- tlc!
  "TLC's output for SPEC under config text CFG, run in a scratch directory so
  its trace files stay out of the repo."
  [spec cfg]
  (let [dir (fs/create-temp-dir {:prefix "bbh-tla-"})]
    (try
      (doseq [f (fs/glob models-dir "*.tla")] (fs/copy f (fs/path dir (fs/file-name f))))
      (spit (str (fs/path dir "check.cfg")) cfg)
      (let [{:keys [out err]} (apply p/sh {:dir (str dir)}
                                     (concat (tlc-command) ["-deadlock" "-difftrace" "-workers" "auto"
                                                            "-config" "check.cfg" (str spec ".tla")]))]
        (str out err))
      (finally (fs/delete-tree dir)))))

(defn- trace [text]
  (->> (str/split-lines text)
       (drop-while #(not (str/starts-with? % "Error:")))
       (take-while #(not (re-find #"states generated" %)))
       (str/join "\n")))

(defn check
  "[{:rule :ok :trace}] for every rule of CONFIG, a file name in verify/tla."
  [config]
  (let [spec (first (str/split config #"\."))
        {:keys [base rules]} (parse-cfg (slurp (str (fs/path models-dir config))))]
    (loop [left rules acc []]
      (if (empty? left)
        acc
        (let [text (tlc! spec (str base "\n" (str/join "\n" (map #(str/join " " %) left)) "\n"))
              states (some->> (re-seq #"([\d,]+) distinct states found" text) last second)
              broken (some->> (re-find #"Error: (?:Invariant|Action property) (\S+) is violated" text) second)]
          (cond broken (recur (remove #(= broken (second %)) left)
                              (conj acc {:rule broken :ok false :trace (trace text)}))
                (str/includes? text "No error has been found")
                (into acc (map #(hash-map :rule (second %) :ok true :states states) left))
                :else (conj acc {:rule "TLC" :ok false :trace text})))))))

(defn -main
  "Check the configs named in ARGS, such as Super.asks.cfg, or every config in
  verify/tla. Exits 1 when a rule breaks."
  [& args]
  (let [configs (or (seq args) (sort (map (comp str fs/file-name) (fs/glob models-dir "*.cfg"))))
        results (doall (for [c configs r (check c)] (assoc r :config c)))]
    (doseq [{:keys [config rule ok states trace]} results]
      (println (format "%-4s %s %s%s" (if ok "ok" "FAIL") config rule (if states (str "  (" states " states)") "")))
      (when-not ok (println trace "\n")))
    (when-not (every? :ok results) (System/exit 1))))
