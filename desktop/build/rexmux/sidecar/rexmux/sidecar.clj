(ns rexmux.sidecar
  "The bb process behind the rexmux window. It reads one EDN message per line
  on stdin and writes them on stdout (rexmux.proto). It owns the command
  registry, the keymap layer, the agents' status and every command's logic;
  the window owns panes, keys and drawing, and never waits on bb for a key.

  handle is pure apart from the :io functions it is given, so tests drive it
  with fake Rex calls; main wires it to stdin, stdout and the rex CLI."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rexmux.agents :as agents]
            [rexmux.commands :as commands]
            [rexmux.proto :as proto]
            [rexmux.registry :as reg]
            [rexmux.rex :as rex]))

;; ---- configuration ----

(defn config-path []
  (or (System/getenv "REXMUX_CONFIG")
      (str (fs/path (or (System/getenv "XDG_CONFIG_HOME") (fs/path (System/getProperty "user.home") ".config")) "rexmux" "config.edn"))))

(def default-config
  {:servers [{:label "local" :address nil}]
   :leader-file nil
   :leader-prefix nil
   :term-nav false
   :extensions-dir nil})

(defn- expand-home [p] (if (and (string? p) (str/starts-with? p "~/")) (str (fs/path (fs/home) (subs p 2))) p))

(defn read-config [path]
  (-> (merge default-config (when (and path (fs/exists? path)) (edn/read-string (slurp path))))
      (update :leader-file expand-home)
      (update :extensions-dir expand-home)))

(defn load-extensions
  "Commands from every .clj file in DIR; each file's last form is a vector of
  command maps. A file that fails to load is reported, not fatal."
  [dir]
  (if (and dir (fs/directory? dir))
    (reduce (fn [{:keys [cmds errors]} f]
              (try {:cmds (into cmds (let [v (load-file (str f))] (when (vector? v) v))) :errors errors}
                   (catch Exception e {:cmds cmds :errors (conj errors (str (fs/file-name f) ": " (ex-message e)))})))
            {:cmds [] :errors []} (sort (map str (fs/glob dir "*.clj"))))
    {:cmds [] :errors []}))

(defn build-registry
  "The registry from the help document, leader.edn, local and extension commands."
  [help config]
  (let [leader (when-let [f (:leader-file config)] (when (fs/exists? f) (edn/read-string (slurp f))))
        ext (load-extensions (:extensions-dir config))]
    {:registry (reg/merge-sources (reg/api-commands help)
                                  (reg/leader-commands leader (:leader-prefix config))
                                  commands/all
                                  (when (:term-nav config) commands/term-nav-commands)
                                  ;; A client's own commands, such as ghostty-rex's reload.
                                  (:extra-commands config)
                                  (:cmds ext))
     :errors (:errors ext)}))

;; ---- state and handling ----

(defn initial-state [config]
  {:tx proto/sender :rx proto/receiver :ctx {} :config config
   :servers (:servers config) :agents agents/empty-state :registry {} :enabled nil})

(defn- out [state msg] (let [[tx m] (proto/stamp (:tx state) msg)] [(assoc state :tx tx) m]))

(defn- emit
  "[state' msgs] after stamping each of MSGS in order."
  [state msgs]
  (reduce (fn [[s acc] m] (let [[s m] (out s m)] [s (conj acc m)])) [state []] msgs))

