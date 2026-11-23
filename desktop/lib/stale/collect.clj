;; The facts desktop/lib/stale.clj judges, read from the running system. Every
;; path hangs off (:root ctx) and every command goes through ctx, so a test can
;; point it at a made-up /proc, profiles and herd.
(ns stale.collect
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [stale]))

(defn- path [ctx & parts] (str (apply fs/path (:root ctx "/") (map #(str/replace % #"^/" "") parts))))

(defn- mtime [p] (try (.toMillis (fs/last-modified-time p {:nofollow-links true})) (catch Exception _ nil)))

(defn- real [p] (try (str (fs/real-path p)) (catch Exception _ nil)))

(defn- slurp-or
  "P's text, or DFLT. A FileReader, since slurp of a path fails on /proc/stat."
  [p dflt]
  (try (slurp (java.io.FileReader. (str p))) (catch Exception _ dflt)))

(defn- expand [ctx p] (str/replace-first p #"^~(?=/)" (:home ctx)))

(defn newest
  "[path ms] of the newest regular file the repo GLOBS match, or nil."
  [repo globs]
  (->> globs
       (mapcat (fn [g] (if (str/includes? g "*") (fs/glob repo g) (let [p (fs/path repo g)] (when (fs/exists? p) [p])))))
       (filter fs/regular-file?)
       (map (fn [p] [(str (fs/relativize repo p)) (mtime p)]))
       (sort-by second)
       last))

(defn- boot-params [ctx which]
  (let [s (slurp-or (path ctx "run" which "parameters") "")]
    {:kernel (second (re-find #"\(kernel \"([^\"]+)\"\)" s))
     :initrd (second (re-find #"\(initrd \"([^\"]+)\"\)" s))
     :args (second (re-find #"\(kernel-arguments \(([^)]*)\)\)" s))}))

(defn- start-ms
  "When PID started, from field 22 of /proc/PID/stat in clock ticks after boot."
  [ctx btime pid]
  (let [stat (slurp-or (path ctx "proc" (str pid) "stat") nil)
        fields (some-> stat (subs (inc (str/last-index-of stat ")"))) str/trim (str/split #" "))]
    (when (and btime (> (count fields) 19))
      (+ (* 1000 btime) (* 10 (parse-long (nth fields 19)))))))

(def interpreters #{"bb" "guile" "python3" "node" "bash"})

(defn- repo-files
  "{realpath ms} of the program ARGV runs when it lives in the repo: argv0, or the
  script an interpreter runs. A script given as an argument, as to wl-paste
  --watch, runs anew each time, so it does not count."
  [ctx argv]
  (into {} (for [a (if (interpreters (str/replace (first argv) #".*/" "")) (take 1 (rest argv)) (take 1 argv))
                 :when (str/starts-with? a "/")
                 :let [r (real (path ctx a))]
                 :when (and r (str/starts-with? r (str (:repo ctx) "/")) (fs/regular-file? r)
                            (not (str/ends-with? r "bb.edn")))]
             [(str (fs/relativize (:repo ctx) r)) (mtime r)])))

(defn procs [ctx]
  (let [btime (some->> (slurp-or (path ctx "proc" "stat") "") (re-find #"(?m)^btime (\d+)") second parse-long)]
    (vec (for [d (fs/list-dir (path ctx "proc"))
               :let [pid (parse-long (str (fs/file-name d)))]
               :when pid
               :let [argv (remove str/blank? (str/split (slurp-or (str d "/cmdline") "") #"\x00"))]
               :when (seq argv)
               :let [exe (try (str (fs/read-link (str d "/exe"))) (catch Exception _ nil))]]
           (cond-> {:pid pid :argv (vec argv) :start-ms (start-ms ctx btime pid) :files (repo-files ctx argv)}
             exe (assoc :exe (str/replace exe #" \(deleted\)$" ""))
             (and exe (str/ends-with? exe " (deleted)")) (assoc :deleted? true))))))

(defn ca-env
  "For each process stale/env-holder names whose environ we may read, whether it
  holds SSL_CERT_FILE."
  [ctx procs]
  (vec (for [{:keys [pid argv]} procs
             :let [label (stale/env-holder argv)]
             :when label
             :let [env (slurp-or (path ctx "proc" (str pid) "environ") nil)]
             :when env]
         {:label label :pid pid :ok? (boolean (re-find #"(^|\x00)SSL_CERT_FILE=" env))})))

(defn services
  "Running Shepherd services with a process: [{:scope :name :pid :command}]."
  [ctx]
  (vec (for [scope [:system :home]
             :let [herd (fn [& args] (apply (:herd ctx) scope args))
                   started (some->> (herd "status") (re-find #"(?s)Started:\n(.*?)(\n\S|$)") second
                                    (re-seq #"(?m)^ \+ (\S+)") (map second))]
             name started
             :let [out (herd "status" name "-n" "0")
                   pid (some->> out (re-find #"Main PID: (\d+)") second parse-long)]
             :when pid]
         {:scope scope :name name :pid pid :command (some->> out (re-find #"Command: (.*)") second)})))

(defn doom [ctx]
  (let [etc (path ctx (:home ctx) ".config/emacs/.local/etc/@")
        env (str etc "/init.d/05-doom-env.load.el")
        synced (last (sort (keep mtime (fs/glob etc "init.*.el"))))
        [src src-ms] (newest (:repo ctx) ["desktop/configs/doom/init.el" "desktop/configs/doom/packages.el"])
        text (slurp-or env nil)]
    (when (or synced text)
      {:synced-ms synced :sources src :sources-ms src-ms
       :env-file (when text env) :env-ms (mtime env) :env-text text
       :env-vars (set (map second (re-seq #"\"([A-Za-z_][A-Za-z0-9_]*)=" (or text ""))))
       :deny-ms (second (newest (:repo ctx) ["desktop/configs/doom/cli.el"]))})))

(defn doom-config
  "{repo path ms} of the Doom config a running Emacs loads: every file in
  desktop/configs/doom but cli.el, which only the doom CLI reads."
  [ctx]
  (into {} (for [f (fs/glob (:repo ctx) "desktop/configs/doom/**")
                 :when (fs/regular-file? f)
                 :let [rel (str (fs/relativize (:repo ctx) f))]
                 :when (not= rel "desktop/configs/doom/cli.el")]
             [rel (mtime f)])))

(defn- ghostty? [{:keys [argv]}]
  (and (re-find #"^\.?ghostty(-real)?$" (str/replace (str (first argv)) #".*/" ""))
       (not-any? #(str/starts-with? % "+") (rest argv))))

(defn- catches-usr2?
  "Whether PID handles SIGUSR2, bit 12 of SigCgt: Ghostty reloads its config on
  it, and a process that does not would die of it."
  [ctx pid]
  (boolean (some-> (slurp-or (path ctx "proc" (str pid) "status") "")
                   (->> (re-find #"SigCgt:\s*([0-9a-fA-F]+)")) second (BigInteger. 16) (.testBit 11))))

(defn- ghostty-snapshot-file [ctx pid start-ms ext]
  (str (fs/path (:stamps ctx) "ghostty" (str pid "-" start-ms "." ext))))

(defn ghostty
  "Each Ghostty process: its config files with the time each last changed, the
  link followed to the repo, their text, and what make refresh saved when it last
  reloaded it."
  [ctx procs]
  (vec (for [{:keys [pid argv start-ms] :as p} procs
             :when (ghostty? p)
             :let [opt (fn [k] (some #(second (re-find (re-pattern (str "^--" k "=(.*)$")) %)) argv))
                   default? (not= "false" (opt "config-default-files"))
                   home-cfg (str (:home ctx) "/.config/ghostty")
                   paths (concat (when default? (cons (str home-cfg "/config")
                                                      (map str (fs/glob (path ctx home-cfg "themes") "*"))))
                                 (keep #(second (re-find #"^--config-file=(.*)$" %)) argv))
                   files (for [f (distinct paths)
                               :let [f (expand ctx f)
                                     p (if (str/starts-with? f (:root ctx "/")) f (path ctx f))]
                               :when (fs/exists? p)]
                           [f (mtime (real p))])
                   snap (ghostty-snapshot-file ctx pid start-ms "conf")]]
         {:pid pid :start-ms start-ms :class (opt "class") :usr2? (catches-usr2? ctx pid)
          :files (into {} files)
          :text (str/join "\n" (keep (fn [[f _]] (slurp-or (if (str/starts-with? f (:root ctx "/")) f (path ctx f)) nil)) files))
          :snapshot (slurp-or snap nil)
          :pending (some-> (slurp-or (ghostty-snapshot-file ctx pid start-ms "pending") nil) str/split-lines (->> (remove str/blank?)) vec)})))

(defn ghostty-reloaded!
  "Record that Ghostty PID took TEXT now, with PENDING the settings its open
  windows still lack."
  [ctx pid start-ms text pending]
  (fs/create-dirs (fs/path (:stamps ctx) "ghostty"))
  (spit (ghostty-snapshot-file ctx pid start-ms "conf") text)
  (spit (ghostty-snapshot-file ctx pid start-ms "pending") (str/join "\n" pending)))

(defn ghostty-seed!
  "Save what each fresh Ghostty without a snapshot runs, so a later reload can
  tell which settings changed."
  [ctx ghosts]
  (doseq [{:keys [pid start-ms text snapshot]} ghosts :when (nil? snapshot)]
    (ghostty-reloaded! ctx pid start-ms text [])))

(defn extensions
  "The Chrome extensions ext-reload serves: newest source change and newest build output."
  [ctx]
  (let [root (fs/path (:repo ctx) "desktop/build/chrome")]
    (vec (for [d (when (fs/exists? root) (sort (fs/list-dir root)))
               :when (and (fs/exists? (fs/path d "bb.edn")) (fs/exists? (fs/path d "ext/manifest.json")))
               :let [rel (str (fs/relativize (:repo ctx) d))]]
           {:name (str (fs/file-name d))
            :src (newest (:repo ctx) [(str rel "/src/**") (str rel "/ext/manifest.json") (str rel "/shadow-cljs.edn")])
            :built-ms (last (sort (keep mtime (fs/glob (str d "/ext") "**.js"))))}))))

(def stow-ignored
  "Files GNU Stow skips by default, so they never get a link."
  #"(^|/)(\.git|\.gitignore|node_modules)(/|$)|~$|(^|/)\.?#|^(README|LICENSE|COPYING)[^/]*$")

(def stowed
  "[repo dir, Home dir] pairs desktop/stow-home links file by file."
  [["desktop/bin" ".local/bin"] ["desktop/pi/agent" ".pi/agent"] ["desktop/configs/ideavim" ""]])

(defn links
  "Repo files desktop/stow-home would link that have no link in Home yet."
  [ctx]
  (let [repo (:repo ctx)
        configs (fs/path repo "desktop/configs")
        apps (for [d (when (fs/directory? configs) (fs/list-dir configs))
                   :let [app (str (fs/file-name d))]
                   :when (and (fs/directory? d) (not= app "ideavim"))]
               [(str "desktop/configs/" app) (str ".config/" app)])]
    {:missing (vec (sort (for [[src dst] (concat stowed apps)
                               :let [dir (fs/path repo src)]
                               :when (fs/directory? dir)
                               f (fs/glob dir "**" {:hidden true})
                               :let [rel (str (fs/relativize dir f))]
                               :when (and (fs/regular-file? f) (not (re-find stow-ignored rel))
                                          (not (fs/exists? (path ctx (:home ctx) dst rel))))]
                           (str src "/" rel))))}))

(defn stamps [ctx]
  (let [dir (:stamps ctx)]
    (into {} (for [f (when (and dir (fs/exists? dir)) (fs/list-dir dir))
                   :when (fs/regular-file? f)]
               [(str/replace (str (fs/file-name f)) "__" ":") (mtime f)]))))

(defn stamp!
  "Record that ID took its sources at MS, by default now: the file's mtime. A
  refresh passes the time it started, so a change made while it ran stays newer."
  [ctx id & [ms]]
  (fs/create-dirs (:stamps ctx))
  (let [f (str (fs/path (:stamps ctx) (str/replace id ":" "__")))]
    (spit f (str (or ms (System/currentTimeMillis)) "\n"))
    (when ms (fs/set-last-modified-time f (java.nio.file.attribute.FileTime/fromMillis ms)))))

(defn restartable
  "System services sudo restarts without a password: %restartable-services in
  modules/engstrand/features/agent-control.scm."
  [repo]
  (some->> (slurp-or (str repo "/modules/engstrand/features/agent-control.scm") nil)
           (re-find #"(?s)\(define %restartable-services\s+'\(([^)]*)\)") second
           (re-seq #"\"([^\"]+)\"") (map second) set))

(def config-globs
  "What make switch builds into the system and Home."
  ["modules/**" "desktop/system.scm" "desktop/home.scm" "desktop/kanata.kbd" "channels.scm"])

(defn facts [ctx]
  (let [system-link (path ctx "var/guix/profiles/system")
        gen (try (str (fs/read-link system-link)) (catch Exception _ nil))
        current (real (path ctx "run/current-system"))
        home-active (real (path ctx (:home ctx) ".guix-home"))
        procs (future (procs ctx))
        services (future (services ctx))
        closure (future (into #{} (mapcat (:requisites ctx)) (remove nil? [current home-active])))]
    {:now-ms (System/currentTimeMillis)
     :boot {:current (boot-params ctx "current-system") :booted (boot-params ctx "booted-system")}
     :system {:current current :latest (real system-link)
              :generation-ms (when gen (mtime (str (fs/path (fs/parent system-link) gen))))}
     :config (newest (:repo ctx) config-globs)
     :home {:active home-active
            :latest (real (path ctx "var/guix/profiles/per-user" (:user ctx) "guix-home"))}
     :closure @closure
     :services @services
     :procs @procs
     :ghostty (ghostty ctx @procs)
     :ca-env (ca-env ctx @procs)
     :sources (into {} (for [{:keys [id sources inputs]} stale/components
                             :let [hit (or (when sources (newest (:repo ctx) sources))
                                           (last (sort-by second (keep (fn [p] (let [p (path ctx (expand ctx p))]
                                                                                 (some->> (mtime p) (vector p))))
                                                                       inputs))))]
                             :when hit]
                         [id hit]))
     :stamps (stamps ctx)
     :links (links ctx)
     :restartable (restartable (:repo ctx))
     :doom (doom ctx)
     :doom-config (doom-config ctx)
     :extensions (extensions ctx)}))
