(ns bbh.super.pane
  "`bbh inbox`: the Herdr inbox pane, fzf over the controller's sections. The
  controller reloads it through fzf's --listen port, which `register` records.
  Picks, notes and read cursors are this client's and live in
  ~/.local/state/bbh/inbox.edn until `send` turns them into one reply."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.super.client :as client]
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
  "The fzf input: one `id<TAB>display` line per thread, grouped in section order."
  [{:keys [sections]}]
  (str/join "\n"
            (for [{section :title :keys [threads]} sections
                  {:keys [id title line came-back updated-at]} threads]
              (str id "\t" (format "%-16s %-4s %s%s  %s  %s" (str/upper-case section) id title
                                     (if came-back " (came back)" "")
                                     (or (some-> line first-line) "")
                                     (or (age updated-at) ""))))))

(defn header [{:keys [badge]}]
  (format "bbh inbox · %d need you   1-4 pick  s send  r note  m mark read  a archive" badge))

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
                        (mapcat (fn [{:keys [id text options recommended stopped]}]
                                  (concat [(str (if stopped "STOPPED  " "") text)]
                                          (map-indexed (fn [i {oid :id label :label}]
                                                         (str "  " (inc i) ". " label
                                                              (when (= oid recommended) "  (recommended)")
                                                              (when (= oid (get pick id)) "  <- picked")))
                                                       options)
                                          [""]))
                                open)
                        [(or summary "Nothing open.")])
                      (when-let [n (get notes id)] ["note:" n])))))

(defn- sections [] (call! {:command :sections :args {:cursors (:cursors (client-state))}}))

(defn- thread [id] (call! {:command :thread :args {:id id}}))

(defn pick!
  "Save option N (1-based) of the newest open ask on thread ID."
  [id n]
  (let [{:keys [asks asks-version]} (thread id)
        ask (last (sort-by :seq (filter #(= :open (:state %)) asks)))
        option (nth (:options ask) (dec n) nil)]
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

(defn note!
  "Read one note for thread ID from the terminal."
  [id]
  (print (str id " note> ")) (flush)
  (when-let [line (some-> (read-line) str/trim not-empty)]
    (let [version (:asks-version (thread id))]
      (save-client-state! (-> (client-state) (assoc-in [:notes id] line)
                               (update-in [:versions id] #(or % version)))))))

(defn- seen! [id]
  (let [{:keys [milestone-seq]} (thread id)]
    (save-client-state! (assoc-in (client-state) [:cursors id] milestone-seq))))

(defn- fzf-args []
  (let [bbh "bbh inbox"]
    ["fzf" "--listen" "--delimiter" "\t" "--with-nth" "2.." "--no-sort" "--layout" "reverse"
     "--disabled" "--no-multi" "--prompt" "Inbox> " "--border"
     "--bind" (str "start:execute-silent(" bbh " register $FZF_PORT)+transform-header(" bbh " header)")
     "--bind" "j:down,k:up"
     "--bind" (str "focus:execute-silent(" bbh " select {1})")
     "--bind" (str/join "," (for [n (range 1 5)] (format "%d:execute-silent(%s pick {1} %d)+refresh-preview" n bbh n)))
     "--bind" (str "s:execute-silent(" bbh " send {1})+reload(" bbh " rows)+transform-header(" bbh " header)+refresh-preview")
     "--bind" (str "r:execute(" bbh " note {1})+refresh-preview")
     "--bind" (str "m:execute-silent(" bbh " dismiss {1})+reload(" bbh " rows)+transform-header(" bbh " header)+refresh-preview")
     "--bind" (str "a:execute-silent(" bbh " archive {1})+reload(" bbh " rows)+transform-header(" bbh " header)+refresh-preview")
     "--bind" (str "enter:execute-silent(" bbh " seen {1})+reload(" bbh " rows)")
     "--preview" (str bbh " preview {1}") "--preview-window" "down,60%,border-top"]))

(defn -main
  "bbh inbox runs the pane; the verbs are what its keys call."
  [& [verb id n]]
  (try
    (case verb
      nil (let [input (rows (sections))]
            (p/shell {:in input :continue true} (str/join " " (map #(str "'" (str/replace % "'" "'\\''") "'") (fzf-args)))))
      "rows" (println (rows (sections)))
      "header" (println (header (sections)))
      "preview" (println (preview (thread id) (client-state)))
      "select" (select! id)
      "clear" (with-state-lock! #(save-client-state! (-> (client-state) (update :picks dissoc id) (update :notes dissoc id) (update :versions dissoc id))))
      "pick" (with-state-lock! #(pick! id (parse-long n)))
      "send" (with-state-lock! #(send! id))
      "note" (with-state-lock! #(note! id))
      "dismiss" (call! {:command :dismiss! :args {:thread id}})
      "archive" (call! {:command :archive! :args {:thread id}})
      "seen" (with-state-lock! #(seen! id))
      "register" (client/register-pane! (client/super-dir) (parse-long id))
      (println "usage: bbh inbox [rows|header|preview T|pick T N|send T|note T|dismiss T|archive T|seen T|register PORT]"))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "bbh inbox:" (ex-message e)))
      (System/exit 1))))
