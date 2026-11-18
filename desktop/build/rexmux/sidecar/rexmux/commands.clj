(ns rexmux.commands
  "Local commands: agents, sessions, servers, layers and help. Each :run gets
  the context the window sent plus what the sidecar knows, under :world:
  {:agents agents-state :servers [..] :server label :registry reg :rex fns}.
  :rex holds the read-only calls a command may make itself, so tests can
  replace them."
  (:require [clojure.string :as str]
            [rexmux.agents :as agents]
            [rexmux.registry :as reg]))

(defn- world [ctx] (:world ctx))
(defn- rex [ctx f & args] (apply (get-in ctx [:world :rex f]) args))
(defn- focus-effect [{:keys [block session server]}]
  [:focus/block (cond-> {:block block :session session} server (assoc :server server))])

(def agent-commands
  [{:id :agent/next-blocked :title "Agents: focus the next blocked one" :category "agent"
    :doc "Focus the next agent that waits for you (OSC 7501 blocked), wrapping around."
    :keys [["ctrl+b" "n"]] :source :local
    :run (fn [ctx _]
           (if-let [a (agents/next-blocked (:agents (world ctx)) (:block-id ctx))]
             [(focus-effect a) [:ui/message (agents/line a)]]
             [[:ui/message "No agent is blocked."]]))}
   {:id :agent/next-attention :title "Agents: focus the next one wanting attention" :category "agent"
    :doc "Focus the next blocked, failed or finished agent, most urgent first."
    :keys [["ctrl+b" "shift+n"]] :source :local
    :run (fn [ctx _]
           (if-let [a (agents/next-attention (:agents (world ctx)) (:block-id ctx))]
             [(focus-effect a) [:ui/message (agents/line a)]]
             [[:ui/message "No agent wants attention."]]))}
   {:id :agent/pick :title "Agents: pick one" :category "agent"
    :doc "List every agent, most urgent first, and focus the one you pick."
    :keys [["ctrl+b" "a"]] :source :local
    :run (fn [ctx _]
           (let [rows (agents/listing (:agents (world ctx)))]
             (if (seq rows)
               [[:ui/pick {:title "Agent" :items (mapv (fn [r] {:label (agents/line r) :value (:block r)}) rows)
                           :then :agent/focus :arg :block}]]
               [[:ui/message "No agents."]])))}
   {:id :agent/focus :title "Agents: focus a block" :category "agent" :source :local
    :args [{:name :block :type "string" :required true :complete :agents}]
    :schema {:type "object" :properties {:block {:type "string"}} :required ["block"]}
    :run (fn [ctx {:keys [block]}]
           (let [e (get-in (world ctx) [:agents :blocks block])]
             [(focus-effect {:block block :session (or (:session e) (:session-id ctx)) :server (:server e)})]))}
   {:id :agent/send :title "Agents: send text to the focused agent" :category "agent" :source :local
    :needs #{:block-id}
    :args [{:name :text :type "string" :required true}]
    :schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
    :run (fn [ctx {:keys [text]}] [[:pane/write {:block (:block-id ctx) :session (:session-id ctx) :text (str text "\r")}]])}
   {:id :agent/broadcast :title "Agents: send text to every agent" :category "agent" :source :local
    :args [{:name :text :type "string" :required true}]
    :schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
    :run (fn [ctx {:keys [text]}]
           (let [rows (agents/listing (:agents (world ctx)))]
             (if (seq rows)
               (vec (for [r rows] [:pane/write {:block (:block r) :session (:session r) :text (str text "\r")}]))
               [[:ui/message "No agents."]])))}
   {:id :agent/collect-results :title "Agents: collect what each agent shows" :category "agent" :source :local
    :run (fn [ctx _]
           (let [rows (agents/listing (:agents (world ctx)))]
             [[:ui/doc {:title "Agent results"
                        :text (str/join "\n\n" (for [r rows]
                                                   (str "== " (agents/line r) "\n"
                                                        (->> (str/split-lines (str (rex ctx :capture (:session r) (:block r))))
                                                             (remove str/blank?) (take-last 15) (str/join "\n")))))}]]))}
   {:id :agent/new-worktree :title "Agents: new jj workspace with an agent" :category "agent" :source :local
    :doc "jj workspace add NAME beside the current repository, then a new window running COMMAND there."
    :needs #{:session-id}
    :args [{:name :name :type "string" :required true} {:name :command :type "string" :required true :doc "such as pi or claude"}]
    :schema {:type "object" :properties {:name {:type "string"} :command {:type "string"}} :required ["name" "command"]}
    :run (fn [ctx {:keys [name command]}]
           (let [dir (rex ctx :new-workspace (:cwd ctx) name)]
             [[:rex/call "session.new_window" {:session_id (:session-id ctx) :label name
                                                 :layout {:block {:flavor "com.superlogical.terminal.shell"
                                                                  :label name
                                                                  :options {:command ["sh" "-lc" command] :cwd dir}}}}]
              [:ui/message (str "Workspace " dir)]]))}])

