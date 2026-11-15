(ns bbh.super.notify
  "Desktop notifications for what waits on the person: an open ask, a Worker
  that needs a human or whose session ended, a long Worker that finished. The
  planning is pure, over controller states, so the website can reuse it for
  push; the effects run notify-send, busctl, emacsclient and hyprctl."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.super.emacs :as emacs]
            [bbh.sysenv :as sysenv]
            [bbh.super.model :as model]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-policy
  "What policy.edn says about notifications when it says nothing."
  {:do-not-disturb false :notify-delay-seconds 5 :notify-long-minutes 10 :notify-expire-ms 5000})

(defn waiting
  "Everything in STATE that waits on the person now, by key: [:ask id],
  [:needs-human worker] (critical) and [:session-ended worker]. Each has :at,
  the epoch ms it began to wait, when the log says, and :news-at when it
  became news later than that."
  [state]
  (merge
   (into {} (for [a (vals (:asks state)) :when (= :open (:state a))]
              [[:ask (:id a)] {:thread (:thread a) :ask (:id a) :text (:text a) :urgency :normal
                               :options (mapv :label (:options a)) :at (:opened-at a)}]))
   (into {} (for [w (vals (:workers state))
                  :let [t (get-in state [:threads (:thread w)])
                        latest? (= (:id w) (or (:worker t) (peek (:history t))))
                        late (model/overdue w)
                        by-health (when (model/active-states (:state w))
                                    (#{:needs-human :session-ended} (get-in w [:health :classification])))
                        kind (cond by-health by-health
                                   late :needs-human
                                   (and (= :failed (:state w)) latest? (not (:resolved-at t))) :needs-human)]
                  :when kind]
              [[kind (:id w)] (cond-> {:thread (:thread w) :worker (:id w)
                                       :urgency (if (= :needs-human kind) :critical :normal)
                                       :at (if by-health (get-in w [:health :since]) (:state-at w))
                                       :text (or (when by-health (get-in w [:health :problem])) late (:reason w)
                                                 (if (= :needs-human kind) "It needs your help." "Its session ended."))}
                                (and late (not by-health) (:state-at w))
                                (assoc :news-at (+ (:state-at w) (model/deadlines (:state w) 0))))]))))

(defn- archived?
  "Whether thread T was archived and has had no milestone since."
  [{:keys [resolved-at milestone-seq]}]
  (boolean (and resolved-at (<= (or milestone-seq 0) resolved-at))))

(defn- finished
  "The Workers of STATE that ended :done after running at least LONG-MS and
  whose thread is still unread, as [:finished worker] items."
  [state long-ms]
  (into {} (for [w (vals (:workers state))
                 :let [t (get-in state [:threads (:thread w)])]
                 :when (and (= :done (:state w)) (:started-at w) (:state-at w) (>= (- (:state-at w) (:started-at w)) long-ms)
                            (some-> t model/unread?) (not (archived? t)))]
             [[:finished (:id w)] {:thread (:thread w) :worker (:id w) :urgency :normal :at (:state-at w)
                                   :text (or (get-in state [:threads (:thread w) :summary]) (str (:id w) " finished."))}])))

(defn unnotified
  "The items of STATE the person has not been told of, under POLICY: what
  waits, and long finishes, that :notified does not hold at the time each
  began. So a re-ask (a new ask id) or a Worker that needs help again counts
  again. An item whose time the log does not say is left out."
  [state policy]
  (let [long-ms (* 60000 (:notify-long-minutes (merge default-policy policy)))]
    (into {} (filter (fn [[k {:keys [at]}]] (and at (not= at (get-in state [:notified k])))))
          (merge (waiting state) (finished state long-ms)))))

(defn current?
  "Whether item KEY still waits on the person in STATE: its key is still
  waiting, or for a finish, its thread is still unread."
  [state now-waiting [kind :as k] item]
  (if (= :finished kind)
    (let [t (get-in state [:threads (:thread item)])]
      (boolean (and (some-> t model/unread?) (not (archived? t)))))
    (contains? now-waiting k)))

(def ^:private max-options 3)

(defn- item-line [{:keys [text options]}]
  (str text (when (seq options)
              (str "  [" (str/join " | " (take max-options options))
                   (when (> (count options) max-options) " | …") "]"))))

(defn- urgency [items]
  (if (some #(= :critical (:urgency %)) items) :critical :normal))

(defn- headline [state thread ks]
  (let [title (get-in state [:threads thread :title])]
    (str thread (if (every? #(= :finished (first %)) ks) " finished" " needs you")
         (when title (str ": " title)))))

(defn plan
  "The notifications for PENDING ({key item}) on STATE: items no longer
  waiting dropped; one per thread, keyed by its id, when one thread remains;
  else one :burst for all. Each is {:key :summary :body :urgency :keys
  :thread :ask}, where :keys are the item keys it shows and :ask the first
  open ask to land on."
  [pending state]
  (let [now-waiting (waiting state)
        live (into {} (filter (fn [[k item]] (current? state now-waiting k item))) pending)
        order (fn [[k item]] [(:thread item) (str k)])
        by-thread (group-by (comp :thread val) (sort-by order live))
        for-thread (fn [thread kvs]
                     (let [items (map val kvs)]
                       {:thread thread :ask (some :ask items) :keys (set (map key kvs))
                        :urgency (urgency items)
                        :summary (headline state thread (map key kvs))
                        :body (str/join "\n" (map item-line items))}))]
    (case (count by-thread)
      0 []
      1 (let [[thread kvs] (first by-thread)] [(assoc (for-thread thread kvs) :key thread)])
      (let [parts (map (fn [[thread kvs]] (for-thread thread kvs)) (sort-by (comp model/id-order key) by-thread))]
        [{:key :burst :thread (:thread (first parts)) :ask (:ask (first parts))
          :keys (set (mapcat :keys parts)) :urgency (urgency parts)
          :summary (str "bbh: " (count parts) " threads need you")
          :body (str/join "\n" (map #(str (:thread %) " " (first (str/split-lines (:body %)))) parts))}]))))

(defn stale
  "The keys of SHOWN ({key {:keys ...}}) none of whose items still wait in STATE."
  [shown state]
  (let [now-waiting (waiting state)]
    (vec (for [[k {:keys [keys items]}] shown
               :when (not-any? #(current? state now-waiting % (get items %)) keys)]
           k))))

(defn due
  "The items of STATE to notify at NOW under POLICY: all the unnotified ones
  once the oldest has waited :notify-delay-seconds, so a burst is one
  notification."
  [state now policy]
  (let [pending (unnotified state policy)
        delay-ms (* 1000 (:notify-delay-seconds (merge default-policy policy)))]
    (if (and (seq pending) (>= (- now (apply min (map (comp :at val) pending))) delay-ms)) pending {})))

(defn timer
  "The command that records item KEY, due since AT, as notified."
  [[kind id :as key] at]
  {:id (str "timer-notify-" (name kind) "-" id "-" at) :command :timer-fired! :args {:kind :notify :subject key :since at}})

(defn notify-send-args
  "notify-send's argv for notification N, replacing REPLACE-ID when given. It
  closes after its :expire-ms, prints its id, then the action a click invokes."
  [{:keys [summary body urgency expire-ms] :or {expire-ms (:notify-expire-ms default-policy)}} replace-id]
  (cond-> ["notify-send" "--app-name=bbh" (str "--urgency=" (name urgency)) (str "--expire-time=" expire-ms)
           "--print-id" "--action=default=Open" "--wait"]
    replace-id (conj (str "--replace-id=" replace-id))
    true (conj summary body)))

(defn goto-form
  "The elisp that shows the Inbox at THREAD and ASK."
  [thread ask]
  (str "(eng/bbh-inbox-goto " (emacs/elisp thread) " " (emacs/elisp ask) ")"))

(defn override-file
  "Where the Inbox's do-not-disturb key keeps its choice, which wins over
  policy.edn's :do-not-disturb while it exists. Not policy.edn, which is a
  tracked file in the guix checkout."
  []
  (fs/path (or (sysenv/getenv "XDG_STATE_HOME") (fs/expand-home "~/.local/state")) "bbh" "notify.edn"))

(defn overrides
  "The Inbox key's choices, {:do-not-disturb bool}, or {} when it made none."
  []
  (let [f (override-file)]
    (or (when (fs/exists? f) (try (select-keys (edn/read-string (slurp (str f))) [:do-not-disturb]) (catch Exception _ nil))) {})))

(defn toggle-dnd!
  "Flip do-not-disturb from what POLICY and the overrides say now, and
  return the new value."
  [policy]
  (let [on (not (:do-not-disturb (merge default-policy policy (overrides))))
        f (override-file)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (pr-str {:do-not-disturb on}))
    on))

(def replay-grace-ms
  "How long before a controller's start an item may have begun and still be
  shown by it: one that began earlier is history, which a restart replays
  from the log, so it is recorded as notified without a notification."
  60000)

(defn new-notifier
  "A notifier over EFFECTS {:show! :close!}. With FILE, what it shows is kept
  there, so a restarted controller can `resume!` it. With LIVE-FROM, epoch
  ms, it shows only items that began then or later; the controller passes its
  start less `replay-grace-ms`."
  ([effects] (new-notifier effects nil))
  ([effects file] (new-notifier effects file nil))
  ([effects file live-from]
   {:effects effects :file file :live-from live-from :shown (atom {}) :seen (atom nil)}))

(defn- shown-id [entry]
  (some-> entry :handle :id (deref 2000 nil)))

(defn- save! [{:keys [file shown]}]
  (when file
    (spit (str file) (pr-str (update-vals @shown #(-> % (dissoc :handle) (assoc :id (shown-id %))))))))

(defn- on-screen?
  "Whether ENTRY, as saved, may still be on screen at NOW: it expires
  :expire-ms after :shown-at. One saved before :shown-at was kept is not."
  [{:keys [shown-at expire-ms] :or {expire-ms (:notify-expire-ms default-policy)}} now]
  (boolean (and shown-at (pos? expire-ms) (< now (+ shown-at expire-ms)))))

(defn- handle-of [id]
  {:id (doto (promise) (deliver id))})

(defn resume!
  "Take over what a previous controller showed, from the notifier's file,
  showing nothing: a restart is not news. Each that still waits in STATE is
  kept, so its thread's next notification replaces it and lists it; the rest
  are closed. Only an entry still on screen at NOW keeps its daemon id, since
  the daemon reuses the ids of expired ones."
  ([n state] (resume! n state (System/currentTimeMillis)))
  ([{:keys [effects file shown] :as n} state now]
   (when (and file (fs/exists? file))
     (let [before (try (edn/read-string (slurp (str file))) (catch Exception _ {}))
           now-waiting (waiting state)]
       (doseq [[k entry] before
               :let [live (on-screen? entry now)
                     prev (when live (handle-of (:id entry)))
                     entry (dissoc entry :id)]]
         (if (some #(current? state now-waiting % (get (:items entry) %)) (:keys entry))
           (swap! shown assoc k (cond-> entry prev (assoc :handle prev)))
           (when prev ((:close! effects) {:handle prev}))))
       (save! n)))))

(defn stop!
  "End the notify-send processes waiting for clicks; the notifications stay,
  for the next controller to resume."
  [{:keys [shown]}]
  (doseq [entry (vals @shown)] (some-> entry :handle :proc p/destroy)))

(defn tick!
  "Once a dispatcher pass: show the plan for what is due on STATE at NOW, or
  nothing under :do-not-disturb; when STATE changed, close shown notifications
  that no longer wait. The Inbox key's overrides win over POLICY. Returns the
  due items, for the caller to record as notified."
  [{:keys [effects shown seen live-from] :as notifier} state now policy]
  (let [due (due state now policy)
        news (into {} (remove #(and live-from (< (or (:news-at (val %)) (:at (val %))) live-from))) due)]
    (when (seq news)
      (let [{dnd :do-not-disturb expire-ms :notify-expire-ms} (merge default-policy policy (overrides))
            threads (set (map :thread (vals news)))
            p (merge (into {} (comp (filter #(threads (key %))) (mapcat (comp :items val))) @shown) news)]
        (when-not dnd
          (doseq [n (map #(assoc % :expire-ms expire-ms) (plan p state))]
            (when (= :burst (:key n))
              (doseq [t (distinct (map :thread (vals p))) :when (@shown t)]
                ((:close! effects) (@shown t))
                (swap! shown dissoc t)))
            (let [prev (@shown (:key n))
                  handle ((:show! effects) n (:handle prev))]
              (swap! shown assoc (:key n) (assoc n :handle handle :shown-at now :items (select-keys p (:keys n))))))
          (save! notifier))))
    (when-not (identical? state @seen)
      (reset! seen state)
      (when-let [gone (seq (stale @shown state))]
        (doseq [k gone]
          (when (:handle (@shown k)) ((:close! effects) (@shown k)))
          (swap! shown dissoc k))
        (save! notifier)))
    due))

(defn- uid []
  (some->> (slurp "/proc/self/status") (re-find #"(?m)^Uid:\s+(\d+)") second))

(defn- runtime-dir []
  (or (System/getenv "XDG_RUNTIME_DIR") (str "/run/user/" (uid))))

(defn- hyprland-signature
  "The live Hyprland instance: the newest under $XDG_RUNTIME_DIR/hypr with a
  socket, since a service's environment can name one that has gone."
  []
  (some->> (fs/glob (fs/path (runtime-dir) "hypr") "*/.socket.sock" {:hidden true})
           seq (sort-by #(.toMillis (fs/last-modified-time (fs/parent %)))) last fs/parent fs/file-name str))

(defn desktop-env
  "What notify-send, busctl and hyprctl need, whatever the service's environment."
  []
  (let [rt (runtime-dir)]
    (cond-> {"XDG_RUNTIME_DIR" rt
             "DBUS_SESSION_BUS_ADDRESS" (or (System/getenv "DBUS_SESSION_BUS_ADDRESS") (str "unix:path=" rt "/bus"))}
      (hyprland-signature) (assoc "HYPRLAND_INSTANCE_SIGNATURE" (hyprland-signature)))))

(defn- run [& args]
  (try (apply p/shell {:out :string :err :string :continue true :extra-env (desktop-env)} args)
       (catch Exception e {:exit -1 :err (ex-message e)})))

(defn- parent-pid [pid]
  (try (some-> (slurp (str "/proc/" pid "/stat")) (str/split #"\) ") second (str/split #" ") second parse-long)
       (catch Exception _ nil)))

(defn- tty-pids
  "The pids whose standard input is TTY."
  [tty]
  (for [d (fs/list-dir "/proc")
        :let [pid (parse-long (str (fs/file-name d)))]
        :when (and pid (= tty (try (str (fs/read-link (fs/path d "fd" "0"))) (catch Exception _ nil))))]
    pid))

(defn window-pid
  "Of CLIENTS (hyprctl clients), the pid of the window whose process is
  among ANCESTORS (pid -> parent pid) of PIDS; else the Herdr Ghostty window's,
  where a Herdr pane's tty belongs to the Herdr server; else nil."
  [clients pids parent]
  (let [windows (set (map :pid clients))
        up (fn [pid] (take-while some? (take 64 (iterate parent pid))))]
    (or (some #(some windows (up %)) pids)
        (some #(when (re-find #"herdr" (str (:class %))) (:pid %)) (sort-by :focusHistoryID clients)))))

(defn- lua-string [s]
  (str "\"" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn focus-dispatch
  "The hyprctl dispatch that focuses the window of PID; Hyprland's Lua config
  takes only Lua dispatchers."
  [pid]
  (str "hl.dsp.focus({ window = " (lua-string (str "pid:" pid)) " })"))

(defn exec-dispatch
  "The hyprctl dispatch that runs shell command CMD in Hyprland's environment."
  [cmd]
  (str "hl.dsp.exec_cmd(" (lua-string cmd) ")"))

(defn click!
  "Show the Inbox at notification N's thread and ask in the bbh-super Emacs
  and focus its window; with no Emacs frame open, open a terminal on it."
  [{:keys [thread ask]}]
  (let [form (goto-form thread ask)
        tty (try (emacs/read-string* (emacs/call! "eng/bbh-inbox-goto" thread ask)) (catch Exception _ "nil"))]
    (if (or (str/blank? tty) (= "nil" tty))
      (run "hyprctl" "dispatch" (exec-dispatch (str "ghostty -e emacsclient -s " (emacs/server) " -t --eval '" form "'")))
      (let [clients (try (json/parse-string (:out (run "hyprctl" "-j" "clients")) true) (catch Exception _ []))]
        (when-let [pid (window-pid clients (tty-pids tty) parent-pid)]
          (run "hyprctl" "dispatch" (focus-dispatch pid)))))))

(defn show!
  "Show N with notify-send, replacing PREV, the handle show! returned for its key.
  Returns {:proc :id}, :id a promise of the daemon's id. A click runs ON-CLICK."
  ([n prev] (show! n prev click!))
  ([n prev on-click]
   (let [replace-id (when prev (deref (:id prev) 2000 nil))
         _ (some-> prev :proc p/destroy)
         proc (apply p/process {:out :pipe :err :string :extra-env (desktop-env)} (notify-send-args n replace-id))
         id (promise)]
     (doto (Thread. #(try (with-open [r (io/reader (:out proc))]
                            (deliver id (some-> (.readLine r) str/trim parse-long))
                            (when (= "default" (some-> (.readLine r) str/trim))
                              (on-click n)))
                          (catch Exception e (deliver id nil)
                            (binding [*out* *err*] (println "bbh super: notification failed:" (ex-message e))))))
       (.setDaemon true) (.start))
     {:proc proc :id id})))

(defn close!
  "Close notification SHOWN, an entry of the notifier's shown map."
  [{:keys [handle]}]
  (when-let [id (some-> handle :id (deref 2000 nil))]
    (run "busctl" "--user" "call" "org.freedesktop.Notifications" "/org/freedesktop/Notifications"
         "org.freedesktop.Notifications" "CloseNotification" "u" (str id)))
  (some-> handle :proc p/destroy))

(def desktop {:show! show! :close! close!})

(defn manual-check!
  "Send one real notification through show!, as the controller does, and
  report a click, which opens the Inbox at THREAD and ASK when given, or
  that it was left alone for WAIT-MS. URGENCY is :normal or :critical."
  [thread ask wait-ms urgency]
  (let [outcome (promise) clicking (volatile! false)
        n {:summary (str (or thread "T0") " needs you: bbh notification check")
           :body "Click to open the Inbox here.  [Yes | No]" :urgency urgency :thread thread :ask ask}
        h (show! n nil (fn [n] (vreset! clicking true) (try (when thread (click! n)) (finally (deliver outcome :clicked)))))]
    (println "sent notification" (deref (:id h) 5000 "(no id: is a notification daemon running?)"))
    (let [start (System/currentTimeMillis)]
      (future (.waitFor ^Process (:proc (:proc h)))
              (let [ms (- (System/currentTimeMillis) start)]
                (Thread/sleep 200)
                (when-not @clicking (deliver outcome ms))))
      (let [o (deref outcome wait-ms nil)]
        (cond (= :clicked o) (println "clicked" (if thread (str "; the Inbox should show " thread (when ask (str " at " ask))) ""))
              o (println "closed unclicked after" (format "%.1f" (/ o 1000.0)) "s")
              :else (do (println "not clicked within" (quot wait-ms 1000) "s; closing it")
                        (close! {:handle h})))))))
