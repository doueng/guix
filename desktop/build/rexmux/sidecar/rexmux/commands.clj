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
    :keys [["ctrl+b" "u"]] :source :local
    :run (fn [ctx _]
           (if-let [a (agents/next-blocked (:agents (world ctx)) (:block-id ctx))]
             [(focus-effect a) [:ui/message (agents/line a)]]
             [[:ui/message "No agent is blocked."]]))}
   {:id :agent/next-attention :title "Agents: focus the next one wanting attention" :category "agent"
    :doc "Focus the next blocked, failed or finished agent, most urgent first."
    :keys [["ctrl+b" "shift+u"]] :source :local
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
    :keys [["ctrl+b" "w"] ["ctrl+b" "e"] ["ctrl+b" "b"] ["ctrl+b" "s"]]
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
    :keys [["ctrl+b" "g"]] :needs #{:session-id}
    :run (fn [ctx _] [[:layer/float {:argv ["jjui"] :cwd (:cwd ctx) :size [0.9 0.85] :close-on-exit true}]])}])

(defn- rex-cmd
  "A keyed command that runs Rex API METHOD with ARGS, through the API
  command so context, defaults and the schema check apply."
  [id title keys method args]
  {:id id :title title :category (namespace id) :source :local :keys keys
   :needs #{:session-id}
   :run (fn [_ _] [[:invoke (keyword "rex" method) args]])})

(def pane-commands
  "Panes and tabs on Herdr's keys (desktop/configs/herdr/config.toml): Ctrl+b
  c new tab, n next tab, 1..9 and Ctrl+1..9 a tab, h j k l focus; plus tmux's
  % and \" splits, x close and z zoom."
  (vec (concat
        [(rex-cmd :pane/split-right "Panes: split right" [["ctrl+b" "%"]] "session.new_split" {:direction "horizontal" :side "after"})
         (rex-cmd :pane/split-down "Panes: split down" [["ctrl+b" "\""]] "session.new_split" {:direction "vertical" :side "after"})
         (rex-cmd :pane/zoom "Panes: zoom or unzoom" [["ctrl+b" "z"]] "session.zoom_block" {})
         (rex-cmd :pane/next "Panes: next" [["ctrl+b" "o"]] "session.focus_next_pane" {})
         {:id :pane/close :title "Panes: close" :category "pane" :source :local :keys [["ctrl+b" "x"]]
          :needs #{:session-id :block-id}
          :run (fn [_ _] [[:invoke :rex/block.close {}]])}
         (rex-cmd :tab/new "Tabs: new" [["ctrl+b" "c"]] "session.new_window" {})
         (rex-cmd :tab/next "Tabs: next" [["ctrl+b" "n"]] "session.focus_next_window" {})
         (rex-cmd :tab/previous "Tabs: previous" [["ctrl+b" "p"]] "session.focus_previous_window" {})
         (rex-cmd :tab/close "Tabs: close" [["ctrl+b" "&"]] "session.close_window" {})
         {:id :tab/pick :title "Tabs: pick one" :category "tab" :source :local :needs #{:session-id}
          :run (fn [ctx _]
                 [[:ui/pick {:title "Tab" :then :tab/focus :arg :window
                             :items (vec (map-indexed (fn [i [w l]] {:label (str (inc i) " " (if (str/blank? l) w l)) :value w})
                                                      (map vector (:window-ids ctx) (concat (:window-labels ctx) (repeat "")))))}]])}
         {:id :tab/focus :title "Tabs: show a tab" :category "tab" :source :local :needs #{:session-id}
          :args [{:name :window :type "string" :required true}]
          :schema {:type "object" :properties {:window {:type "string"}} :required ["window"]}
          :run (fn [_ {:keys [window]}] [[:invoke :rex/session.focus_window {:window_id window}]])}]
        (for [[d k] [["left" "h"] ["down" "j"] ["up" "k"] ["right" "l"]]]
          (rex-cmd (keyword "pane" (str "focus-" d)) (str "Panes: focus " d) [["ctrl+b" k]]
                   "session.focus_direction" {:direction d}))
        (for [n (range 1 10)]
          {:id (keyword "tab" (str "select-" n)) :title (str "Tabs: select " n) :category "tab" :source :local
           :keys [["ctrl+b" (str n)] [(str "ctrl+" n)]] :needs #{:session-id}
           :run (fn [ctx _]
                  (if-let [w (get (vec (:window-ids ctx)) (dec n))]
                    [[:invoke :rex/session.focus_window {:window_id w}]]
                    [[:ui/message (str "No tab " n)]]))}))))

(def term-nav-commands
  "Bare Ctrl+h/j/k/l as in Herdr (config :term-nav true): keyleader term-nav
  gives the key to Vim, Emacs and friends, and otherwise moves focus, leaving
  the window for Hyprland at the layout's edge."
  (vec (for [[d k] [["left" "h"] ["down" "j"] ["up" "k"] ["right" "l"]]]
         {:id (keyword "pane" (str "term-nav-" d)) :title (str "Panes: " d ", or the key to an editor")
          :category "pane" :source :local :keys [[(str "ctrl+" k)]] :needs #{:session-id :block-id}
          :run (fn [ctx _]
                 (rex ctx :spawn (:server ctx) ["keyleader" "term-nav" d]
                      {"REX_BLOCK" (:block-id ctx) "REX_SESSION" (:session-id ctx) "TERM_PROGRAM" "rex"})
                 [])})))

(def bbh-commands
  "bbh from rexmux, as Herdr's prefix t popup and typed messages had it."
  [{:id :bbh/inbox :title "bbh: inbox, the threads that need you" :category "bbh" :source :local
    :keys [["ctrl+b" "t"]] :needs #{:session-id}
    :run (fn [_ _] [[:layer/float {:argv ["bbh" "inbox"] :size [0.85 0.8] :close-on-exit true}]])}
   {:id :bbh/say :title "bbh: tell the Router, or a thread as T12 ..." :category "bbh" :source :local
    :doc "Sends TEXT with bbh say --from rex: to the thread it starts with, else the Router. A Worker's
Router sees it as [routed to T12 W3 from rex]."
    :args [{:name :text :type "string" :required true}]
    :schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
    :run (fn [ctx {:keys [text]}]
           (rex ctx :spawn (:server ctx) ["bbh" "say" "--from" "rex" text] {})
           [[:ui/message (str "Sent: " text)]])}])

(def help-commands
  [{:id :mx/describe-command :title "Help: describe a command" :category "help" :source :local
    :keys [["ctrl+b" "?"]]
    :args [{:name :command :type "string" :required true :complete :commands}]
    :schema {:type "object" :properties {:command {:type "string"}} :required ["command"]}
    :run (fn [ctx {:keys [command]}]
           (let [id (keyword (str/replace command #"^:" ""))]
             (if-let [text (reg/describe (:registry (world ctx)) id)]
               [[:ui/doc {:title command :text text}]]
               [[:ui/message (str "No command " command)]])))}
   {:id :mx/reload :title "Help: reload commands and keys" :category "help" :source :local
    :run (fn [_ _] [[:sidecar/reload {}]])}])

(def all (vec (concat agent-commands session-commands pane-commands bbh-commands help-commands)))
