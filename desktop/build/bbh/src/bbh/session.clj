(ns bbh.session
  "A conversation: its transcript, REPL and model engine, reported as events.

  Events are maps with a :type of :text, :tool-call, :tool-result or :usage."
  (:require [babashka.fs :as fs]
            [bbh.repl :as repl]
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

(defn new-session
  "DIR holds the transcript and logs, INIT is loaded into every new REPL, CWD is
  where the REPL runs, MODEL names the engine MAKE-ENGINE builds, and EMIT
  receives every event."
  [{:keys [dir init cwd model make-engine emit notes]}]
  (atom {:dir dir :init init :cwd (or cwd (str (fs/cwd)))
         :model model :make-engine make-engine :engine (make-engine model)
         :emit (or emit (fn [_])) :notes (vec notes)
         :messages (transcript/load-messages dir)
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
  (let [{:keys [dir init cwd]} @s
        conn (repl/start! {:dir dir :cwd cwd})
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
  "Evaluate CODE for tool call ID. Emits the call and its result and returns the
  text the model sees."
  [s id code]
  (locking (:code-lock @s)
    (emit! s {:type :tool-call :id id :code code})
    (let [{:keys [repl manifest interrupted]} @s
          r (when-not interrupted
              (swap! s assoc :evaluating true)
              (try (repl/eval! repl code (:deadline-ms manifest))
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
                [(render r (:max-output-chars manifest)) (if (:error? r) :failed :completed)])]
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

(defmethod turn! :loop [s]
  (loop [round 1]
    (let [system (system-prompt! s)
          {:keys [engine manifest messages]} @s
          {:keys [message usage]}
          ((:sample engine)
           {:messages (into [{:role "system" :content system}] messages)
            :tools [{:type "function"
                     :function {:name "clojure" :description (:tool-description manifest)
                                :parameters tool-input-schema}}]}
           {:on-text #(when (seq %) (emit! s {:type :text :text %}))
            :on-open #(swap! s assoc :cancel %)})
          _ (swap! s assoc :cancel nil)
          message (cond-> message (:interrupted @s) (dissoc :tool_calls))]
      (when (or (seq (:content message)) (seq (:tool_calls message)))
        (record! s message))
      (when usage
        (add-usage! s {:used (:prompt_tokens usage) :cost (:cost usage)}))
      (when (seq (:tool_calls message))
        (doseq [call (:tool_calls message)]
          (record! s {:role "tool" :tool_call_id (:id call) :content (call-tool! s call)}))
        (cond (:interrupted @s) nil
              (< round max-rounds) (recur (inc round))
              :else (swap! s update :notes conj (str "The previous turn stopped after " max-rounds " tool rounds.")))))))

(defn set-model! [s model]
  (let [engine ((:make-engine @s) model)]
    (stop-engine! s)
    (swap! s assoc :model model :engine engine)
    model))

(def commands
  {"help" {:doc "List commands."
           :run (fn [_ _] (str/join "\n" (for [[k {:keys [doc]}] (sort commands)] (str "/" k "  " doc))))}
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
  Returns :end-turn or :cancelled."
  [s text]
  (swap! s assoc :interrupted false)
  (let [[_ cmd args] (re-matches #"(?s)/(\S+)\s*(.*)" text)]
    (if-let [{:keys [run]} (get commands cmd)]
      (do (emit! s {:type :text :text (run s args)}) :end-turn)
      (let [{:keys [prompt reply]} (expand s text)]
        (if reply
          (do (emit! s {:type :text :text reply}) :end-turn)
          (let [notes (take-notes! s)]
            (record! s {:role "user"
                        :content (if (seq notes) (str "[bbh] " (str/join " " notes) "\n\n" prompt) prompt)})
            (turn! s)
            (if (:interrupted @s) :cancelled :end-turn)))))))
