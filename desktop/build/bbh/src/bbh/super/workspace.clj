(ns bbh.super.workspace
  "A Worker's jj workspace from creation to landing to removal. Everything
  that knows jj, its revsets, the workspace layout under DIR/workspaces, the
  [thread] description prefix and the wip placeholder lives here; the
  controller calls `workspace!`, `land!`, `advance!` and `retire!` through its
  :launch map, so tests can replace each, and asks `retirable?`, `landed-ids`,
  `kept-milestone` and `orphans` the questions that need its state."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- jj-out [dir & args]
  (let [{:keys [exit out err]} (apply p/shell {:dir dir :out :string :err :string :continue true} "jj" args)]
    (if (zero? exit) (str/trim out) (throw (ex-info (str "jj " (str/join " " args) ": " (str/trim err)) {:error :jj})))))

(defn thread-prefix
  "What every description of a change a Worker of THREAD lands starts with."
  [thread]
  (str "[" thread "] "))

(defn wip
  "The description a new Worker workspace's working-copy change gets, so jj log
  and jjui show whose it is. The Worker replaces it when it commits; land!
  refuses to land it."
  [{:keys [thread id title]}]
  (str (thread-prefix thread) "wip(" id ")" (when-not (str/blank? title) (str ": " title))))

(defn workspace!
  "A jj workspace of REPO for WORKER under DIR, named <thread>-<generation>,
  its working-copy change described with (wip WORKER). Returns its path; an
  existing one is reused. A REPO that does not exist yet is a new project, made
  with `jj git init`."
  [dir {:keys [repo thread generation] :as worker}]
  (let [name (str thread "-" generation)
        path (str (fs/path dir "workspaces" name))]
    (when-not (fs/exists? repo)
      (fs/create-dirs repo)
      (jj-out repo "git" "init"))
    (when-not (fs/exists? path)
      (fs/create-dirs (fs/parent path))
      (jj-out repo "workspace" "add" "--name" name path)
      (jj-out path "describe" "-m" (wip worker)))
    path))

(defn- revs
  "One line per revision of REVSET in DIR: commit id, then the first line of its description."
  [dir revset]
  (remove str/blank? (str/split-lines (jj-out dir "log" "--no-graph" "-r" revset "-T" "commit_id.short() ++ \" \" ++ description.first_line() ++ \"\\n\""))))

;; Matches the placeholder (wip) and the older form without the thread prefix, wip(T63 W60).
(def ^:private wip-description "description(regex:\"^(.T[0-9]+. )?wip[(]\")")

;; A Worker's working-copy change before it has work: empty, undescribed or still described by wip.
(def ^:private placeholder (str "(empty() & (description(exact:\"\") | " wip-description "))"))

(defn- prefix!
  "Prefix the description of each commit of REVSET in WORKSPACE that does not
  start with (thread-prefix THREAD). Only a Worker's own unlanded commits are
  passed, so landed history is never rewritten."
  [workspace revset thread]
  (let [prefix (thread-prefix thread)
        ids (str/split-lines (jj-out workspace "log" "--no-graph" "-r" revset "-T" "change_id ++ \"\\n\""))]
    (doseq [id (remove str/blank? ids)
            :let [d (jj-out workspace "log" "--no-graph" "-r" id "-T" "description")]
            :when (not (str/starts-with? d prefix))]
      (jj-out workspace "describe" "-r" id "-m" (str prefix d)))))

