;; rexctl: stable verbs over the rex CLI for agents (T229). The rex skill
;; (desktop/pi/agent/skills/rexmux/SKILL.md) teaches them; desktop/bin/rexctl is the
;; entry point and desktop/build/rexctl/test.bb the tests.
(ns rexctl
  "Panes are Rex terminal blocks, named by full ID (block:<uuid>), an ID suffix or
  a label. Every pane verb resolves its target with rex block inspect, so it needs
  no session and does not depend on how a client draws the session. Results are
  maps; the CLI prints them as JSON or EDN."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:dynamic *server*
  "The server rex -S names, or nil for rex's own default (REX_SERVER, else the
  local socket)."
  nil)

(defn bin
  "REXCTL_REX, else Home's rex, which matches the server Home runs (a stray
  rex earlier on PATH, such as an old one in ~/.local/bin, may not), else rex."
  []
  (or (System/getenv "REXCTL_REX")
      (let [home (str (fs/home) "/.guix-home/profile/bin/rex")] (when (fs/executable? home) home))
      "rex"))

(defn rex
  "Run rex with ARGS against *server*; {:exit :out :err}. Never starts a server."
  [& args]
  (let [[opts args] (if (map? (first args)) [(first args) (rest args)] [{} args])]
    (apply p/sh (merge {:continue true} opts)
           (concat [(bin) "--autostart=false"] (when *server* ["-S" *server*]) args))))

(defn fail! [msg data] (throw (ex-info msg (assoc data :rexctl true))))

(defn rexj
  "The parsed JSON rex prints for ARGS, or throws with rex's error."
  [& args]
  (let [{:keys [exit out err]} (apply rex args)]
    (if (zero? exit)
      (when-not (str/blank? out) (json/parse-string out true))
      (fail! (str/trim (str err out)) {:args (vec args)}))))

(defn block-call [pane method args]
  (rexj "block" "call" "-b" pane method (json/generate-string (or args {}))))

;; Pure parts

(def states ["blocked" "error" "done" "working" "idle"])
(def rank (zipmap states (range)))

