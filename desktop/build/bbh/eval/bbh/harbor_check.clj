(ns bbh.harbor-check
  "Offline check of the Harbor bbh agent. It imports harbor/bbh_agent.py with the pinned
  Harbor, then runs Harbor's own ACP runner against harbor/bbh-acp with a scripted model in a
  temporary workspace. No Docker and no model API calls. Needs uv."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def harbor-version "0.25.0")
(def root (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))

(defn- run! [opts & args]
  (let [r (apply p/shell (merge {:out :string :err :string :continue true} opts) args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "Failed: " (str/join " " args) "\n" (:out r) (:err r)) {})))
    (:out r)))

(defn- check! [ok? message]
  (if ok? (println "ok  " message) (throw (ex-info (str "FAIL " message) {}))))

(defn -main [& _]
  (let [tmp (str (fs/create-temp-dir {:prefix "bbh-harbor-check"}))
        home (str tmp "/home") ws (str tmp "/ws") logs (str tmp "/logs")
        script (str tmp "/script.edn") runner (str tmp "/runner/acp_runner.py")
        harbor (str "harbor==" harbor-version)]
    (try
      (doseq [d [(str home "/app") (str home "/bin") ws logs (fs/parent runner)]] (fs/create-dirs d))
      (fs/copy-tree (str root "/src") (str home "/app/src"))
      (fs/copy (str root "/bb.edn") (str home "/app/bb.edn"))
      (fs/create-sym-link (str home "/bin/bb") (str (fs/which "bb")))
      ;; Import the agent class with the pinned Harbor and write the init it would upload.
      ;; Load it as the workflow does: PYTHONPATH, `--agent bbh_agent:BbhAgent`, and an --ae template.
      (let [out (run! {:dir tmp :extra-env {"PYTHONPATH" (str root "/harbor") "OPENROUTER_API_KEY" "sk-check"}}
                      "uvx" "-q" "--python" "3.12" "--from" harbor "python" "-c"
                      (str "import bbh_agent, pathlib, shutil, importlib.util\n"
                           "from harbor.agents.factory import AgentFactory\n"
                           "from harbor.models.trial.config import AgentConfig\n"
                           "c = AgentConfig(name='bbh_agent:BbhAgent', model_name='openrouter/x/y', env={'OPENROUTER_API_KEY': '${OPENROUTER_API_KEY}'})\n"
                           "a = AgentFactory.create_agent_from_config(c, logs_dir=pathlib.Path('" logs "'))\n"
                           "assert type(a) is bbh_agent.BbhAgent and a._extra_env['OPENROUTER_API_KEY'] == 'sk-check', a._extra_env\n"
                           "pathlib.Path('" home "/init.clj').write_text(bbh_agent.eval_init())\n"
                           "shutil.copy(importlib.util.find_spec('harbor.agents.installed.acp_runner').origin, '" runner "')\n"
                           "print(a.name())"))]
        (check! (= "bbh" (str/trim out)) "Harbor's factory loads bbh_agent:BbhAgent from PYTHONPATH and expands the key template"))
      (spit script (pr-str [{:code "(spit \"proof.txt\" (pr-str {:effort (:effort (bbh.env/manifest)) :skills (bbh.env/skills)}))"
                             ;; ACP's UsageUpdate requires used and size; OpenRouter models supply the size.
                             :usage {:input 100 :cached 0 :written 0 :output 10 :cost 0.0123 :size 200000}}
                            "done"]))
      ;; Harbor uploads acp_runner.py alone, so run a copy in isolation, as in the container.
      (run! {:dir ws :extra-env {"BBH_HOME" home "XDG_DATA_HOME" (str tmp "/data") "BBH_EFFORT" "high"
                                 "HARBOR_ACP_REQUESTED_MODEL" (str "script/" script)}}
            "timeout" "120" "uv" "run" "-q" "--no-project" "--python" "3.12" "--with" "agent-client-protocol"
            "python" "-I" runner "--instruction" "Write proof.txt" "--logs-dir" logs
            "--launcher" (str root "/harbor/bbh-acp"))
      (let [s (json/parse-string (slurp (str logs "/acp-summary.json")) true)
            proof (some-> (fs/path ws "proof.txt") str (#(when (fs/exists? %) (read-string (slurp %)))))]
        (check! (nil? (:error s)) (str "the ACP runner finished without error" (some->> (:error s) pr-str (str " "))))
        (check! (= (str "script/" script) (:resolved_session_model_id s)) "session/set_model selected the requested model")
        (check! (= "end_turn" (get-in s [:prompt_response :stopReason])) "the prompt ended its turn")
        (check! (= {:effort "high" :skills []} proof) "the tool call ran in the task workspace with BBH_EFFORT and no skills")
        (check! (= 0.0123 (get-in s [:latest_usage_update :cost :amount])) "bbh reported its cost to Harbor")
        (check! (seq (fs/glob (str tmp "/data") "bbh/conversations/*/messages.jsonl")) "the transcript landed under XDG_DATA_HOME"))
      (finally (fs/delete-tree tmp)))))
