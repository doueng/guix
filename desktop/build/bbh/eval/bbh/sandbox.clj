(ns bbh.sandbox
  "Containment for a full replay: the command runs where every write under home lands in an overlay, credentials
  and desktop sockets are hidden, commands with outside effects are blocked, and super-* reaches a stub. These are
  Linux namespaces, not a security boundary: the network stays open for the model API. See eval/README.md."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.models :as models]
            [bbh.session :as session]
            [bbh.super.client :as client]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net ServerSocket]))

(def ^:private setup
  "Run by bash inside unshare -rm with SCRATCH HOME UID GID, then the command."
  "set -e
s=\"$1\"; home=\"$2\"; uid=\"$3\"; gid=\"$4\"; shift 4
mkdir -p \"$s/up\" \"$s/work\" \"$s/real\"
for p in .claude .claude.json; do
  if [ -d \"$home/$p\" ]; then mkdir -p \"$s/real/$p\"; mount --bind \"$home/$p\" \"$s/real/$p\"
  elif [ -f \"$home/$p\" ]; then touch \"$s/real/$p\"; mount --bind \"$home/$p\" \"$s/real/$p\"; fi
done
mount -t overlay overlay -o \"lowerdir=$home,upperdir=$s/up,workdir=$s/work\" \"$home\"
for p in .claude .claude.json; do
  if [ -e \"$s/real/$p\" ] && [ -e \"$home/$p\" ]; then mount --bind \"$s/real/$p\" \"$home/$p\"; fi
done
for p in .ssh .gnupg .password-store .config/gh; do
  if [ -d \"$home/$p\" ]; then mount -t tmpfs none \"$home/$p\"; fi
done
for p in .git-credentials .netrc; do
  if [ -f \"$home/$p\" ]; then mount --bind /dev/null \"$home/$p\"; fi
done
mount -t tmpfs none /tmp
if [ -d \"/run/user/$uid\" ]; then mount -t tmpfs none \"/run/user/$uid\"; fi
exec unshare --user --map-user=\"$uid\" --map-group=\"$gid\" -- \"$@\"")

(def blocked-always
  "Commands with effects outside the replay: privileges, services, the desktop and GitHub."
  #{"sudo" "herd" "systemctl" "loginctl" "reboot" "shutdown" "hyprctl" "ydotool" "emacsclient" "notify-send" "gh"})

(def blocked-when
  "Commands blocked only with these arguments, as a source predicate over the argument vector."
  {"git" "(some #{\"push\"} args)"
   "jj" "(some #{\"push\"} args)"
   "guix" "(#{\"system\" \"home\"} (first args))"
   "make" "(some #{\"switch\" \"home\"} args)"})

(defn- replay-dir [home] (str home "/.local/share/bbh-replay"))

(defn- shim [cmd pred]
  (str "#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as json] '[clojure.string :as str])
(let [args (vec *command-line-args*)
      dir (str (System/getProperty \"user.home\") \"/.local/share/bbh-replay\")]
  (if " pred "
    (do (spit (str dir \"/blocked.jsonl\") (str (json/generate-string {:cmd " (pr-str cmd) " :args args}) \"\\n\") :append true)
        (binding [*out* *err*] (println (str \"[bbh replay] blocked: " cmd " \" (str/join \" \" args))))
        (System/exit 126))
    (let [shims (str dir \"/bin\")
          real (some #(let [f (fs/path % " (pr-str cmd) ")] (when (and (not= (str %) shims) (fs/executable? f)) (str f)))
                     (str/split (System/getenv \"PATH\") #\":\"))]
      (if real (apply p/exec real args) (System/exit 127)))))
"))

(defn- write-shims! [up]
  (let [bin (str up "/.local/share/bbh-replay/bin")]
    (fs/create-dirs bin)
    (doseq [[cmd pred] (concat (for [c blocked-always] [c "true"]) blocked-when)
            :let [f (str bin "/" cmd)]]
      (spit f (shim cmd pred))
      (fs/set-posix-file-permissions f "rwxr-xr-x"))
    bin))

(defn- id-of [flag] (str/trim (:out (p/shell {:out :string} "id" flag))))

(defn argv
  "The argv that runs CMD contained, with writes under HOME going to SCRATCH/up."
  [{:keys [scratch home]} cmd]
  (into ["unshare" "-rm" "bash" "-c" setup "sandbox" scratch home (id-of "-u") (id-of "-g")] cmd))

(defn super-stub!
  "Serve bbh.super.client's protocol on localhost and point this home's super dir at it. Each request is appended
  to OUT/super.jsonl. Returns the ServerSocket."
  [out]
  (let [server (ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        asks (atom 0)
        dir (client/super-dir)]
    (fs/create-dirs dir)
    (spit (str dir "/port") (str (.getLocalPort server)))
    (spit (str dir "/token") "replay")
    (future
      (try
        (loop []
          (with-open [sock (.accept server)
                      in (io/reader (.getInputStream sock))
                      w (io/writer (.getOutputStream sock))]
            (let [{:keys [command args]} (edn/read-string (.readLine in))]
              (spit (str out "/super.jsonl") (str (json/generate-string {:command command :args args}) "\n") :append true)
              (.write w (pr-str (case command
                                  :ask! {:ok (str "a" (swap! asks inc))}
                                  (:thread :threads :projects) {:ok {}}
                                  {:ok true})))
              (.write w "\n")
              (.flush w)))
          (recur))
        (catch java.net.SocketException _ nil)))
    server))

(defn- prepare!
  "Inside the sandbox: recreate the workspace at BASE when it is gone, and start a fresh wf run like the recorded
  one. Returns the new run id, or nil."
  [cwd {:keys [repo workspace base wf]}]
  (when (and repo (not (fs/exists? cwd)))
    (p/shell {:out :string :err :string} "jj" "-R" repo "workspace" "add" "--name" workspace "-r" base cwd))
  (when-let [{:keys [workflow task check repro bench]} wf]
    (let [r (apply p/shell {:dir cwd :out :string :err :string :continue true :extra-env {"WF_RUN" ""}}
                   "wf" "start" workflow "--task" task
                   (concat (when check ["--check" check]) (when repro ["--repro" repro]) (when bench ["--bench" bench])))]
      (or (second (re-find #"workflows/([^/\s]+)/evidence" (str (:out r) (:err r))))
          (throw (ex-info (str "wf start failed: " (:err r)) {}))))))

(defn drive!
  "Inside the sandbox: prepare, run one bbh session on PROMPT, and write OUT/result.edn. OLD-RUN in the prompt
  becomes the fresh wf run."
  [{:keys [out init cwd model prompt prepare old-run]}]
  (fs/create-dirs out)
  (let [server (super-stub! out)
        run (prepare! cwd prepare)
        s (session/new-session {:dir (str out "/conversation") :init init :cwd cwd :model model
                                :make-engine models/engine :emit (fn [_])
                                :env (cond-> {"BBH_SUPER_TOKEN" "replay"} run (assoc "WF_RUN" run))})
        prompt (cond-> prompt (and run old-run) (str/replace old-run run))]
    (try
      (session/start-repl! s)
      (let [end (session/prompt! s prompt)
            f (fs/file out "conversation" "claude-session")]
        (spit (str out "/result.edn") (pr-str {:end end :run run :claude-session (when (fs/exists? f) (str/trim (slurp f)))})))
      (finally (session/close! s) (.close server)))))

(defn- jsonl [f] (if (fs/exists? f) (mapv #(json/parse-string % true) (str/split-lines (slurp f))) []))

(defn run!
  "Run drive! for SPEC {:init :cwd :model :prompt} contained in SCRATCH. Returns what came out:
  {:out :result :blocked :super :changed}, where :changed lists the paths written under home."
  [scratch spec & {:keys [timeout-ms]}]
  (let [home (System/getProperty "user.home")
        _ (when (fs/starts-with? (fs/absolutize scratch) home)
            (throw (ex-info (str "sandbox: scratch " scratch " is under home, which the overlay covers; use /tmp or /var/tmp") {})))
        up (str scratch "/up")
        bin (write-shims! up)
        out (str (replay-dir home) "/run")
        ;; This session's own bbh variables, such as its super token, stay outside.
        env (-> (into {} (remove (fn [[k _]] (re-find #"^(BBH_|WF_)" k))) (System/getenv))
                (assoc "PATH" (str (str/replace bin up home) ":" (System/getenv "PATH"))))
        proc (apply p/process {:out :string :err :string :env env}
                 (argv {:scratch scratch :home home}
                       ["bb" "--config" (System/getProperty "babashka.config") "-e"
                        (str "(require 'bbh.sandbox) (bbh.sandbox/drive! " (pr-str (assoc spec :out out)) ")")]))
        r (deref proc (or timeout-ms (* 30 60 1000)) nil)
        _ (when-not r (p/destroy-tree proc) @proc)
        r (or r (assoc @proc :timed-out true))
        seen (str/replace out home up)
        result (let [f (str seen "/result.edn")] (when (fs/exists? f) (edn/read-string (slurp f))))]
    ;; ~/.claude is the real one, for credentials, so the replay's Claude Code session is removed from it.
    (when-let [id (:claude-session result)]
      (doseq [f (fs/glob (str home "/.claude/projects") (str "*/" id ".jsonl"))] (fs/delete f)))
    {:out seen
     :timed-out (boolean (:timed-out r))
     :exit (:exit r)
     :err (:err r)
     :result result
     :blocked (jsonl (str (replay-dir up) "/blocked.jsonl"))
     :super (jsonl (str seen "/super.jsonl"))
     :changed (->> (fs/glob up "**" {:hidden true})
                   (filter fs/regular-file?)
                   (map #(str (fs/relativize up %)))
                   (remove #(str/starts-with? % ".local/share/bbh-replay/"))
                   sort vec)}))

(defn cleanup!
  "Delete SCRATCH, including overlayfs's mode-000 work directory."
  [scratch]
  (p/shell {:continue true :err :string} "chmod" "-R" "u+rwx" scratch)
  (fs/delete-tree scratch))
