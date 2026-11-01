(ns bbh.compare
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.tasks :as tasks]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def root (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))
(def desktop (str (fs/parent (fs/parent root))))
(def default-model "deepseek/deepseek-v4.1-flash")
(def model (or (System/getenv "BBH_EVAL_MODEL") default-model))

(def codemode-settings {:defaultTools ["+codemode"] :codemode {:mode "only"}})

(def profiles
  "Each profile speaks one protocol, :pi (RPC) or :bbh (ACP). Pi profiles list their tools,
  extensions, appended system prompts and settings. bbh profiles name their init file or the
  environment variable that points at one."
  {:pi {:engine :pi :tools "read,bash,edit,write"}
   :pi-codemode {:engine :pi :tools "read,bash,edit,write,codemode"
                 :extensions ["builtin:codemode"] :settings codemode-settings}
   :pi-sci {:engine :pi
            :extensions [(str (fs/path desktop "pi/agent/extensions/sci-codemode/index.ts"))]
            :append-system-prompt [(str (fs/path desktop "pi/agent/AGENTS.md"))]
            :settings (assoc codemode-settings :extensions ["-builtin:codemode"])}
   :bbh {:engine :bbh :init (str (fs/path desktop "configs/bbh/init.clj"))}
   :bbh-baseline {:engine :bbh :init-env "BBH_EVAL_BASELINE_INIT"}})

(def default-profiles [:pi :pi-sci :bbh])

(defn bbh? [profile] (= :bbh (get-in profiles [profile :engine])))