(defn status-of
  "The most urgent OSC 7501 record of RECORDS, as {:state :app :msg :kind :title},
  or nil. An unknown state counts as idle, as the protocol says."
  [records]
  (when-let [r (first (sort-by #(rank (:state %) (rank "idle")) records))]
    (cond-> (select-keys r [:state :kind :msg :title :progress :updated_at])
      (or (:effective_app r) (:app r)) (assoc :app (or (:effective_app r) (:app r)))
      (not (rank (:state r))) (assoc :state "idle"))))

(def shells #{"bash" "fish" "zsh" "sh" "dash" "ksh" "mksh" "tcsh" "csh" "nu" "elvish" "xonsh" "ash"})

(defn at-prompt?
  "True when PROCESS (the result of a block's process method) shows a shell
  in the foreground with nothing started from it: no command, editor or agent
  runs in it. A pane whose own program is not a shell has no prompt."
  [{:keys [child foreground]}]
  (boolean (and child foreground (= (:pid child) (:pid foreground))
                (shells (str/replace (str (:name foreground)) #"^-" "")))))

(defn session-panes
  "The panes of INSPECT, a session inspect result, in tab order, without the
  per-block facts. :focused marks each active tab's focused pane, and :viewed a
  session an app client is attached to, so its focused pane may take the
  person's keys."
  [inspect]
  (let [viewed (boolean (some #(and (= "app" (get-in % [:info :kind]))
                                    (= "connected" (:connection_state %)))
                              (:clients inspect)))]
    (vec (for [[i w] (map-indexed vector (:windows inspect))
               layer (:layers w)
               b (:blocks layer)
               :when (= "com.superlogical.terminal" (:creator_name b))]
           {:session (:session_id inspect) :session_label (:label inspect)
            :tab (:window_id w) :tab_label (:label w) :tab_index (inc i)
            :tab_active (= (:window_id w) (:active_window_id inspect))
            :pane (:block_id b) :label (:label b) :flavor (:flavor b)
            :floating (= "floating" (:kind layer))
            :focused (and (= (:window_id w) (:active_window_id inspect))
                          (= (:block_id b) (:focused_block_id w)))
            :viewed viewed}))))

(defn guard
  "Why typing into or closing PANE needs the person's say-so, or nil. SELF is
  the caller's own block (REX_BLOCK)."
  [{:keys [pane focused viewed]} self]
  (cond (and self (= pane self)) "it is your own pane"
        (and focused viewed) "it is the focused pane of a session an app shows, so the person may be typing there"))

(defn tail-lines [text n]
  (let [ls (str/split-lines (str text))]
    (str/join "\n" (if (and n (pos? n)) (take-last n ls) ls))))

(defn matcher
  "A predicate on text from :match (a literal) or :regex."
  [{:keys [match regex]}]
  (cond match #(str/includes? % match)
        regex (let [re (re-pattern regex)] #(boolean (re-find re %)))))

(defn hits
  "The lines of TEXT that PRED holds for."
  [pred text]
  (filterv pred (str/split-lines (str text))))

(defn since-command
  "The lines of TEXT from the last one that echoes CMD on, or its last N lines
  when no line does."
  [text cmd n]
  (let [ls (str/split-lines (str text))
        first-line (first (str/split-lines cmd))
        i (last (keep-indexed #(when (str/includes? %2 first-line) %1) ls))]
    (if i (str/join "\n" (drop i ls)) (tail-lines text n))))

(defn split-direction
  "right for a pane wider than tall on screen, else below (Herdr's rule)."
  [{:keys [columns rows cell_width_px cell_height_px]}]
  (let [w (* (or columns 80) (or cell_width_px 8)) h (* (or rows 24) (or cell_height_px 16))]
    (if (>= w (* 1.2 h)) "right" "below")))

;; Server facts

(defn sessions [] (:sessions (rexj "ls" "--json")))

(defn inspect-session [s] (rexj "session" "inspect" s "--json"))

(defn pane-facts
  "PANE with its foreground process, cwd, whether its shell is at a prompt and
  its agent status."
  [{:keys [pane] :as row}]
  (let [proc (try (block-call pane "process" {}) (catch Exception _ nil))
        recs (try (:records (block-call pane "program_status" {})) (catch Exception _ nil))]
    (assoc row
           :fg (get-in proc [:foreground :name])
           :cwd (or (get-in proc [:foreground :cwd]) (get-in proc [:child :cwd]))
           :at_prompt (at-prompt? proc)
           :exited (when-let [e (:last_exit proc)] (:exit_code e))
           :status (status-of recs))))

(defn self-pane [] (System/getenv "REX_BLOCK"))

(defn ls
  "Every terminal pane of SESSION, or of every session, with its facts."
  [{:keys [session]}]
  (let [ids (if session [session] (map :session_id (sessions)))
        self (self-pane)]
    (->> ids
         (pmap #(session-panes (inspect-session %)))
         (apply concat)
         (pmap #(cond-> (pane-facts %) (= self (:pane %)) (assoc :self true)))
         (map #(cond-> % *server* (assoc :server *server*)))
         vec)))

(defn resolve-pane
  "The pane row of TARGET (an ID, suffix or label), or REX_BLOCK when TARGET is
  nil. Throws when it names no terminal."
  [target]
  (let [target (or target (self-pane) (fail! "name a pane: rexctl is not running inside Rex (REX_BLOCK is unset)" {}))
        {:keys [session_id block]} (rexj "block" "inspect" target "--json")
        rows (session-panes (inspect-session session_id))]
    (or (some #(when (= (:block_id block) (:pane %)) %) rows)
        (fail! (str target " is not a terminal pane in a tab") {:pane (:block_id block)}))))

(defn checked
  "ROW, or throws when FORCE is false and guard objects."
  [row force]
  (if-let [why (and (not force) (guard row (self-pane)))]
    (fail! (str "refusing " (:pane row) ": " why "; pass --force only when the person asked for it") {:pane (:pane row)})
    row))

;; Verbs

(defn whoami []
  (let [w (rexj "whoami" "--json")]
    {:in_rex (some? (self-pane)) :session (System/getenv "REX_SESSION") :pane (self-pane)
     :server (or *server* (System/getenv "REX_SERVER")) :whoami w}))

(defn read-pane
  "The text of PANE: its last LINES lines of screen and scrollback with soft
  wraps joined, or the visible screen with :visible."
  [target {:keys [lines visible format]}]
  (let [{:keys [pane]} (resolve-pane target)
        fmt (or format "text")
        {:keys [exit out err]} (apply rex (concat ["capture" "-b" pane "--format" fmt]
                                                  (when-not visible ["--trim" "--unwrap"])))]
    (when-not (zero? exit) (fail! (str/trim err) {:pane pane}))
    (if visible
      (tail-lines (str/replace out #"\n$" "") (:rows (block-call pane "size" {})))
      (tail-lines out (or lines 80)))))

(defn send-text [target text {:keys [force]}]
  (let [{:keys [pane]} (checked (resolve-pane target) force)
        {:keys [exit err]} (rex "send" "-b" pane text)]
    (when-not (zero? exit) (fail! (str/trim err) {:pane pane}))
    {:pane pane :sent text}))

(defn send-keys [target ks {:keys [force]}]
  (let [{:keys [pane]} (checked (resolve-pane target) force)
        {:keys [exit err]} (apply rex "send-key" "-b" pane ks)]
    (when-not (zero? exit) (fail! (str/trim err) {:pane pane}))
    {:pane pane :keys (vec ks)}))

(defn now [] (System/currentTimeMillis))

(defn poll
  "Call (f) every 200 ms until it returns a truthy value or TIMEOUT ms pass
  (nil waits forever); the value, or nil on timeout."
  [timeout f]
  (let [end (when timeout (+ (now) timeout))]
    (loop []
      (or (f)
          (when (or (nil? end) (< (now) end))
            (Thread/sleep 200)
            (recur))))))

(defn wait-pane
  "Wait until PANE shows text (:match or :regex in its last :lines lines), its
  shell is back at a prompt (:prompt), its agent reports one of :status, or its
  program exits (:exit). With :new, a text match counts only beyond the lines
  that matched when the wait began, such as the echo of the command typed.
  {:pane :ok :elapsed_ms ...}; :ok false on timeout."
  [target {:keys [timeout lines prompt status exit new] :as o}]
  (let [{:keys [pane]} (resolve-pane target)
        t0 (now)
        pred (matcher o)
        text #(read-pane pane {:lines (or lines 200)})
        seen (if (and pred new) (count (hits pred (text))) 0)
        want (when status (set (str/split status #",")))
        hit (poll timeout
                  (fn []
                    (cond pred (let [hs (hits pred (text))] (when (> (count hs) seen) {:line (peek hs)}))
                          prompt (when (at-prompt? (block-call pane "process" {})) {:at_prompt true})
                          want (let [s (status-of (:records (block-call pane "program_status" {})))]
                                 (when (want (or (:state s) "none")) {:status s}))
                          exit (when-let [e (:last_exit (block-call pane "process" {}))] {:exit (:exit_code e)})
                          :else (fail! "wait needs --match, --regex, --prompt, --status or --exit" {}))))]
    (merge {:pane pane :ok (some? hit) :elapsed_ms (- (now) t0)} hit)))

(defn run-in
  "Type CMD and Enter into PANE, whose shell must be at a prompt. With :wait,
  wait for the prompt to come back (after the command was seen running, or the
  screen settled) and return the output's tail."
  [target cmd {:keys [force wait timeout lines]}]
  (let [row (checked (resolve-pane target) force)
        pane (:pane row)]
    (when-not (or force (at-prompt? (block-call pane "process" {})))
      (fail! (str pane " is running a program, not at a shell prompt; read it first, or pass --force") {:pane pane}))
    (let [before (read-pane pane {:lines 400})]
      (send-text pane (str cmd "\r") {:force true})
      (if-not wait
        {:pane pane :sent cmd}
        (let [t0 (now) busy (atom false) last-text (atom nil) stable (atom 0)
              done (poll (or timeout 600000)
                         (fn []
                           (let [p (at-prompt? (block-call pane "process" {}))
                                 txt (read-pane pane {:lines 400})]
                             (when-not p (reset! busy true))
                             (if (= txt @last-text) (swap! stable inc) (reset! stable 0))
                             (reset! last-text txt)
                             (and p (not= txt before) (or @busy (>= @stable 2))))))]
          {:pane pane :sent cmd :ok (boolean done) :elapsed_ms (- (now) t0)
           :output (since-command (read-pane pane {:lines (max 400 (or lines 0))}) cmd (or lines 40))})))))

(defn creation-flags [{:keys [cwd label focus keep-open]}]
  (concat (when cwd ["-c" cwd]) (when label ["--label" label])
          [(str "--focus=" (boolean focus))] (when keep-open ["--keep-open"])))

(defn split-pane
  "A new pane beside TARGET (REX_BLOCK by default), running CMD or a shell.
  DIR is right, left, above, below (down and up too), or chosen from the
  pane's shape. It takes focus only with :focus."
  [target {:keys [dir ratio cmd] :as o}]
  (let [{:keys [pane session tab]} (resolve-pane target)
        dir (case dir "down" "below" "up" "above" nil (split-direction (block-call pane "size" {})) dir)
        r (apply rexj (concat ["split" "-b" pane (str "--split=" dir) "--json"]
                              (when ratio ["--ratio" (str ratio)]) (creation-flags o)
                              (when (seq cmd) (cons "--" cmd))))]
    {:pane (first (:block_ids r)) :anchor pane :session session :tab tab :dir dir}))

(defn new-tab
  "A new tab (Rex window) in SESSION (the caller's, else the only one) running
  CMD or a shell, without focus unless :focus."
  [{:keys [session name cmd] :as o}]
  (let [session (or session (System/getenv "REX_SESSION")
                    (let [ss (sessions)] (when (= 1 (count ss)) (:session_id (first ss))))
                    (fail! "name a session with -s: several exist and REX_SESSION is unset" {}))
        r (apply rexj (concat ["window" "new" "-s" session] (when name [name]) ["--json"]
                              (creation-flags o) (when (seq cmd) (cons "--" cmd))))]
    {:pane (first (:block_ids r)) :tab (:window_id r) :session session}))

(defn exec-cmd
  "Run CMD in a new pane that stays open (a split beside :anchor, or a new tab
  with :tab), wait for it to exit and return its exit code and output's tail."
  [{:keys [anchor tab lines timeout] :as o}]
  (let [o (assoc o :keep-open true)
        created (if tab (new-tab o) (split-pane anchor o))
        w (wait-pane (:pane created) {:exit true :timeout (or timeout 600000)})]
    (merge created {:ok (:ok w) :exit (:exit w) :elapsed_ms (:elapsed_ms w)
                    :output (read-pane (:pane created) {:lines (or lines 40)})})))

(defn close-pane [target {:keys [force]}]
  (let [{:keys [pane session]} (checked (resolve-pane target) force)]
    (let [{:keys [exit err]} (rex "block" "close" "-s" session pane)]
      (when-not (zero? exit) (fail! (str/trim err) {:pane pane})))
    {:closed pane}))

(defn status [target]
  (let [{:keys [pane]} (resolve-pane target)
        recs (:records (block-call pane "program_status" {}))]
    {:pane pane :status (status-of recs) :records recs}))

(defn snapshot
  "Write every pane's text and each session's layout under DIR; the paths."
  [{:keys [session dir lines]}]
  (let [dir (str (or dir (fs/create-temp-dir {:prefix "rexctl-snapshot-"})))
        panes (ls {:session session})]
    (fs/create-dirs dir)
    (doseq [s (distinct (map :session panes))]
      (spit (str dir "/" (str/replace s ":" "-") ".json")
            (json/generate-string (inspect-session s) {:pretty true})))
    (let [rows (vec (for [r panes
                          :let [f (str dir "/" (str/replace (:pane r) ":" "-") ".txt")]]
                      (do (spit f (try (read-pane (:pane r) {:lines (or lines 100000)}) (catch Exception e (ex-message e))))
                          (assoc r :file f))))]
      (spit (str dir "/panes.json") (json/generate-string rows {:pretty true}))
      {:dir dir :layout (str dir "/panes.json")
       :panes (mapv #(select-keys % [:pane :label :tab_label :file]) rows)})))

(defn servers
  "This machine's server and your Rex hosts, each {:label :endpoint ...}; pass
  either to -S. When the host list is out of reach, just the connected server."
  []
  (try (let [h (rexj "hosts" "list" "--json")]
         (vec (cons (assoc (:home h) :label (or (not-empty (get-in h [:home :label])) "home"))
                    (:hosts h))))
       (catch Exception e
         [{:label "current" :endpoint (:endpoint (rexj "whoami" "--json")) :hosts_error (ex-message e)}])))

(defn app-client
  "The client to drive: CLIENT, else the only connected app."
  [client]
  (or client
      (let [apps (filter #(and (= "app" (:kind %)) (= "connected" (:connection_state %)))
                         (map #(merge % (:info %)) (:clients (rexj "client" "ls" "--json"))))]
        (case (count apps)
          1 (or (:label (first apps)) (:client_id (first apps)))
          0 (fail! "no Rex app is connected" {})
          (fail! "several Rex apps are connected; pick one with --client" {:apps (map :client_id apps)})))))

(defn mx
  "List CLIENT's actions (matching QUERY), or do action NAME with KVS."
  [{:keys [client name query kvs]}]
  (let [c (app-client client)]
    (if name
      (let [{:keys [exit out err]} (apply rex "-C" c "do" name kvs)]
        (when-not (zero? exit) (fail! (str/trim (str err out)) {:action name}))
        {:client c :did name :result (when-not (str/blank? out) (try (json/parse-string out true) (catch Exception _ (str/trim out))))})
      (let [as (rexj "-C" c "actions" "--json")
            as (if (map? as) (:actions as) as)]
        {:client c
         :actions (vec (for [a as
                             :when (or (nil? query) (str/includes? (str/lower-case (str (:name a) " " (:title a))) (str/lower-case query)))]
                         (select-keys a [:name :title :args])))}))))