(defn land!
  "Rebase the revision of the Worker in WORKSPACE, the commits its workspace has
  that the line lacks, onto the tip of the line, so finished work forms one
  line. The line is the user's committed history, `::default@-` (their
  working-copy change stays in progress; advance! moves it after), and LANDED, the
  change ids finished Workers landed. ONTO names the tip when
  the line has more than one head. Runs jj in WORKSPACE, so the user's working
  copy is not even snapshotted. Throws, leaving the Worker to act, when its
  working copy has uncommitted changes and when the tip is unclear. Returns
  {:onto tip :landed change-id}, the head of what it landed or nil, or, when
  the rebase leaves conflicts in its commits, {:onto tip :conflicts revs :todo
  text}: the rebase stands and the Worker resolves them, so a repeat once they
  are resolved lands."
  [{:keys [workspace landed onto thread]}]
  (let [refuse (fn [error message] (throw (ex-info message {:error error})))]
    (when (seq (revs workspace (str "@ & ~empty() & (description(exact:\"\") | " wip-description ")")))
      (refuse :in-progress "your working copy has uncommitted changes: commit them with a real description (jj commit -m), or discard them, before super-done!"))
    (let [line (str "(::default@-" (apply str (for [id landed] (str " | ::present(" id ")"))) ") ~ " placeholder)
          heads (if onto (revs workspace (str "exactly(" onto ", 1)")) (revs workspace (str "heads(" line ")")))
          _ (when (not= 1 (count heads))
              (refuse :unclear-tip (str "the line has " (count heads) " heads, so its tip is unclear: " (str/join "; " heads)
                                        ". Ask the person which one your revision goes on, then (super-done! {:onto \"<commit id>\"}).")))
          tip (first (str/split (first heads) #" "))
          mine (str "(" tip "..@) ~ " placeholder)
          _ (when-let [w (seq (revs workspace (str "(" mine ") & " wip-description)))]
              (refuse :wip (str "these commits still have the wip placeholder description: " (str/join "; " w)
                                ". Describe them (jj describe -r <id> -m ...), then (super-done!) again.")))]
      (if (empty? (revs workspace mine))
        {:onto tip :landed nil}
        (do (when thread (prefix! workspace mine thread))
            (jj-out workspace "rebase" "-s" (str "roots(" tip "..@)") "-d" tip)
            (if-let [c (seq (revs workspace (str "(" mine ") & conflicts()")))]
              {:onto tip :conflicts (vec c)
               :todo (str "Landing on " tip " left conflicts in " (str/join "; " c)
                          ". Resolve them now in your workspace (edit the files, or jj resolve), squash the fix into the conflicted commit, then call (super-done!) again.")}
              {:onto tip :landed (jj-out workspace "log" "--no-graph" "-r" (str "heads(" mine ")") "-T" "change_id")}))))))

(defn advance!
  "Move the user's working-copy change, default@ of REPO, onto TIP, the head a
  Worker just landed, so their checkout contains the new work. Only an empty
  change moves, unless MOVE is :always (policy :move-working-copy). Runs one
  `jj rebase` in REPO, the default workspace, so jj snapshots the user's edits
  first and the check and the move are a single operation under jj's
  working-copy lock; a concurrent jj of theirs merges as any concurrent
  operation does. A stale working copy or any jj failure moves nothing.
  Returns :moved, :current when default@ already has TIP (it is never moved down), :skipped when REPO
  is not the default workspace, or {:kept :not-empty}, {:kept :editing :at
  commit} when default@ is a commit the line already has, as after `jj edit`
  of a landed commit, so no move applies, or {:kept :failed :message m},
  which the caller tells the user about."
  [{:keys [repo tip move]}]
  (try
    (let [q (fn [rev] (jj-out repo "--ignore-working-copy" "log" "--no-graph" "-r" rev "-T" "commit_id"))]
      (if (not= (q "present(@)") (q "present(default@)"))
        :skipped
        (let [{:keys [exit err]} (p/shell {:dir repo :out :string :err :string :continue true}
                                          "jj" "rebase" "-r" (str "default@ ~ ::" tip " ~ " tip "::" (when-not (= :always move) " & empty()")) "-d" tip)]
          (cond (not (zero? exit)) {:kept :failed :message (str/trim err)}
                (re-find #"(?m)^Rebased " err) :moved
                (not (str/blank? (q (str "default@ & " tip "::")))) :current
                (not (str/blank? (q (str "default@ & ::" tip))))
                {:kept :editing :at (q "default@")}
                :else {:kept :not-empty}))))
    (catch Exception e {:kept :failed :message (ex-message e)})))

(defn retire!
  "Forget the jj workspace of a finished Worker and delete its directory, so its
  empty working-copy change does not dangle. Idempotent: a workspace jj already
  forgot, a missing directory and a REPO that is gone are each skipped. With
  KEEP?, for work that did not land, the workspace is snapshotted first and its
  commits off the user's line get the bookmark super/<workspace name>, so they
  stay findable. A done finish was refused while the working copy had
  uncommitted changes, so it needs no KEEP?. Runs in REPO without a snapshot,
  so the user's working copy is untouched. Returns the bookmark, or nil."
  [{:keys [repo workspace keep?]}]
  (let [name (str (fs/file-name workspace))
        known? (and repo (fs/directory? (fs/path repo ".jj"))
                    (contains? (set (str/split-lines (jj-out repo "--ignore-working-copy" "workspace" "list" "-T" "name ++ \"\\n\""))) name))
        bookmark (when (and known? keep?)
                   (when (fs/directory? workspace) (jj-out workspace "status"))
                   (let [work (str "heads((::" name "@ ~ ::default@) ~ " placeholder ")")]
                     (when-not (str/blank? (jj-out repo "--ignore-working-copy" "log" "--no-graph" "-r" work "-T" "commit_id"))
                       (let [b (str "super/" name)]
                         (jj-out repo "--ignore-working-copy" "bookmark" "set" "--allow-backwards" b "-r" (str "latest(" work ")"))
                         b))))]
    ;; jj discards only an undescribed empty working copy on forget, so a wip one goes explicitly.
    (when known? (jj-out repo "--ignore-working-copy" "abandon" "-r" (str name "@ & " placeholder " & ~::default@")))
    (when known? (jj-out repo "--ignore-working-copy" "workspace" "forget" name))
    (when (fs/exists? workspace) (fs/delete-tree workspace))
    bookmark))

(defn retirable?
  "Whether Worker W's workspace goes: a done Worker's at once, since its work
  landed; a failed or cancelled one's once its thread is resolved, so it can be
  inspected until then. No Worker, for a directory nothing owns, also goes."
  [st w]
  (or (nil? w)
      (= :done (:state w))
      (boolean (and (#{:failed :cancelled} (:state w)) (get-in st [:threads (:thread w) :resolved-at])))))

(defn landed-ids
  "The change ids that done Workers of REPO landed in ST, which land! adds to the line."
  [st repo]
  (keep #(when (and (= :done (:state %)) (= repo (:repo %))) (:landed %)) (vals (:workers st))))

(defn kept-milestone
  "The milestone that tells the person why WORKER's landing at TIP left their
  working copy in REPO where it was, from R, what advance! returned, or nil
  when it moved."
  [{:keys [worker repo tip]} r]
  (when (map? r)
    (let [short (subs tip 0 (min 8 (count tip)))
          how (str "To take it: jj rebase -r @ -d " short " in " repo ".")]
      (case (:kept r)
        :not-empty (str worker " landed " short ". Your working copy has changes, so it was left where it is. " how
                        " To always move it, set :move-working-copy :always in policy.edn.")
        :editing (str worker " landed " short " on top of " (subs (:at r) 0 (min 8 (count (:at r))))
                      ", the commit your working copy edits (jj edit), so it stays there. To work on the tip: jj new "
                      short " in " repo ".")
        (str worker " landed " short ", but moving your working copy failed: " (:message r) " " how)))))

(defn- repo-of
  "The repository a jj workspace directory DIR belongs to, from its .jj/repo pointer."
  [dir]
  (let [ptr (fs/path dir ".jj" "repo")]
    (when (fs/regular-file? ptr)
      (str (fs/parent (fs/parent (fs/normalize (fs/path dir ".jj" (str/trim (slurp (str ptr)))))))))))

(defn orphans
  "The workspace directories under DIR/workspaces that no live Worker of ST
  owns, as {:workspace :worker :state :repo}, each with its Worker or nil."
  [dir st]
  (let [owner (into {} (keep #(when (:workspace %) [(str (:workspace %)) %]) (vals (:workers st))))]
    (vec (for [d (when (fs/directory? (fs/path dir "workspaces")) (sort (map str (fs/list-dir (fs/path dir "workspaces")))))
               :let [w (owner d)]
               :when (and (fs/directory? d) (retirable? st w))]
           {:workspace d :worker (:id w) :state (:state w) :repo (or (repo-of d) (:repo w))}))))

(def impl
  "The controller's default :launch steps for workspaces."
  {:workspace! workspace! :land! land! :advance! advance! :retire! retire!})
