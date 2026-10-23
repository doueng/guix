(ns bbh.workflow-eval
  "Model-driven check of the workflow engine. A bbh conversation gets a real bug in a toy project and
  must fix it through wf. The report says whether it followed the briefs and the gates.
  Spends model credits. Args: [MODEL] [OUTPUT-DIR]. Runs for several minutes, so start it in the background."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.claude]
            [bbh.models :as models]
            [bbh.session :as session]
            [bbh.workflow :as wf]
            [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.string :as str]))

(def ^:private user-prompt
  "calc/add in src/calc.clj returns the difference of its arguments, but it should return their sum. Fix it. Running sh repro.sh in the project shows the bug.")

(defn- toy-project []
  (let [dir (str (fs/create-temp-dir {:prefix "wf-eval-project"}))]
    (fs/create-dirs (str dir "/src"))
    (spit (str dir "/src/calc.clj") "(ns calc)\n\n(defn add [a b] (- a b))\n")
    (spit (str dir "/repro.sh")
          "#!/bin/sh\nbb -cp src -e \"(require 'calc) (System/exit (if (= 5 (calc/add 2 3)) 0 1))\"\n")
    (fs/set-posix-file-permissions (str dir "/repro.sh") "rwxr-xr-x")
    dir))

(defn- journal [id]
  (->> (str/split-lines (slurp (str (#'wf/journal-file id))))
       (remove str/blank?)
       (mapv edn/read-string)))

(defn- phase-trail [events]
  (vec (for [e events :when (#{:attest :enter :verdict :done :block} (:type e))]
         (case (:type e)
           :attest [(:phase e) :attest (:outcome e)]
           :enter [(:phase e) :enter (:visit e)]
           :verdict [(:phase e) :verdict (:source e)]
           :done [:done]
           :block [:blocked (:reason e)]))))

(defn -main [& args]
  (let [model (or (first args) (models/default-model))
        out (str (fs/absolutize (or (second args) (str (fs/create-temp-dir {:prefix "wf-eval"})))))
        _ (fs/create-dirs out)
        proj (toy-project)
        log (str out "/events.edn")
        emit (fn [ev]
               (spit log (str (pr-str (select-keys ev [:type :text :code :used :spent])) "\n") :append true))
        before (wf/latest-id)
        s (session/new-session {:dir (str out "/conversation")
                                :init (str (fs/expand-home "~/.config/bbh/init.clj"))
                                :cwd proj :model model :make-engine models/engine :emit emit})
        _ (session/start-repl! s)
        outcome (deref (future (try (session/prompt! s user-prompt) (catch Exception e (str "error: " (ex-message e)))))
                       (* 25 60 1000) :timeout)
        _ (session/close! s)
        run (wf/latest-id)
        new-run? (not= before run)
        events (when run (journal run))
        tool-calls (->> (when (fs/exists? log) (str/split-lines (slurp log)))
                        (map edn/read-string)
                        (filter #(= :tool-call (:type %)))
                        (map :code))
        fixed? (str/includes? (slurp (str proj "/src/calc.clj")) "(+ a b)")
        repro (p/shell {:dir proj :continue true :out :string :err :string} "sh" "repro.sh")
        report {:model model
                :outcome (if (keyword? outcome) outcome (str outcome))
                :spent (:spent @s)
                :new-run? new-run?
                :run run
                :status (when run (:status (wf/status run)))
                :phase-trail (phase-trail events)
                :wf-calls (count (filter #(str/includes? % "wf") tool-calls))
                :used-jev? (boolean (some #(str/includes? % "jev") tool-calls))
                :reviews (vec (for [e events :when (= :verdict (:type e))]
                                {:phase (:phase e) :source (:source e) :verdict (:verdict e)}))
                :fixed? fixed?
                :repro-exit (:exit repro)
                :project proj
                :conversation (str out "/conversation")}]
    (spit (str out "/report.edn") (with-out-str (clojure.pprint/pprint report)))
    (prn report)
    report))