(def session-commands
  [{:id :session/switch :title "Sessions: switch" :category "session" :source :local
    :keys [["ctrl+b" "s"]]
    :run (fn [ctx _]
           (let [ss (rex ctx :sessions)]
             [[:ui/pick {:title "Session" :items (mapv (fn [s] {:label (or (not-empty (:label s)) (:session_id s)) :value (:session_id s)}) ss)
                         :then :session/focus :arg :session}]]))}
   {:id :session/focus :title "Sessions: show a session" :category "session" :source :local
    :args [{:name :session :type "string" :required true :complete :session}]
    :schema {:type "object" :properties {:session {:type "string"}} :required ["session"]}
    :run (fn [_ {:keys [session]}] [[:focus/session {:session session}]])}
   {:id :session/new :title "Sessions: new" :category "session" :source :local
    :args [{:name :label :type "string" :required true}]
    :schema {:type "object" :properties {:label {:type "string"}} :required ["label"]}
    :run (fn [_ {:keys [label]}] [[:session/create {:label label}]])}
   {:id :server/switch :title "Servers: switch" :category "server" :source :local
    :run (fn [ctx _]
           [[:ui/pick {:title "Server" :items (mapv (fn [s] {:label (str (:label s) "  " (:address s)) :value (:label s)}) (:servers (world ctx)))
                       :then :server/focus :arg :server}]])}
   {:id :server/focus :title "Servers: show a server" :category "server" :source :local
    :args [{:name :server :type "string" :required true}]
    :schema {:type "object" :properties {:server {:type "string"}} :required ["server"]}
    :run (fn [_ {:keys [server]}] [[:focus/server {:server server}]])}
   {:id :layer/jjui :title "Layers: jjui over the panes" :category "layer" :source :local
    :keys [["ctrl+b" "j"]] :needs #{:session-id}
    :run (fn [ctx _] [[:layer/float {:argv ["jjui"] :cwd (:cwd ctx) :size [0.9 0.85] :close-on-exit true}]])}])

(def help-commands
  [{:id :mx/describe-command :title "Help: describe a command" :category "help" :source :local
    :keys [["ctrl+h" "x"]]
    :args [{:name :command :type "string" :required true :complete :commands}]
    :schema {:type "object" :properties {:command {:type "string"}} :required ["command"]}
    :run (fn [ctx {:keys [command]}]
           (let [id (keyword (str/replace command #"^:" ""))]
             (if-let [text (reg/describe (:registry (world ctx)) id)]
               [[:ui/doc {:title command :text text}]]
               [[:ui/message (str "No command " command)]])))}
   {:id :mx/reload :title "Help: reload commands and keys" :category "help" :source :local
    :run (fn [_ _] [[:sidecar/reload {}]])}])

(def all (vec (concat agent-commands session-commands help-commands)))
