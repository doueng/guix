;; Offline test of skill-diet's bbh eval adapter: drives it with bbh's script
;; engine and checks result.json and the isolation the eval relies on.
;;   bb desktop/build/skill-diet/adapter-test.bb
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def adapter (str (fs/absolutize (fs/path (fs/parent *file*) "../../pi/agent/skills/skill-diet/adapters/bbh"))))
(def tmp (fs/create-temp-dir {:prefix "skill-diet-adapter-test"}))
(def failures (atom 0))
(defn check [ok what] (println (if ok "PASS" "FAIL") what) (when-not ok (swap! failures inc)))

(defn run! [label skill-dir]
  (let [dir (fs/path tmp label)
        work (doto (fs/path dir "work") fs/create-dirs)
        out (fs/path dir "out")
        script (str (fs/path dir "script.edn"))]
    ;; A repository root with an AGENTS.md, which the adapter must hide.
    (fs/create-dirs (fs/path work ".git"))
    (spit (str (fs/path work "AGENTS.md")) "LEAKED-INSTRUCTIONS")
    (spit script (pr-str [{:code (str "(do " (when skill-dir (str "(slurp \"" skill-dir "/SKILL.md\") "))
                                      "[(mapv :name (bbh.env/skills)) (count (#'bbh.env/instruction-files))])")
                           :usage {:input 100 :cached 60 :written 10 :output 5}}
                          {:text "PLUM" :usage {:input 120 :cached 100 :written 0 :output 3}}]))
    (let [{:keys [exit]} (p/shell {:continue true :out :string :err :string
                                   :extra-env {"EVAL_MODEL" (str "script/" script)}}
                                  adapter (or skill-dir "") "go" (str out) (str work))
          result (json/parse-string (slurp (str (fs/path out "result.json"))) true)
          tool-output (->> (str/split-lines (slurp (str (fs/path out "events.jsonl"))))
                           (map #(json/parse-string % true))
                           (keep #(get-in % [:params :update]))
                           (filter #(= "tool_call_update" (:sessionUpdate %)))
                           (map #(get-in % [:content 0 :content :text]))
                           (str/join "\n"))]
      {:exit exit :result result :tool-output tool-output})))

(let [skill (str (fs/path tmp "sk/before/demo"))
      _ (fs/create-dirs skill)
      _ (spit (str (fs/path skill "SKILL.md")) "---\nname: demo\ndescription: Demo.\n---\n\nplum\n")
      {:keys [exit result tool-output]} (run! "skill" skill)]
  (check (zero? exit) "skill run exits 0")
  (check (= "PLUM" (:answer result)) "answer is the final reply")
  (check (= {:input 50 :output 8 :cacheRead 160 :cacheWrite 10} (:tokens result)) "tokens exclude cache reads and writes from input")
  (check (= 2 (:turns result)) "turns count model requests")
  (check (and (= 1 (count (:calls result))) (str/includes? (:command (first (:calls result))) (str skill "/SKILL.md")))
         "the call records the skill read under the given path")
  (check (str/includes? tool-output "[[\"demo\"] 0]") "only the skill under test is listed and no AGENTS.md is read"))

(let [{:keys [exit result tool-output]} (run! "none" nil)]
  (check (zero? exit) "no-skill run exits 0")
  (check (str/includes? tool-output "[[] 0]") "no skill is listed when the skill dir is empty")
  (check (= "PLUM" (:answer result)) "no-skill run answers"))

(fs/delete-tree tmp)
(System/exit (if (zero? @failures) 0 1))
