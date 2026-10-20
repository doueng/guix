(ns bbh.session
  "A conversation: its transcript, REPL and model engine, reported as events.

  Events are maps with a :type of :text, :tool-call, :tool-result or :usage."
  (:require [babashka.fs :as fs]
            [bbh.context :as context]
            [bbh.delimiters :as delimiters]
            [bbh.repl :as repl]
            [bbh.transcript :as transcript]
            [bbh.usage :as usage]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private max-rounds 100)

(defn render [{:keys [out err value]} max-chars]
  (context/cap (->> [(when (seq out) (str "out:\n" (str/trimr out)))
             (when (seq err) (str "err:\n" (str/trimr err)))
             (when value (str "=> " value))]
            (remove nil?)
            (str/join "\n"))
       max-chars))

(defn new-session
  "DIR holds the transcript and logs, INIT is loaded into every new REPL, CWD is
  where the REPL runs, MODEL names the engine MAKE-ENGINE builds, and EMIT
  receives every event. The REPL keeps its port file in DIR even after /resume
  moves the transcript, so a front end finds it under the id it started with."
  [{:keys [dir init cwd model make-engine emit notes]}]
  (atom {:dir dir :repl-dir dir :init init :cwd (or cwd (str (fs/cwd)))
         :model model :make-engine make-engine :engine (make-engine model)
         :emit (or emit (fn [_])) :notes (vec notes)
         :messages (transcript/load-messages dir)
         :compaction (transcript/load-compaction dir)
         :interrupted false :evaluating false :cancel nil
         :manifest repl/defaults :repl nil :spent 0.0 :limits nil
         :code-lock (Object.)}))

(defn emit! [s event]
  ((:emit @s) event))

(defn record! [s msg]
  (transcript/append! (:dir @s) msg)
  (swap! s update :messages conj msg))

(defn start-repl!
  "Start the REPL and load init. Returns a problem description or nil."
  [s]
  (let [{:keys [repl-dir init cwd]} @s
        conn (repl/start! {:dir repl-dir :cwd cwd})
        r (when (and init (fs/exists? init))
            (repl/eval! conn (str "(load-file " (pr-str (str init)) ")") 30000))]
    (swap! s assoc :repl conn)
    (when (or (:timeout r) (:died r) (:error? r))
      (str "Loading " init " failed:\n" (render r 4000)))))

(defn restart-repl! [s]
  (some-> (:repl @s) repl/kill!)
  (start-repl! s))

(defmulti turn!
  "Run the model on the transcript, whose last message is the user's."
  (fn [s] (get-in @s [:engine :kind])))

(defmulti stop-engine!
  "Release whatever the engine keeps running between turns."
  (fn [s] (get-in @s [:engine :kind])))

(defmethod stop-engine! :default [_] nil)

(defn close! [s]
  (stop-engine! s)
  (some-> (:repl @s) repl/kill!))

(defn interrupt!
  "Abort the model's turn, killing the REPL if it is evaluating."
  [s]
  (let [{:keys [cancel evaluating repl]} @s]
    (swap! s assoc :interrupted true)
    (some-> cancel (apply []))
    (when evaluating (some-> repl repl/kill!))))

(defn add-usage!
  "Report a sample's tokens in context and its cost."
  [s {:keys [used size cost]}]
  (let [spent (:spent (swap! s update :spent + (or cost 0)))]
    (emit! s {:type :usage :used used :size size :spent spent})))

(defn system-prompt!
  "Refresh the manifest and return the system prompt, noting invalid entries."
  [s]
  (let [{:keys [manifest problems]} (repl/manifest (:repl @s))]
    (swap! s assoc :manifest manifest)
    (cond-> (:system manifest)
      (seq problems) (str "\n\n[bbh] bbh.env/manifest is invalid, so these defaults apply:\n"
                          (str/join "\n" problems)))))

(defn run-code!
  "Evaluate CODE for tool call ID, healing a truncated form or extra trailing
  closers first. Emits the call and its result and returns the text the model
  sees."
  [s id code]
  (locking (:code-lock @s)
    (emit! s {:type :tool-call :id id :code code})
    (let [{:keys [repl manifest interrupted]} @s
          {:keys [content note]} (delimiters/repair code)
          r (when-not interrupted
              (swap! s assoc :evaluating true)
              (try (repl/eval! repl (or content code) (:deadline-ms manifest))
                   (finally (swap! s assoc :evaluating false))))
          [text status]
          (cond interrupted
                ["Skipped because the user interrupted." :failed]
                (or (:timeout r) (:died r))
                (let [why (cond (:interrupted @s) "The user interrupted the evaluation."
                                (:timeout r) (str "Timed out after " (:deadline-ms manifest) " ms.")
                                :else "The REPL process exited.")
                      init-problem (restart-repl! s)
                      partial (render r (:max-output-chars manifest))]
                  [(str why " The REPL process was killed and restarted, so live definitions are gone and init.clj was reloaded."
                        (when init-problem (str "\n" init-problem))
                        (when (seq partial) (str "\nOutput before that:\n" partial)))
                   :failed])
                :else
                [(render r (:max-output-chars manifest)) (if (:error? r) :failed :completed)])
          text (cond->> text note (str note "\n"))]
      (emit! s {:type :tool-result :id id :code code :text text :status status})
      text)))

(def tool-input-schema
  {:type "object"
   :properties {:code {:type "string" :description "Clojure source to evaluate."}}
   :required ["code"]})

(defn- call-tool! [s {:keys [id function]}]
  (let [code (try (:code (json/parse-string (:arguments function) true)) (catch Exception _ nil))]
    (cond (not= "clojure" (:name function)) (str "Unknown tool " (:name function) ". The only tool is clojure.")
          (not (string? code)) "Arguments must be JSON of the form {\"code\": \"...\"}."
          :else (run-code! s id code))))

(defn context
  "The messages the model sees after the latest compaction."
  [s]
  (let [{:keys [messages compaction manifest]} @s]
    (context/view messages compaction manifest)))

(defn compact!
  "Fold the transcript before its recent messages into a summary, which can
  FOCUS on what the user names. Returns a description for the user."
  [s focus]
  (system-prompt! s)
  (let [{:keys [dir messages compaction manifest engine]} @s
        from (or (:first-kept compaction) 0)
        cut (context/cut-point messages from (:keep-recent-tokens manifest))]
    (cond (not (:sample engine)) "Claude Code compacts its own context, so compaction only applies to OpenRouter models."
          (nil? cut) "Nothing to compact yet."
          :else
          (let [{:keys [message usage]}
                (try ((:sample engine)
                      {:messages (context/summary-request (:summary compaction) (subvec messages from cut) focus)}
                      {:on-text (fn [_]) :on-open #(swap! s assoc :cancel %)})
                     (finally (swap! s assoc :cancel nil)))
                summary (str/trim (str (:content message)))]
            (swap! s update :spent + (or (:cost usage) 0))
            (cond (:interrupted @s) "Compaction was interrupted."
                  (str/blank? summary) "Compaction failed because the model returned an empty summary."
                  :else (let [c (transcript/append-compaction! dir {:first-kept cut :summary summary})]
                          (swap! s assoc :compaction c)
                          (str "Compacted " (- cut from) " messages into a summary of about "
                               (context/tokens summary) " tokens.")))))))

(defn- note! [s text]
  (emit! s {:type :text :text (str "[bbh] " text "\n\n")}))

(defn- sample! [s system]
  (let [{:keys [engine manifest]} @s]
    (try
      ((:sample engine)
       {:messages (into [{:role "system" :content system}] (context s))
        :tools [{:type "function"
                 :function {:name "clojure" :description (:tool-description manifest)
                            :parameters tool-input-schema}}]}
       {:on-text #(when (seq %) (emit! s {:type :text :text %}))
        :on-open #(swap! s assoc :cancel %)
        :on-retry #(note! s %)})
      (finally (swap! s assoc :cancel nil)))))

(defmethod turn! :loop [s]
  (loop [round 1 overflowed false]
    (let [system (system-prompt! s)
          _ (when (> (context/tokens (context s)) (:compact-at-tokens (:manifest @s)))
              (note! s (compact! s nil)))
          result (when-not (:interrupted @s)
                   (try (sample! s system)
                        (catch clojure.lang.ExceptionInfo e
                          (if (and (:overflow (ex-data e)) (not overflowed)) ::overflow (throw e)))))]
      (if (= ::overflow result)
        (do (note! s (str "The request exceeded the model's context. " (compact! s nil)))
            (when-not (:interrupted @s) (recur round true)))
        (let [{:keys [message usage]} result
              message (cond-> message (:interrupted @s) (dissoc :tool_calls))]
          (when (or (seq (:content message)) (seq (:tool_calls message)))
            (record! s message))
          (when usage
            (add-usage! s {:used (:prompt_tokens usage) :cost (:cost usage)}))
          (when (seq (:tool_calls message))
            (doseq [call (:tool_calls message)]
              (record! s {:role "tool" :tool_call_id (:id call) :content (call-tool! s call)}))
            (cond (:interrupted @s) nil
                  (< round max-rounds) (recur (inc round) false)
                  :else (swap! s update :notes conj (str "The previous turn stopped after " max-rounds " tool rounds.")))))))))

(defn set-model! [s model]
  (let [engine ((:make-engine @s) model)]
    (stop-engine! s)
    (swap! s assoc :model model :engine engine)
    model))

(defn- conversation-ids
  "The other saved conversations beside this one, newest first."
  [s]
  (let [dir (fs/path (:dir @s))]
    (->> (fs/list-dir (fs/parent dir))
         (filter #(fs/exists? (fs/path % "messages.jsonl")))
         (map #(str (fs/file-name %)))
         (remove #{(str (fs/file-name dir))})
         sort
         reverse)))

(defn- one-line [text n]
  (let [line (str/trim (str/replace (str text) #"\s+" " "))]
    (if (> (count line) n) (str (subs line 0 (dec n)) "…") line)))

(defn- list-conversations [s]
  (let [root (fs/parent (fs/path (:dir @s)))
        ids (take 10 (conversation-ids s))]
    (if (empty? ids)
      "No other conversations."
      (str/join "\n"
                (concat
                 (for [id ids
                       :let [messages (transcript/load-messages (fs/path root id))
                             prompt (some #(when (= "user" (:role %)) (:content %)) messages)]]
                   (str id "  " (count messages) " messages  "
                        (one-line (str/replace (str prompt) #"(?s).*</skill>" "") 60)))
                 ["/resume ID or /resume last continues one."])))))

(defn resume!
  "Move S to the saved conversation ID, or to the newest other one for last,
  in a fresh REPL. Returns a description for the user."
  [s id]
  (let [ids (conversation-ids s)
        id (if (= "last" id) (first ids) id)]
    (if-not (some #{id} ids)
      "No such conversation. /resume lists them."
      (let [dir (str (fs/path (fs/parent (fs/path (:dir @s))) id))
            messages (transcript/load-messages dir)
            _ (stop-engine! s)
            _ (swap! s assoc :dir dir :messages messages :compaction (transcript/load-compaction dir)
                     :notes ["This conversation resumed in a new REPL, so earlier live definitions are gone."])
            problem (restart-repl! s)
            reply (some #(when (and (= "assistant" (:role %)) (not (str/blank? (:content %)))) (:content %))
                        (rseq messages))]
        (str "Resumed " id " with " (count messages) " messages."
             (when reply (str "\nLast reply: " (one-line reply 300)))
             (when problem (str "\n" problem)))))))

(declare commands)

(defn all-commands
  "bbh's commands, then the bbh.env/commands its expand-prompt handles, as
  {:name :description} maps."
  [s]
  (let [{:keys [value]} (repl/call (:repl @s) "(when-let [f (resolve 'bbh.env/commands)] (f))" 10000)]
    (concat (for [[k {:keys [doc]}] (sort commands)] {:name k :description doc})
            (when (sequential? value) value))))

(def commands
  {"help" {:doc "List commands."
           :run (fn [s _] (str/join "\n" (for [{:keys [name description]} (all-commands s)]
                                              (str "/" name "  " description))))}
   "port" {:doc "Show the nREPL port for cider-connect."
           :run (fn [s _] (str "nREPL 127.0.0.1:" (:port (:repl @s))))}
   "restart" {:doc "Kill and restart the REPL. The transcript stays."
              :run (fn [s _]
                     (let [problem (restart-repl! s)]
                       (swap! s update :notes conj "The user restarted the REPL, so live definitions are gone.")
                       (str "REPL restarted on port " (:port (:repl @s)) (when problem (str "\n" problem)))))}
   "model" {:doc "Show the model, or switch with /model ID."
            :run (fn [s args]
                   (if (str/blank? args)
                     (str "Model: " (:model @s))
                     (str "Model: " (set-model! s (str/trim args)))))}
   "compact" {:doc "Summarize older messages now. /compact TEXT says what the summary should keep."
              :run (fn [s args] (compact! s args))}
   "resume" {:doc "List other conversations, or continue one with /resume ID or /resume last."
             :run (fn [s args] (if (str/blank? args) (list-conversations s) (resume! s (str/trim args))))}
   "usage" {:doc "Show subscription limits and spend."
            :run (fn [s _] (usage/report {:limits (:limits @s) :spent (:spent @s)}))}})

(defn- take-notes! [s]
  (let [notes (:notes @s)]
    (swap! s assoc :notes [])
    notes))

(defn- expand
  "Let the env rewrite user input through bbh.env/expand-prompt, which returns
  a prompt string, {:reply text} to answer without the model, or nil."
  [s text]
  (let [first? (not-any? #(= "user" (:role %)) (:messages @s))
        {:keys [value error]} (repl/call (:repl @s)
                                         (str "(when-let [f (resolve 'bbh.env/expand-prompt)] (f "
                                              (pr-str {:text text :first? first?}) "))")
                                         10000)]
    (cond error (do (emit! s {:type :text :text (str "[bbh] bbh.env/expand-prompt " error "\n\n")})
                    {:prompt text})
          (string? value) {:prompt value}
          (:reply value) {:reply (str (:reply value))}
          :else {:prompt text})))

(defn prompt!
  "Handle one user input: a bbh command, an env reply, or a model turn.
  Returns :end-turn or :cancelled, and throws for a /command nothing handles."
  [s text]
  (swap! s assoc :interrupted false)
  (let [[_ cmd args] (re-matches #"(?s)/(\S+)\s*(.*)" text)]
    (if-let [{:keys [run]} (get commands cmd)]
      (do (emit! s {:type :text :text (run s args)}) :end-turn)
      (let [{:keys [prompt reply]} (expand s text)]
        (cond
          reply
          (do (emit! s {:type :text :text reply}) :end-turn)

          (and (= prompt text) (re-matches #"(?s)/[^\s/]+(\s.*)?" text))
          (throw (ex-info (str "Unknown command " (first (str/split text #"\s")) ". /help lists commands.") {}))

          :else
          (let [notes (take-notes! s)]
            (record! s {:role "user"
                        :content (if (seq notes) (str "[bbh] " (str/join " " notes) "\n\n" prompt) prompt)})
            (turn! s)
            (if (:interrupted @s) :cancelled :end-turn)))))))
