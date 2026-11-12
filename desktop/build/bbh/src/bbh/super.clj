(ns bbh.super
  "The super-agent controller: `bbh super serve`. Commands arrive on a loopback
  socket as one EDN line each with a token, are run through the store as one
  transaction, and the dispatcher drains the outbox into bbh mailboxes. `call!`
  is the client every surface and Worker uses."
  (:require [bbh.sysenv :as sysenv]
            [babashka.fs :as fs]
            [bbh.inbox :as inbox]
            [bbh.conversation :as conversation]
            [bbh.super.client :as client :refer [call! data-dir new-id super-dir]]
            [bbh.super.emacs :as emacs]
            [bbh.super.model :as model]
            [bbh.super.notify :as notify]
            [bbh.super.process :as process]
            [bbh.super.sessions :as sessions]
            [bbh.super.store :as store]
            [bbh.super.workspace :as workspace]
            [bbh.version :as version]
            [bbh.workflow :as workflow]
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
  "Tell every Inbox view registered under DIR to reload its rows, and the hooks
  of this process, such as the website's stream. A port that refuses the
  connection is forgotten."
  [dir]
  (client/changed! dir)
  (doseq [f (when (fs/exists? (client/listeners-dir dir)) (fs/list-dir (client/listeners-dir dir)))
          :let [port (parse-long (str/trim (slurp (str f))))]]
    (try (with-open [sock (Socket. "127.0.0.1" (int port))
                     out (io/writer (.getOutputStream sock))]
           (let [body "changed"]
             (.write out (str "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: " (count body) "\r\n\r\n" body))
             (.flush out)))
         (catch java.io.IOException _ (fs/delete-if-exists f)))))

(defn- shown
  "STATE without what no view shows: when each health was observed."
  [state]
  (update state :workers update-vals #(cond-> % (:health %) (update :health dissoc :at :since))))

(declare policy)

(defn- run!
  "Commit COMMAND for PRINCIPAL on controller C. Serialized: the controller is
  the single writer. A commit that changed what the Inbox views show reloads
  them; a health pass that found nothing new does not. What started waiting
  on the person goes to the notifier."
  [{:keys [db state dir notifier] :as c} principal command]
  (let [[before r] (locking db
                     (let [before @state
                           r (store/commit! db before principal command)]
                       (reset! state (:state r))
                       [before r]))]
    (when-not (or (:repeat? r) (= (shown before) (shown (:state r)))) (notify-views! dir))
    (when (and notifier (not (identical? before (:state r))))
      (try (notify/observe! notifier before (:state r) (System/currentTimeMillis) (policy c))
           (catch Exception e (binding [*out* *err*] (println "bbh super: notifying failed:" (ex-message e))))))
    r))

;; ---- Workers: a record first, then the workspace, the wf run and the buffer --

(defn- policy
  "The controller's policy over the defaults: its :policy when given, else
  ~/.config/bbh/policy.edn."
  [c]
  (merge model/default-policy
         (or (:policy c)
             (let [f (client/policy-file)]
               (when (fs/exists? f) (edn/read-string (slurp (str f))))))))

(defn wf-start!
  "The wf run for WORKER in WORKSPACE, its brief as the task."
  [workspace {:keys [workflow brief check bench repro]}]
  (workflow/start! workflow {:cwd workspace :task brief :check check :bench bench :repro repro}))

(defn wf-close!
  "Close wf run WF-RUN of a Worker that ended in STATE for REASON: a run still
  active or blocked is abandoned with the reason, so it is nobody's default
  run. A run that is done, abandoned or was never written stays as it is."
  [{:keys [wf-run state reason]}]
  (when (fs/exists? (fs/path (workflow/state-root) wf-run "journal.edn"))
    (when (#{:active :blocked} (:status (workflow/status wf-run)))
      (workflow/abandon! wf-run (str "its Worker ended " (name state) (when-not (str/blank? reason) (str ": " reason)))))))

(defn open!
  "The Worker's buffer in the super-agent's Emacs: a bbh session on the
  Worker's conversation in its jj workspace, with its token and wf run in the
  session's environment, named after the thread, and its MODEL, when it has
  one, as BBH_MODEL. Returns the buffer name."
  [{:keys [workspace thread title conversation token wf-run model]}]
  (emacs/open! {:conversation conversation :dir workspace :title (str thread " " title)
                :env (cond-> {"BBH_SUPER_TOKEN" token "WF_RUN" wf-run} model (assoc "BBH_MODEL" model))}))

(defn release!
  "Move the live Emacs session of CONVERSATION out of its workspace into DIR,
  so deleting the workspace cannot leave it stuck busy. Returns its buffer name,
  or nil when none runs."
  [{:keys [conversation dir]}]
  (some-> (emacs/call! "eng/bbh-super-release" conversation dir) emacs/read-string* (#(when-not (= "nil" %) %))))

(declare run! promote!)

(defn- launch-steps! [{:keys [dir state launch] :as c} w token]
  (let [{:keys [workspace! wf-start! open!]} launch
        t (get-in @state [:threads (:thread w)])
        workspace (workspace! dir (assoc w :title (:title t)))
        conversation (conversation/dir (:conversations c) (str "super-" (:id w)))
        _ (fs/create-dirs conversation)
        wf-run (or (:wf-run w) (wf-start! workspace w))
        _ (run! c :controller {:id (str "prepared-" (:id w)) :command :worker-prepared!
                                :args {:worker (:id w) :workspace workspace :wf-run wf-run
                                       :conversation (fs/file-name conversation) :started-at (System/currentTimeMillis)}})
        buffer (open! {:repo (:repo w) :workspace workspace :thread (:thread w) :title (:title t) :model (:model w)
                     :conversation (fs/file-name conversation) :token token :wf-run wf-run})]
    (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                         :args {:started-at (System/currentTimeMillis) :worker (:id w) :conversation (fs/file-name conversation) :wf-run wf-run :workspace workspace :buffer buffer}})))

(defn- launch!
  "The side effects that make Worker W a process, then the commit that records
  them. Each step is idempotent by the Worker's name, so a repeat after a crash
  reuses what exists. A step that throws fails the Worker with its reason, so it
  frees its slot and its thread shows why, and the caller still gets the error,
  except a buffer that could not open because Emacs is down: that Worker waits."
  [c w token]
  (try (launch-steps! c w token)
       (catch Exception e
         (if (and (= :emacs (:error (ex-data e))) (not ((get-in c [:launch :emacs-up?]))))
           ;; Emacs is down: the Worker stays :starting, and reconcile! opens
           ;; its buffer once Emacs is back.
           (binding [*out* *err*] (println "bbh super:" (:id w) "waits for Emacs:" (ex-message e)))
           (do (run! c :controller {:id (str "launch-failed-" (:id w)) :command :finish-worker!
                                    :args {:worker (:id w) :state-after :failed :reason (str "Launch failed: " (ex-message e))}})
               (promote! c)
               (throw e))))))

(defn- promote!
  "Start the next queued Worker that may run now, if any. Choosing it, moving
  it to :starting and issuing its token is one commit, so two
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

(defn- resolve-start
  "ARGS of start-worker! with what they leave out filled in, so a thread and a
  brief are enough to continue a thread. :project names a project, which brings
  its :repo and :check; an unknown name is refused with the known ones. Without
  one, the thread's last Worker brings its :repo, :check and :bench. Its
  :workflow is the default either way. A :repo that is a known project's
  brings that project's :check. What ARGS name always wins."
  [c {:keys [thread project] :as args}]
  (let [all (projects c)
        last-w (->> (vals (:workers @(:state c))) (filter #(= thread (:thread %))) (sort-by (comp model/id-order :id)) last)
        p (when project
            (or (get all (name project))
                (throw (ex-info (str "no project " (pr-str (name project)) "; known: " (str/join ", " (sort (keys all))))
                                {:error :no-such-project :projects (vec (sort (keys all)))}))))
        from (cond p (select-keys p [:repo :check])
                   (and last-w (or (nil? (:repo args)) (= (:repo args) (:repo last-w)))) (select-keys last-w [:repo :check :bench]))
        args (merge (select-keys last-w [:workflow]) from (dissoc args :project))
        known (when-let [r (:repo args)] (some #(when (= (str (fs/normalize r)) (str (fs/normalize (:repo %)))) %) (vals all)))]
    (cond-> args
      (and (not (:check args)) (:check known)) (assoc :check (:check known)))))

(defn- start-worker*
  "Commit start-worker! CMD for PRINCIPAL with the workflow's requirements and
  the policy, launch the Worker unless it queued or this is a repeat, and return
  its result with where it runs."
  [c principal cmd]
  (let [{:keys [args] :as cmd} (update cmd :args #(resolve-start c %))
        t (new-token)
        requires (try (:requires (workflow/load-workflow (:workflow args)))
                      (catch Exception _ (throw (ex-info (str "unknown workflow " (pr-str (:workflow args))) {:error :unknown-workflow}))))
        r (run! c principal (assoc cmd :token-hash (sha256 t) :policy (policy c) :requires requires))
        w (get-in @(:state c) [:workers (:id (:result r))])]
    (when (and (not (:repeat? r)) (= :starting (:state w)))
      (launch! c w t))
    (merge (:result r) (select-keys (get-in @(:state c) [:workers (:id (:result r))]) [:state :workspace :buffer :conversation :wf-run]))))

(defn- reopen!
  "Open resumed Worker WORKER's buffer on its conversation and wf run with
  its new TOKEN, for the command with id ID. A buffer that does not open is an
  error, and the Worker shows as ended again on the next health pass."
  [{:keys [state launch] :as c} worker token id]
  (let [w (get-in @state [:workers worker])
        buffer ((:open! launch) {:repo (:repo w) :workspace (:workspace w) :thread (:thread w) :model (:model w)
                                 :title (get-in @state [:threads (:thread w) :title])
                                 :conversation (:conversation w) :token token :wf-run (:wf-run w)})]
    (run! c :controller {:id (str "resumed-" id) :command :worker-prepared! :args {:worker worker :buffer buffer}})))

(defn- resume*
  "Commit resume! CMD for PRINCIPAL, then reopen the Worker's buffer. Returns
  the Worker's id."
  [c principal cmd]
  (let [t (new-token)
        r (run! c principal (assoc cmd :token-hash (sha256 t) :policy (policy c)))]
    (when-not (:repeat? r) (reopen! c (:result r) t (:id cmd)))
    (:result r)))

(defn- steer*
  "Commit steer! CMD for PRINCIPAL. When it resumed a Worker whose session
  ended, reopen its buffer, which then takes the steering text."
  [c principal cmd]
  (let [t (new-token)
        r (run! c principal (assoc cmd :token-hash (sha256 t) :policy (policy c)))]
    (when (and (:resumed (:result r)) (not (:repeat? r))) (reopen! c (:worker (:result r)) t (:id cmd)))
    (:result r)))

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
      :threads (vec (map #(model/thread-view st %) (sort-by (comp model/id-order :id) (vals (:threads st)))))
      :thread (let [{:keys [id]} args
                    t (some->> (get-in st [:threads id]) (model/thread-view st))
                    of (fn [kind order] (vec (sort-by order (keep #(when (= id (:thread %)) (model/linked st kind (:id %))) (vals (kind st))))))]
                (when t (assoc t :asks (of :asks :seq) :replies (of :replies :seq) :workers (of :workers :generation))))
      :events (store/events db (or (:after args) 0) (:thread args))
      :ask (model/linked st :asks (:id args))
      :reply (model/linked st :replies (:id args))
      :worker (model/linked st :workers (:id args))
      :projects (projects c)
      :machine @(:machine c)
      :sections (let [sections (model/sections st)
                      {held :sections fresh :fresh} (when (:shown args) (model/hold (:shown args) sections))]
                  {:sections (or held sections) :badge (model/badge sections) :machine @(:machine c)
                   :order (model/order sections) :fresh (or fresh 0)})
      ::unknown)))

(declare reconcile! ensure-router! conversation-root conversation-jobs)

(def ^:private land-lock (Object.))

(defn- land-worker!
  "Land the revision of the Worker that CMD, a :done finish by PRINCIPAL, ends,
  before the finish is committed, so a refusal leaves it running. Returns what
  land! returns, or nil when there is nothing to land. Called under land-lock
  with the commit, so the next one sees this one landed."
  [{:keys [state launch]} principal cmd]
  (let [st @state
        {:keys [worker onto]} (:args (model/authorize st principal cmd))
        w (get-in st [:workers worker])]
    (when (and (:workspace w) (fs/exists? (:workspace w)) (not (#{:done :failed :cancelled} (:state w))))
      ((:land! launch) {:workspace (:workspace w) :onto onto :thread (:thread w) :landed (workspace/landed-ids st (:repo w))}))))

(defn- advance-worker!
  "After Worker WORKER landed TIP, move the user's working copy onto it with
  advance!. When it stays, the thread gets a milestone saying why and how to
  take the work, so the user decides. Called under land-lock."
  [{:keys [state launch] :as c} worker tip]
  (let [{:keys [repo thread]} (get-in @state [:workers worker])
        r ((:advance! launch) {:repo repo :tip tip :move (:move-working-copy (policy c))})]
    (when-let [text (workspace/kept-milestone {:worker worker :repo repo :tip tip} r)]
      ;; A fixed id, so a retry after a crash says it once.
      (run! c :owner {:id (str "kept-" worker) :command :milestone! :args {:thread thread :text text}}))
    r))

(defn- stop-jobs!
  "Stop the detached jobs of Worker WORKER that are still running, now that it
  ended, each marked exited 137. Jobs whose identity cannot be verified are
  left alone."
  [{:keys [state] :as c} worker]
  (when-let [conversation (get-in @state [:workers worker :conversation])]
    (doseq [j (conversation-jobs c conversation)
            :when (and (= :running (:state j)) (process/stop-job! j))]
      (spit (str (:base j) ".exit") "137\n"))))

(defn- release-session!
  "Move Worker W's session out of its workspace before the workspace goes. Its
  buffer outlives the Worker, so the person can still talk to it, and a shell
  whose directory is deleted stays busy forever. With Emacs down there is no
  session to move, so a failure is logged and the retire goes on."
  [{:keys [launch]} w]
  (when (:conversation w)
    (try ((:release! launch) {:conversation (:conversation w)
                              :dir (str (if (some-> (:repo w) fs/directory?) (:repo w) (fs/home)))})
         (catch Exception e (binding [*out* *err*] (println "bbh super: releasing" (:id w) "failed:" (ex-message e)))))))

(defn- retire-worker!
  "Remove Worker WORKER's workspace when it is retirable?, keeping work that
  did not land under a bookmark."
  [{:keys [state launch] :as c} worker]
  (let [st @state
        w (get-in st [:workers worker])]
    (when (and (:workspace w) (workspace/retirable? st w))
      (release-session! c w)
      ((:retire! launch) (assoc w :keep? (not= :done (:state w)))))))

(defn- effect!
  "Run effect KIND that finished Worker W owes. Each can run again after a
  crash: a stopped job, a closed run, a working copy already on the tip and a
  workspace already gone are all left as they are."
  [{:keys [launch] :as c} w kind]
  (case kind
    :stop-jobs (stop-jobs! c (:id w))
    :close-wf ((:wf-close! launch) (select-keys w [:id :wf-run :state :reason]))
    :advance (advance-worker! c (:id w) (:landed w))
    :retire (retire-worker! c (:id w))))

(defn- settle!
  "Run what finished Workers still owe (model/obligations), in order, and
  record each under a fixed id, effect-<worker>-<kind> when done or
  effect-<worker>-<kind>-fail-<n> with its error, so a repeat records nothing twice. A Worker's first
  failure ends its pass; the next health pass tries again, and after
  model/effect-tries the thread needs the person. Under land-lock, since an
  effect moves the person's working copy."
  [{:keys [state] :as c}]
  (locking land-lock
    (doseq [id (keep #(when (:owes %) (:id %)) (vals (:workers @state)))]
      (loop []
        (let [st @state
              w (get-in st [:workers id])]
          (when-let [kind (first (model/obligations st w))]
            (when (try (effect! c w kind)
                       (run! c :controller {:id (str "effect-" id "-" (name kind)) :command :effect-done! :args {:worker id :kind kind}})
                       true
                       (catch Exception e
                         (binding [*out* *err*] (println "bbh super:" (name kind) "of" id "failed:" (ex-message e)))
                         (run! c :controller {:id (str "effect-" id "-" (name kind) "-fail-" (inc (get-in w [:effects kind :tries] 0)))
                                              :command :effect-failed! :args {:worker id :kind kind :error (or (ex-message e) (str e))}})
                         false))
              (recur))))))))

(defn- sweep!
  "The workspace directories under DIR/workspaces that no live Worker owns, as
  {:workspace :worker :state :repo}. With CONFIRM, each is retired as its
  Worker's finish would have been, work off the line kept under a bookmark."
  [{:keys [dir state launch] :as c} confirm]
  (let [st @state]
    (vec (for [{d :workspace :as row} (workspace/orphans dir st)
               :let [w (get-in st [:workers (:worker row)])]]
           (if confirm
             (do (when w (release-session! c w))
                 (assoc row :bookmark ((:retire! launch) {:repo (:repo row) :workspace d :keep? (not= :done (:state w))})))
             row)))))

(defn- finish*
  "Finish the Worker that CMD names for PRINCIPAL. A :done finish lands its
  revision first, under land-lock with the commit, so the next landing sees this
  one, and moves the user's working copy onto it. A landing that left conflicts
  commits nothing and returns them, so the Worker resolves them and finishes
  again. Then settle! runs what its end owes and a queued Worker may take its
  slot."
  [c principal {:keys [args] :as cmd}]
  (let [r (if (= :done (keyword (:state-after args)))
            (locking land-lock
              (let [l (land-worker! c principal cmd)]
                (if (:conflicts l)
                  {:conflicts (select-keys l [:conflicts :todo])}
                  (let [r (run! c principal (assoc-in cmd [:args :landed] (:landed l)))]
                    r))))
            (run! c principal cmd))]
    (if-let [conflicts (:conflicts r)]
      conflicts
      (do (settle! c)
          (when-not (:repeat? r) (promote! c))
          (:result r)))))

(defn handle
  "The response for one request map on controller C."
  [c {:keys [token id command args]}]
  (try
    (let [principal (or (principal-of @(:state c) token) (throw (ex-info "unauthorized" {:error :unauthorized})))
          command (keyword command)
          read (read-command c {:command command :args args})]
      (if (not= ::unknown read)
        {:ok read}
        (let [cmd {:id (or id (new-id)) :command command :args (or args {}) :policy (policy c)}]
          (case command
            :register-router!
            (let [t (new-token)
                  r (run! c principal (assoc cmd :token-hash (sha256 t)))]
              {:ok (cond-> (:result r) (not (:repeat? r)) (assoc :token t))})
            :start-worker! {:ok (start-worker* c principal cmd)}
            :resume! {:ok (resume* c principal cmd)}
            :steer! {:ok (steer* c principal cmd)}
            :cancel!
            (let [r (run! c principal cmd)]
              (reconcile! c)
              (settle! c)
              {:ok (:result r)})
            ;; An archived thread's Worker no longer holds a slot.
            :archive!
            (let [r (run! c principal cmd)]
              ;; Workers that ended before effects were recorded owe nothing, so theirs go here.
              (doseq [w (vals (:workers @(:state c))) :when (and (= (:thread args) (:thread w)) (not (:owes w)))]
                (try (retire-worker! c (:id w))
                     (catch Exception e (binding [*out* *err*] (println "bbh super: retiring" (:id w) "failed:" (ex-message e))))))
              (settle! c)
              (promote-all! c)
              {:ok (:result r)})
            :sweep!
            (if (= :owner principal)
              {:ok (sweep! c (:confirm args))}
              {:error :forbidden :message "only the owner sweeps workspaces"})
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
            :finish-worker! {:ok (finish* c principal cmd)}
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
  (let [dir (conversation/dir conversations id)]
    (if (fs/exists? dir) (conversation/root dir) dir)))

(defn- repl-dirs
  "Where conversation ID's REPL may keep repl.port and jobs/. A REPL writes
  them where it started and keeps running when its session continues, so
  that can be any conversation in the chain."
  [c id]
  (conversation/chain (conversation-root c id)))

(defn- conversation-jobs [c id]
  (into [] (mapcat process/jobs) (repl-dirs c id)))

(defn observe-jobs!
  "Rediscover completed detached jobs from their durable exit markers. Running
  jobs are left alone. No execution is launched here, including after restart.
  The event and its outbox row commit together with a stable worker/job id."
  [{:keys [state] :as c}]
  (doseq [w (vals (:workers @state))
          :when (and (:conversation w) (= :working (:state w)))
          dir (repl-dirs c (:conversation w))
          f (fs/glob (conversation/file dir :jobs) "*.exit")
          :let [job (str/replace (fs/file-name f) #"\.exit$" "")
                base (fs/path (fs/parent f) job)]
          :when (and (not (get-in w [:jobs job])) (fs/exists? (str base ".cmd")))
          :let [exit (try (parse-long (str/trim (slurp (str f))))
                          (catch java.io.IOException _ nil))
                waiting-until (some-> (process/read-text (str base ".waiting")) parse-long)]
          ;; A job-wait that is still running reports this exit itself.
          :when (and (some? exit) (not (and waiting-until (< (System/currentTimeMillis) waiting-until))))]
    (run! c :controller {:id (str "job-exited-" (pr-str [(:id w) job])) :command :job-exited!
                         :args {:worker (:id w) :job job :exit exit :log (str base ".log")
                                :seen (fs/exists? (str base ".seen"))}})))

;; ---- reaping the sessions of Workers that ended ---------------------------

(defn end-session!
  "Tell the super-agent's Emacs that CONVERSATION's Worker ended: its buffer
  stays readable, sends what you type to the Router, and shuts its client down
  so it never starts claude again (T55)."
  [{:keys [conversation]}]
  (emacs/call! "eng/bbh-super-end" conversation))

(def session-io
  "How the controller reads and ends sessions; a test passes its own as :sessions."
  {:live sessions/live :kill! sessions/kill! :end! end-session! :machine sessions/machine})

(defn- session-agents
  "Each conversation of SESSIONS to its announced session, from agent.json."
  [c sessions]
  (into {} (keep #(when-let [a (inbox/agent (conversation-root c (:conversation %)))] [(:conversation %) a])) sessions))

(defn- reap!
  "End session S of a Worker that ended: Emacs first, so its buffer stays and
  does not respawn, then its process group. Returns whether it is gone."
  [{:keys [sessions]} s]
  (try ((:end! sessions) s)
       (catch Exception e (binding [*out* *err*] (println "bbh super: Emacs did not end" (:conversation s) "-" (ex-message e)))))
  (let [gone ((:kill! sessions) s)]
    (println "bbh super: reaped the session of" (:conversation s) (if gone "" "(still running)"))
    gone))

(defn- reap-sessions!
  "Kill the SESSIONS of this controller's Workers that ended, each once it is
  idle or after the grace. Returns {conversation gone?} for each it tried."
  [{:keys [state stale-since reap-grace-ms] :as c} sessions & {:keys [all?]}]
  (let [agents (session-agents c sessions)
        {:keys [kill since stale]} (sessions/plan @state sessions agents @stale-since
                                                  (System/currentTimeMillis) (or reap-grace-ms sessions/default-grace-ms))
        ;; A sweep of ALL? sessions forgets those no longer stale, as a resumed Worker's.
        _ (swap! stale-since #(merge (if all? (select-keys % (map :conversation stale)) %) since))
        tried (into {} (map (juxt :conversation #(boolean (reap! c %)))) kill)]
    (swap! stale-since #(apply dissoc % (keep (fn [[conversation gone]] (when gone conversation)) tried)))
    tried))

(defn observe-machine!
  "The health pass's sweep and log line: reap the sessions of Workers that
  ended, then record MemAvailable, swap used and live sessions against active
  Workers as a :machine-observed event, kept for bbh super list."
  [{:keys [state sessions machine] :as c}]
  (let [live ((:live sessions))
        tried (reap-sessions! c live :all? true)
        reaped (keep (fn [[conversation gone]] (when gone conversation)) tried)
        live (remove (comp (set reaped) :conversation) live)
        report (merge ((:machine sessions))
                      (sessions/report @state live (session-agents c live) (sessions/stale @state live) reaped)
                      {:at (System/currentTimeMillis)})]
    (reset! machine report)
    (run! c :controller {:id (new-id) :command :machine-observed! :args report})
    report))

(defn- reap-when-idle!
  "Watch the session of Worker WORKER, which just ended, and reap it once its
  turn ends, as after the Worker's super-done! and report, or after the grace.
  Stops when the session is gone or the Worker runs again."
  [{:keys [state stopped] :as c} worker]
  (doto (Thread. #(loop []
                    (let [w (get-in @state [:workers worker])
                          a (some->> (:conversation w) (conversation-root c) inbox/agent)
                          s (some-> a (select-keys [:pid :start]) (assoc :conversation (:conversation w)))]
                      ;; One attempt: a session it cannot kill, or an error, is the health pass's to retry.
                      (when (and s (not @stopped) (not (sessions/holds-session? w))
                                 (= {} (try (reap-sessions! c [s])
                                            (catch Exception e (binding [*out* *err*] (println "bbh super: reaping" worker "failed:" (ex-message e)))))))
                        (Thread/sleep 2000)
                        (recur))))
               (str "reap-" worker))
    (.setDaemon true) (.start)))

(defn- watch-ended!
  "Start reap-when-idle! for each Worker that left the active states since the
  last scan. Workers that ended while the controller was down are the health
  pass's to sweep."
  [{:keys [state was-active] :as c}]
  (let [active (set (keep #(when (sessions/holds-session? %) (:id %)) (vals (:workers @state))))
        ended (remove active @was-active)]
    (reset! was-active active)
    (doseq [id ended :when (get-in @state [:workers id :conversation])]
      (reap-when-idle! c id))))

(defn- repl-answering? [dir]
  (try
    (let [port (some-> (process/read-text (conversation/file dir :repl-port)) parse-long)]
      (when port
        (with-open [s (Socket.)]
          (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 300)
          (.setSoTimeout s 300)
          (bencode/write-bencode (.getOutputStream s) {"op" "describe" "id" "health"})
          (.flush (.getOutputStream s))
          (boolean (bencode/read-bencode (java.io.PushbackInputStream. (.getInputStream s)))))))
    (catch Exception _ false)))

(defn reconcile!
  "Observe before acting, including after sleep/restart. A Worker whose
  session ended waits for the person to resume it; nothing here relaunches a
  session or its jobs."
  [{:keys [state] :as c}]
  (locking (:recovery-lock c)
    (let [now (System/currentTimeMillis)
          grace (or (:restart-grace-ms c) 30000)
          ;; Asked once a pass, and only when a Worker needs its buffer opened.
          emacs-up? (delay ((get-in c [:launch :emacs-up?])))]
      (doseq [w (vals (:workers @state))
              :when (or (model/active-states (:state w)) (= :queued (:state w)))
              :let [root (when (:conversation w) (conversation-root c (:conversation w)))
                    agent (when root (inbox/agent root))
                    jobs (if root (conversation-jobs c (:conversation w)) [])
                    health (model/health
                            @state w
                            {:agent agent :jobs jobs
                             :wf (when (:wf-run w) (try (select-keys (workflow/status (:wf-run w)) [:status :phase]) (catch Exception _ {:status :missing})))
                             :repl? (boolean (and agent (some repl-answering? (repl-dirs c (:conversation w)))))
                             :waits (into {}
                                          (for [o (vals (:outbox @state))
                                                :when (and root (= :sent (:state o)) (= (:conversation w) (:conversation o)))
                                                :let [ms (some->> (inbox/waiting-since root (:message o)) (- now))]
                                                :when ms]
                                            [(:id o) ms]))}
                            now (* 60000 (:stale-delivery-minutes (policy c))))]]
        (when (model/health-changed? w health)
          (run! c :controller {:id (new-id) :command :health! :args {:worker (:id w) :health health :at now}}))
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
              ;; dispatch! sends the cancellation and marks its row done once the session took it.
              (when (or (not agent) (not (model/cancel-owed? @state w)))
                (run! c :controller {:id (str "cancelled-" (:id w)) :command :cancel-delivered! :args {:worker (:id w)}})
                (promote! c))))

          ;; A launch that met Emacs down: open its buffer once Emacs is back.
          ;; Its token was issued at promotion, so open! needs a new one.
          (and (not agent) (= :starting (:state w)) (:workspace w) (:conversation w)
               (> (- now (or (:started-at w) (:boot-at c))) grace)
               @emacs-up?)
          (let [token (new-token)]
            (run! c :controller {:id (new-id) :command :issue-token! :token-hash (sha256 token) :args {:principal [:worker (:id w)]}})
            (try
              (let [buffer ((get-in c [:launch :open!])
                            {:repo (:repo w) :workspace (:workspace w) :thread (:thread w) :model (:model w)
                             :title (get-in @state [:threads (:thread w) :title])
                             :conversation (:conversation w) :token token :wf-run (:wf-run w)})]
                (run! c :controller {:id (str "running-" (:id w)) :command :worker-running!
                                     :args (merge (select-keys w [:conversation :wf-run :workspace :started-at]) {:worker (:id w) :buffer buffer})}))
              (catch Exception e
                (run! c :controller {:id (str "launch-failed-" (:id w)) :command :finish-worker!
                                     :args {:worker (:id w) :state-after :failed :reason (str "Launch failed: " (ex-message e))}})
                (promote! c))))))
      ;; Jobs outlive a Worker that ended elsewhere: a failed launch, a controller that was down.
      (doseq [w (vals (:workers @state)) :when (#{:done :failed} (:state w))]
        (stop-jobs! c (:id w)))
      (try (ensure-router! c)
           (catch Exception e (binding [*out* *err*] (println "bbh super: the Router did not start:" (ex-message e))))))))

(defn- ensure-router!
  "With :manage-router, start the Router session and the Inbox when no Router
  session runs and Emacs is up, at most once a restart grace. A new
  registration revokes the token of the session that died."
  [{:keys [state launch] :as c}]
  (when (:manage-router c)
    (let [conversation (or (get-in @state [:router :router :conversation]) "router")
          now (System/currentTimeMillis)]
      (when (and (not (inbox/agent (conversation-root c conversation)))
                 (> (- now @(:router-tried c)) (or (:restart-grace-ms c) 30000))
                 ((:emacs-up? launch)))
        (reset! (:router-tried c) now)
        (let [t (new-token)]
          (run! c :controller {:id (new-id) :command :register-router! :token-hash (sha256 t) :args {:conversation conversation}})
          ((:open-router! launch) {:conversation conversation :token t}))))))

(defn dispatch!
  "One pass: send each pending outbox row to its Worker's mailbox, mark a sent
  row done once its mailbox file is gone, and drop a row whose Worker has
  finished, deleting its file. Each step is a command with a fixed id, so a
  pass repeated after a crash changes nothing twice."
  [{:keys [state] :as c}]
  (let [router (get-in @state [:router :router :conversation])
        rows (sort-by (comp model/id-order :id) (vals (:outbox @state)))
        for-router? (fn [o] (and (= :pending (:state o)) (= router (:conversation o))))
        batch (filter for-router? rows)
        send! (fn [{:keys [id conversation]} text kind]
                ;; The file is named after the row, so a pass that died before
                ;; recording the send writes the same message again, not a second.
                (let [m (inbox/send! (conversation-root c conversation) text nil
                                     (cond-> {:message-id (model/message-id id)} kind (assoc :kind kind)))]
                  (run! c :controller {:id (str "sent-" id) :command :outbox-sent! :args {:outbox id :message (:id m)}})))]
    ;; The Router's turn takes its pending rows together, oldest first. Summaries
    ;; of messages already routed to a Worker ride along but never wake it alone.
    (when (some #(not= :summary (:delivery %)) batch)
      (doseq [{:keys [id text delivery] :as o} batch]
        (send! o (str (when-not (= :summary delivery) (str id " ")) text) nil)))
  (doseq [{:keys [id conversation text message] :as o} rows
          :when (not (and (= :pending (:state o)) (= router conversation)))]
    (if (model/dead-letter? @state o)
      ;; Its Worker has finished, so the row is dropped and its file deleted unread.
      (do (run! c :controller {:id (str "dropped-" id) :command :outbox-dropped! :args {:outbox id}})
          ;; A pending row may have a file too, written by a pass that died before recording it.
          (inbox/ack! {:root (conversation-root c conversation)} (or message (model/message-id id))))
      (case (:state o)
        :pending (send! o text (#{:steer :cancel} (:delivery o)))
        :sent (when-not (inbox/waiting-since (conversation-root c conversation) message)
                (run! c :controller {:id (str "acked-" id) :command :outbox-acked! :args {:outbox id}}))
        nil)))))

(defn- notify-tick!
  "Show or close desktop notifications on C's current state, once a pass."
  [c]
  (some-> (:notifier c) (notify/tick! @(:state c) (System/currentTimeMillis) #(policy c))))

(defn- serve-web!
  "Serve the website from controller C with WEB's options for `bbh.web/serve!`.
  While its port is taken, as by a bbh web still running on its own, try again
  every 30 s."
  [{:keys [dir conversations stopped] :as c} web]
  (let [serve! (requiring-resolve 'bbh.web/serve!)]
    (doto (Thread. #(loop []
                      (when-not @stopped
                        (if-let [w (serve! (merge {:conversations conversations :super-dir dir} web))]
                          (do (reset! (:web c) w) (println "bbh super: serving the website"))
                          (do (Thread/sleep 30000) (recur))))))
      (.setDaemon true) (.start))))

(defn start!
  "Open the store under DIR, listen on a loopback port written to <dir>/port,
  and run the dispatcher every SCAN-MS. Returns the controller; `stop!` ends it.
  The controller is the store's only writer, so a second start on the same
  directory is refused while <dir>/lock is held."
  [{:keys [dir conversations scan-ms launch policy health-ms restart-grace-ms wf-root manage-router web sessions reap-grace-ms notify] :or {scan-ms 500 health-ms 120000}}]
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
        c {:dir dir :conversations (or conversations (conversation/base))
           :launch (merge workspace/impl
                          {:wf-start! wf-start! :wf-close! wf-close! :open! open! :release! release!
                           :open-router! client/open-router!
                           ;; Whether open! can work: the daemon's, unless a caller brought its own open!.
                           :emacs-up? (if (:open! launch) (constantly true) emacs/alive?)}
                          launch)
           :policy policy
           :sessions (merge session-io sessions) :machine (atom nil) :stale-since (atom {}) :was-active (atom #{}) :last-machine (atom 0) :reap-grace-ms reap-grace-ms
           :boot-at (System/currentTimeMillis) :recovery-lock (Object.) :restart-grace-ms restart-grace-ms :last-health (atom 0) :wf-root wf-root
           :db db :state (atom (store/replay db)) :server server :lock channel :stopped (atom false) :dispatcher (atom nil)
           :in-flight (atom 0) :manage-router manage-router :router-tried (atom 0) :web (atom nil)
           ;; NOTIFY, effects such as notify/desktop, turns desktop notifications on.
           :notifier (some-> notify (notify/new-notifier (fs/path dir "notifications.edn")))}]
    (ensure-owner-token! c)
    (some-> (:notifier c) (notify/resume! @(:state c)))
    (spit (str (fs/path dir "port")) (str (.getLocalPort server)))
    ;; Each client gets its own thread and a read timeout, so a client that
    ;; never sends its line, or a slow launch, holds up nobody else. Commits
    ;; stay serialized by run!.
    (doto (Thread. #(while (not @(:stopped c))
                      (try (let [sock (.accept server)]
                             (.setSoTimeout sock 10000)
                             (swap! (:in-flight c) inc)
                             (doto (Thread. (fn [] (try (serve-client c sock)
                                                        (catch Exception e (when-not @(:stopped c) (binding [*out* *err*] (println "bbh super:" (ex-message e)))))
                                                        (finally (swap! (:in-flight c) dec)))))
                               (.setDaemon true) (.start)))
                           (catch Exception e (when-not @(:stopped c) (binding [*out* *err*] (println "bbh super:" (ex-message e))))))))
      (.setDaemon true) (.start))
    (reset! (:dispatcher c)
     (doto (Thread. #(while (not @(:stopped c))
                      (try
                        (when (or (some (fn [w] (= :cancelling (:state w))) (vals (:workers @(:state c))))
                                  (> (- (System/currentTimeMillis) @(:last-health c)) health-ms))
                          ;; A pass can find a Worker gone, which frees its slot.
                          (reconcile! c) (settle! c) (promote-all! c) (reset! (:last-health c) (System/currentTimeMillis)))
                        ;; At start and every health interval: reap sessions that outlived their
                        ;; Worker, and log memory, swap and live sessions.
                        (when (> (- (System/currentTimeMillis) @(:last-machine c)) health-ms)
                          (reset! (:last-machine c) (System/currentTimeMillis))
                          (try (observe-machine! c)
                               (catch Exception e (binding [*out* *err*] (println "bbh super: observing sessions failed:" (ex-message e))))))
                        (observe-jobs! c) (dispatch! c) (watch-ended! c)
                        (notify-tick! c)
                           (catch Exception e (binding [*out* *err*] (println "bbh super dispatch:" (ex-message e)))))
                      (Thread/sleep (long scan-ms))))
      (.setDaemon true) (.start)))
    (when web (serve-web! c web))
    c))

(defn stop! [{:keys [stopped server db dir lock dispatcher in-flight web notifier]}]
  (reset! stopped true)
  (some-> notifier notify/stop!)
  (some-> web deref :stop (apply [:timeout 5000]))
  (.close ^ServerSocket server)
  ;; Let the requests already accepted finish, a launch among them.
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (while (and (pos? @in-flight) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50)))
  ;; Wait for the current observation/delivery transaction before closing SQLite.
  (when-let [t @dispatcher] (.join ^Thread t))
  (fs/delete-if-exists (fs/path dir "port"))
  (locking db (store/close! db))
  (.close ^java.nio.channels.FileChannel lock))

(defn serve!
  "Run the controller on DIR, and the website when WEB, recording its version
  under DATA-DIR, until it exits: when the source changes and it is idle, so its service restarts it on
  the new code, or when DIR is deleted, as a test's temp directory is."
  [{:keys [dir data-dir interval-ms] :as opts}]
  (let [boot (version/fingerprint)
        c (start! (dissoc opts :data-dir :interval-ms))]
    (version/record! data-dir "controller" boot)
    (println "bbh super on" dir "running" boot)
    (version/follow! {:boot boot :interval-ms (or interval-ms 10000)
                      :idle? #(and (zero? @(:in-flight c)) (zero? (or (some-> (:web c) deref :in-flight deref) 0)))
                      :gone? #(not (fs/exists? (fs/path dir "super.db")))
                      :exit! (fn [why]
                               (println "bbh super: exiting because" why)
                               (try (stop! c) (catch Exception _))
                               (System/exit 0))})
    @(promise)))

(defn -main
  "bbh super serve: run the controller until killed or its code changes."
  []
  (serve! {:dir (super-dir) :data-dir (str (data-dir)) :manage-router true :web {:publish? true} :notify notify/desktop
           :interval-ms (some-> (sysenv/getenv "BBH_SUPER_FOLLOW_MS") parse-long)}))
