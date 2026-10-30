(ns bbh.session
  "A conversation: its transcript, REPL and model engine, reported as events.

  Events are maps with a :type of :text, :thought, :tool-call, :tool-result or
  :usage."
  (:require [babashka.fs :as fs]
            [bbh.delimiters :as delimiters]
            [bbh.repl :as repl]
            [bbh.runs :as runs]
            [bbh.transcript :as transcript]
            [bbh.usage :as usage]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private max-rounds 100)

(defn- cap [s n]
  (if (<= (count s) n)
    s
    (let [half (quot n 2)]
      (str (subs s 0 half) "\n[... " (- (count s) n) " chars elided ...]\n" (subs s (- (count s) half))))))

(defn render [{:keys [out err value]} max-chars]
  (cap (->> [(when (seq out) (str "out:\n" (str/trimr out)))
             (when (seq err) (str "err:\n" (str/trimr err)))
             (when value (str "=> " value))]
            (remove nil?)
            (str/join "\n"))
       max-chars))

(defn spill!
  "TEXT for the model. When it is longer than MAX-CHARS, the full text is saved
  under the conversation's outputs/ directory and the capped text starts with
  its path."
  [s id text max-chars]
  (if (<= (count text) max-chars)
    text
    (let [f (fs/path (:dir @s) "outputs" (str (str/replace (str id) #"[^A-Za-z0-9_.-]" "_") ".txt"))]
      (fs/create-dirs (fs/parent f))
      (spit (str f) text)
      (str "[bbh] Full output (" (count text) " chars) saved to " f ". Read parts with read-lines.\n"
           (cap text max-chars)))))

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
         :interrupted false :evaluating false :cancel nil
         :manifest repl/defaults :repl nil :spent 0.0 :limits nil
         :context-size nil :last-input nil :turn-usage nil
         :code-lock (Object.)}))

(defn emit! [s event]
  ((:emit @s) event))

(defn record! [s msg]
  (transcript/append! (:dir @s) msg)
  (swap! s update :messages conj msg))

(defn trace-run!
  "Append a run of KIND that started at START-MS to the current turn's trace.
  FIELDS can override the default \"ok\" :status."
  [s kind start-ms fields]
  (let [{:keys [dir trace model manifest]} @s
        turn? (= "turn" kind)]
    (runs/append! dir start-ms
                  (merge {:id (if turn? trace (str (random-uuid))) :trace trace :parent (when-not turn? trace)
                          :kind kind :model model :effort (some-> (:effort manifest) name) :status "ok"}
                         fields))))

(defn- sample-fields
  "The run fields of one model reply."
  [{:keys [message usage truncated interrupted]}]
  (cond-> {:usage (select-keys usage [:input :cached :written :output :thinking :cost])
           :tool-calls (count (:tool_calls message))}
    truncated (assoc :truncated true)
    interrupted (assoc :status "interrupted")))

(defn- traced-sample!
  "Call the engine's sample function and record the request as an llm run."
  [s req opts]
  (let [start (System/currentTimeMillis)]
    (try (let [r ((:sample (:engine @s)) req opts)]
           (trace-run! s "llm" start (sample-fields r))
           r)
         (catch Exception e
           (trace-run! s "llm" start {:status "error" :error (ex-message e)})
           (throw e)))))

(defn start-repl!
  "Start the REPL and load init. Returns a problem description or nil."
  [s]
  (let [{:keys [dir repl-dir init cwd]} @s
        conn (repl/start! {:dir repl-dir :cwd cwd
                           :env {"BBH_INBOX_SESSION" (transcript/inbox-id dir)}})
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

(def ^:private bookkeeping
  "Files a session writes before anyone talks to it. A folder holding only these is not a conversation."
  #{"repl.log" "repl.port" "workflow-run" "claude.log" "claude-session" "claude-seen" "inbox-session"})

(defn- delete-if-unused! [dir]
  (when (and dir (fs/directory? dir)
             (every? #(bookkeeping (str (fs/file-name %))) (fs/list-dir dir)))
    (fs/delete-tree dir)))

(defn close!
  "Stop the engine and the REPL. A session nobody talked to leaves no folder behind for /resume to skip."
  [s]
  (stop-engine! s)
  (some-> (:repl @s) repl/kill!)
  (delete-if-unused! (:repl-dir @s)))

(defn interrupt!
  "Abort the model's turn, killing the REPL if it is evaluating."
  [s]
  (let [{:keys [cancel evaluating repl]} @s]
    (swap! s assoc :interrupted true)
    (some-> cancel (apply []))
    (when evaluating (some-> repl repl/kill!))))

(defn- add-request [turn {:keys [input cached written output thinking cache] :as request}]
  (-> (or turn {})
      (update :requests (fnil inc 0))
      (update :input (fnil + 0) input)
      (update :cached (fnil + 0) cached)
      (update :written (fnil + 0) (or written 0))
      (update :output (fnil + 0) (or output 0))
      (update :thinking (fnil + 0) (or thinking 0))
      (cond-> (= :miss cache) (update :misses (fnil conj []) (select-keys request [:n :ratio :input])))))

(defn add-usage!
  "Account for one usage report, a map of :input :cached :written :output
  :thinking :cost and :size, any of which may be missing. A report with :input
  is one model request, classified against the request before it: :cold when
  there was none to reuse, :miss when less than half of that request's input
  was read back from the cache, and :hit otherwise."
  [s {:keys [input cost size] :as report}]
  (let [{:keys [last-input turn-usage]} @s
        cached (or (:cached report) 0)
        request (when input
                  (assoc (select-keys report [:input :written :output :thinking])
                         :cached cached
                         :effort (:effort (:manifest @s))
                         :n (inc (:requests turn-usage 0))
                         :cache (cond (nil? last-input) :cold
                                      (< cached (* 0.5 last-input)) :miss
                                      :else :hit)
                         :ratio (if (pos? input) (/ (double cached) input) 0.0)))
        st (swap! s #(cond-> (-> % (update :spent + (or cost 0))
                                 (update-in [:turn-usage :cost] (fnil + 0) (or cost 0)))
                       size (assoc :context-size size)
                       request (-> (assoc :last-input input)
                                   (update :turn-usage add-request request))))]
    (emit! s {:type :usage :used (:last-input st) :size (:context-size st) :spent (:spent st)
              :request request})))

(defn- prompt-key [p]
  ((juxt :model :effort :system :tool-description) p))

(defn- record-prompt!
  "Append the prompt to prompts.jsonl when it differs from the conversation's
  last recorded one, so the transcript says what the model was told."
  [s system]
  (let [{:keys [dir model manifest messages recorded-prompt]} @s
        p {:at (count messages) :time (str (java.time.Instant/now)) :model model
           :effort (some-> (:effort manifest) name) :system system
           :tool-description (:tool-description manifest)}
        last-key (if (= dir (first recorded-prompt))
                   (second recorded-prompt)
                   (some-> (peek (transcript/load-prompts dir)) prompt-key))]
    (when (not= last-key (prompt-key p))
      (transcript/append-prompt! dir p))
    (swap! s assoc :recorded-prompt [dir (prompt-key p)])))

(defn system-prompt!
  "Refresh the manifest and return the system prompt, noting invalid entries."
  [s]
  (let [{:keys [manifest problems]} (repl/manifest (:repl @s))
        _ (swap! s assoc :manifest manifest)
        system (cond-> (:system manifest)
                 (seq problems) (str "\n\n[bbh] bbh.env/manifest is invalid, so these defaults apply:\n"
                                     (str/join "\n" problems)))]
    (record-prompt! s system)
    system))

(defn run-code!
  "Evaluate CODE for tool call ID, healing a truncated form or extra trailing
  closers first. Emits the call and its result and returns the text the model
  sees."
  [s id code]
  (locking (:code-lock @s)
    (emit! s {:type :tool-call :id id :code code})
    (let [start (System/currentTimeMillis)
          {:keys [repl manifest interrupted]} @s
          {:keys [content note]} (delimiters/repair code)
          r (when-not interrupted
              (swap! s assoc :evaluating true)
              (try (repl/eval! repl (or content code) (:deadline-ms manifest))
                   (finally (swap! s assoc :evaluating false))))
          ;; Before the restart below, so the run survives a close and leaves out the reload.
          _ (trace-run! s "tool" start
                        (cond-> {:tool-call-id id :chars (count (render r Long/MAX_VALUE))
                                 :status (cond (:interrupted @s) "interrupted"
                                               (or (:timeout r) (:died r) (:error? r)) "error"
                                               :else "ok")}
                          (or (:timeout r) (:died r)) (assoc :restart true)))
          [text status]
          (cond interrupted
                ["Skipped because the user interrupted." :failed]
                (or (:timeout r) (:died r))
                (let [why (cond (:interrupted @s) "The user interrupted the evaluation."
                                (:timeout r) (str "Timed out after " (:deadline-ms manifest) " ms.")
                                :else "The REPL process exited.")
                      init-problem (restart-repl! s)
                      partial (spill! s id (render r Long/MAX_VALUE) (:max-output-chars manifest))]
                  [(str why " The REPL process was killed and restarted, so live definitions are gone and init.clj was reloaded."
                        (when init-problem (str "\n" init-problem))
                        (when (seq partial) (str "\nOutput before that:\n" partial)))
                   :failed])
                :else
                [(spill! s id (render r Long/MAX_VALUE) (:max-output-chars manifest)) (if (:error? r) :failed :completed)])
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

(defn- note! [s text]
  (emit! s {:type :text :text (str "[bbh] " text "\n\n")}))

(defn rollover-due?
  "Whether the last request's input reached :rollover-at-tokens, or most of the
  model's window when that is smaller."
  [s]
  (let [{:keys [last-input context-size manifest interrupted]} @s]
    (and last-input (not interrupted)
         (>= last-input (cond-> (:rollover-at-tokens manifest)
                          context-size (min (long (* 0.8 context-size))))))))

(defn- roll-over!
  "Move S to a new conversation that links back to this one, keeping the REPL.
  Returns the notice for the model."
  [s]
  (let [{:keys [dir messages last-input]} @s
        id (transcript/new-id)]
    (stop-engine! s)
    (let [new-dir (transcript/continue! dir id)]
      (swap! s assoc :dir new-dir :messages [] :last-input nil :turn-from 0))
    (note! s (str "Context reached " (or last-input "the model's limit of") " tokens, so this continues in conversation " id "."))
    (str "This is a continuation session. The previous session ended because its context filled up. "
         "Its transcript has " (count messages) " messages, one JSON object per line with role, content and tool_calls, in "
         (fs/path dir "messages.jsonl") ". "
         "Get context from it only as you need it, during the session, rather than reading it all up front. "
         "It can start with a link to an earlier session. "
         "The REPL kept running, so live definitions remain.")))

(defn continue-mid-turn!
  "Roll over during a turn, leaving a user message that resumes the work."
  [s]
  (record! s {:role "user"
              :content (str "[bbh] " (roll-over! s)
                            " It ended in the middle of a turn, so read its last user request and its latest messages, then carry on with that work.")}))

(defn- sample! [s system messages]
  (let [{:keys [manifest dir]} @s]
    (try
      (traced-sample!
       s
       {:messages (into [{:role "system" :content system}] messages)
        :tools [{:type "function"
                 :function {:name "clojure" :description (:tool-description manifest)
                            :parameters tool-input-schema}}]
        :effort (:effort manifest)
        :max-tokens (:max-tokens manifest)
        :session-id (str (fs/file-name dir))}
       {:on-text #(when (seq %) (emit! s {:type :text :text %}))
        :on-thought #(when (seq %) (emit! s {:type :thought :text %}))
        :on-open #(swap! s assoc :cancel %)
        :on-retry #(note! s %)})
      (finally (swap! s assoc :cancel nil)))))

(def quiet-rounds
  "Tool rounds in a row without text for the user before bbh asks for an update."
  5)

(def ^:private max-reminders 3)

(def cut-off-call
  "[bbh] Your reply hit the model's output limit, so this call's code is incomplete and did not run. Split the work into smaller evaluations.")

(defn- close-cut-off-call
  "MESSAGE with its last call's arguments replaced by {} when the output limit
  left them as invalid JSON, which providers reject in later requests."
  [message]
  (let [i (dec (count (:tool_calls message)))
        args (get-in message [:tool_calls i :function :arguments])]
    (if (try (json/parse-string args) true (catch Exception _ false))
      message
      (assoc-in message [:tool_calls i :function :arguments] "{}"))))

(def quiet-reminder
  "[bbh] The user hasn't heard from you in a while. Say in a few words what you're doing, then continue.")

(defmethod turn! :loop [s]
  (loop [round 1 overflowed false quiet 0 reminders 0]
    (let [system (system-prompt! s)
          _ (when (rollover-due? s) (continue-mid-turn! s))
          result (when-not (:interrupted @s)
                   (try (sample! s system (:messages @s))
                        (catch clojure.lang.ExceptionInfo e
                          (if (and (:overflow (ex-data e)) (not overflowed)) ::overflow (throw e)))))]
      (if (= ::overflow result)
        (do (continue-mid-turn! s)
            (when-not (:interrupted @s) (recur round true quiet reminders)))
        (let [{:keys [message usage truncated]} result
              message (cond-> message
                        (:interrupted @s) (dissoc :tool_calls)
                        (and truncated (seq (:tool_calls message))) close-cut-off-call)
              calls (:tool_calls message)]
          (when (or (seq (:content message)) (seq calls))
            (record! s message))
          (when usage
            (add-usage! s usage))
          (when (and truncated (empty? calls) (not (:interrupted @s)))
            (note! s "The reply hit the model's output limit and was cut off."))
          (when (seq calls)
            ;; The reminder goes into the tool result as it is recorded, so the
            ;; prefix of every later request stays the same.
            (let [quiet (if (str/blank? (:content message)) (inc quiet) 0)
                  remind? (and (>= quiet quiet-rounds) (< reminders max-reminders))
                  last-id (:id (peek calls))]
              (doseq [call calls]
                (let [text (if (and truncated (= last-id (:id call)))
                             cut-off-call
                             (call-tool! s call))]
                  (record! s {:role "tool" :tool_call_id (:id call)
                              :content (cond-> text (and remind? (= last-id (:id call)))
                                         (str "\n\n" quiet-reminder))})))
              (cond (:interrupted @s) nil
                    (< round max-rounds) (recur (inc round) false (if remind? 0 quiet) (cond-> reminders remind? inc))
                    :else (swap! s update :notes conj (str "The previous turn stopped after " max-rounds " tool rounds."))))))))))

(defn set-model! [s model]
  (let [engine ((:make-engine @s) model)]
    (stop-engine! s)
    (swap! s assoc :model model :engine engine :last-input nil :context-size nil)
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

(defn- latest-ids
  "The conversations beside this one that never continued into another,
  newest first."
  [s]
  (let [root (fs/parent (fs/path (:dir @s)))]
    (remove #(fs/exists? (fs/path root % "continued-in")) (conversation-ids s))))

(defn- list-conversations [s]
  (let [root (fs/parent (fs/path (:dir @s)))
        ids (take 10 (latest-ids s))]
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
  in a fresh REPL. Returns what the user needs to know, blank when it went well."
  [s id]
  (let [ids (conversation-ids s)
        id (if (= "last" id) (first (latest-ids s)) id)]
    (if-not (some #{id} ids)
      "No such conversation. /resume lists them."
      (let [dir (transcript/continuation (str (fs/path (fs/parent (fs/path (:dir @s))) id)))
            messages (transcript/load-messages dir)
            _ (stop-engine! s)
            _ (swap! s assoc :dir dir :messages messages :last-input nil
                     :notes ["This conversation resumed in a new REPL, so earlier live definitions are gone."])
            problem (restart-repl! s)]
        (str problem)))))

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
  (swap! s assoc :interrupted false :turn-usage nil :trace nil)
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
          (let [_ (when (rollover-due? s) (swap! s update :notes conj (roll-over! s)))
                notes (take-notes! s)
                start (System/currentTimeMillis)
                _ (swap! s assoc :trace (str (random-uuid)) :turn-from (count (:messages @s)))
                finish! #(trace-run! s "turn" start
                                     (merge {:messages [(:turn-from @s) (count (:messages @s))] :usage (:turn-usage @s)} %))]
            (record! s {:role "user"
                        :content (if (seq notes) (str "[bbh] " (str/join " " notes) "\n\n" prompt) prompt)})
            (try (turn! s)
                 (catch Exception e
                   (finish! {:status "error" :error (ex-message e)})
                   (throw e)))
            (finish! (when (:interrupted @s) {:status "interrupted"}))
            (if (:interrupted @s) :cancelled :end-turn)))))))
