(ns bbh.super
  "The super-agent controller: `bbh super serve`. Commands arrive on a loopback
  socket as one EDN line each with a token, are run through the store as one
  transaction, and the dispatcher drains the outbox into bbh mailboxes. `call!`
  is the client every surface and Worker uses."
  (:require [bbh.sysenv :as sysenv]
            [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.super.client :as client :refer [call! data-dir new-id super-dir]]
            [bbh.super.emacs :as emacs]
            [bbh.super.model :as model]
            [bbh.super.process :as process]
            [bbh.super.store :as store]
            [bbh.super.triage :as triage]
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

(defn notify-views!
  "Tell every Inbox view registered under DIR to reload its rows. A port that
  refuses the connection is forgotten."
  [dir]
  (doseq [f (when (fs/exists? (client/listeners-dir dir)) (fs/list-dir (client/listeners-dir dir)))
          :let [port (parse-long (str/trim (slurp (str f))))]]
    (try (with-open [sock (Socket. "127.0.0.1" (int port))
                     out (io/writer (.getOutputStream sock))]
           (let [body "changed"]
             (.write out (str "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " (count body) "\r\n\r\n" body))
             (.flush out)))
         (catch java.io.IOException _ (fs/delete-if-exists f)))))

(defn- shown
  "STATE without what changes on every health pass and no view shows: the
  heartbeat and when each health was observed."
  [state]
  (-> state
      (update :router dissoc :heartbeat)
      (update :workers update-vals #(cond-> % (:health %) (update :health dissoc :at :since)))))

(defn- run!
  "Commit COMMAND for PRINCIPAL on controller C. Serialized: the controller is
  the single writer. A commit that changed what the Inbox views show reloads
  them; a health pass that found nothing new does not."
  [{:keys [db state dir]} principal command]
  (let [[before r] (locking db
                     (let [before @state
                           r (store/commit! db before principal command)]
                       (reset! state (:state r))
                       [before r]))]
    (when-not (or (:repeat? r) (= (shown before) (shown (:state r)))) (notify-views! dir))
    r))

;; ---- Workers: a record first, then the workspace, the wf run and the buffer --

(defn- policy
  "The controller's policy over the defaults: its :policy when given, else
  ~/.config/bbh/policy.edn."
  [c]
  (merge model/default-policy
         (or (:policy c)
             (let [f (fs/path (or (sysenv/getenv "XDG_CONFIG_HOME") (fs/expand-home "~/.config")) "bbh" "policy.edn")]
               (when (fs/exists? f) (edn/read-string (slurp (str f))))))))

(defn- jj-out [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir dir :out :string :err :string :continue true} "jj" args)]
    (if (zero? exit) (str/trim out) (throw (ex-info (str "jj " (str/join " " args) ": " (str/trim err)) {:error :jj})))))

(defn workspace!
  "A jj workspace of REPO for WORKER under DIR, named <thread>-<generation>.
  Returns its path; an existing one is reused. A REPO that does not exist yet
  is a new project, made with `jj git init`."
  [dir {:keys [repo thread generation]}]
  (let [name (str thread "-" generation)
        path (str (fs/path dir "workspaces" name))]
    (when-not (fs/exists? repo)
      (fs/create-dirs repo)
      (jj-out repo "git" "init"))
    (when-not (fs/exists? path)
      (fs/create-dirs (fs/parent path))
      (jj-out repo "workspace" "add" "--name" name path))
    path))

(defn- revs
  "One line per revision of REVSET in DIR: commit id, then the first line of its description."
  [dir revset]
  (remove str/blank? (str/split-lines (jj-out dir "log" "--no-graph" "-r" revset "-T" "commit_id.short() ++ \" \" ++ description.first_line() ++ \"\\n\""))))

(def ^:private placeholder "(empty() & description(exact:\"\"))")

(defn land!
  "Rebase the revision of the Worker in WORKSPACE, the commits its workspace has
  that the line lacks, onto the tip of the line, so finished work forms one
  line. The line is the user's committed history, `::default@-` (their
  working-copy change stays in progress and is never moved), and LANDED, the
  change ids finished Workers landed. ONTO names the tip when
  the line has more than one head. Runs jj in WORKSPACE, so the user's working
  copy is not even snapshotted. Throws, leaving the Worker to act, when its
  working copy has uncommitted changes, when the tip is unclear, and when the
  rebase leaves conflicts in its commits; a repeat once they are resolved is a
  no-op. Returns {:onto tip :landed change-id}, the head of what it landed or nil."
  [{:keys [workspace landed onto]}]
  (let [refuse (fn [error message] (throw (ex-info message {:error error})))]
    (when (seq (revs workspace "@ & ~empty() & description(exact:\"\")"))
      (refuse :in-progress "your working copy has uncommitted changes: commit them, or discard them, before super-done!"))
    (let [line (str "(::default@-" (apply str (for [id landed] (str " | ::present(" id ")"))) ") ~ " placeholder)
          heads (if onto (revs workspace (str "exactly(" onto ", 1)")) (revs workspace (str "heads(" line ")")))
          _ (when (not= 1 (count heads))
              (refuse :unclear-tip (str "the line has " (count heads) " heads, so its tip is unclear: " (str/join "; " heads)
                                        ". Ask the person which one your revision goes on, then (super-done! {:onto \"<commit id>\"}).")))
          tip (first (str/split (first heads) #" "))
          mine (str "(" tip "..@) ~ " placeholder)]
      (if (empty? (revs workspace mine))
        {:onto tip :landed nil}
        (do (jj-out workspace "rebase" "-s" (str "roots(" tip "..@)") "-d" tip)
            (when-let [c (seq (revs workspace (str "(" mine ") & conflicts()")))]
              (refuse :conflicts (str "landing on " tip " left conflicts in " (str/join "; " c)
                                      ". Resolve them in your workspace, then super-done! again.")))
            {:onto tip :landed (jj-out workspace "log" "--no-graph" "-r" (str "heads(" mine ")") "-T" "change_id")})))))

(defn retire!
  "Forget the jj workspace of a done Worker and delete its directory, so its
  empty working-copy change does not dangle. A done finish was refused while
  the working copy had uncommitted changes, so nothing is lost. Runs in REPO
  without a snapshot, so the user's working copy is untouched."
  [{:keys [repo workspace]}]
  (let [name (str (fs/file-name workspace))
        names (set (str/split-lines (jj-out repo "--ignore-working-copy" "workspace" "list" "-T" "name ++ \"\\n\"")))]
    (when-not (names name) (throw (ex-info (str "no jj workspace " name " in " repo) {:error :jj})))
    (jj-out repo "--ignore-working-copy" "workspace" "forget" name)
    (fs/delete-tree workspace)))

(defn wf-start!
  "The wf run for WORKER in WORKSPACE, its brief as the task."
  [workspace {:keys [workflow brief check bench repro]}]
  (workflow/start! workflow {:cwd workspace :task brief :check check :bench bench :repro repro}))

(defn open!
  "The Worker's buffer in the super-agent's Emacs: a bbh session on the
  Worker's conversation in its jj workspace, with its token and wf run in the
  session's environment, named after the thread. Returns the buffer name."
  [{:keys [workspace thread title conversation token wf-run]}]
  (emacs/open! {:conversation conversation :dir workspace :title (str thread " " title)
                :env {"BBH_SUPER_TOKEN" token "WF_RUN" wf-run}}))

(declare run! promote!)

(defn- launch-steps! [{:keys [dir state launch] :as c} w token]
  (let [{:keys [workspace! wf-start! open!]} launch
        t (get-in @state [:threads (:thread w)])
        workspace (workspace! dir w)
        conversation (str (fs/path (:conversations c) (str "super-" (:id w))))
        _ (fs/create-dirs conversation)
        wf-run (or (:wf-run w) (wf-start! workspace w))
        _ (run! c :controller {:id (str "prepared-" (:id w)) :command :worker-prepared!
                                :args {:worker (:id w) :workspace workspace :wf-run wf-run
                                       :conversation (fs/file-name conversation) :started-at (System/currentTimeMillis)}})
        buffer (open! {:repo (:repo w) :workspace workspace :thread (:thread w) :title (:title t)
                     :conversation (fs/file-name conversation) :token token :wf-run wf-run})]
    (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                         :args {:started-at (System/currentTimeMillis) :worker (:id w) :conversation (fs/file-name conversation) :wf-run wf-run :workspace workspace :buffer buffer}})))

(defn- launch!
  "The side effects that make Worker W a process, then the commit that records
  them. Each step is idempotent by the Worker's name, so a repeat after a crash
  reuses what exists. A step that throws fails the Worker with its reason, so it
  frees its slot and its thread shows why, and the caller still gets the error."
  [c w token]
  (try (launch-steps! c w token)
       (catch Exception e
         (run! c :controller {:id (str "launch-failed-" (:id w)) :command :finish-worker!
                              :args {:worker (:id w) :state-after :failed :reason (str "Launch failed: " (ex-message e))}})
         (promote! c)
         (throw e))))

(defn- promote!
  "Start the next queued Worker that may run now, if any. Choosing it, moving
  it to :starting with its locks and issuing its token is one commit, so two
  callers at once never launch the same Worker or pass the cap."
  [{:keys [state] :as c}]
  (let [t (new-token)]
    (when-let [id (:result (run! c :controller {:id (str "promote-" (sha256 t)) :command :promote-worker!
                                                 :token-hash (sha256 t) :policy (policy c)}))]
      (launch! c (get-in @state [:workers id]) t)
      id)))

(defn- promote-all!
  "Start queued Workers until none may start now, as after a health pass or an
  archive that freed slots. A failed launch fails only that Worker."
  [{:keys [state] :as c}]
  (loop [n (count (filter #(= :queued (:state %)) (vals (:workers @state))))]
    (when (and (pos? n)
               (try (promote! c)
                    (catch Exception e
                      (binding [*out* *err*] (println "bbh super: promotion failed:" (ex-message e)))
                      true)))
      (recur (dec n)))))

(defn- expand-home [path]
  (let [p (str path)]
    (if (str/starts-with? p "~/") (str (fs/path (fs/home) (subs p 2))) p)))

(defn projects
  "The repositories Workers can start in, by name: policy :projects, with ~
  expanded, then every other repository a Worker has used, named after its
  folder, with that Worker's check. A listed project wins over a used one."
  [c]
  (let [listed (into {} (for [[n p] (:projects (policy c))] [(name n) (update p :repo expand-home)]))
        listed-repos (set (map (comp str fs/normalize :repo) (vals listed)))
        used (->> (vals (:workers @(:state c)))
                  (filter :repo)
                  (sort-by (comp model/id-order :id))
                  (remove #(listed-repos (str (fs/normalize (:repo %)))))
                  (map (fn [w] [(str (fs/file-name (:repo w))) {:repo (:repo w) :check (:check w) :used true}]))
                  (into {}))]
    (merge used listed)))

(defn- resolve-project
  "ARGS of start-worker! with :project replaced by its :repo, and its :check
  unless ARGS name one. An unknown name is refused with the known ones."
  [c {:keys [project] :as args}]
  (if-not project
    args
    (let [all (projects c)
          p (or (get all (name project))
                (throw (ex-info (str "no project " (pr-str (name project)) "; known: " (str/join ", " (sort (keys all))))
                                {:error :no-such-project :projects (vec (sort (keys all)))})))]
      (cond-> (-> args (dissoc :project) (assoc :repo (:repo p)))
        (and (not (:check args)) (:check p)) (assoc :check (:check p))))))

(defn- start-worker*
  "Commit start-worker! CMD for PRINCIPAL with the workflow's requirements and
  the policy, launch the Worker unless it queued or this is a repeat, and return
  its result with where it runs."
  [c principal cmd]
  (let [{:keys [args] :as cmd} (update cmd :args #(resolve-project c %))
        t (new-token)
        requires (try (:requires (workflow/load-workflow (:workflow args)))
                      (catch Exception _ (throw (ex-info (str "unknown workflow " (pr-str (:workflow args))) {:error :unknown-workflow}))))
        r (run! c principal (assoc cmd :token-hash (sha256 t) :policy (policy c) :requires requires))
        w (get-in @(:state c) [:workers (:id (:result r))])]
    (when (and (not (:repeat? r)) (= :starting (:state w)))
      (launch! c w t))
    (merge (:result r) (select-keys (get-in @(:state c) [:workers (:id (:result r))]) [:state :workspace :buffer :conversation :wf-run]))))

(defn- principal-of [state token]
  (when (string? token)
    (let [t (get-in state [:tokens (sha256 token)])]
      (when (and t (not (:revoked t))) (:principal t)))))

(defn evidence-sha
  "sha256 of wf run ID's evidence under ROOT (the wf state root): each file's
  path under the evidence directory and its bytes, in path order. \"none\" for a
  Worker without a wf run, whose approval then binds to the revision alone."
  [root id]
  (if-not id
    "none"
    (let [root (fs/path root id "evidence")
          md (MessageDigest/getInstance "SHA-256")]
      (doseq [f (sort-by str (filter fs/regular-file? (fs/glob root "**")))]
        (.update md (.getBytes (str (fs/relativize root f) "\n") "UTF-8"))
        (.update md (fs/read-all-bytes f)))
      (apply str (map #(format "%02x" %) (.digest md))))))

(defn- read-command
  "Reads never write and need no command id."
  [{:keys [db state] :as c} {:keys [command args]}]
  (let [st @state]
    (case command
      :health (into {} (map (fn [[id w]] [id (cond-> (:health w) (#{:done :cancelled} (:state w)) (assoc :classification :done) (= :failed (:state w)) (assoc :classification :needs-human))]) (:workers st)))
      :threads (vec (sort-by (comp model/id-order :id) (vals (:threads st))))
      :thread (let [{:keys [id]} args
                    t (get-in st [:threads id])
                    of (fn [kind order] (vec (sort-by order (keep #(when (= id (:thread %)) (model/linked st kind (:id %))) (vals (kind st))))))]
                (when t (assoc t :asks (of :asks :seq) :replies (of :replies :seq) :workers (of :workers :generation))))
      :events (store/events db (or (:after args) 0) (:thread args))
      :ask (model/linked st :asks (:id args))
      :reply (model/linked st :replies (:id args))
      :worker (model/linked st :workers (:id args))
      :projects (projects c)
      :sections (let [sections (model/sections st (or (:cursors args) {}))
                      {held :sections fresh :fresh} (when (:shown args) (model/hold (:shown args) sections))]
                  {:sections (or held sections) :badge (model/badge sections)
                   :order (model/order sections) :fresh (or fresh 0)})
      ::unknown)))

(declare reconcile!)

(def ^:private land-lock (Object.))

(defn- land-worker!
  "Land the revision of the Worker that CMD, a :done finish by PRINCIPAL, ends,
  before the finish is committed, so a refusal leaves it running. Returns the
  change id it landed, or nil. Called under land-lock with the commit, so the
  next one sees this one landed."
  [{:keys [state launch]} principal cmd]
  (let [st @state
        {:keys [worker onto]} (:args (model/authorize st principal cmd))
        w (get-in st [:workers worker])]
    (when (and (:workspace w) (fs/exists? (:workspace w)) (not (#{:done :failed :cancelled} (:state w))))
      (:landed ((:land! launch) {:workspace (:workspace w) :onto onto
                                 :landed (keep #(when (and (= :done (:state %)) (= (:repo w) (:repo %))) (:landed %)) (vals (:workers st)))})))))

(defn- retire-worker!
  "Remove done Worker WORKER's workspace; a failure is logged, the finish stands."
  [{:keys [state launch]} worker]
  (let [w (get-in @state [:workers worker])]
    (when (and (= :done (:state w)) (:repo w) (:workspace w) (fs/exists? (:workspace w)))
      (try ((:retire! launch) w)
           (catch Exception e (binding [*out* *err*] (println "bbh super: retiring" worker "failed:" (ex-message e))))))))

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
            :start-worker! {:ok (start-worker* c principal cmd)}
            :cancel!
            (let [r (run! c principal cmd)]
              (reconcile! c)
              {:ok (:result r)})
            ;; An archived thread's Worker no longer holds a slot.
            :archive!
            (let [r (run! c principal cmd)]
              (promote-all! c)
              {:ok (:result r)})
            (:ask! :approve!)
            (let [st @(:state c)
                  worker (if (= :ask! command)
                           (or (when (vector? principal) (second principal)) (get-in st [:threads (:thread args) :worker]))
                           (get-in st [:asks (:ask args) :worker]))
                  sha (evidence-sha (or (:wf-root c) (workflow/state-root)) (get-in st [:workers worker :wf-run]))
                  cmd (if (= :ask! command)
                        (cond-> cmd (= :review (keyword (:kind args))) (assoc-in [:args :evidence-sha] sha))
                        (assoc-in cmd [:args :current-sha] sha))]
              {:ok (:result (run! c principal cmd))})
            :finish-worker!
            (let [r (if (= :done (keyword (:state-after args)))
                      (locking land-lock
                        (let [r (run! c principal (assoc-in cmd [:args :landed] (land-worker! c principal cmd)))]
                          (retire-worker! c (:result r))
                          r))
                      (run! c principal cmd))]
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
        ;; Created owner-only before the token is written, so it is never readable by others.
        (fs/delete-if-exists f)
        (fs/create-file f {:posix-file-permissions "rw-------"})
        (spit (str f) t)
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

(defn- watchdog-brief [w health]
  (str "You are the Watchdog for Worker " (:id w) " of thread " (:thread w) ". The controller found it needs a person:\n\n"
       (:problem health) "\n\nIts record: " (pr-str (dissoc w :health :brief)) "\nIts health: " (pr-str health)
       "\n\nRead more with (sh \"bbh super worker '{:id \\\"" (:id w) "\\\"}'\") and the thread and events reads, and look at its conversation and jobs if that helps. "
       "Change nothing: you may only read. Then file exactly one stopped action ask, "
       "(super-ask! {:kind :action :text \"<what is wrong, in one line> Run: <the exact command that fixes it>\" "
       ":options [{:id \"run\" :label \"I will run it\"} {:id \"leave\" :label \"Leave it\"}] :stopped true}), "
       "and end your turn. When the reply arrives, call (super-answered! <reply id>) and (super-done!)."))

(defn- watchdog!
  "Start the Watchdog for Worker W's current problem: a thread that says which
  Worker it watches and a read-only investigation Worker on it, in W's
  repository. Keyed by the Worker and its problem, so a repeated pass starts
  nothing new. A Worker without a repository, one only attached to a
  conversation, gets none."
  [c w health]
  (when (:repo w)
    (let [k (str (:id w) "-" (subs (sha256 (str (:problem health))) 0 12))
          thread (:result (run! c :controller {:id (str "watchdog-thread-" k) :command :thread!
                                               :args {:title (str "Watchdog: " (:id w) " " (get-in @(:state c) [:threads (:thread w) :title]))
                                                      :watchdog-of (:id w)}}))]
      (start-worker* c :controller {:id (str "watchdog-worker-" k) :command :start-worker!
                                    :args {:thread thread :workflow :investigation :repo (:repo w) :brief (watchdog-brief w health)}}))))

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
                    since (if (and (= :needs-human classification) (= problem (get-in w [:health :problem])))
                            (or (get-in w [:health :since]) now)
                            now)
                    health {:at now :process (select-keys agent [:pid :start]) :repl repl-ok?
                            :workflow wf :classification classification :problem problem :since since
                            :oldest-unacked (some->> (vals (:outbox @state))
                                                    (filter #(and (= (:thread w) (:thread %)) (#{:pending :sent} (:state %))))
                                                    (sort-by (comp model/id-order :id)) first :id)
                            :oldest-stopped-ask (some->> (vals (:asks @state)) (filter #(and (= (:thread w) (:thread %)) (= :open (:state %)) (:stopped %))) (sort-by :seq) first :seq)
                            :jobs (mapv #(select-keys % [:id :state :pid :start]) jobs)}]]
        (run! c :controller {:id (new-id) :command :health! :args {:worker (:id w) :health health :at now}})
        (when (and (= :needs-human classification)
                   (>= (- now since) (* 60000 (:watchdog-after-minutes (policy c))))
                   (not (get-in @state [:threads (:thread w) :watchdog-of]))
                   (not (get-in @state [:threads (:thread w) :resolved-at])))
          ;; A failed start is retried next pass by the same command ids; the log says why.
          (try (watchdog! c w health)
               (catch Exception e
                 (binding [*out* *err*] (println "bbh super: Watchdog for" (:id w) "did not start:" (ex-message e))))))
        (cond
          (and agent (= :starting (:state w)) (:wf-run w))
          (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                               :args (merge (select-keys w [:conversation :wf-run :workspace :buffer :started-at]) {:worker (:id w)})})

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
                                    :args {:worker (:id w) :state-after :failed
                                           :reason (str "Worker failed three restart attempts; needs human"
                                                        (when-let [e (:restart-error w)] (str ". Last error: " e)))}})
                (promote! c))
            (let [token (new-token)]
              (run! c :controller {:id (new-id) :command :restart-worker! :token-hash (sha256 token) :args {:worker (:id w) :at now}})
              (try
                (let [buffer ((get-in c [:launch :open!])
                            {:repo (:repo w) :workspace (:workspace w) :thread (:thread w)
                             :title (get-in @state [:threads (:thread w) :title])
                             :conversation (:conversation w) :token token :wf-run (:wf-run w)})]
                  (if (= :starting (:state w))
                    (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                                         :args (merge (select-keys w [:conversation :wf-run :workspace :started-at]) {:worker (:id w) :buffer buffer})})
                    (run! c :controller {:id (new-id) :command :worker-restarted! :args {:worker (:id w) :buffer buffer}})))
                (catch Exception e
                  ;; The attempt is already counted; say why it failed so the end state names it.
                  (binding [*out* *err*] (println "bbh super: restart of" (:id w) "failed:" (ex-message e)))
                  (run! c :controller {:id (new-id) :command :restart-failed! :args {:worker (:id w) :reason (ex-message e)}}))))))))))

(defn- untriaged?
  "Whether outbox row O for the Router still waits for Jev's verdict on C."
  [c o]
  (and (:triage c) (not= :summary (:delivery o)) (nil? (:triage o))))

(defn triage!
  "Judge each pending Router message once. Runs on its own thread, so a slow or
  down TypeSafe delays only the Router, never a Worker's delivery."
  [{:keys [state] :as c}]
  (let [router (get-in @state [:router :router :conversation])]
    (doseq [o (sort-by (comp model/id-order :id) (vals (:outbox @state)))
            :when (and (= :pending (:state o)) (= router (:conversation o)) (untriaged? c o))]
      (let [verdict (try ((:triage c) (str/replace (:text o) #"^\[[^\]]*\] " ""))
                         (catch Exception _ {:failed true}))]
        (run! c :controller {:id (str "triaged-" (:id o)) :command :router-triaged!
                             :args (assoc verdict :outbox (:id o) :threshold (:noise-probability (policy c)))})))))

(defn dispatch!
  "One pass: send each pending outbox row to its Worker's mailbox, and mark a
  sent row acked once its mailbox file is gone. Each step is a command with a
  fixed id, so a pass repeated after a crash changes nothing twice. Router
  messages wait for `triage!`."
  [{:keys [state] :as c}]
  (let [router (get-in @state [:router :router :conversation])
        rows (sort-by (comp model/id-order :id) (vals (:outbox @state)))
        for-router? (fn [o] (and (= :pending (:state o)) (= router (:conversation o))))
        batch (filter for-router? rows)
        line (fn [{:keys [id text delivery] {:keys [choice probability]} :triage}]
               (str (when-not (= :summary delivery) (str id " "))
                    (when choice (format "(Jev: %s %.2f) " (name choice) probability))
                    text))]
    ;; The Router reads its pending rows as one prompt, oldest first, each with
    ;; Jev's triage. Summaries of messages already routed to a Worker ride along
    ;; but never wake it alone, and noise never reaches it.
    (when (and (some #(not= :summary (:delivery %)) batch) (not-any? #(untriaged? c %) batch))
      (let [m (inbox/send! (conversation-root c router) (str/join "\n\n" (map line batch)))]
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
  and run the dispatcher every SCAN-MS. TRIAGE, a function from a message to
  {:choice :probability}, judges what reaches the Router (none: all of it).
  Returns the controller; `stop!` ends it.
  The controller is the store's only writer, so a second start on the same
  directory is refused while <dir>/lock is held."
  [{:keys [dir conversations scan-ms launch policy health-ms restart-grace-ms triage wf-root] :or {scan-ms 500 health-ms 120000}}]
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
           :launch (merge {:workspace! workspace! :wf-start! wf-start! :open! open! :land! land! :retire! retire!} launch) :policy policy
           :boot-at (System/currentTimeMillis) :recovery-lock (Object.) :restart-grace-ms restart-grace-ms :last-health (atom 0) :triage triage :wf-root wf-root
           :db db :state (atom (store/load db)) :server server :lock channel :stopped (atom false) :dispatcher (atom nil) :triager (atom nil)}]
    (ensure-owner-token! c)
    (spit (str (fs/path dir "port")) (str (.getLocalPort server)))
    ;; Each client gets its own thread and a read timeout, so a client that
    ;; never sends its line, or a slow launch, holds up nobody else. Commits
    ;; stay serialized by run!.
    (doto (Thread. #(while (not @(:stopped c))
                      (try (let [sock (.accept server)]
                             (.setSoTimeout sock 10000)
                             (doto (Thread. (fn [] (try (serve-client c sock)
                                                        (catch Exception e (when-not @(:stopped c) (binding [*out* *err*] (println "bbh super:" (ex-message e))))))))
                               (.setDaemon true) (.start)))
                           (catch Exception e (when-not @(:stopped c) (binding [*out* *err*] (println "bbh super:" (ex-message e))))))))
      (.setDaemon true) (.start))
    (when triage
      (reset! (:triager c)
              (doto (Thread. #(while (not @(:stopped c))
                                (try (triage! c)
                                     (catch Exception e (binding [*out* *err*] (println "bbh super triage:" (ex-message e)))))
                                (Thread/sleep (long scan-ms))))
                (.setDaemon true) (.start))))
    (reset! (:dispatcher c)
     (doto (Thread. #(while (not @(:stopped c))
                      (try
                        (when (or (some (fn [w] (= :cancelling (:state w))) (vals (:workers @(:state c))))
                                  (> (- (System/currentTimeMillis) @(:last-health c)) health-ms))
                          ;; A pass can find a Worker gone, which frees its slot.
                          (reconcile! c) (promote-all! c) (reset! (:last-health c) (System/currentTimeMillis)))
                        (observe-jobs! c) (dispatch! c)
                           (catch Exception e (binding [*out* *err*] (println "bbh super dispatch:" (ex-message e)))))
                      (Thread/sleep (long scan-ms))))
      (.setDaemon true) (.start)))
    c))

(defn stop! [{:keys [stopped server db dir lock dispatcher triager]}]
  (reset! stopped true)
  (.close ^ServerSocket server)
  ;; Wait for the current observation/delivery transaction before closing SQLite.
  (when-let [t @dispatcher] (.join ^Thread t))
  ;; A triage call can take its HTTP timeout; its commit after close would fail harmlessly.
  (when-let [t @triager] (.interrupt ^Thread t) (.join ^Thread t 1000))
  (fs/delete-if-exists (fs/path dir "port"))
  (locking db (store/close! db))
  (.close ^java.nio.channels.FileChannel lock))

(defn -main
  "bbh super serve: run the controller until killed."
  []
  (start! {:dir (super-dir) :triage triage/jev})
  (println "bbh super on" (super-dir))
  @(promise))
