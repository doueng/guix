(ns rexg.model
  "The ghostty-rex client's state and rules, with no I/O: keys, sidecar
  messages and Rex results go in, outs come back for rexg.main to perform. A
  port of rexmux's model.rs, so the Bombadil specs and the TLA+ model written
  for rexmux's window check the same rules here.

  Outs: [:sidecar msg] [:rex server method payload tag] [:open-stream server
  block ticket] [:close-stream block] [:clipboard text] [:open url]
  [:paste server session block text] [:hints block] [:quit].
  Tags: [:view s] [:effect] [:created s] [:ticket s block] [:sessions s]
  [:revive s block window] [:project s label cwd] [:ignore]."
  (:require [clojure.string :as str]
            [rexg.hints :as hints]
            [rexg.keys :as keys]))

(def mx-key "alt+x")

;; ---- views ----

(defn- leaves [node floating]
  (cond (nil? node) []
        (:block_id node) [{:block (:block_id node) :rect (vec (or (:rect node) [0 0 1 1])) :floating floating}]
        (:split node) (into (leaves (get-in node [:split :before]) floating)
                            (leaves (get-in node [:split :after]) floating))
        :else []))

(defn view-of
  "The view in a session.view or session.attach result."
  [v]
  (let [v (or (:view v) v)]
    {:session (str (:session_id v)) :label (str (:label v)) :revision (or (:revision v) 0)
     :active (:active_window_id v)
     :windows (vec (for [w (:windows v)]
                     {:id (str (:window_id w)) :label (str (:label w))
                      :focused (:focused_block_id w) :zoomed (:zoomed_block_id w)
                      :layout (some #(when (= "tiled" (:kind %)) (:layout %)) (:layers w))
                      :tiles (vec (mapcat #(when-not (= "minimized" (:state %))
                                             (leaves (:layout %) (not= "tiled" (:kind %))))
                                          (:layers w)))}))}))

