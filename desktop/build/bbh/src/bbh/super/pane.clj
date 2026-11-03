(ns bbh.super.pane
  "`bbh inbox`: the verbs behind the Inbox buffer in the super-agent's Emacs,
  which shows `rows`, `header` and `preview` and calls the rest from its keys.
  The controller reloads it through the port `register` records. Picks, notes
  and read cursors are this client's and live in ~/.local/state/bbh/inbox.edn
  until `send` turns them into one reply. Bare `bbh inbox` opens a terminal
  frame on that buffer."
  (:require [babashka.fs :as fs]
            [bbh.super.client :as client]
            [bbh.super.emacs :as emacs]
            [bbh.super.model :as model]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:dynamic *state-file* nil)

(defn- state-file []
  (or *state-file* (str (fs/path (or (System/getenv "XDG_STATE_HOME") (fs/expand-home "~/.local/state")) "bbh" "inbox.edn"))))

(defn client-state []
  (let [f (state-file)]
    (if (fs/exists? f) (edn/read-string (slurp f)) {:picks {} :notes {} :cursors {}})))

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

(defn select! [id]
  (with-state-lock! #(save-client-state! (assoc (client-state) :selected {:thread id :at (System/currentTimeMillis)}))))

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

(defn rows
  "The Inbox rows: one `id<TAB>display` line per thread, grouped in section
  order, after a `new` line when threads wait behind the held order. A held
  row that changed section names where it went."
  [{:keys [sections fresh]}]
  (str/join "\n"
            (concat
             (when (pos? (or fresh 0)) [(format "new\t── %d new · n to show ──" fresh)])
             (for [{section :title :keys [threads]} sections
                   {:keys [id title line came-back updated-at now]} threads]
               (str id "\t" (format "%-16s %-4s %s%s%s  %s  %s" (str/upper-case section) id title
                                      (if came-back " (came back)" "")
                                      (if now (str " → " (model/section-titles now)) "")
                                      (or (some-> line first-line) "")
                                      (or (age updated-at) "")))))))

(defn header [{:keys [badge]}]
  (format "bbh inbox · %d need you   1-4 pick  s send  A approve  r note  m mark read  a archive  n new  RET open  R router  g refresh" badge))

(defn preview
  "What the selected thread needs: its open ask with numbered options and the
  saved picks, or its summary."
  [{:keys [id title asks summary workers execution]} {:keys [picks notes]}]
  (let [open (sort-by :seq (filter #(= :open (:state %)) asks))
        pick (get picks id {})]
    (str/join "\n"
              (concat [(str id "  " title) (str "execution " (name (or execution :none))
                                                 (when-let [w (last workers)] (str ", " (:id w) " " (name (:state w)))))
                       ""]
                      (if (seq open)
                        (mapcat (fn [{:keys [id text options recommended stopped kind revision evidence-sha]}]
                                  (concat [(str (if stopped "STOPPED  " "") text)]
                                          (when (= :review kind)
                                            [(str "  REVIEW revision " revision " evidence " (subs (str evidence-sha "        ") 0 8) "  A approve")])
                                          (map-indexed (fn [i {oid :id label :label}]
                                                         (str "  " (inc i) ". " label
                                                              (when (= oid recommended) "  (recommended)")
                                                              (when (= oid (get pick id)) "  <- picked")))
                                                       options)
                                          [""]))
                                open)
                        [(or summary "Nothing open.")])
                      (when-let [n (get notes id)] ["note:" n])))))

(defn- sections
  "The controller's sections held in the order this pane last showed, which
  is remembered the first time and on `reveal`."
  []
  (let [{:keys [cursors shown]} (client-state)
        r (call! {:command :sections :args (cond-> {:cursors cursors} shown (assoc :shown shown))})]
    (when-not shown (with-state-lock! #(save-client-state! (assoc (client-state) :shown (:order r)))))
    r))

(defn reveal!
  "Show the current order, new threads included."
  []
  (let [r (call! {:command :sections :args {:cursors (:cursors (client-state))}})]
    (with-state-lock! #(save-client-state! (assoc (client-state) :shown (:order r))))))

(defn- thread [id] (call! {:command :thread :args {:id id}}))

(defn pick!
  "Save option N (1-based) of the newest open ask on thread ID. With ASK, only
  when that is still the newest open ask, so a caller that judged one ask never
  picks on another that opened since."
  [id n & {expected :ask}]
  (let [{:keys [asks asks-version]} (thread id)
        ask (last (sort-by :seq (filter #(= :open (:state %)) asks)))
        option (nth (:options ask) (dec n) nil)]
    (when (and expected (not= expected (:id ask)))
      (throw (ex-info (str "The ask on " id " changed; look again") {:error :stale})))
    (when (and ask option)
      (when-let [version (get-in (client-state) [:versions id])]
        (when (not= version asks-version) (throw (ex-info "Draft is stale; clear it before choosing again" {:error :stale}))))
      (save-client-state! (-> (client-state) (assoc-in [:picks id (:id ask)] (:id option))
                               (assoc-in [:versions id] asks-version))))))

(defn send!
  "Send the saved picks and note on thread ID as one reply. A stale version is
  reported, not resent."
  [id]
  (let [{:keys [picks notes versions]} (client-state)
        {:keys [asks-version]} (thread id)
        asks-version (or (get versions id) asks-version)]
    (when (or (seq (get picks id)) (get notes id))
      (call! {:command :reply! :args {:thread id :asks-version asks-version
                                      :picks (get picks id {}) :note (get notes id "")}})
      (save-client-state! (-> (client-state) (update :picks dissoc id) (update :notes dissoc id) (update :versions dissoc id))))))

(defn approve!
  "Approve the newest open review ask on thread ID at the revision and evidence
  it shows. The controller refuses it as stale if either moved."
  [id]
  (let [{:keys [asks]} (thread id)]
    (when-let [a (last (sort-by :seq (filter #(and (= :open (:state %)) (= :review (:kind %))) asks)))]
      (call! {:command :approve! :args {:thread id :ask (:id a) :revision (:revision a) :evidence-sha (:evidence-sha a)}}))))

(defn note!
  "Save LINE as the note for thread ID."
  [id line]
  (let [version (:asks-version (thread id))]
    (save-client-state! (-> (client-state) (assoc-in [:notes id] line)
                             (update-in [:versions id] #(or % version))))))

(defn- seen! [id]
  (let [{:keys [milestone-seq]} (thread id)]
    (save-client-state! (assoc-in (client-state) [:cursors id] milestone-seq))))

(defn- buffer
  "The Emacs buffer of thread ID's latest Worker, running or finished, or an
  error naming why there is none."
  [id]
  (let [w (last (:workers (thread id)))]
    (or (:buffer w)
        (throw (ex-info (str id (if w (str "'s Worker " (:id w) " has no buffer") " has no Worker")) {:error :no-buffer})))))

(defn -main
  "bbh inbox opens the Inbox in the super-agent's Emacs; the verbs are what its keys call."
  [& [verb id n & more]]
  (try
    (case verb
      ;; Opening shows the current order; only live reloads hold it still.
      nil (do (reveal!) (emacs/attach! "(eng/bbh-inbox)"))
      "rows" (println (rows (sections)))
      ;; The Inbox buffer's reload: the header, then the rows, from one read.
      "view" (let [s (sections)] (println (header s)) (println (rows s)))
      "header" (println (header (sections)))
      "preview" (println (if (= "new" id) "New threads wait here so the list holds still while you look. Press n to show them." (preview (thread id) (client-state))))
      "reveal" (reveal!)
      "select" (select! id)
      "clear" (with-state-lock! #(save-client-state! (-> (client-state) (update :picks dissoc id) (update :notes dissoc id) (update :versions dissoc id))))
      "pick" (with-state-lock! #(pick! id (parse-long n)))
      "send" (with-state-lock! #(send! id))
      "note" (let [line (str/trim (str/join " " (remove nil? (cons n more))))]
               (when (str/blank? line) (throw (ex-info "A note needs text" {:error :invalid})))
               (with-state-lock! #(note! id line)))
      "approve" (approve! id)
      "dismiss" (call! {:command :dismiss! :args {:thread id}})
      "archive" (call! {:command :archive! :args {:thread id}})
      "seen" (with-state-lock! #(seen! id))
      "buffer" (println (buffer id))
      "register" (client/register-view! (client/super-dir) (parse-long id))
      (println "usage: bbh inbox [view|rows|header|preview T|reveal|pick T N|send T|approve T|note T TEXT|dismiss T|archive T|seen T|buffer T|register PORT]"))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "bbh inbox:" (ex-message e)))
      (System/exit 1))))
