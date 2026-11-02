(ns bbh.super
  "The super-agent controller: `bbh super serve`. Commands arrive on a loopback
  socket as one EDN line each with a token, are run through the store as one
  transaction, and the dispatcher drains the outbox into bbh mailboxes. `call!`
  is the client every surface and Worker uses."
  (:require [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.super.client :as client :refer [call! data-dir herdr new-id super-dir]]
            [bbh.super.model :as model]
            [bbh.super.process :as process]
            [bbh.super.store :as store]
            [bbh.workflow :as workflow]
            [babashka.process :as p]
            [bencode.core :as bencode]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net ServerSocket Socket InetAddress]
           [java.security MessageDigest SecureRandom]))

(defn- sha256 [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn- new-token []
  (let [b (byte-array 24)] (.nextBytes (SecureRandom.) b) (apply str (map #(format "%02x" %) b))))

;; ---- controller ---------------------------------------------------------

(defn notify-panes!
  "Tell every fzf pane registered under DIR to reload its rows. A port that
  refuses the connection is forgotten."
  [dir]
  (doseq [f (when (fs/exists? (client/listeners-dir dir)) (fs/list-dir (client/listeners-dir dir)))
          :let [port (parse-long (str/trim (slurp (str f))))]]
    (try (with-open [sock (Socket. "127.0.0.1" (int port))
                     out (io/writer (.getOutputStream sock))]
           (let [body "reload(bbh inbox rows)+refresh-preview"]
             (.write out (str "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " (count body) "\r\n\r\n" body))
             (.flush out)))
         (catch java.io.IOException _ (fs/delete-if-exists f)))))

(defn- run!
  "Commit COMMAND for PRINCIPAL on controller C. Serialized: the controller is
  the single writer. A commit that changed something reloads the panes."
  [{:keys [db state dir]} principal command]
  (let [r (locking db
            (let [r (store/commit! db @state principal command)]
              (reset! state (:state r))
              r))]
    (when-not (:repeat? r) (notify-panes! dir))
    r))

;; ---- Workers: a record first, then the workspace, the wf run and the pane --

(defn- policy
  "The controller's policy over the defaults: its :policy when given, else
  ~/.config/bbh/policy.edn."
  [c]
  (merge model/default-policy
         (or (:policy c)
             (let [f (fs/path (or (System/getenv "XDG_CONFIG_HOME") (fs/expand-home "~/.config")) "bbh" "policy.edn")]
               (when (fs/exists? f) (edn/read-string (slurp (str f))))))))

(defn- jj! [dir & args]
  (let [{:keys [exit err]} (apply p/shell {:dir dir :out :string :err :string :continue true} "jj" args)]
    (when-not (zero? exit) (throw (ex-info (str "jj " (str/join " " args) ": " (str/trim err)) {:error :jj})))))

(defn workspace!
  "A jj workspace of REPO for WORKER under DIR, named <thread>-<generation>.
  Returns its path; an existing one is reused."
  [dir {:keys [repo thread generation]}]
  (let [name (str thread "-" generation)
        path (str (fs/path dir "workspaces" name))]
    (when-not (fs/exists? path)
      (fs/create-dirs (fs/parent path))
      (jj! repo "workspace" "add" "--name" name path))
    path))

(defn wf-start!
  "The wf run for WORKER in WORKSPACE, its brief as the task."
  [workspace {:keys [workflow brief check]}]
  (workflow/start! workflow {:cwd workspace :task brief :check check}))

(defn pane!
  "A Herdr pane for WORKER: split beside a pane of the workspace that holds
  REPO (else a new workspace), in the jj workspace, with the Worker's token
  and wf run in its environment, running bbh on the Worker's conversation,
  named after the thread. Returns the pane id."
  [{:keys [repo workspace thread title conversation token wf-run]}]
  (let [panes (:panes (herdr "pane" "list"))
        anchor (some #(when (= (str (fs/canonicalize repo)) (:cwd %)) %) panes)
        env ["--env" (str "BBH_SUPER_TOKEN=" token) "--env" (str "WF_RUN=" wf-run)]
        pane (if anchor
               (get-in (apply herdr "pane" "split" (:pane_id anchor) "--direction" "right" "--cwd" workspace "--no-focus" env) [:pane :pane_id])
               (let [ws (apply herdr "workspace" "create" "--cwd" workspace "--label" (str thread " " title) "--no-focus" env)]
                 (:pane_id (first (:panes (herdr "pane" "list" "--workspace" (:workspace_id ws)))))))]
    (herdr "pane" "run" pane "bbh" "--id" conversation)
    (herdr "pane" "rename" pane (str thread " " title))
    pane))

(declare run!)

(defn- launch!
  "The side effects that make Worker W a process, then the commit that records
  them. Each step is idempotent by the Worker's name, so a repeat after a crash
  reuses what exists."
  [{:keys [dir state launch] :as c} w token]
  (let [{:keys [workspace! wf-start! pane!]} launch
        t (get-in @state [:threads (:thread w)])
        workspace (workspace! dir w)
        conversation (str (fs/path (:conversations c) (str "super-" (:id w))))
        _ (fs/create-dirs conversation)
        wf-run (or (:wf-run w) (wf-start! workspace w))
        _ (run! c :controller {:id (str "prepared-" (:id w)) :command :worker-prepared!
                                :args {:worker (:id w) :workspace workspace :wf-run wf-run
                                       :conversation (fs/file-name conversation) :started-at (System/currentTimeMillis)}})
        pane (pane! {:repo (:repo w) :workspace workspace :thread (:thread w) :title (:title t)
                     :conversation (fs/file-name conversation) :token token :wf-run wf-run})]
    (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                         :args {:started-at (System/currentTimeMillis) :worker (:id w) :conversation (fs/file-name conversation) :wf-run wf-run :workspace workspace :pane pane}})))

(defn- promote!
  "Start the next queued Worker that may run now, if any."
  [{:keys [state] :as c}]
  (when-let [w (model/next-queued @state (policy c))]
    (let [t (new-token)]
      (run! c :controller {:id (str "token-" (sha256 t)) :command :issue-token! :token-hash (sha256 t) :args {:principal [:worker (:id w)]}})
      (launch! c w t))))

(defn- principal-of [state token]
  (when (string? token)
    (let [t (get-in state [:tokens (sha256 token)])]
      (when (and t (not (:revoked t))) (:principal t)))))

(defn- read-command
  "Reads never write and need no command id."
  [{:keys [db state]} {:keys [command args]}]
  (let [st @state]
    (case command
      :health (into {} (map (fn [[id w]] [id (cond-> (:health w) (#{:done :cancelled} (:state w)) (assoc :classification :done) (= :failed (:state w)) (assoc :classification :needs-human))]) (:workers st)))
      :threads (vec (sort-by :id (vals (:threads st))))
      :thread (let [{:keys [id]} args
                    t (get-in st [:threads id])
                    of (fn [kind] (vec (sort-by :seq (filter #(= id (:thread %)) (vals (kind st))))))]
                (when t (assoc t :asks (of :asks) :replies (of :replies) :workers (vec (sort-by :generation (filter #(= id (:thread %)) (vals (:workers st))))))))
      :events (store/events db (or (:after args) 0) (:thread args))
      :sections (let [sections (model/sections st (or (:cursors args) {}))]
                  {:sections sections :badge (model/badge sections)})
      ::unknown)))

(declare reconcile!)

(defn handle
  "The response for one request map on controller C."
  [c {:keys [token id command args]}]
  (try
    (let [principal (or (principal-of @(:state c) token) (throw (ex-info "unauthorized" {:error :unauthorized})))
          command (keyword command)
          read (read-command c {:command command :args args})]
      (if (not= ::unknown read)
        {:ok read}
        (let [cmd {:id (or id (new-id)) :command command :args (or args {})}]
          (case command
            (:attach-worker! :register-router!)
            (let [t (new-token)
                  r (run! c principal (assoc cmd :token-hash (sha256 t)))]
              {:ok (cond-> (:result r) (not (:repeat? r)) (assoc :token t))})
            :start-worker!
            (let [t (new-token)
                  r (run! c principal (assoc cmd :token-hash (sha256 t) :policy (policy c)))
                  w (get-in @(:state c) [:workers (:id (:result r))])]
              (when (and (not (:repeat? r)) (= :starting (:state w)))
                (launch! c w t))
              {:ok (let [w (get-in @(:state c) [:workers (:id (:result r))])]
                     (merge (:result r) (select-keys w [:state :workspace :pane :conversation :wf-run])))})
            :cancel!
            (let [r (run! c principal cmd)]
              (reconcile! c)
              {:ok (:result r)})
            :finish-worker!
            (let [r (run! c principal cmd)]
              (when-not (:repeat? r) (promote! c))
              {:ok (:result r)})
            {:ok (:result (run! c principal cmd))}))))
    (catch clojure.lang.ExceptionInfo e
      {:error (or (:error (ex-data e)) :failed) :message (ex-message e) :data (dissoc (ex-data e) :error)})
    (catch Exception e
      {:error :failed :message (ex-message e)})))

(defn- ensure-owner-token!
  "The owner's token, from <dir>/token when the store still knows it, else a
  new one issued and written there, readable by the user only."
  [{:keys [dir state] :as c}]
  (let [f (fs/path dir "token")
        known (when (fs/exists? f) (str/trim (slurp (str f))))]
    (if (= :owner (principal-of @state known))
      known
      (let [t (new-token)]
        (run! c :controller {:id (str "token-" (sha256 t)) :command :issue-token! :token-hash (sha256 t) :args {:principal :owner}})
        (spit (str f) t)
        (fs/set-posix-file-permissions f "rw-------")
        t))))

(defn- serve-client [c ^Socket sock]
  (with-open [sock sock
              in (io/reader (.getInputStream sock))
              out (io/writer (.getOutputStream sock))]
    (let [line (.readLine in)
          response (try (handle c (edn/read-string line))
                        (catch Exception e {:error :bad-request :message (ex-message e)}))]
      (.write out (pr-str response))
      (.write out "\n")
      (.flush out))))

;; ---- dispatcher ---------------------------------------------------------

(defn- conversation-root [{:keys [conversations]} id]
  (let [dir (str (fs/path conversations id))]
    (if (fs/exists? dir) (inbox/root-dir dir) dir)))

(defn observe-jobs!
  "Rediscover completed detached jobs from their durable exit markers. Running
  jobs are left alone. No execution is launched here, including after restart.
  The event and its outbox row commit together with a stable worker/job id."
  [{:keys [state] :as c}]
  (doseq [w (vals (:workers @state))
          :when (and (:conversation w) (#{:working :waiting} (:state w)))
          f (fs/glob (fs/path (conversation-root c (:conversation w)) "jobs") "*.exit")
          :let [job (str/replace (fs/file-name f) #"\.exit$" "")
                base (fs/path (fs/parent f) job)]
          :when (and (not (get-in w [:jobs job])) (fs/exists? (str base ".cmd")))
          :let [exit (try (parse-long (str/trim (slurp (str f))))
                          (catch java.io.IOException _ nil))]
          :when (some? exit)]
    (run! c :controller {:id (str "job-exited-" (pr-str [(:id w) job])) :command :job-exited!
                         :args {:worker (:id w) :job job :exit exit :log (str base ".log")}})))

(defn- repl-answering? [root]
  (try
    (let [port (some-> (process/read-text (fs/path root "repl.port")) parse-long)]
      (when port
        (with-open [s (Socket.)]
          (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
          (.setSoTimeout s 300)
          (bencode/write-bencode (.getOutputStream s) {"op" "describe" "id" "health"})
          (.flush (.getOutputStream s))
          (boolean (bencode/read-bencode (java.io.PushbackInputStream. (.getInputStream s)))))))
    (catch Exception _ false)))

(defn reconcile!
  "Observe before acting, including after sleep/restart. Persist every restart
  attempt before launching; never relaunch a surviving process or its jobs."
  [{:keys [state] :as c}]
  (locking (:recovery-lock c)
    (let [now (System/currentTimeMillis)
          grace (or (:restart-grace-ms c) 30000)]
      (run! c :controller {:id (new-id) :command :health! :args {:at now}})
      (doseq [w (vals (:workers @state))
              :when (or (model/active-states (:state w)) (= :queued (:state w)))
              :let [root (when (:conversation w) (conversation-root c (:conversation w)))
                    agent (when root (inbox/agent root))
                    jobs (if root (process/jobs root) [])
                    uncertain? (some #(#{:unknown :lost} (:state %)) jobs)
                    wf (when (:wf-run w) (try (select-keys (workflow/status (:wf-run w)) [:status :phase]) (catch Exception _ {:status :missing})))
                    repl-ok? (boolean (and agent (repl-answering? root)))
                    limit-ms (* 60000 (:stale-delivery-minutes (policy c)))
                    waited-ms #(- now (inst-ms (java.time.Instant/parse (:sent-at %))))
                    stale (when repl-ok?
                            (some->> (vals (:outbox @state))
                                     (filter #(and (= :sent (:state %)) (= (:conversation w) (:conversation %)) (:sent-at %)
                                                   (>= (waited-ms %) limit-ms)))
                                     (sort-by :sent-at) first))
                    [classification problem]
                    (cond (= :queued (:state w)) [:waiting]
                          uncertain? [:needs-human (format "Job %s can no longer be identified as the process that started it; check it before anything kills or reruns it."
                                                           (:id (first (filter #(#{:unknown :lost} (:state %)) jobs))))]
                          (and (:workflow w) (= :missing (:status wf))) [:needs-human (format "Its wf run %s is missing, so it cannot be restarted." (:wf-run w))]
                          (and agent (not repl-ok?)) [:needs-human (format "Its session (pid %s) is alive but its REPL does not answer." (:pid agent))]
                          stale [:needs-human (format "Delivery %s has waited %d min in its mailbox while the Worker runs; it is not taking messages."
                                                      (:id stale) (quot (waited-ms stale) 60000))]
                          agent [(if (= :waiting (:state w)) :waiting :running)]
                          (:workflow w) [:recoverable]
                          :else [:needs-human "Its session is gone and it has no workflow to restart it from."])
                    health {:at now :process (select-keys agent [:pid :start]) :repl repl-ok?
                            :workflow wf :classification classification :problem problem
                            :oldest-unacked (some->> (vals (:outbox @state))
                                                    (filter #(and (= (:thread w) (:thread %)) (#{:pending :sent} (:state %))))
                                                    (sort-by #(parse-long (subs (:id %) 1))) first :id)
                            :oldest-stopped-ask (some->> (vals (:asks @state)) (filter #(and (= (:thread w) (:thread %)) (= :open (:state %)) (:stopped %))) (sort-by :seq) first :seq)
                            :jobs (mapv #(select-keys % [:id :state :pid :start]) jobs)}]]
        (run! c :controller {:id (new-id) :command :health! :args {:worker (:id w) :health health :at now}})
        (cond
          (and agent (= :starting (:state w)) (:wf-run w))
          (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                               :args (merge (select-keys w [:conversation :wf-run :workspace :pane :started-at]) {:worker (:id w)})})

          (= :cancelling (:state w))
          (let [blocked (filter #(#{:unknown :lost} (:state %)) jobs)
                running (filter #(= :running (:state %)) jobs)
                stopped? (every? true? (mapv process/stop-job! running))]
            (when (and (empty? blocked) stopped?)
              (doseq [j running] (spit (str (:base j) ".exit") "137\n"))
              (when (and root agent (not (fs/exists? (fs/path root (str "0-cancel-" (:id w) ".received")))))
                (inbox/send! root (str "[Cancelled] " (:cancel-reason w)) nil
                             {:kind :cancel :message-id (str "0-cancel-" (:id w))}))
              (when (or (not agent) (fs/exists? (fs/path root (str "0-cancel-" (:id w) ".received"))))
                (run! c :controller {:id (str "cancelled-" (:id w)) :command :cancel-delivered! :args {:worker (:id w)}})
                (promote! c))))

          (and (not agent) (:workflow w) (:workspace w) (:conversation w)
               (not= :missing (:status wf)) (not uncertain?) (not= :queued (:state w))
               (> (- now (or (:restart-at w) (:started-at w) (:boot-at c))) grace))
          (if (>= (or (:restarts w) 0) 3)
            (do (run! c :controller {:id (str "restarts-exhausted-" (:id w)) :command :finish-worker!
                                    :args {:worker (:id w) :state-after :failed :reason "Worker failed three restart attempts; needs human"}})
                (promote! c))
            (let [token (new-token)]
              (run! c :controller {:id (new-id) :command :restart-worker! :token-hash (sha256 token) :args {:worker (:id w) :at now}})
              (try
                (let [pane ((get-in c [:launch :pane!])
                            {:repo (:repo w) :workspace (:workspace w) :thread (:thread w)
                             :title (get-in @state [:threads (:thread w) :title])
                             :conversation (:conversation w) :token token :wf-run (:wf-run w)})]
                  (if (= :starting (:state w))
                    (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                                         :args (merge (select-keys w [:conversation :wf-run :workspace :started-at]) {:worker (:id w) :pane pane})})
                    (run! c :controller {:id (new-id) :command :worker-restarted! :args {:worker (:id w) :pane pane}})))
                (catch Exception _ nil)))))))))

(defn dispatch!
  "One pass: send each pending outbox row to its Worker's mailbox, and mark a
  sent row acked once its mailbox file is gone. Each step is a command with a
  fixed id, so a pass repeated after a crash changes nothing twice."
  [{:keys [state] :as c}]
  (let [router (get-in @state [:router :router :conversation])
        rows (sort-by #(parse-long (subs (:id %) 1)) (vals (:outbox @state)))
        for-router? (fn [o] (and (= :pending (:state o)) (= router (:conversation o))))
        batch (filter for-router? rows)]
    ;; The Router reads its pending rows as one prompt, oldest first.
    (when (seq batch)
      (let [m (inbox/send! (conversation-root c router) (str/join "\n\n" (map :text batch)))]
        (doseq [{:keys [id]} batch]
          (run! c :controller {:id (str "sent-" id) :command :outbox-sent! :args {:outbox id :message (:id m)}}))))
  (doseq [{:keys [id conversation text message] :as o} rows
          :when (not (and (= :pending (:state o)) (= router conversation)))]
    (case (:state o)
      :pending (let [root (conversation-root c conversation)
                     m (inbox/send! root text nil (when (= :steer (:delivery o)) {:kind :steer}))]
                 (run! c :controller {:id (str "sent-" id) :command :outbox-sent! :args {:outbox id :message (:id m)}}))
      :sent (when-not (fs/exists? (fs/path (conversation-root c conversation) "mailbox" (str message ".json")))
              (run! c :controller {:id (str "acked-" id) :command :outbox-acked! :args {:outbox id}}))
      nil))))

(defn start!
  "Open the store under DIR, listen on a loopback port written to <dir>/port,
  and run the dispatcher every SCAN-MS. Returns the controller; `stop!` ends it.
  The controller is the store's only writer, so a second start on the same
  directory is refused while <dir>/lock is held."
  [{:keys [dir conversations scan-ms launch policy health-ms restart-grace-ms] :or {scan-ms 500 health-ms 120000}}]
  (fs/create-dirs dir)
  (let [channel (java.nio.channels.FileChannel/open (fs/path dir "lock")
                                                    (into-array java.nio.file.OpenOption
                                                                [java.nio.file.StandardOpenOption/CREATE
                                                                 java.nio.file.StandardOpenOption/WRITE]))
        _ (when-not (try (.tryLock channel) (catch Exception _ nil))
            (.close channel)
            (throw (ex-info (str "bbh super is already running on " dir) {:dir dir})))
        db (store/open (str (fs/path dir "super.db")))
        server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        c {:dir dir :conversations (or conversations (str (fs/path (data-dir) "conversations")))
           :launch (merge {:workspace! workspace! :wf-start! wf-start! :pane! pane!} launch) :policy policy
           :boot-at (System/currentTimeMillis) :recovery-lock (Object.) :restart-grace-ms restart-grace-ms :last-health (atom 0)
           :db db :state (atom (store/load db)) :server server :lock channel :stopped (atom false) :dispatcher (atom nil)}]
    (ensure-owner-token! c)
    (spit (str (fs/path dir "port")) (str (.getLocalPort server)))
    (doto (Thread. #(while (not @(:stopped c))
                      (try (serve-client c (.accept server))
                           (catch Exception e (when-not @(:stopped c) (binding [*out* *err*] (println "bbh super:" (ex-message e))))))))
      (.setDaemon true) (.start))
    (reset! (:dispatcher c)
     (doto (Thread. #(while (not @(:stopped c))
                      (try
                        (when (or (some (fn [w] (= :cancelling (:state w))) (vals (:workers @(:state c))))
                                  (> (- (System/currentTimeMillis) @(:last-health c)) health-ms))
                          (reconcile! c) (reset! (:last-health c) (System/currentTimeMillis)))
                        (observe-jobs! c) (dispatch! c)
                           (catch Exception e (binding [*out* *err*] (println "bbh super dispatch:" (ex-message e)))))
                      (Thread/sleep (long scan-ms))))
      (.setDaemon true) (.start)))
    c))

(defn stop! [{:keys [stopped server db dir lock dispatcher]}]
  (reset! stopped true)
  (.close ^ServerSocket server)
  ;; Wait for the current observation/delivery transaction before closing SQLite.
  (when-let [t @dispatcher] (.join ^Thread t))
  (fs/delete-if-exists (fs/path dir "port"))
  (locking db (store/close! db))
  (.close ^java.nio.channels.FileChannel lock))

(defn -main
  "bbh super serve: run the controller until killed."
  []
  (start! {:dir (super-dir)})
  (println "bbh super on" (super-dir))
  @(promise))