(defn pi-args
  "Pi RPC command line for a profile. A script selects the fixture provider."
  [{:keys [tools extensions append-system-prompt]} model-id script]
  (-> ["pi" "--mode" "rpc" "--offline" "--no-session"
       "--no-extensions" "--no-mcp" "--no-skills" "--no-context-files"
       "--no-prompt-templates" "--no-themes" "--no-approve"
       "--provider" (if script "bbh-eval" "openrouter")
       "--model" (if script "script" model-id) "--thinking" "off"]
      (into (when script ["--extension" (str (fs/path root "eval/fixture-provider.ts"))]))
      (into (mapcat #(vector "--extension" %) extensions))
      (into (mapcat #(vector "--append-system-prompt" %) append-system-prompt))
      (into (when tools ["--tools" tools]))))

(defn fingerprint []
  (into (sorted-map)
        (for [path (concat (fs/glob (fs/path root "src") "**.clj")
                           (fs/glob (fs/path root "eval") "**.clj")
                           (fs/glob (fs/path desktop "build/pi-sci/src") "**.ts")
                           [(fs/path root "bb.edn")
                            (fs/path desktop "configs/bbh/init.clj")
                            (fs/path desktop "pi/agent/AGENTS.md")
                            (fs/path desktop "pi/agent/extensions/sci-codemode/index.ts")
                            (fs/path desktop "build/pi-sci/dist/sci.js")]
                           (when-let [baseline (System/getenv "BBH_EVAL_BASELINE_INIT")] [baseline]))
              :when (fs/regular-file? path)]
          [(str path)
           (format "%064x" (java.math.BigInteger.
                            1 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                       (.getBytes (slurp (str path)) "UTF-8"))))])))

(defn json! [path value]
  (spit (str path) (str (json/generate-string value {:pretty true}) "\n")))

(defn start! [profile dir ws model-id & [script]]
  (let [agent (str (fs/path dir "agent"))
        init (str (fs/path dir "init.clj"))
        _ (fs/create-dirs agent)
        _ (doseq [file ["auth.json" "models.json" "models-store.json"]
                  :let [source (fs/expand-home (str "~/.pi/agent/" file))]
                  :when (fs/exists? source)]
            (fs/create-sym-link (fs/path agent file) source))
        spec (profiles profile)
        _ (json! (fs/path agent "settings.json") (merge {:quietStartup true} (:settings spec)))
        init-source (or (:init spec)
                        (some-> (:init-env spec) System/getenv)
                        (when (bbh? profile)
                          (throw (ex-info (str profile " requires " (:init-env spec)) {}))))
        _ (when init-source
            (spit init (str (slurp init-source)
                            "\n(ns bbh.env) (defn skills [] []) (reset! poteto? false) (ns user)\n")))
        _ (when script
            (spit (str (fs/path dir "script.edn")) (pr-str script))
            (json! (fs/path dir "script.json") script))
        args (if (bbh? profile)
               ["bb" "--config" (str (fs/path root "bb.edn")) "-m" "bbh.main" "acp"]
               (pi-args spec model-id script))
        proc (apply p/process {:dir ws :err :append :err-file (fs/file dir "stderr.log")
                               :extra-env {"PI_CODING_AGENT_DIR" agent "PI_TELEMETRY" "0"
                                           "XDG_DATA_HOME" (str (fs/path dir "data"))
                                           "BBH_EVAL_SCRIPT" (str (fs/path dir "script.json"))
                                           "BBH_INIT" init "BBH_MODEL" (if script
                                                                       (str "script/" (fs/path dir "script.edn"))
                                                                       (str "openrouter/" model-id))}} args)
        q (LinkedBlockingQueue.)
        events (atom [])
        writer (io/writer (:in proc))
        c {:profile profile :dir dir :ws ws :proc proc :queue q :events events :writer writer :ids (atom 0)}]
    (future
      (try
        (doseq [line (line-seq (io/reader (:out proc)))]
          (spit (str (fs/path dir "events.jsonl")) (str line "\n") :append true)
          (let [event (try (json/parse-string line true)
                           (catch Exception _ {:invalid-json line}))]
            (swap! events conj event)
            (.put q event)))
        (catch Exception e (.put q {:reader-error (ex-message e)}))
        (finally (.put q {:eof true}))))
    c))

(defn send! [c message]
  (let [id (str (swap! (:ids c) inc))]
    (locking (:writer c)
      (.write (:writer c) (str (json/generate-string (assoc message :id id)) "\n"))
      (.flush (:writer c)))
    id))

(defn wait! [c pred seconds]
  (let [deadline (+ (System/nanoTime) (* seconds 1000000000))]
    (loop []
      (let [remaining (- deadline (System/nanoTime))
            event (when (pos? remaining)
                    (.poll (:queue c) (max 1 (quot remaining 1000000)) TimeUnit/MILLISECONDS))]
        (when (or (nil? event) (:eof event) (:reader-error event) (:invalid-json event))
          (throw (ex-info "Protocol ended, timed out or emitted invalid JSON"
                          {:last event :dir (:dir c)})))
        (if (pred event) event (recur))))))

(defn rpc! [c message]
  (let [id (send! c message)
        response (wait! c #(= id (:id %)) 30)]
    (when (or (:error response) (= false (:success response)))
      (throw (ex-info "Protocol command failed" {:response response})))
    response))

(defn initialize! [c]
  (if (bbh? (:profile c))
    (do (rpc! c {:jsonrpc "2.0" :method "initialize" :params {:protocolVersion 1}})
        (get-in (rpc! c {:jsonrpc "2.0" :method "session/new"
                         :params {:cwd (:ws c) :mcpServers []}}) [:result :sessionId]))
    (do (rpc! c {:type "get_state"}) nil)))

(defn prompt! [c sid text seconds]
  (if (bbh? (:profile c))
    (let [id (send! c {:jsonrpc "2.0" :method "session/prompt"
                       :params {:sessionId sid :prompt [{:type "text" :text text}]}})
          response (wait! c #(= id (:id %)) seconds)]
      (when (or (:error response) (not= "end_turn" (get-in response [:result :stopReason])))
        (throw (ex-info "ACP prompt failed or was cancelled" {:response response})))
      response)
    (let [id (send! c {:type "prompt" :message text})
          response (wait! c #(= id (:id %)) seconds)]
      (when-not (:success response) (throw (ex-info "Pi rejected prompt" {:response response})))
      (when-not (= "handled" (get-in response [:data :disposition]))
        (let [settled (wait! c #(= "agent_settled" (:type %)) seconds)]
          (when (:aborted settled) (throw (ex-info "Pi prompt was aborted" {:event settled}))))))))

(defn stop! [c]
  (try (.close (:writer c)) (catch Exception _))
  (when (= ::timeout (deref (:proc c) 5000 ::timeout))
    (p/destroy-tree (:proc c)))
  (deref (:proc c) 5000 nil))

(defn metrics [profile events]
  (if (bbh? profile)
    (let [updates (keep #(get-in % [:params :update]) events)]
      {:calls (count (filter #(= "tool_call" (:sessionUpdate %)) updates))
       :tool-errors (count (filter #(and (= "tool_call_update" (:sessionUpdate %)) (= "failed" (:status %))) updates))
       :reported-cost (last (keep #(get-in % [:cost :amount]) updates))
       :provider-errors (vec (keep :error events))})
    (let [messages (keep #(when (and (= "message_end" (:type %)) (= "assistant" (get-in % [:message :role]))) (:message %)) events)]
      {:calls (count (filter #(and (= "tool_execution_start" (:type %)) (nil? (:parentToolCallId %))) events))
       :tool-errors (count (filter #(and (= "tool_execution_end" (:type %)) (:isError %) (nil? (:parentToolCallId %))) events))
       :reported-cost (reduce + 0 (keep #(get-in % [:usage :cost :total]) messages))
       :provider-errors (mapv :errorMessage (filter #(= "error" (:stopReason %)) messages))})))

(defn trial! [out profile task repetition model-id]
  (let [dir (str (fs/path out (str (:id task) "__" repetition "__" (name profile))))
        ws (str (fs/path dir "ws"))]
    (fs/create-dirs ws)
    (tasks/write-files! ws (:files task))
    (let [c (start! profile dir ws model-id)
          outcome (try
                    (let [sid (initialize! c)]
                      (doseq [text (or (:prompts task) [(:prompt task)])]
                        (prompt! c sid text 120))
                      {:protocol :completed})
                    (catch Exception e {:protocol :error :error (ex-message e) :detail (ex-data e)})
                    (finally (stop! c)))
          grade (try (tasks/grade task ws)
                     (catch Exception e {:passed false :grader-error (ex-message e)}))
          telemetry (metrics profile @(:events c))
          record (merge {:task (:id task) :profile profile :repetition repetition :model model-id :dir dir
                         :grade grade} outcome telemetry
                        {:passed (and (:passed grade) (= :completed (:protocol outcome))
                                      (empty? (:provider-errors telemetry)))})]
      (json! (fs/path dir "result.json") record)
      (spit (str (fs/path out "results.jsonl")) (str (json/generate-string record) "\n") :append true)
      (println (if (:passed record) "PASS" "FAIL") (:id task) (name profile) repetition
               "calls" (:calls record) "errors" (:tool-errors record))
      (flush)
      record)))

(defn -main [& [out attempts selected]]
  (let [out (str (fs/absolutize (or out (str (fs/create-temp-dir {:prefix "bbh-compare"})))))
        attempts (parse-long (or attempts "3"))
        selected (if selected (mapv keyword (str/split selected #",")) default-profiles)
        tasks (tasks/selected-tasks)
        sources (fingerprint)]
    (when-not (pos-int? attempts)
      (throw (ex-info "Repetitions must be a positive integer" {:attempts attempts})))
    (when-not (and (seq selected) (= (count selected) (count (distinct selected)))
                   (every? profiles selected))
      (throw (ex-info "Unknown or duplicated evaluation profiles" {:profiles selected})))
    (doseq [p selected :let [env (get-in profiles [p :init-env])] :when env]
      (when-not (some-> (System/getenv env) fs/regular-file?)
        (throw (ex-info (str p " needs a readable " env " file") {}))))
    (tasks/verify-graders!)
    (fs/create-dirs out)
    (when (fs/exists? (fs/path out "results.jsonl"))
      (throw (ex-info "Refusing to append a new evaluation to existing results" {:out out})))
    (json! (fs/path out "manifest.json")
           {:pi-version (str/trim (:out @(p/process {:out :string} "pi" "--version")))
            :model model :profiles selected :attempts attempts :task-ids (mapv :id tasks)
            :fixture-source "eval/bbh/tasks.clj, four tasks adapted from desktop/build/pi-sci/eval/tasks.mjs"
            :sources sources
            :bbh-init "production init with skills and poteto disabled"
            :baseline-init (System/getenv "BBH_EVAL_BASELINE_INIT")
            :pi-sci "production SCI extension and AGENTS guidance; other extensions disabled"
            :timing "not a performance benchmark"})
    (let [records (doall
                   (for [repetition (range 1 (inc attempts))
                         [i task] (map-indexed vector tasks)
                         :let [shift (mod (+ repetition i) (count selected))]
                         profile (concat (drop shift selected) (take shift selected))]
                     (trial! out profile task repetition model)))]
      (json! (fs/path out "summary.json")
             (into {} (for [[profile trials] (group-by :profile records)]
                        [profile {:passed (count (filter :passed trials)) :total (count trials)
                                  :calls (reduce + (map :calls trials))
                                  :tool-errors (reduce + (map :tool-errors trials))}])))
      (let [after (fingerprint)
            stable (= sources after)]
        (json! (fs/path out "source-stability.json") {:stable stable :before sources :after after})
        (when-not stable (throw (ex-info "Sources changed during the evaluation" {:out out}))))
      (println "Artifacts:" out))
    (shutdown-agents)))
