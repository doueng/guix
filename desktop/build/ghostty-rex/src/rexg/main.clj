(ns rexg.main
  "The ghostty-rex client (T207): Ghostty draws, Rex holds the sessions, and
  this bb process does the rest. It attaches to one Rex session per Ghostty
  window and shows all of it in that one terminal, as Herdr does: the tab
  bar, the panes of the active tab with their titles, the status line and
  the overlays. rexg-render (csrc/rexg-render.c, libghostty-vt) composes the
  panes; this process owns the Rex connection, keys, the model (rexg.model)
  and the sidecar (rexmux.sidecar, run in-process).

  One thread runs every state change, from a queue: keys, Rex responses and
  events, sidecar messages, renderer lines. Pane bytes go straight from each
  stream's reader to the renderer and never wait on it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rexg.keys :as keys]
            [rexg.model :as model]
            [rexg.rex :as rex]
            [rexg.screen :as screen]
            [rexg.ws :as ws]
            [rexmux.proto :as proto]
            [rexmux.sidecar :as sidecar])
  (:import [java.util.concurrent LinkedBlockingQueue]))

;; ---- configuration ----

(defn- expand-home [p] (if (and (string? p) (str/starts-with? p "~")) (str (fs/home) (subs p 1)) p))

(defn config-path []
  (or (not-empty (System/getenv "REXG_CONFIG"))
      (let [base (or (System/getenv "XDG_CONFIG_HOME") (str (fs/path (fs/home) ".config")))
            own (str (fs/path base "ghostty-rex" "config.edn"))]
        (if (fs/exists? own) own (str (fs/path base "rexmux" "config.edn"))))))

(defn shell-path
  "The configured shell, a bare name resolved on PATH: a tab Rex restores
  after a reboot runs a login shell whose PATH may lack it."
  [sh]
  (when sh
    (if (or (str/includes? sh "/") (= sh "none")) sh (or (some-> (fs/which sh) str) sh))))

(defn load-config [path]
  (let [c (sidecar/read-config path)]
    (cond-> c
      (get-in c [:startup :cwd]) (update-in [:startup :cwd] expand-home)
      true (assoc :shell (shell-path (or (not-empty (System/getenv "REXG_SHELL")) (:shell c)))))))

