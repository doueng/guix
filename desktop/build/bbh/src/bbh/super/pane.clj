(ns bbh.super.pane
  "`bbh inbox`: the verbs behind the Inbox buffer in the super-agent's Emacs,
  which shows `org` and calls the rest from its keys.
  The controller reloads it through the port `register` records. Picks and
  notes are the thread's draft in the controller, which the phone shows too,
  until `send` turns it into one reply, and so is what you have read. The
  selection is this client's and lives in ~/.local/state/bbh/inbox.edn. Bare
  `bbh inbox` opens a terminal frame on that buffer."
  (:require [bbh.sysenv :as sysenv]
            [babashka.fs :as fs]
            [bbh.super.client :as client]
            [bbh.super.emacs :as emacs]
            [bbh.super.model :as model]
            [bbh.super.notify :as notify]
            [bbh.version :as version]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:dynamic *state-file* nil)

(defn- state-file []
  (or *state-file* (str (fs/path (or (sysenv/getenv "XDG_STATE_HOME") (fs/expand-home "~/.local/state")) "bbh" "inbox.edn"))))

(defn client-state []
  (let [f (state-file)]
    (if (fs/exists? f) (edn/read-string (slurp f)) {})))

(defn- save-client-state! [s]
  (fs/create-dirs (fs/parent (state-file)))
  (let [tmp (fs/create-temp-file {:dir (fs/parent (state-file)) :prefix ".inbox"})]
    (spit (str tmp) (pr-str s))
    (fs/set-posix-file-permissions tmp "rw-------")
    (fs/move tmp (state-file) {:atomic-move true :replace-existing true})))

(defn with-state-lock! [f]
  (fs/create-dirs (fs/parent (state-file)))
  (with-open [ch (java.nio.channels.FileChannel/open (fs/path (str (state-file) ".lock"))
                  (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE java.nio.file.StandardOpenOption/WRITE]))]
    (loop [attempts 0]
      (if (try (.tryLock ch) (catch Exception _ nil))
        (f)
        (if (< attempts 500)
          (do (Thread/sleep 10) (recur (inc attempts)))
          (throw (ex-info "Inbox state lock unavailable" {:error :busy})))))))