(defn active-window [view]
  (or (some #(when (= (:id %) (:active view)) %) (:windows view)) (first (:windows view))))

(defn visible
  "The tiles to draw: the zoomed block alone, else every tile."
  [view]
  (when-let [w (active-window view)]
    (if-let [z (:zoomed w)] [{:block z :rect [0 0 1 1] :floating false}] (:tiles w))))

(defn resize-request
  "The session.resize_pane payload (less session_id) that moves the border
  nearest BLOCK of the split DIRECTION crosses by DELTA."
  [layout block direction delta]
  (when-let [[orient sign] ({"left" ["horizontal" -1] "right" ["horizontal" 1]
                             "up" ["vertical" -1] "down" ["vertical" 1]} direction)]
    (letfn [(contains [n] (or (= block (:block_id n))
                              (and (:split n) (or (contains (get-in n [:split :before])) (contains (get-in n [:split :after]))))))
            (first-leaf [n] (or (:block_id n) (some-> (get-in n [:split :before]) first-leaf)))
            (find [n best] (if-let [sp (:split n)]
                             (let [best (if (= orient (:direction sp)) sp best)]
                               (or (some #(when (contains (get sp %)) (find (get sp %) best)) [:before :after]) best))
                             best))]
      (when-let [sp (find layout nil)]
        (let [b (first-leaf (:before sp)) a (first-leaf (:after sp))]
          (when (and b a)
            {:direction orient :before_block_id b :after_block_id a
             :ratio (-> (+ (or (:ratio sp) 0.5) (* sign delta)) (max 0.1) (min 0.9))}))))))

;; ---- the model ----

(defn new-model
  "SERVERS as [{:label .. :address ..}]; OPTS :startup :shell :prefer-session."
  [servers & [opts]]
  (merge {:servers (mapv #(assoc % :client-id "" :view nil :sessions []) servers)
          :current 0 :catalogue [] :bindings [] :keymap-layer nil :keymap-actions nil
          :prefix [] :minibuffer nil :echo "" :doc nil :agents [] :attention []
          :streams {} :sidecar-up false :hints nil :selection nil
          :previous-session nil :revive-pending false :focus-first-pending false
          :next-id 0 :pending {} :invoked {} :last-ctx nil}
         opts))

(defn server [m] (get-in m [:servers (:current m)]))
(defn view [m] (:view (server m)))
(defn session-id [m] (:session (view m)))
(defn focused-block [m] (some-> (view m) active-window :focused))

(defn context [m]
  (let [v (view m) w (some-> v active-window)]
    (cond-> {:server (:label (server m))}
      v (assoc :session-id (:session v) :window-ids (mapv :id (:windows v)) :window-labels (mapv :label (:windows v)))
      w (assoc :window-id (:id w))
      (and w (:focused w)) (assoc :block-id (:focused w))
      (seq (:client-id (server m))) (assoc :client-id (:client-id (server m))))))

(defn with-options
  "V with each new shell block's options given OPTS where it lacks them."
  [v opts]
  (cond
    (map? v) (let [v (if (and (= "com.superlogical.terminal.shell" (:flavor v)) (seq opts))
                       (update v :options #(merge opts (or % {})))
                       v)]
               (into {} (map (fn [[k x]] [k (with-options x opts)])) v))
    (sequential? v) (mapv #(with-options % opts) v)
    :else v))

(defn block-options [m] (cond-> {} (:shell m) (assoc :shell (:shell m))))

(defn- rex [m method payload tag]
  [:rex (:current m) method (with-options payload (block-options m)) tag])

(defn rex-out [m method payload] (rex m method payload [:effect]))

(defn perform-reply [server request result]
  [:rex server "client.reply"
   (if (contains? result :error)
     {:call_id request :error {:code "refused" :message (:error result)}}
     {:call_id request :result (:ok result)})
   [:ignore]])

(defn startup-request
  "session.create's payload for a :startup SPEC: one window per entry, each a
  shell block running its command, or a login shell when it has none."
  [spec shell]
  {:label (or (:label spec) "main")
   :initial_windows (vec (for [w (:windows spec)]
                           (let [opts (cond-> {}
                                        (vector? (:command w)) (assoc :command (:command w))
                                        shell (assoc :shell shell)
                                        (:cwd spec) (assoc :cwd (:cwd spec)))]
                             {:window_label (or (:label w) "")
                              :layout {:block (cond-> {:flavor "com.superlogical.terminal.shell"}
                                                (:label w) (assoc :label (:label w))
                                                (seq opts) (assoc :options opts))}})))})

(defn shell-quote [s]
  (if (and (seq s) (re-matches #"[A-Za-z0-9\-_./=:@+,]+" s))
    s
    (str "'" (str/replace s "'" "'\"'\"'") "'")))

(defn icon [state] (get {"blocked" "!" "error" "✗" "done" "✓" "working" "●" "idle" "○"} state "?"))

(defn fuzzy
  "Fuzzy score of QUERY in TEXT, or nil when it does not match."
  [query text]
  (if (str/blank? query)
    0
    (let [t (str/lower-case text)]
      (loop [qs (remove #{\space} (str/lower-case query)) ti 0 prev nil score 0]
        (if-let [qc (first qs)]
          (when-let [i (str/index-of t (str qc) ti)]
            (recur (rest qs) (inc i) i
                   (+ score 1 (if (= prev (dec i)) 5 0)
                      (if (or (zero? i) (not (Character/isLetterOrDigit (.charAt t (dec i))))) 3 0))))
          (- (* score 100) (count t)))))))

(defn coerce [spec text]
  (let [t (str/trim text)]
    (case (:kind spec)
      "integer" (or (parse-long t) t)
      "number" (or (parse-double t) t)
      "boolean" (case t ("true" "yes" "y") true ("false" "no" "n") false t)
      ("object" "array") (try (cheshire.core/parse-string t true) (catch Exception _ t))
      text)))

(defn- spec-of [v]
  {:name (some-> (:name v) name) :required (boolean (:required v)) :complete (some-> (:complete v) name)
   :doc (str (:doc v)) :choices (mapv str (:enum v))
   :kind (let [t (:type v)] (if (sequential? t) (or (first (remove #{"null"} t)) "string") (or t "string")))})

;; ---- sidecar link ----

(defn- ctx-changed [m]
  (let [ctx (context m)]
    (if (or (= ctx (:last-ctx m)) (not (:sidecar-up m)))
      [m []]
      [(assoc m :last-ctx ctx) [[:sidecar {:op :context :ctx ctx}]]])))

(defn hello [m]
  [(assoc m :sidecar-up true :last-ctx (context m))
   [[:sidecar {:op :hello :client-id (:client-id (server m)) :ctx (context m)}]]])

(defn invoke [m id args origin]
  (if-not (:sidecar-up m)
    [(assoc m :echo "sidecar is not running")
     (if origin [(perform-reply (first origin) (second origin) {:error "sidecar is not running"})] [])]
    (let [n (inc (:next-id m))]
      [(-> m (assoc :next-id n) (assoc-in [:pending n] origin) (assoc-in [:invoked n] id))
       [[:sidecar {:op :commands/invoke :id n :command id :args (or args {}) :ctx (context m)}]]])))

(defn send-keymap [m]
  (if (nil? (:keymap-layer m))
    []
    (vec (for [i (range (count (:servers m))) :when (seq (get-in m [:servers i :client-id]))]
           [:rex i "client.keymap" {:defaults [(:keymap-layer m)] :actions (:keymap-actions m) :server_actions "none"} [:ignore]]))))

(declare apply-effects)

(defn- reply [m msg]
  (let [id (:id msg)]
    (if-not (contains? (:pending m) id)
      [m []]
      (let [origin (get-in m [:pending id])
            m (update m :pending dissoc id)]
        (cond
          (:error msg)
          (let [detail (for [i (:invalid msg)] (str (:path i) ": " (:error i)))]
            [(-> m (update :invoked dissoc id)
                 (assoc :echo (if (seq detail) (str (:error msg) " (" (str/join "; " detail) ")") (:error msg))))
             (if origin [(perform-reply (first origin) (second origin) {:error (:error msg)})] [])])

          (:prompt msg)
          (let [command (get-in m [:invoked id]) m (update m :invoked dissoc id)]
            (if origin
              [m [(perform-reply (first origin) (second origin) {:error "the command needs arguments"})]]
              [(assoc m :minibuffer {:mode {:kind :prompt :command command :specs (mapv spec-of (:prompt msg)) :index 0
                                            :partial (or (:partial msg) {})}
                                     :input "" :selected 0})
               []]))

          :else
          (let [[m outs] (apply-effects (update m :invoked dissoc id) (:effects msg))]
            [m (cond-> outs origin (conj (perform-reply (first origin) (second origin) {:ok {}})))]))))))

(defn sidecar
  "One message from the sidecar."
  [m msg]
  (case (:op msg)
    :ready (hello m)
    :commands/changed (let [m (assoc m :catalogue (mapv (fn [c] (-> c (update :id #(str (if (keyword? %) (subs (str %) 1) %)))
                                                                  (update :enabled #(if (nil? %) true %))))
                                                       (:commands msg))
                                     :bindings (vec (for [b (get-in msg [:keymap :bindings])] [(vec (:keys b)) (:action b)]))
                                     :keymap-layer (:keymap msg) :keymap-actions (:actions msg))]
                        [m (send-keymap m)])
    :agents [(assoc m :agents (vec (:rows msg)) :attention (vec (:attention msg))) []]
    :reply (reply m msg)
    :effects (apply-effects m (:effects msg))
    :log [(assoc m :echo (str (:text msg))) []]
    [m []]))

(defn sidecar-down [m]
  [(assoc m :sidecar-up false :pending {} :echo "sidecar restarted; commands that were running failed")
   (vec (for [[_ o] (:pending m) :when o] (perform-reply (first o) (second o) {:error "sidecar restarted"})))])

;; ---- effects ----

(defn attach [m srv session]
  (if (str/blank? session)
    [m []]
    (let [old (get-in m [:servers srv :view :session])]
      [(cond-> (assoc m :current srv) (and old (not= old session)) (assoc :previous-session old))
       [[:rex srv "session.attach" {:session_id session} [:view srv]]]])))

(defn refresh-streams
  "Open a stream for each visible pane that has none and close the rest, so a
  pane never has two streams (verify/tla, OneStreamPerPane)."
  [m]
  (if-let [v (view m)]
    (let [vis (set (map :block (visible v)))
          stale (remove vis (keys (:streams m)))
          m (update m :streams #(apply dissoc % stale))
          fresh (remove #(contains? (:streams m) %) (distinct (map :block (visible v))))]
      [(reduce #(assoc-in %1 [:streams %2] :asked) m fresh)
       (into (mapv #(vector :close-stream %) stale)
             (for [b fresh]
               [:rex (:current m) "data.connect" {:session_id (:session v) :block_id b :data_ref_id "terminal"
                                                   :subprotocol "rex.data.terminal.v1"}
                [:ticket (:current m) b]]))])
    [m []]))

(defn- focus-block [m j]
  (let [m (if-let [i (and (:server j) (first (keep-indexed #(when (= (:server j) (:label %2)) %1) (:servers m))))]
            (assoc m :current i) m)
        session (or (:session j) (session-id m) "")
        [m outs] (if (not= session (session-id m)) (attach m (:current m) session) [m []])]
    [m (conj outs (rex m "session.focus_block" {:session_id session :block_id (:block j)} [:effect]))]))

(defn startup-window [m label]
  (some #(when (= label (:label %)) %) (get-in m [:startup :windows])))

(defn apply-effect [m [kind arg arg2]]
  (case (if (keyword? kind) (subs (str kind) 1) (str kind))
    "rex/call" [m [(rex m (str arg) (or arg2 {}) [:effect])]]
    "rex/key" (if-let [sid (session-id m)]
                [m (mapv #(rex m "client.key" {:session_id sid :key (keys/to-rex (keys/parse %))} [:ignore]) arg)]
                [m []])
    "pane/write" [m [(rex m "com.superlogical.terminal.write"
                           {:session_id (or (:session arg) (session-id m) "") :block_id (:block arg) :args {:data (:text arg)}}
                           [:effect])]]
    "focus/block" (focus-block m arg)
    "focus/window" (if-let [sid (session-id m)]
                     [m [(rex m "session.focus_window" {:session_id sid :window_id (:window arg)} [:effect])]]
                     [m []])
    "focus/session" (attach m (:current m) (str (:session arg)))
    "focus/server" (let [label (:server arg)
                         i (first (keep-indexed #(when (= label (:label %2)) %1) (:servers m)))]
                     (if i
                       (let [[m o1] (refresh-streams (assoc m :current i :echo (str "server " label)))
                             [m o2] (ctx-changed m)]
                         [m (cond-> (into o1 o2)
                              (nil? (get-in m [:servers i :view])) (conj [:rex i "session.list" {} [:sessions i]]))])
                       [(assoc m :echo (str "no server " label)) []]))
    "session/create" [m [(rex m "session.create" {:label (:label arg)} [:created (:current m)])]]
    "layer/float" (if-let [sid (session-id m)]
                    (let [[w h] (or (:size arg) [0.8 0.7])
                          argv (if-let [wd (System/getenv "WAYLAND_DISPLAY")]
                                 (into ["env" (str "WAYLAND_DISPLAY=" wd)] (:argv arg))
                                 (:argv arg))
                          opts (cond-> {:command argv}
                                 (:cwd arg) (assoc :cwd (:cwd arg))
                                 (:close-on-exit arg) (assoc :exit {:on_completion true}))]
                      [m [(rex m "session.new_layer"
                               {:session_id sid :bounds [(/ (- 1 w) 2) (/ (- 1 h) 2) w h] :focus true
                                :layout {:block {:flavor "com.superlogical.terminal.shell" :options opts}}}
                               [:effect])]])
                    [m []])
    "ui/message" [(assoc m :echo (if (string? arg) arg (pr-str arg))) []]
    "ui/mx" [(assoc m :minibuffer {:mode {:kind :mx} :input "" :selected 0}) []]
    "ui/doc" [(assoc m :doc [(str (:title arg)) (str (:text arg))]) []]
    "ui/pick" [(assoc m :minibuffer {:mode {:kind :pick :title (or (:title arg) "Pick")
                                            :items (mapv (fn [i] [(str (:label i)) (:value i)]) (:items arg))
                                            :then (let [t (:then arg)] (if (keyword? t) (subs (str t) 1) (str t)))
                                            :arg (name (or (:arg arg) "value"))}
                                     :input "" :selected 0})
               []]
    "clipboard/copy" [m [[:clipboard (str arg)]]]
    "hints/open" (if-let [b (focused-block m)] [m [[:hints b]]] [(assoc m :echo "No focused pane.") []])
    "clipboard/paste" (if (and (session-id m) (focused-block m))
                        [m [[:paste (:current m) (session-id m) (focused-block m) nil]]]
                        [m []])
    "session/last" (if-let [p (:previous-session m)]
                     (attach m (:current m) p)
                     [(assoc m :echo "No previous session.") []])
    "session/startup" [m [[:rex (:current m) "session.list" {} [:project (:current m) (or (:label arg) "main") (:cwd arg)]]]]
    "pane/resize" (let [sid (session-id m) block (focused-block m)
                        layout (some-> (view m) active-window :layout)
                        delta (or (:delta arg) (if (:cells arg) (/ (double (:cells arg)) (if (#{"up" "down"} (:direction arg)) 40 120)) 0.05))]
                    (if-let [p (and sid block (resize-request layout block (:direction arg) delta))]
                      [m [(rex m "session.resize_pane" (assoc p :session_id sid) [:effect])]]
                      [(assoc m :echo "No border to move that way.") []]))
    "keymap/layer" [m (send-keymap m)]
    [(assoc m :echo (str "unknown effect " kind)) []]))

(defn apply-effects [m effects]
  (reduce (fn [[m outs] e] (let [[m o] (apply-effect m e)] [m (into outs o)])) [m []] effects))

;; ---- Rex ----

(defn revive-checks [m srv]
  (if-let [v (get-in m [:servers srv :view])]
    (vec (for [w (:windows v)
               [i t] (map-indexed vector (remove :floating (:tiles w)))]
           [:rex srv "block.method" {:session_id (:session v) :block_id (:block t) :method "process"}
            [:revive srv (:block t) (if (and (startup-window m (:label w)) (zero? i)) (:label w) "")]]))
    []))

(defn revive
  "After a restart Rex starts each block's shell anew without its program. A
  startup tab left a bare shell gets its :restore (else :command) again in
  the configured shell; any other bare pane gets the configured shell."
  [m srv block window process]
  (let [spec (startup-window m window)
        fg (get-in process [:foreground :name] "")
        bare (or (nil? (get-in process [:child :pid])) (= (get-in process [:child :pid]) (get-in process [:foreground :pid])))
        shell (or (:shell m) "fish")
        argv (when spec (or (:restore spec) (:command spec)))
        line (cond (not (and (#{"bash" "sh" "zsh" "fish" "dash"} fg) bare)) nil
                   argv (str "exec " shell " -l -c " (shell-quote (str/join " " (map shell-quote argv))) "\r")
                   (not= fg (last (str/split shell #"/"))) (str "exec " shell " -l\r"))
        sid (get-in m [:servers srv :view :session])]
    (if (and line sid)
      [(assoc m :echo (if (str/blank? window) "restored a pane's shell" (str "restored tab " window)))
       [[:rex srv "com.superlogical.terminal.write" {:session_id sid :block_id block :args {:data line}} [:effect]]]]
      [m []])))

(defn set-view [m srv v]
  (let [old (get-in m [:servers srv :view])]
    (if (and old (= (:session old) (:session v)) (> (:revision old) (:revision v)))
      [m []]
      (let [m (assoc-in m [:servers srv :view] v)
            [m o1] (if (= srv (:current m)) (refresh-streams m) [m []])
            o2 (if (:revive-pending m) (revive-checks m srv) [])
            first-w (first (:windows v))
            o3 (if (and (:focus-first-pending m) first-w (not= (:id first-w) (:active v)))
                 [[:rex srv "session.focus_window" {:session_id (:session v) :window_id (:id first-w)} [:effect]]]
                 [])
            m (assoc m :revive-pending false :focus-first-pending false)
            [m o4] (ctx-changed m)]
        [m (vec (concat o1 o2 o3 o4))]))))

(defn connected [m srv client-id]
  (let [m (assoc-in m [:servers srv :client-id] client-id)]
    [m (into [[:rex srv "session.list" {} [:sessions srv]]]
             (filter #(= srv (second %)) (send-keymap m)))]))

(defn rex-response [m [tk srv & more :as tag] result]
  (if (contains? result :error)
    [(cond-> (assoc m :echo (:error result)) (= tk :ticket) (update :streams dissoc (first more))) []]
    (let [v (:ok result)]
      (case tk
        :view (set-view m srv (view-of v))
        :sessions (let [lst (mapv (fn [s] [(str (:session_id s)) (str (:label s))]) (:sessions v))
                        m (assoc-in m [:servers srv :sessions] lst)]
                    (if (and (nil? (get-in m [:servers srv :view])) (= srv (:current m)))
                      (let [want (or (:prefer-session m) (get-in m [:startup :label]))
                            found (when want (some (fn [[id l :as e]] (when (or (= id want) (= l want) (str/ends-with? id want)) e)) lst))
                            startup-missing (and (nil? (:prefer-session m)) (nil? found))]
                        (if (and (or startup-missing (empty? lst)) (:startup m))
                          [(assoc m :echo "starting the startup session")
                           [[:rex srv "session.create" (startup-request (:startup m) (:shell m)) [:created srv]]]]
                          (let [m (assoc m :revive-pending (boolean (and found (nil? (:prefer-session m)))))]
                            (if-let [[sid] (or found (last lst))] (attach m srv sid) [m []]))))
                      [m []]))
        :created (attach (assoc m :focus-first-pending true) srv (str (:session_id v)))
        :ticket (let [block (first more)]
                  (if (and (:ticket v) (contains? (:streams m) block))
                    [(assoc-in m [:streams block] :open) [[:open-stream srv block (:ticket v)]]]
                    [m []]))
        :revive (revive m srv (first more) (second more) v)
        :project (let [[label cwd] more
                       found (some #(when (= label (:label %)) (:session_id %)) (:sessions v))]
                   (if found
                     (attach m srv found)
                     (let [spec (cond-> (assoc (or (:startup m) {:windows [{:label "shell"}]}) :label label)
                                  cwd (assoc :cwd cwd))]
                       [m [[:rex srv "session.create" (with-options (startup-request spec (:shell m)) (block-options m)) [:created srv]]]])))
        [m []]))))

(defn stream-closed [m block]
  (refresh-streams (update m :streams dissoc block)))

(defn rex-event [m srv ev]
  (let [kind (:type ev) payload (:payload ev)]
    (case kind
      "session_event" (let [sid (:session_id payload)
                            shown (= sid (get-in m [:servers srv :view :session]))]
                        (if (and shown (#{"session_view_changed" "active_window_changed" "window_created" "window_closed" "block_closed"}
                                        (get-in payload [:event :type])))
                          [m [[:rex srv "session.view" {:session_id sid} [:view srv]]]]
                          [m []]))
      ("session_created" "session_destroyed") [m [[:rex srv "session.list" {} [:sessions srv]]]]
      "client_perform_requested" (let [action (:action payload) request (str (:call_id payload))]
                                   (if-let [id (some #(when (= action (:action %)) (:id %)) (:catalogue m))]
                                     (invoke m id (or (:args payload) {}) [srv request])
                                     [m [(perform-reply srv request {:error (str "no action " action)})]]))
      [m []])))

;; ---- keys ----

(defn continuations
  "The commands bound under the pending prefix, for which-key."
  [m]
  (let [n (count (:prefix m))]
    (->> (:bindings m)
         (filter (fn [[ks _]] (and (> (count ks) n) (= (subvec ks 0 n) (:prefix m)))))
         (map (fn [[ks a]] [(str/join " " (subvec ks n)) (or (some #(when (= a (:action %)) (:title %)) (:catalogue m)) a)]))
         distinct sort vec)))

(defn open-hints
  "Tiny fingers on BLOCK, whose screen shows LINES and soft-wraps at WIDTH."
  [m block lines width]
  (let [ts (hints/targets lines width)]
    (if (empty? ts)
      (assoc m :echo "Nothing to copy on this pane.")
      (assoc m :echo (str (count ts) " hints: type one to copy, Shift to paste, Esc to quit")
             :hints {:block block :session (session-id m) :targets ts :input ""}))))

(defn- hint-key [m k]
  (let [h (:hints m)]
    (cond
      (or (= "escape" (:key k)) (and (:ctrl k) (#{"c" "g"} (:key k)))) [(assoc m :hints nil :echo "") []]
      (= "backspace" (:key k)) [(update-in m [:hints :input] #(subs % 0 (max 0 (dec (count %))))) []]
      (not (and (re-matches #"[a-z]" (:key k)) (not (:ctrl k)) (not (:alt k)) (not (:super k)))) [m []]
      :else
      (let [input (str (:input h) (:key k))
            r (hints/step (:targets h) input)]
        (case r
          :none [(assoc-in m [:hints :input] "") []]
          :more [(assoc-in m [:hints :input] input) []]
          (let [text (:text r) short (subs text 0 (min 40 (count text))) m (assoc m :hints nil)]
            (if (:shift k)
              [(assoc m :echo (str "Pasted: " short)) [[:paste (:current m) (:session h) (:block h) text]]]
              [(assoc m :echo (str "Copied: " short))
               (cond-> [[:clipboard text]] (hints/web-url? text) (conj [:open text]))])))))))

(defn candidates
  "The minibuffer's candidates, [label value], best first."
  [m]
  (if-let [mb (:minibuffer m)]
    (let [mode (:mode mb)
          items (case (:kind mode)
                  :mx (vec (for [c (:catalogue m) :when (:enabled c)] [(str (:title c) "  (" (:id c) ")") (:id c)]))
                  :pick (:items mode)
                  :prompt (let [spec (get (:specs mode) (:index mode))]
                            (if (seq (:choices spec))
                              (mapv #(vector % %) (:choices spec))
                              (case (:complete spec)
                                "commands" (mapv #(vector (:id %) (:id %)) (:catalogue m))
                                "agents" (mapv #(vector (str (:state %) " " (:app %) " " (:msg %)) (:block %)) (:agents m))
                                "session" (mapv (fn [[id l]] [(if (str/blank? l) id l) id]) (:sessions (server m)))
                                []))))
          scored (keep-indexed (fn [i it] (when-let [s (fuzzy (:input mb) (first it))] [s i it])) items)]
      (mapv #(nth % 2) (if (str/blank? (:input mb)) scored (sort-by (fn [[s i]] [(- s) i]) scored))))
    []))

(defn prompt-text [m]
  (let [mode (get-in m [:minibuffer :mode])]
    (case (:kind mode)
      :mx "M-x "
      :pick (str (:title mode) ": ")
      :prompt (let [s (get (:specs mode) (:index mode))] (str (:command mode) " " (:name s) (when (seq (:kind s)) (str " (" (:kind s) ")")) ": "))
      "")))

(defn- mini-accept [m]
  (let [cands (candidates m)
        mb (:minibuffer m)
        chosen (get cands (min (:selected mb) (max 0 (dec (count cands)))))
        m (assoc m :minibuffer nil)
        mode (:mode mb)]
    (case (:kind mode)
      :mx (if chosen (invoke m (str (second chosen)) {} nil) [(assoc m :echo (str "no command matches " (:input mb))) []])
      :pick (if chosen (invoke m (:then mode) {(keyword (:arg mode)) (second chosen)} nil) [m []])
      :prompt (let [spec (get (:specs mode) (:index mode))
                    value (if (or (seq (:choices spec)) (:complete spec))
                            (cond chosen (second chosen)
                                  (and (:complete spec) (seq (:input mb))) (coerce spec (:input mb))
                                  :else ::invalid)
                            (coerce spec (:input mb)))]
                (cond
                  (= value ::invalid) [(assoc m :minibuffer (assoc mb :selected 0)
                                              :echo (str (:name spec) " must be one of " (str/join ", " (:choices spec))))
                                       []]
                  (< (inc (:index mode)) (count (:specs mode)))
                  [(assoc m :minibuffer {:mode (-> mode (update :index inc) (assoc-in [:partial (keyword (:name spec))] value))
                                         :input "" :selected 0})
                   []]
                  :else (invoke m (:command mode) (assoc (:partial mode) (keyword (:name spec)) value) nil))))))

(defn- mini-key [m k]
  (let [ch (keys/chord k)
        n (count (candidates m))]
    (case ch
      ("escape" "ctrl+g") (let [pending (into {} (filter (fn [[_ o]] o)) (:pending m))]
                            [(assoc m :minibuffer nil :echo "Quit" :pending pending
                                    :invoked (select-keys (:invoked m) (keys pending)))
                             []])
      "backspace" [(update m :minibuffer #(-> % (update :input (fn [s] (subs s 0 (max 0 (dec (count s)))))) (assoc :selected 0))) []]
      "ctrl+u" [(update m :minibuffer assoc :input "" :selected 0) []]
      ("down" "ctrl+n" "tab") [(update-in m [:minibuffer :selected] #(if (zero? n) 0 (mod (inc %) n))) []]
      ("up" "ctrl+p" "shift+tab") [(update-in m [:minibuffer :selected] #(if (zero? n) 0 (mod (+ % n -1) n))) []]
      "enter" (mini-accept m)
      (if-let [t (and (not (:ctrl k)) (not (:alt k)) (not (:super k)) (:text k))]
        (if (#{"\r" "\t"} t) [m []] [(update m :minibuffer #(-> % (update :input str t) (assoc :selected 0))) []])
        [m []]))))

(defn key
  "One key press K."
  [m k]
  (let [ch (keys/chord k)]
    (cond
      (:hints m) (hint-key m k)
      (and (:doc m) (or (#{"escape" "q" "ctrl+g"} ch))) [(assoc m :doc nil) []]
      (:minibuffer m) (mini-key m k)
      (and (empty? (:prefix m)) (= ch mx-key)) [(assoc m :minibuffer {:mode {:kind :mx} :input "" :selected 0}) []]
      (and (seq (:prefix m)) (#{"escape" "ctrl+g"} ch)) [(assoc m :prefix [] :echo "Quit") []]
      :else
      (let [sq (conj (:prefix m) ch)
            bound (some (fn [[ks a]] (when (= ks sq) a)) (:bindings m))]
        (cond
          bound (let [m (assoc m :prefix [] :echo "")]
                  (if-let [id (some #(when (= bound (:action %)) (:id %)) (:catalogue m))]
                    (invoke m id {} nil)
                    [(assoc m :echo (str bound " is not a command")) []]))
          (some (fn [[ks _]] (and (> (count ks) (count sq)) (= (subvec ks 0 (count sq)) sq))) (:bindings m))
          [(assoc m :prefix sq :echo (str (str/join " " sq) "-")) []]
          (seq (:prefix m)) [(assoc m :prefix [] :echo (str (str/join " " sq) " is undefined")) []]
          :else (if-let [sid (session-id m)]
                  [m [(rex m "client.key" (cond-> {:session_id sid :key (keys/to-rex k)}
                                             (focused-block m) (assoc :block_id (focused-block m)))
                          [:ignore])]]
                  [m []]))))))