(defn server-env?
  "Whether a new Rex server should get variable K from the session. Rex
  starts its server from an allowlist that keeps WAYLAND_DISPLAY but drops
  the rest of the Guix session env: HYPRLAND_INSTANCE_SIGNATURE, so hyprctl
  in every pane failed (T216), and SSL_CERT_FILE, GIT_SSL_CAINFO and
  GUIX_LOCPATH, so jj git push failed certificate checks (T219). So pass
  everything but what describes this terminal or process."
  [k]
  (not (or (#{"TERM" "TERMINFO" "TERM_PROGRAM" "TERM_PROGRAM_VERSION" "PWD" "OLDPWD"
              "SHLVL" "_" "INSIDE_EMACS"} k)
           (re-find #"^(REX_|REXG_|GHOSTTY_)" k))))

(defn start-args
  "rex server start, passing the variables of ENV that server-env? keeps."
  [env]
  (into ["server" "start"] (mapcat #(when (server-env? %) ["--env" %])) (sort (keys env))))

(defn start-local!
  "Start the local Rex server, which rex server start does only when none
  answers, replacing a socket a dead one left."
  [log]
  (let [{:keys [exit out err]} (apply p/sh (or (System/getenv "REXG_REX") "rex") (start-args (System/getenv)))]
    (log "rex server start" exit (str/trim (str out err)))))

(def reload-command
  "M-x Client: reload, also rex -C ghostty-rex do mx.client.reload and make
  ghostty-rex-reload: the client starts again from the checkout's bb code and
  config on the session it showed, in the same window."
  {:id :client/reload :title "Client: reload its bb code and config" :category "help" :source :local
   :doc "Run the ghostty-rex client anew in this window, on this session, so an edit to its bb code or config.edn takes effect. Rex keeps the panes and their programs. rexg-render, the C half, needs make build NAME=ghostty-rex or make home."
   :run (fn [_ _] [[:client/reload {}]])})

(defn reload!
  "Replace this process with a new client on SESSION: the same bb, bb.edn
  and options, so newly edited code loads."
  [session config render]
  (let [bb (or (some-> (fs/which "bb") str) "bb")
        edn (System/getProperty "babashka.config")]
    (apply p/exec bb (concat (when edn ["--config" edn]) ["attach"]
                             (when session ["--session" session])
                             (when config ["--config" config])
                             (when render ["--render" render])))))

;; ---- the renderer ----

(defn- frames!
  "Writes MSGS, [type payload-bytes] pairs, in one write."
  [{:keys [^java.io.OutputStream in lock]} msgs]
  (let [buf (java.io.ByteArrayOutputStream.)]
    (doseq [[type ^bytes payload] msgs]
      (let [n (alength payload)]
        (.write buf (byte-array [(unchecked-byte (int type)) (unchecked-byte (bit-shift-right n 24)) (unchecked-byte (bit-shift-right n 16))
                                 (unchecked-byte (bit-shift-right n 8)) (unchecked-byte n)]))
        (.write buf payload)))
    (locking lock
      (.write in (.toByteArray buf))
      (.flush in))))

(defn- frame! [r type ^bytes payload] (frames! r [[type payload]]))

(defn- str-msg [type s] [type (.getBytes (str s) "UTF-8")])
(defn- send-str! [r type s] (frames! r [(str-msg type s)]))

(defn- clean [s] (str/replace (str s) #"[\t\n\r\u0000-\u001f]" " "))

(defn spans-text
  "Spans as rexg-render's overlay lines: ROW COL FG BG FLAGS, a tab, the text."
  [spans]
  (str/join "\n" (for [{:keys [row col fg bg flags text]} spans]
                     (str row " " col " " (or fg "-") " " (or bg "-") " " flags "\t" (clean text)))))

;; ---- the app ----

(defn- base64 [^String s] (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))

(defn run
  "Run the client. OPTS: :render (rexg-render's path), :config, :label (the
  Rex client label, for rex -C), :session (show this one instead of the
  startup session)."
  [{:keys [render config label session]}]
  (let [;; The client label Rex knows us by, for rex -C LABEL do mx.tab.select_N.
        client-label label
        cfg (assoc (load-config (or config (config-path))) :extra-commands [reload-command])
        servers (mapv #(select-keys % [:label :address]) (or (seq (:servers cfg)) [{:label "local" :address nil}]))
        q (LinkedBlockingQueue.)
        put! (fn [ev] (.put q ev))
        rproc (p/process {:in :stream :out :inherit :err :stream} render)
        r {:in (:in rproc) :lock (Object.)}
        app (atom {:m (model/new-model servers {:startup (:startup cfg) :shell (:shell cfg) :prefer-session session
                                                 :env (into {} (keep (fn [k] (when-let [v (System/getenv k)] [k v]))) ["REX_SERVER" "HOME"])})
                   :conns {} :tags {} :streams {} :sizes {} :wh [80 24] :text-reqs {} :next-req 0
                   :last-draw nil :title nil :sidecar (sidecar/initial-state cfg) :tx proto/sender})
        log (fn [& xs] (when-let [f (System/getenv "REXG_LOG")] (spit f (str (str/join " " xs) "\n") :append true)))
        ;; Its stderr is this terminal, so the sidecar logs to REXG_LOG.
        sc-io (assoc (sidecar/make-io cfg "ghostty-rex") :log #(log "sidecar" %))
        sc-exec (java.util.concurrent.Executors/newSingleThreadExecutor)]
    ;; Renderer lines: size, pane readiness and modes, text replies.
    (future
      (with-open [rd (io/reader (:err rproc))]
        (loop [lines (line-seq rd)]
          (when-let [l (first lines)]
            (if (str/starts-with? l "t ")
              (let [[_ req c] (str/split l #" ")
                    body (take-while #(not= "." %) (rest lines))]
                (put! [:text req (parse-long c) (vec body)])
                (recur (drop (inc (count body)) (rest lines))))
              (do (put! [:render-line l]) (recur (rest lines))))))
        (put! [:quit "renderer exited"])))
    ;; Keys from the terminal.
    (future
      (let [rd (java.io.InputStreamReader. System/in "UTF-8") buf (char-array 4096)]
        (loop [rest-s ""]
          (let [n (.read rd buf)]
            (if (neg? n)
              (put! [:quit "terminal closed"])
              (let [s (str rest-s (String. buf 0 n))
                    _ (log "input" (pr-str s))
                    [evs more] (keys/read-input s)]
                (doseq [e evs] (put! e))
                ;; An unfinished escape sequence waits for the rest briefly.
                (recur more)))))))
    (letfn [(sidecar-send! [msg]
              (let [[tx m] (proto/stamp (:tx @app) msg)]
                (swap! app assoc :tx tx)
                (.submit sc-exec ^Runnable
                         (fn []
                           (try
                             (let [[s msgs] (sidecar/settle! sc-io (:sidecar @app) #(sidecar/handle % sc-io m))]
                               (swap! app assoc :sidecar s)
                               (doseq [o msgs] (put! [:sidecar o])))
                             (catch Throwable e
                               (log "sidecar" (ex-message e))
                               (put! [:sidecar {:op :log :text (str "sidecar: " (ex-message e))}])
                               ;; A request still ends with one answer (rexmux.qnt's link rule).
                               (when (:id m) (put! [:sidecar {:op :reply :id (:id m) :error (str "sidecar: " (ex-message e))}]))))))))
            (rex-send! [srv method payload tag]
              (if-let [c (get-in @app [:conns srv])]
                (try (let [id ((:send! c) method payload)]
                       (swap! app assoc-in [:tags srv id] tag))
                     (catch Exception e (swap! app update :m assoc :echo (str method ": " (ex-message e)))))
                (swap! app update :m assoc :echo (str (get-in @app [:m :servers srv :label]) " is not connected"))))
            (close-stream! [block]
              (when-let [close (get-in @app [:streams block])]
                (close)
                (swap! app update :streams dissoc block)
                (send-str! r \x block)))
            (perform! [outs]
              (doseq [[kind & xs :as o] outs]
                (when-not (= "client.keymap" (nth o 2 nil)) (log "out" (pr-str o)))
                (case kind
                  :sidecar (sidecar-send! (first xs))
                  :rex (apply rex-send! xs)
                  :open-stream (let [[srv block ticket] xs
                                     sock (rex/socket-path (get-in @app [:m :servers srv :address]))]
                                 (close-stream! block)
                                 (send-str! r \o block)
                                 ;; A new pane needs the layout again to know its place.
                                 (swap! app assoc-in [:last-draw :layout] nil)
                                 (try
                                   (let [prefix (.getBytes (str block "\u0000") "UTF-8")
                                         close (rex/open-stream! sock ticket
                                                                 (fn [^bytes b]
                                                                   (let [out (byte-array (+ (alength prefix) (alength b)))]
                                                                     (System/arraycopy prefix 0 out 0 (alength prefix))
                                                                     (System/arraycopy b 0 out (alength prefix) (alength b))
                                                                     (frame! r \d out)))
                                                                 (fn [msg]
                                                                   ;; A size goes to rexg-render from this thread,
                                                                   ;; in order with the bytes around it (T225).
                                                                   (if (= "resize" (:type msg))
                                                                     (send-str! r \r (str block " " (:cols msg 80) " " (:rows msg 24)))
                                                                     (put! [:stream-msg block msg])))
                                                                 (fn [why] (put! [:stream-closed block why])))]
                                     (swap! app assoc-in [:streams block] close))
                                   (catch Exception e (put! [:stream-closed block (ex-message e)]))))
                  :close-stream (close-stream! (first xs))
                  ;; OSC 52: Ghostty puts it on the clipboard. Tests also get a file.
                  :clipboard (do (send-str! r \w (str "\u001b]52;c;" (base64 (first xs)) "\u0007"))
                                 (when-let [f (System/getenv "REXG_CLIPBOARD_FILE")] (spit f (first xs))))
                  :open (if-let [f (System/getenv "REXG_OPEN_FILE")]
                          (spit f (first xs))
                          (p/process ["xdg-open" (first xs)] {:out :discard :err :discard}))
                  :paste (let [[srv session block text] xs
                               text (or text (let [{:keys [exit out]} (p/sh "wl-paste" "--no-newline")] (when (zero? exit) out)))]
                           (when (seq text)
                             (rex-send! srv "block.event" {:session_id session :block_id block
                                                            :event {:type "paste" :payload {:text text}}} [:ignore])))
                  ;; rexg-render answers with the block's screen, as [:text req cols lines].
                  :read-text (let [[what block] xs
                                   n (inc (:next-req @app)) req (str "t" n)]
                               (swap! app #(-> % (assoc :next-req n) (assoc-in [:text-reqs req] what)))
                               (send-str! r \t (str req " " block)))
                  :scroll (let [[block lines] xs] (send-str! r \s (str block " " lines)))
                  :quit (put! [:quit "quit"])
                  :reload (put! [:quit "reload"])
                  ;; Each answer names its attempt; rexg.model drops a stale one.
                  :connect (let [[srv gen] xs
                                 sock (rex/socket-path (get-in @app [:m :servers srv :address]))
                                 c (try (rex/connect! sock client-label (fn [msg] (put! [:rex srv gen msg])))
                                        (catch Exception e (or (ex-message e) "no server")))]
                             (put! (if (map? c) [:connected srv gen c] [:connect-failed srv gen c])))
                  :conn/adopt (let [[srv gen] xs
                                    c (get-in @app [:attempts [srv gen]])]
                                (swap! app #(-> % (update :attempts dissoc [srv gen])
                                                (assoc-in [:conns srv] (assoc c :gen gen))
                                                (assoc-in [:tags srv] {})))
                                (follow-events! srv))
                  :conn/close (let [[srv gen] xs]
                                (some-> (get-in @app [:attempts [srv gen]]) :ws ws/close!)
                                (swap! app update :attempts dissoc [srv gen]))
                  :conn/drop (let [[srv gen] xs
                                   c (get-in @app [:conns srv])]
                               (when (= gen (:gen c))
                                 (try (ws/close! (:ws c)) (catch Exception _))
                                 (swap! app update :conns dissoc srv)))
                  :start-local-server (start-local! log)
                  :scroll-bottom (send-str! r \s (str (first xs) " bottom"))
                  :after (let [[ms ev] xs] (future (Thread/sleep ms) (put! ev)))
                  nil)))
            (follow-events! [srv]
              ;; Agents' status from OSC 7501, as the rexmux sidecar follows
              ;; it: every event of the server, on a control connection of its
              ;; own, so the model's connection keeps only its session's events
              ;; and no other client's perform requests. It ran rex events
              ;; --all, a 20 MB process, until T225. Opened again on each
              ;; connect, since it ends with the server.
              (future
                (try
                  (let [deliver! (fn [ev]
                                   (.submit sc-exec ^Runnable
                                            (fn [] (try (let [ev {:op :rex/event :event ev :server (get-in @app [:m :servers srv :label])}
                                                              [s msgs] (sidecar/settle! sc-io (:sidecar @app) #(sidecar/deliver % sc-io ev))]
                                                          (swap! app assoc :sidecar s)
                                                          (doseq [o msgs] (put! [:sidecar o])))
                                                        (catch Throwable e (log "sidecar event" (ex-message e)))))))
                        c (rex/connect! (rex/socket-path (get-in @app [:m :servers srv :address])) (str client-label "-events")
                                        (fn [[k payload]] (when (= :event k) (deliver! payload))))]
                    ((:send! c) "session.attach_all" {:event_scope "all"})
                    (let [[old] (swap-vals! app assoc-in [:events srv] c)]
                      (some-> (get-in old [:events srv]) :ws ws/close!)))
                  (catch Exception e (log "events" (ex-message e))))))
            (step! [f & args]
              (let [[m outs] (apply f (:m @app) args)]
                (swap! app assoc :m m)
                (perform! outs)))
            (sync-sizes! []
              (let [{:keys [m wh sizes]} @app
                    [w h] wh
                    sid (model/session-id m)]
                (when sid
                  (doseq [[block [c rr]] (screen/content-sizes m w h) :when (not= [c rr] (get sizes block))]
                    (swap! app assoc-in [:sizes block] [c rr])
                    (rex-send! (:current m) "session.own_block" {:session_id sid :block_ids [block] :mode "take"} [:ignore])
                    (rex-send! (:current m) "com.superlogical.terminal.resize"
                               {:session_id sid :block_id block :args {:columns c :rows rr}} [:ignore])))))
            (draw! []
              (let [{:keys [m wh last-draw title]} @app
                    [w h] wh
                    {:keys [layout spans cursor] :as d} (screen/render m w h (:panes m))
                    lay (str/join "\n" (for [{:keys [block x y] tw :w th :h} layout] (str block " " x " " y " " tw " " th)))
                    sp (spans-text spans)
                    cur (str/join " " (map #(if (keyword? %) (name %) %) cursor))]
                ;; One view, ended by k: rexg-render draws none of it before
                ;; it has all of it (T223). A large view reaches the pipe in
                ;; pieces, so reading what is there is not enough.
                (let [msgs (cond-> []
                             (not= lay (:layout last-draw)) (conj (str-msg \l lay))
                             (not= sp (:spans last-draw)) (conj (str-msg \v sp))
                             (not= cur (:cursor last-draw)) (conj (str-msg \c cur)))]
                  (when (seq msgs) (frames! r (conj msgs (str-msg \k "")))))
                (when (not= (:title d) title)
                  (send-str! r \w (str "\u001b]2;" (clean (:title d)) "\u0007"))
                  (swap! app assoc :title (:title d)))
                (swap! app assoc :last-draw {:layout lay :spans sp :cursor cur})))
            (mouse! [{:keys [x y] :as ev}]
              (let [{:keys [m wh]} @app
                    [w h] wh]
                (step! model/mouse (screen/pane-at m w h x y) ev)))
            (handle! [[kind & xs]]
              (case kind
                :key (let [k (first xs)]
                       (log "key" (pr-str k) (keys/chord k))
                       (step! model/key k))
                :paste (let [m (:m @app)]
                         (when (and (model/session-id m) (model/focused-block m))
                           (perform! [[:paste (:current m) (model/session-id m) (model/focused-block m) (first xs)]])))
                :mouse (mouse! (first xs))
                :focus nil
                :rex (let [[srv gen [k & more]] xs
                           live? (= gen (get-in @app [:conns srv :gen]))]
                       (case k
                         :response (let [[id result] more
                                         tag (when live? (get-in @app [:tags srv id]))]
                                     (when tag
                                       (swap! app update-in [:tags srv] dissoc id)
                                       (step! model/rex-response tag result)))
                         :event (when live? (step! model/rex-event srv (first more)))
                         :closed (do (log "lost" srv gen (first more))
                                     (step! model/closed srv gen (first more)))))
                :sidecar (step! model/sidecar (first xs))
                :stream-msg (let [[block msg] xs]
                              (case (:type msg)
                                "child_exited" (step! model/child-exited block (:exit_code msg 0))
                                nil))
                :stream-closed (let [[block why] xs]
                                 (when (get-in @app [:streams block])
                                   (swap! app update :streams dissoc block)
                                   (send-str! r \x block)
                                   (swap! app update :m assoc :echo (str "stream closed: " why))
                                   (step! model/stream-closed block)))
                :render-line (let [[w a b] (str/split (first xs) #" ")]
                               (log "render" (first xs))
                               (case w
                                 "size" (do (swap! app assoc :wh [(parse-long a) (parse-long b)] :last-draw nil)
                                            (send-str! r \f ""))
                                 "mode" (let [bits (parse-long b)]
                                          (step! model/renderer-mode a bits))
                                 "err" (when (get-in @app [:streams a])
                                         (swap! app update :m assoc :echo (str "pane " a ": " b)))
                                 nil))
                :text (let [[req cols lines] xs
                            what (get-in @app [:text-reqs req])]
                        (swap! app update :text-reqs dissoc req)
                        (when what (step! model/text-read what lines cols)))
                ;; The connection is rexg.model's state machine; this only connects.
                :connect (step! model/connect (first xs))
                ;; The model adopts the attempt or closes it ([:conn/adopt] or [:conn/close]).
                :connected (let [[srv gen c] xs]
                             (swap! app assoc-in [:attempts [srv gen]] c)
                             (step! model/connected srv gen (:client-id c)))
                :connect-failed (let [[srv gen why] xs]
                                  (log "connect failed" srv gen why)
                                  (step! model/connect-failed srv gen why))
                nil))]
      (doseq [i (range (count servers))]
        (put! [:connect i]))
      ;; The sidecar says ready; the model answers hello.
      (put! [:sidecar {:op :ready}])
      (loop []
        (let [ev (.take q)]
          (if (= :quit (first ev))
            (do (log "quit" (second ev))
                (doseq [[_ close] (:streams @app)] (try (close) (catch Exception _)))
                (doseq [[_ c] (:events @app)] (try (ws/close! (:ws c)) (catch Exception _)))
                ;; Close the control connections, so Rex drops this client at once:
                ;; a reload's exec must not leave a second ghostty-rex for rex -C.
                (doseq [[_ c] (:conns @app)] (try (ws/close! (:ws c)) (catch Exception _)))
                (try (frame! r \q (byte-array 0)) (catch Exception _))
                (deref rproc 2000 nil)
                (when (= "reload" (second ev))
                  ;; The startup session needs no --session, which would also
                  ;; turn off reviving its tabs after a later server restart.
                  (let [m (:m @app)]
                    (reload! (when-not (= (:label (model/view m)) (get-in m [:startup :label])) (model/session-id m))
                             config render)))
                (shutdown-agents)
                (System/exit 0))
            (do (try (handle! ev)
                     ;; Batch: handle what else is queued before drawing once.
                     (loop [] (when-let [e (.poll q)]
                                (if (= :quit (first e)) (.put q e) (do (handle! e) (recur)))))
                     (sync-sizes!)
                     (draw!)
                     (catch Throwable e
                       (log "error" (pr-str ev) (ex-message e))
                       (swap! app update :m assoc :echo (str "error: " (ex-message e)))))
                (recur))))))))

(defn -main [& args]
  (let [opts (loop [[a b & more :as as] args o {}]
               (cond (empty? as) o
                     (= a "--session") (recur more (assoc o :session b))
                     (= a "--config") (recur more (assoc o :config b))
                     (= a "--render") (recur more (assoc o :render b))
                     :else (recur (rest as) o)))]
    (run (merge {:render (or (System/getenv "REXG_RENDER") "rexg-render")
                 :label (or (System/getenv "REXG_LABEL") "ghostty-rex")}
                opts))))