(defn- enabled-ids [registry ctx]
  (set (keep #(when (reg/enabled? % ctx) (:id %)) (vals registry))))

(defn catalogue-msg [state]
  (let [{:keys [layer actions]} (reg/keymap-layer (:registry state))]
    {:op :commands/changed
     :commands (reg/catalogue (:registry state) (:ctx state))
     :keymap layer :actions actions
     :conflicts (reg/key-conflicts (:registry state))}))

(defn agents-msg [state]
  {:op :agents :rows (agents/listing (:agents state)) :attention (mapv :block (agents/attention (:agents state)))})

(defn- world [state io]
  {:agents (:agents state) :servers (:servers state) :registry (:registry state)
   :rex (:rex io)})

(defn- with-ctx [state ctx]
  (let [old (:block-id (:ctx state))
        blk (:block-id ctx)]
    (cond-> (assoc state :ctx ctx)
      (and blk (not= old blk)) (update :agents agents/focused blk))))

(defn- run-command [state io {:keys [id command args ctx]}]
  (let [ctx (or ctx (:ctx state))
        cid (if (keyword? command) command (keyword (str/replace (str command) #"^:" "")))
        res (reg/invoke (:registry state) (assoc ctx :world (world state io)) cid args)
        reload? (some #(= :sidecar/reload (first %)) (:effects res))
        res (cond-> res reload? (update :effects (fn [es] (vec (remove #(= :sidecar/reload (first %)) es)))))]
    {:reply (merge {:op :reply :id id} res) :reload? reload?}))

(defn notify-attention!
  "Tell the desktop about each agent that newly wants the person (blocked,
  failed or done) in a pane they are not looking at, as Herdr's system toasts
  did. IO's :notify gets a summary and a body."
  [io ctx before after]
  (when-let [notify (:notify io)]
    (let [old (set (map :block (agents/attention before)))]
      (doseq [{:keys [block state app msg] :as row} (agents/attention after)
              :when (and (not (old block)) (not= block (:block-id ctx)))]
        (notify (str (or app "agent") " " ({"blocked" "needs you" "error" "failed" "done" "is done"} state state))
                (str (when-not (str/blank? msg) (str msg "\n")) "Alt+Space u goes there: " (agents/line row)))))))

(defn deliver
  "[state' outgoing-msgs] for one MSG already accepted. IO holds :load-help
  (fn [server]) and :rex, the read-only calls commands may make. Rex events
  arrive here as {:op :rex/event :event .. :server label}."
  [state io msg]
  (let [[state msgs]
        (case (:op msg)
         :hello (let [help ((:load-help io) (first (:servers state)))
                           {:keys [registry errors]} (build-registry help (:config state))
                                    synced (reduce agents/report agents/empty-state (when-let [f (:resync io)] (f)))
                                    s (assoc state :registry registry :client-id (:client-id msg) :ctx (or (:ctx msg) {})
                                             :agents synced)
                                s (assoc s :enabled (enabled-ids registry (:ctx s)))]
                     [s (concat [(catalogue-msg s) (agents-msg s)]
                                (for [e errors] {:op :log :level :error :text e}))])
          :context (let [s (with-ctx state (:ctx msg))
                         en (enabled-ids (:registry s) (:ctx s))
                         changed? (not= en (:enabled state))
                         s (assoc s :enabled en)]
                     [s (cond-> [] changed? (conj (catalogue-msg s))
                                (not= (:agents s) (:agents state)) (conj (agents-msg s)))])
          :commands/invoke (let [{:keys [reply reload?]} (run-command state io msg)]
                             (if reload?
                               (let [{:keys [registry errors]} (build-registry ((:load-help io) (first (:servers state))) (:config state))
                                     s (assoc state :registry registry)]
                                 [s (concat [(update reply :effects (fnil conj []) [:ui/message "Reloaded commands and keys."])
                                             (catalogue-msg s)]
                                            (for [e errors] {:op :log :level :error :text e}))])
                               [state [reply]]))
          :rex/event (let [r (agents/from-event (:event msg))
                           a (:agents state)
                           a2 (cond (nil? r) a
                                    (:closed r) (agents/block-closed a (:block r))
                                    :else (agents/report a (assoc r :server (:server msg))))
                           s (assoc state :agents a2)]
                       (notify-attention! io (:ctx state) a a2)
                       [s (when (not= a a2) [(agents-msg s)])])
          :effect/result [state []]
          :panel/event [state []]
          :eval [state [{:op :reply :id (:id msg)
                         :value (try (pr-str (load-string (:code msg))) (catch Exception e (str "error: " (ex-message e))))}]]
          :ping [state [{:op :pong :id (:id msg)}]]
          :bye [(assoc state :stop true) []]
          [state [{:op :log :level :warn :text (str "unknown op " (:op msg))}]])]
    (emit state msgs)))

(defn handle
  "[state' outgoing-msgs] for one MSG from the window: checks its :seq first,
  drops a repeat, and reports a gap before delivering."
  [state io msg]
  (let [[rx verdict] (proto/accept (:rx state) msg)
        state (assoc state :rx rx)]
    (case verdict
      :duplicate [state []]
      :invalid (emit state [{:op :log :level :warn :text (str "message without :seq: " (:op msg))}])
      (let [[s gap] (if (map? verdict)
                      (emit state [{:op :log :level :warn :text (str "sidecar missed messages " (:gap verdict))}])
                      [state []])
            [s msgs] (deliver s io msg)]
        [s (into gap msgs)]))))

;; ---- process ----

(def ^:private lock (Object.))

(defn- write! [msgs]
  (locking lock
    (doseq [m msgs] (println (proto/encode m)))
    (flush)))

(defn spawn-env
  "The environment a command the sidecar spawns gets on top of its own: ENV,
  REX_SERVER when ADDRESS is set, and desktop/bin last on PATH. The window
  inherits Hyprland's PATH, which lacks ~/.local/bin, so a bare keyleader or
  bbh was not found and Ctrl+L showed an error instead of clearing (T207).
  Last, so what PATH already has, such as a test's stubs, comes first."
  [env address path]
  (cond-> (assoc env "PATH" (str (when-not (str/blank? path) (str path ":")) commands/bin-dir))
    address (assoc "REX_SERVER" address)))

(defn resolve-argv
  "ARGV with a bare program name found on the PATH of ENV, as spawn-env
  makes it. The JVM looks the program up on its own PATH, not the child's."
  [[program & args :as argv] env]
  (if-let [found (and (not (str/includes? program "/"))
                      (fs/which program {:paths (str/split (get env "PATH" "") #":")}))]
    (into [(str found)] args)
    argv))

(defn make-io
  "The :io map handle gets in a live process: Rex through the rex CLI,
  desktop notifications as APP. The rexmux sidecar process and the
  ghostty-rex client, which runs the sidecar in-process, both use it."
  [config app]
  {:load-help rex/help
   :notify (fn [summary body]
             (babashka.process/process ["notify-send" (str "--app-name=" app) summary body]
                                       {:out :discard :err :discard}))
   :resync (fn [] (vec (for [srv (:servers config)
                             r (try (rex/program-statuses srv) (catch Exception _ []))]
                         (assoc r :server (:label srv)))))
   :rex {:capture (fn [session block] (rex/capture (first (:servers config)) session block))
         :cwd (fn [session block]
                (let [p (rex/call (first (:servers config)) "block.method" {:session_id session :block_id block :method "process"})]
                  (or (get-in p [:foreground :cwd]) (get-in p [:child :cwd]))))
         :spawn (fn [server argv env]
                  (let [address (:address (or (some #(when (= server (:label %)) %) (:servers config))
                                              (first (:servers config))))
                        env (spawn-env env address (System/getenv "PATH"))]
                    (babashka.process/process (resolve-argv argv env)
                                              {:extra-env env :out :discard :err :discard})))
         :sessions (fn [] (rex/sessions (first (:servers config))))
         :new-workspace (fn [cwd name]
                          (let [dir (str (fs/path (fs/parent (or cwd ".")) name))]
                            (babashka.process/shell {:dir cwd} "jj" "workspace" "add" "--name" name dir)
                            dir))}})

(defn -main [& _]
  (let [config (read-config (config-path))
        state (atom (initial-state config))
        io (make-io config "rexmux")
        step! (fn [f]
                (locking lock
                  (let [[s msgs] (f @state)]
                    (reset! state s)
                    (write! msgs))))]
    (step! #(emit % [{:op :ready}]))
    (doseq [srv (:servers config)]
      (rex/events! srv (fn [ev] (step! #(deliver % io {:op :rex/event :event ev :server (:label srv)})))))
    (loop []
      (when-let [line (read-line)]
        (when-not (str/blank? line) (step! #(handle % io (proto/decode line))))
        (when-not (:stop @state) (recur))))
    (shutdown-agents)
    (System/exit 0)))