(defn select!
  "Record that the Inbox cursor is on thread ID, and on ASK when given, for voice."
  [id & [ask]]
  (with-state-lock! #(save-client-state! (assoc (client-state) :selected (cond-> {:thread id :at (System/currentTimeMillis)} ask (assoc :ask ask))))))

(defn- call! [request]
  (let [{:keys [ok error message data]} (client/call! request)]
    (if error
      (throw (ex-info (str (name error) (when message (str ": " message))) (assoc data :error error)))
      ok)))

(defn- age [iso]
  (when iso
    (let [s (quot (- (System/currentTimeMillis) (.toEpochMilli (java.time.Instant/parse iso))) 1000)]
      (cond (< s 60) "now" (< s 3600) (str (quot s 60) "m") (< s 86400) (str (quot s 3600) "h") :else (str (quot s 86400) "d")))))

(defn- first-line [s] (first (str/split-lines (str s))))

;; The Inbox is an org outline: a heading per thread, then per open ask with
;; its options in the body, and under each ask a Details heading (folded by
;; Emacs) and an Answer heading whose body is the one editable text. Emacs
;; reads the ids back from the headings, so every heading starts with its id.

(defn- indent [prefix s]
  (str/join "\n" (map #(str prefix %) (str/split-lines (str s)))))

(defn- org-ask
  "The org subtree of open ASK, with DRAFT's pick and answer for it."
  [{:keys [id text options recommended kind revision evidence-sha details evidence]} draft]
  (let [picked (get-in draft [:picks id])
        answer (get-in draft [:answers id])]
    (str/join "\n"
              (concat [(str "** " id " " text (when (not= :question kind) (str "  [" (name kind) "]")))]
                      (when (= :review kind)
                        [(str "   revision " revision "  evidence " (subs (str evidence-sha "        ") 0 8) "  A approve")])
                      (map-indexed (fn [i {oid :id label :label}]
                                     (str "   " (inc i) ". " label
                                          (when (= oid recommended) "  (recommended)")
                                          (when (= oid picked) "  <- picked")))
                                   options)
                      (when (or details evidence)
                        (concat ["*** Details"]
                                (when details [(indent "    " details)])
                                (when evidence [(str "    " (if (str/starts-with? evidence "/") (str "[[file:" evidence "][" evidence "]]") evidence))])))
                      ["*** Answer"]
                      ;; An empty line, so a blank Answer has a line to type on.
                      [(if (str/blank? answer) "" (indent "    " answer))]))))

(defn org-view
  "The Inbox outline of SECTIONS, in section order: each thread's heading
  starts with its section and id, then its open asks, else its line."
  [{:keys [sections]}]
  (str/join "\n"
            (for [{:keys [section threads]} sections
                  {:keys [id title line came-back updated-at execution asks draft model]} threads]
              (str/join "\n"
                        (concat [(str "* " (str/upper-case (name section)) " " id " " title
                                      (if came-back " (came back)" "")
                                      (if model (str " [" model "]") "")
                                      (let [tail (str/join " · " (remove str/blank? [(when-not (contains? #{nil :none} execution) (name execution)) (age updated-at)]))]
                                        (when-not (str/blank? tail) (str "  " tail))))]
                                (if (seq asks)
                                  (map #(org-ask % draft) asks)
                                  (when-let [l (some-> line first-line not-empty)] [(str "  " l)]))
                                (when-let [n (not-empty (:note draft))] [(str "  note: " n)]))))))

(defn header
  "The Inbox's first line: what needs you, the processes on older code, then the keys."
  [{:keys [badge]} stale]
  (format "bbh inbox · %d need you%s   1-9 pick  . recommended  i answer  s/C-n send  S send all  TAB fold  RET open  A approve  r note  m read  M all read  U unread  D dismiss  a archive  x cancel  R router  J jjui (q closes)  gJ close jjui  gr reload  g? help" badge
          (if (seq stale) (str " · old code: " (str/join ", " stale)) "")))

(defn- sections
  "The controller's sections in the current order."
  []
  (call! {:command :sections}))

(defn- thread [id] (call! {:command :thread :args {:id id}}))

;; The split view (design 4): a compact list of every thread on the left and
;; the selected thread on the right. `split` prints the list as #+thread lines
;; and the selected thread's progress as #+progress lines ahead of the same
;; outline `org` prints, so one run feeds both panes.

(defn- one-line [s] (str/trim (str/replace (str s) #"\s+" " ")))

(defn split-rows
  "The #+thread lines of SECTIONS, one per thread in section order, with
  unseen=1 marking an unread change. Each field is key=value, tab-separated,
  so a reader finds them by name."
  [{:keys [sections]}]
  (for [{:keys [section threads]} sections
        {:keys [id title status asks updated-at worker] :as t} threads]
    (str "#+thread: " (str/join "\t" (for [[k v] [[:id id] [:section (name section)] [:status (some-> status name)] [:asks (count asks)]
                                                   [:age (or (age updated-at) "")] [:unseen (if (model/unread? t) 1 0)]
                                                   [:worker (or worker "")] [:title (one-line title)]]]
                                         (str (name k) "=" v))))))

(def ^:private progress-kinds
  {:milestone "milestone" :note "note" :ask-opened "asked" :reply-sent "replied" :worker-started "started"
   :worker-finished "finished" :worker-steered "steered" :asks-answered-in-chat "in chat"})

(defn- progress-text [{:keys [kind data]}]
  (case kind
    (:milestone :note) (:text data)
    :ask-opened (str (:id data) " " (:text data))
    :reply-sent (str (:id data) " " (str/join " " (concat (keys (:picks data)) (keys (:answers data))))
                     (when-not (str/blank? (:note data)) (str " note: " (:note data))))
    :worker-started (:id data)
    :worker-finished (str (:id data) " " (some-> (:state data) name)
                          (when-let [l (:landed data)] (str ", landed " (subs l 0 (min 8 (count l))))))
    :worker-steered "by the Router"
    :asks-answered-in-chat (str/join " " (:asks data))))

(def ^:private progress-time (java.time.format.DateTimeFormatter/ofPattern "MM-dd HH:mm"))

(defn split-progress
  "The #+progress lines of thread ID from its EVENTS: the newest N that tell
  how its work went, oldest first, each with its local time."
  [id events n]
  (for [{:keys [at kind] :as e} (take-last n (filter (comp progress-kinds :kind) events))]
    (str "#+progress: " (str/join "\t" [id
                                       (try (.format progress-time (.atZone (java.time.Instant/parse at) (java.time.ZoneId/systemDefault)))
                                            (catch Exception _ ""))
                                       (progress-kinds kind)
                                       (one-line (progress-text e))]))))

(defn- open-ask
  "Thread T's open ask ASK, else with no ASK its newest open ask."
  [{:keys [asks]} ask]
  (let [open (sort-by :seq (filter #(= :open (:state %)) asks))]
    (if ask (some #(when (= ask (:id %)) %) open) (last open))))

(defn pick!
  "Save option N (1-based) of open ask ASK on thread ID, else of its newest
  open ask. A named ask that is no longer open is refused, so a caller that
  judged one ask never picks on another."
  [id n & {expected :ask}]
  (let [ask (open-ask (thread id) expected)
        option (nth (:options ask) (dec n) nil)]
    (when (and expected (nil? ask))
      (throw (ex-info (str "The ask on " id " changed; look again") {:error :stale})))
    (when (and ask option)
      (call! {:command :draft! :args {:thread id :pick [(:id ask) (:id option)]}}))))

(defn answer!
  "Save TEXT as the free-text answer to ask ASK in thread ID's draft; blank
  TEXT removes it."
  [id ask text]
  (call! {:command :draft! :args {:thread id :answer [ask text]}}))

(defn send!
  "Send thread ID's draft as one reply, which removes the draft. ANSWERS,
  {ask text}, are what the Inbox shows typed under each ask; they replace the
  draft's, and a blank one drops it. Nothing to send sends nothing. A stale
  draft is reported, not resent."
  [id & {:keys [answers]}]
  (let [t (thread id)
        {:keys [picks note asks-version] :or {asks-version (:asks-version t)}} (:draft t)
        answers (into {} (remove (comp str/blank? val))
                      (merge (get-in t [:draft :answers]) (update-vals (or answers {}) str/trim)))]
    (when (or (seq picks) (seq answers) (not (str/blank? note)))
      (call! {:command :reply! :args (cond-> {:thread id :asks-version asks-version :picks (or picks {}) :note note}
                                       (seq answers) (assoc :answers answers))}))))

(defn approve!
  "Approve open review ask ASK on thread ID, else its newest, at the revision
  and evidence it shows. The controller refuses it as stale if either moved."
  [id & [ask]]
  (let [{:keys [asks]} (thread id)]
    (when-let [a (last (sort-by :seq (filter #(and (= :open (:state %)) (= :review (:kind %)) (or (nil? ask) (= ask (:id %)))) asks)))]
      (call! {:command :approve! :args {:thread id :ask (:id a) :revision (:revision a) :evidence-sha (:evidence-sha a)}}))))

(defn note!
  "Save LINE as the note of thread ID's draft."
  [id line]
  (call! {:command :draft! :args {:thread id :note line}}))

(defn cancel!
  "Cancel thread ID's Worker, archived or not: the way to stop one whose
  session is gone, which nothing else ends."
  [id]
  (call! {:command :cancel! :args {:thread id :reason "Cancelled from the inbox"}}))

(defn read!
  "Mark threads IDS read, or unread when READ is false; [\"all\"] is every
  thread now unread."
  [ids read]
  (let [ids (if (= ["all"] ids)
              (for [{:keys [threads]} (:sections (sections)) t threads :when (model/unread? t)] (:id t))
              ids)]
    (call! {:command :read! :args {:threads (vec ids) :read read}})))

(defn- buffer
  "The Emacs buffer of thread ID's latest Worker, running or finished, or an
  error naming why there is none."
  [id]
  (let [w (last (:workers (thread id)))]
    (or (:buffer w)
        (throw (ex-info (str id (if w (str "'s Worker " (:id w) " has no buffer") " has no Worker")) {:error :no-buffer})))))

(defn workdir
  "Where thread T's latest Worker works: its jj workspace while that still
  exists, else the repo it was started on, or an error naming why there is none."
  [{:keys [id workers]}]
  (let [w (last workers)]
    (or (some #(when (and % (fs/directory? %)) (str %)) [(:workspace w) (:repo w)])
        (throw (ex-info (str id (if w (str "'s Worker " (:id w) " has no workspace or repo") " has no Worker")) {:error :no-dir})))))

(defn -main
  "bbh inbox opens the Inbox in the super-agent's Emacs; the verbs are what its keys call."
  [& [verb id n & more]]
  (try
    (case verb
      nil (emacs/attach! "(eng/bbh-inbox)")
      ;; The Inbox buffer's reload: the header, then the outline, from one read.
      ;; The buffer keeps your place, folds and unsent answers across reloads.
      "org" (let [s (sections)] (println (header s (version/stale (client/data-dir)))) (println (org-view s)))
      "split" (let [s (sections)]
                (println (header s (version/stale (client/data-dir))))
                (doseq [l (split-rows s)] (println l))
                (when id
                  (println (str "#+selected: " id))
                  (doseq [l (split-progress id (call! {:command :events :args {:thread id}}) 8)] (println l)))
                ;; The list's selection moved there: tell voice, as select does.
                (when (= "select" n) (select! id))
                (println (org-view s)))
      "select" (select! id n)
      "clear" (call! {:command :discard-draft! :args {:thread id}})
      "pick" (pick! id (parse-long n) :ask (first more))
      "answer" (answer! id n (str/join " " more))
      ;; ANSWERS is an EDN map {ask text}, so text keeps its newlines.
      "send" (send! id :answers (some-> n edn/read-string))
      "note" (let [line (str/trim (str/join " " (remove nil? (cons n more))))]
               (when (str/blank? line) (throw (ex-info "A note needs text" {:error :invalid})))
               (note! id line))
      "approve" (approve! id n)
      "dismiss" (call! {:command :dismiss! :args {:thread id}})
      "resume" (call! {:command :resume! :args {:thread id}})
      "archive" (call! {:command :archive! :args {:thread id}})
      "cancel" (println (cancel! id))
      "read" (read! (remove nil? (list* id n more)) true)
      "unread" (read! (remove nil? (list* id n more)) false)
      "buffer" (println (buffer id))
      "dir" (println (workdir (thread id)))
      "register" (client/register-view! (client/super-dir) (parse-long id))
      "dnd" (let [f (client/policy-file)]
              (println (if (notify/toggle-dnd! (when (fs/exists? f) (edn/read-string (slurp (str f)))))
                         "Do not disturb: bbh notifications are off; gn turns them on"
                         "bbh notifications are on")))
      (println "usage: bbh inbox [org|split [T [select]]|select T [ASK]|pick T N [ASK]|answer T ASK TEXT|send T [ANSWERS]|clear T|approve T [ASK]|note T TEXT|dismiss T|resume T|archive T|cancel T|read T...|all|unread T...|buffer T|dir T|register PORT|dnd]"))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "bbh inbox:" (ex-message e)))
      (System/exit 1))))
