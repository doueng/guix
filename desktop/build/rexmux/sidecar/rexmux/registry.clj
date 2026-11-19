(ns rexmux.registry
  "The command registry behind M-x and the keymap. A command is data:

    {:id :agent/next-blocked :title .. :category .. :doc ..
     :keys [[\"ctrl+b\" \"n\"]]      ; key sequences, Rex key names
     :when (fn [ctx] ..)            ; nil means always
     :args [{:name :agent :type .. :enum .. :required .. :complete :block}]
     :needs #{:session-id}          ; context the command cannot run without
     :source :rex-api|:leader|:local|:ext
     :run (fn [ctx args] effects)}

  Commands come from Rex's help document (one per method and block method),
  from leader.edn entries with a :rex implementation, and from local
  namespaces. Invoking fills context arguments, checks the rest against the
  schema, and answers with effects, a prompt for what is missing, or errors."
  (:require [clojure.string :as str]
            [rexmux.schema :as schema]))

;; ---- context ----

(def ctx-args
  "Rex argument names the client fills from its context."
  {:session_id :session-id :block_id :block-id :window_id :window-id :client_id :client-id})

(def arg-defaults
  "Values for arguments M-x would otherwise ask for or leave out: a new
  block is a shell, and a block the user makes gets focus."
  {:layout {:block {:flavor "com.superlogical.terminal.shell"}}
   :focus true})

(defn has [& ks] (fn [ctx] (every? #(some? (get ctx %)) ks)))

(defn enabled?
  "True when CMD can run in CTX: its :needs are present and :when allows it."
  [cmd ctx]
  (and (every? #(some? (get ctx %)) (:needs cmd))
       (if-let [w (:when cmd)] (boolean (w ctx)) true)))

;; ---- ids, titles and action names ----

(defn id-str [id] (if (keyword? id) (subs (str id) 1) (str id)))

(defn action-name
  "The Rex action name this client declares for command ID, such as
  mx.agent.next_blocked for :agent/next-blocked. Rex names are lower case with
  dots, so / and - become . and _."
  [id]
  (str "mx." (-> (id-str id) (str/replace "/" ".") (str/replace "-" "_") str/lower-case)))

(defn humanize [method]
  (let [[group & more] (str/split (str/replace method #"^com\.superlogical\." "") #"\.")
        words (str/replace (str/join " " (or (seq more) [group])) "_" " ")]
    (str (str/capitalize group) ": " words)))

;; ---- commands from Rex's help document ----

(defn- needs-of [schema]
  (let [req (set (map keyword (:required schema)))]
    (set (keep (fn [[k c]] (when (req k) c)) ctx-args))))

(defn api-command
  "The command for one server method of the help document."
  [{:keys [name description input_schema request_example validation_notes]}]
  (let [schema (or input_schema {:type "object" :properties {}})]
    {:id (keyword "rex" name)
     :title (humanize name)
     :category (first (str/split name #"\."))
     :doc description
     :notes validation_notes
     :example request_example
     :schema schema
     :args (schema/arg-specs schema)
     :needs (needs-of schema)
     :source :rex-api
     :run (fn [_ctx args] [[:rex/call name args]])}))

(defn block-method-command
  "The command for a block type's method: a block.method call on the focused
  block, whose own arguments are checked against the method's schema."
  [creator {:keys [name description args_schema request_example] :as m}]
  (let [full (or (get-in m [:invoke :method]) (str creator "." name))
        schema (or args_schema {:type "object" :properties {}})]
    {:id (keyword "rex" full)
     :title (humanize full)
     :category (last (str/split creator #"\."))
     :doc description
     :example request_example
     :schema schema
     :args (schema/arg-specs schema)
     :needs #{:session-id :block-id}
     :source :rex-api
     :block-method {:creator creator :method name}
     :run (fn [ctx args]
            [[:rex/call "block.method" {:session_id (:session-id ctx) :block_id (:block-id ctx)
                                         :method name :args args}]])}))

(defn api-commands
  "Every command of HELP, the parsed output of rex api list --json."
  [help]
  (vec (concat (map api-command (:methods help))
               (for [{:keys [creator methods]} (:block_types help) m methods]
                 (block-method-command creator m)))))

;; ---- commands from leader.edn ----

(defn leader-commands
  "Commands for leader.edn bindings that name a :rex implementation:
  a command id keyword (an alias of another command, run with no arguments),
  or [method args] for a direct Rex call. With a PREFIX, such as
  [\"ctrl+b\" \"space\"], the leader's keys are bound under it in Rex too;
  without one, keyleader reaches them through rex do."
  [leader prefix]
  (vec (for [{:keys [keys label rex]} (:bindings leader) :when rex]
         (let [id (keyword "leader" (str/join "-" (map #(str/replace % #"[^A-Za-z0-9]" "_") keys)))]
           {:id id :title (str "Leader: " label) :category "leader" :doc label
            :keys (if prefix [(into (vec prefix) (map #(if (= % "TAB") "tab" %) keys))] [])
            :source :leader
            :alias (when (keyword? rex) rex)
            :run (if (keyword? rex)
                   (fn [_ _] [[:invoke rex {}]])
                   ;; Through the API command, so context, defaults and the
                   ;; schema check apply as they do in M-x.
                   (let [[m a] rex] (fn [_ _] [[:invoke (keyword "rex" m) (or a {})]])))}))))

;; ---- the registry ----

(defn index [cmds] (into {} (map (juxt :id identity)) cmds))

(defn merge-sources
  "One registry map from command lists; later sources win on the same id."
  [& sources]
  (reduce merge {} (map index sources)))

(defn catalogue
  "What the window lists in M-x: plain data, no functions, with :enabled
  computed for CTX. Sorted by category then title."
  [registry ctx]
  (->> (vals registry)
       (map (fn [c] (-> (select-keys c [:id :title :category :doc :keys :args :source])
                        (assoc :action (action-name (:id c)) :enabled (enabled? c ctx)))))
       (sort-by (juxt :category :title))
       vec))

(defn keymap-layer
  "The client.keymap defaults layer named mx: one binding per key sequence of
  every command, and the actions list declaring each command."
  [registry]
  (let [cmds (sort-by (comp id-str :id) (vals registry))]
    {:layer {:name "mx"
             :bindings (vec (for [c cmds ks (:keys c)] {:keys (vec ks) :action (action-name (:id c))}))}
     :actions (vec (for [c cmds]
                     (cond-> {:name (action-name (:id c)) :title (:title c) :kind "client"}
                       (:category c) (assoc :category (str (:category c)))
                       (:doc c) (assoc :description (first (str/split-lines (str (:doc c))))))))}))

(defn key-conflicts
  "Key sequences bound to more than one command, and sequences that are a
  strict prefix of another (a key cannot both run and start a sequence)."
  [registry]
  (let [pairs (for [c (vals registry) ks (:keys c)] [(vec ks) (:id c)])
        by-keys (group-by first pairs)
        seqs (set (keys by-keys))]
    (vec (concat
          (for [[ks ps] by-keys :when (> (count (distinct (map second ps))) 1)]
            {:keys ks :ids (sort (map (comp id-str second) ps)) :problem :duplicate})
          (for [a seqs b seqs :when (and (< (count a) (count b)) (= a (subvec b 0 (count a))))]
            {:keys a :longer b :problem :prefix})))))

(defn by-action [registry action] (some #(when (= action (action-name (:id %))) %) (vals registry)))

;; ---- invoking ----

(defn fill
  "ARGS with context arguments filled in from CTX where the command takes
  them, and absent arguments from arg-defaults."
  [cmd ctx args]
  (reduce (fn [a {:keys [name]}]
            (let [ck (ctx-args name)]
              (cond (contains? a name) a
                    (and ck (some? (get ctx ck))) (assoc a name (get ctx ck))
                    (and (= :rex-api (:source cmd)) (contains? arg-defaults name)) (assoc a name (arg-defaults name))
                    :else a)))
          (or args {}) (:args cmd)))

(defn invoke
  "Run command ID of REGISTRY in CTX with ARGS. One of:
    {:effects [..]}                       done
    {:prompt [specs] :partial args}       required arguments are missing
    {:error text :invalid [..]}           unknown id, missing context, or bad args"
  [registry ctx id args]
  (if-let [cmd (get registry id)]
    (let [missing-ctx (remove #(some? (get ctx %)) (:needs cmd))
          args (fill cmd ctx args)
          errs (when (:schema cmd) (schema/errors (:schema cmd) args))
          absent (filter #(= "is required" (:error %)) errs)
          other (remove #(= "is required" (:error %)) errs)]
      (cond (seq missing-ctx) {:error (str (id-str id) " needs " (str/join ", " (map name missing-ctx))) :invalid []}
            (seq other) {:error (str "invalid arguments for " (id-str id)) :invalid (vec other)}
            (seq absent) {:prompt (vec (for [{[k] :path} absent] (some #(when (= k (:name %)) %) (:args cmd))))
                          :partial args}
            (and (:when cmd) (not ((:when cmd) ctx))) {:error (str (id-str id) " is not available here") :invalid []}
            :else (let [effects ((:run cmd) ctx args)]
                    (if-let [[_ alias aargs] (first (filter #(= :invoke (first %)) effects))]
                      (invoke registry ctx alias aargs)
                      {:effects (vec effects)}))))
    {:error (str "no command " (id-str id)) :invalid []}))

(defn describe
  "Help text for command ID: title, doc, keys, arguments and Rex's notes."
  [registry id]
  (when-let [c (get registry id)]
    (str/join "\n"
              (concat [(str (id-str (:id c)) "  (" (name (:source c)) ")") (:title c) "" (str (:doc c))]
                      (when (seq (:keys c)) ["" (str "Keys: " (str/join ", " (map #(str/join " " %) (:keys c))))])
                      (when (seq (:args c))
                        (cons "\nArguments:"
                              (for [{:keys [name type enum required doc]} (:args c)]
                                (str "  " (clojure.core/name name) (when required " (required)")
                                     (when type (str " " (if (sequential? type) (str/join "|" type) type)))
                                     (when enum (str " one of " (str/join ", " enum)))
                                     (when doc (str ": " doc))))))
                      (when (seq (:notes c)) (cons "\nNotes:" (map #(str "  - " %) (:notes c))))))))
