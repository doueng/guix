;; What runs stale after a change in ~/guix, and the command that refreshes it.
;; desktop/bin/stale collects facts (profiles, Shepherd services, processes, file
;; times) into one map; assess turns them into rows without touching the system,
;; so desktop/build/stale/test.bb can feed it made-up profiles and processes.
(ns stale
  (:require [clojure.string :as str]))

(def restartable-system-services
  "Services the user may restart with sudo and no password, as
  modules/engstrand/features/agent-control.scm lists them."
  #{"bluetooth" "kanata" "ntpd" "tailscaled" "upower-daemon" "ydotoold"})

(def manual-home-services
  "Home services a refresh leaves alone: restarting bbh-super restarts the Router
  and every Worker, bbh-emacs is the Emacs they draw in."
  {"bbh-super" "restarts the super-agent: the Router and every Worker"
   "bbh-emacs" "restarts the Emacs the super-agent draws in"})

(def components
  "Processes outside Shepherd, or more than one process per component. :argv0
  matches the program's file name, :arg any one argument; argv is never matched
  as a whole, since agent prompts quote every program name. :sources are repo
  globs whose newest change the process must have started after; :inputs are
  absolute paths, compared by the time their link changed."
  [{:id "hyprland" :label "Hyprland" :argv0 #"^Hyprland$"
    :fix {:cmd "log out and back in" :manual "re-login"}}
   {:id "hyprland-config" :label "Hyprland config" :of "hyprland"
    :inputs ["~/.config/engstrand/mux"]
    :note "Hyprland reloads its own .lua files; this tracks what they read"
    :fix {:cmd "hyprctl reload" :run [:hyprctl "reload"]}}
   {:id "kanata-config" :label "kanata config" :of-service "kanata"
    :inputs ["/etc/kanata"]
    :fix {:cmd "hyprkan reload" :run [:bin "hyprkan" "reload"]}}
   {:id "hyprkan" :label "hyprkan follower" :arg #"/hyprkan$" :argv-count 2
    :sources ["desktop/bin/hyprkan"] :fix {:respawn true}}
   {:id "keyleader" :label "keyleader" :arg #"/keyleader$"
    :sources ["desktop/bin/keyleader" "desktop/lib/**" "desktop/configs/keybindings/*.edn"] :fix {:respawn true}}
   {:id "palette" :label "Cmd+Space palette" :arg #"/palette$"
    :sources ["desktop/bin/palette" "desktop/lib/**" "desktop/configs/keybindings/*.edn"] :fix {:respawn true}}
   {:id "dusk" :label "dusk shell" :arg #"build/dusk/target/release/dusk$|^dusk\.runtime$"
    :sources ["desktop/build/dusk/target/release/dusk" "desktop/build/dusk/runtime/**" "desktop/configs/dusk/**" "desktop/lib/dusk/**"]
    :fix {:cmd "pkill -x dusk; ~/.local/bin/dusk" :manual "restart dusk"}}
   {:id "bbh" :label "bbh super-agent" :service "bbh-super" :arg #"build/bbh/bb\.edn$"
    :sources ["desktop/build/bbh/src/**" "desktop/build/bbh/bb.edn" "desktop/configs/bbh/**"]
    :fix {:cmd "herd restart bbh-super" :manual "restart the super-agent (Router and Workers)"}}
   {:id "emacs" :label "Emacs daemons" :service "bbh-emacs" :argv0 #"^\.?emacs(-[0-9.]+-real)?$" :arg #"^--(fg-)?daemon"
    :sources ["desktop/configs/doom/**"]
    :fix {:cmd "restart Emacs (herd restart bbh-emacs for the super-agent's)" :manual "restart Emacs"}}
   {:id "rex-server" :label "Rex server" :argv0 #"^rex$" :arg #"^server$"
    :fix {:cmd "rex server stop; the next window starts a new one" :manual "closes every Rex session"}}
   {:id "ghostty-rex" :label "ghostty-rex client" :arg #"build/ghostty-rex/bb\.edn$"
    :sources ["desktop/build/ghostty-rex/src/**" "desktop/build/ghostty-rex/bb.edn" "desktop/build/rexmux/sidecar/**"
              "desktop/lib/mux.clj" "desktop/configs/ghostty-rex/config.edn"]
    :fix {:cmd "rex -C ghostty-rex do mx.client.reload" :run [:sh "rex" "-C" "ghostty-rex" "do" "mx.client.reload"]}}
   {:id "herdr-server" :label "Herdr server" :argv0 #"^herdr$" :arg #"^server$"
    :fix {:cmd "herdr server stop; the next window starts a new one" :manual "closes every Herdr session"}}
   {:id "herdr-config" :label "Herdr config" :of "herdr-server" :sources ["desktop/configs/herdr/**"]
    :fix {:cmd "herdr server reload-config" :run [:sh "herdr" "server" "reload-config"]}}])

(defn store-items
  "The /gnu/store items a string names."
  [s]
  (set (re-seq #"/gnu/store/[0-9a-z]{32}-[^/\s\"'=:]+" (str s))))

(defn short-name [item]
  (some-> item (str/replace #"^/gnu/store/[0-9a-z]{32}-" "")))

(defn- first-item
  "The first store item in STRS, in order: a program's own package before its arguments."
  [& strs]
  (some #(first (re-seq #"/gnu/store/[0-9a-z]{32}-[^/\s\"'=:]+" (str %))) strs))

(defn- package [item] (second (re-find #"^(.*?)(-[0-9][^-]*)*$" (or (short-name item) ""))))

(defn- current-of
  "What the current generation holds of the package ITEM belongs to."
  [closure item]
  (let [p (package item)]
    (some->> closure (filter #(= p (package %))) sort last short-name)))

(defn- by-pid [facts] (into {} (map (juxt :pid identity)) (:procs facts)))

(defn- loaded-ms
  "When the process last took its sources: its start, or a later refresh."
  [facts id start-ms]
  (max (or start-ms 0) (get-in facts [:stamps id] 0)))

(defn- old-items
  "Store items the process runs that the current system and Home no longer hold."
  [facts proc]
  (sort (remove (:closure facts) (store-items (str (:exe proc) " " (str/join " " (:argv proc)))))))

(defn- newer
  "[path ms] of the newest source changed after MS, or nil."
  [facts id ms]
  (let [[path t] (get-in facts [:sources id])]
    (when (and t (> t ms)) [path t])))

(defn- proc-why
  "Why PROC, part of component ID, runs old code, or nil."
  [facts id proc]
  (let [old (old-items facts proc)
        changed (newer facts id (loaded-ms facts id (:start-ms proc)))
        own (some (fn [[f t]] (when (> t (loaded-ms facts id (:start-ms proc))) f)) (:files proc))]
    (cond (:deleted? proc) "its executable was deleted"
          (seq old) (str "runs " (str/join ", " (map short-name old)) ", gone from the current generation")
          changed (str (first changed) " changed after it started")
          own (str own " changed after it started"))))

(defn- fix-for [spec proc]
  (let [{:keys [respawn] :as fix} (:fix spec)]
    (if respawn
      {:cmd (str "restart: " (str/join " " (:argv proc))) :run [:respawn (:pid proc) (:argv proc)]}
      fix)))

(defn- row [m] (merge {:state :fresh} m))

(defn- matches? [spec {:keys [argv]}]
  (let [base (some-> (first argv) (str/replace #".*/" ""))]
    (and (seq argv)
         (or (not (:argv-count spec)) (<= (count argv) (:argv-count spec)))
         (if (:argv0 spec) (boolean (re-find (:argv0 spec) (or base ""))) true)
         (if (:arg spec) (boolean (some #(re-find (:arg spec) %) argv)) true)
         (boolean (or (:argv0 spec) (:arg spec))))))

(defn boot-row [{:keys [boot]}]
  (let [{:keys [current booted]} boot
        diff (sort (filter #(not= (get current %) (get booted %)) [:kernel :initrd :args :firmware]))]
    (row (cond-> {:id "boot" :label "kernel, initrd, firmware" :group :system
                  :running (short-name (:kernel booted)) :current (short-name (:kernel current))}
           (seq diff) (assoc :state :stale :why (str (str/join ", " (map name diff)) " differ from the booted system")
                             :fix {:cmd "sudo reboot" :manual "reboot"})))))

(defn system-row [{:keys [system config]}]
  (let [{:keys [current latest generation-ms]} system
        [path t] config]
    (row (cond-> {:id "system" :label "system generation" :group :system
                  :running (short-name current) :current (short-name latest)}
           (not= current latest)
           (assoc :state :stale :why "the newest generation is not the active one"
                  :fix {:cmd "make switch" :manual "make switch (sudo)"})
           (and (= current latest) t generation-ms (> t generation-ms))
           (assoc :state :stale :why (str path " changed after the last switch")
                  :fix {:cmd "make switch" :manual "make switch (sudo)"})))))

(defn home-row [{:keys [home]}]
  (let [{:keys [active latest]} home]
    (row (cond-> {:id "home" :label "Home generation" :group :home
                  :running (short-name active) :current (short-name latest)}
           (not= active latest)
           (assoc :state :stale :why "~/.guix-home is not the newest Home generation"
                  :fix {:cmd "make home" :manual "make home"})))))

(defn- service-fix [{:keys [scope name]}]
  (cond (and (= name "root") (= scope :system)) {:cmd "sudo reboot" :manual "reboot"}
        (= name "root") {:cmd "log out and back in" :manual "re-login: the home Shepherd runs every home service"}
        (and (= scope :home) (manual-home-services name))
        {:cmd (str "herd restart " name) :manual (manual-home-services name)}
        (= scope :home) {:cmd (str "herd restart " name) :run [:herd :home name]}
        (restartable-system-services name) {:cmd (str "sudo herd restart " name) :run [:herd :system name]}
        :else {:cmd (str "sudo herd restart " name " (or reboot)") :manual "needs your sudo password"}))

(defn service-rows
  "A row per Shepherd service with a process, except the ones a component claims."
  [facts claimed]
  (let [procs (by-pid facts)]
    (for [{:keys [scope name pid command] :as svc} (:services facts)
          :when (not (claimed name))
          :let [proc (cond-> (merge {:argv (str/split (or command "") #" ")} (procs pid))
                       ;; Guix loads each new Home config into the running Shepherd, so
                       ;; the one it started with going away is no reason to restart.
                       (= name "root") (update :argv #(vec (remove (fn [a] (str/ends-with? a "shepherd.conf")) %))))
                why (proc-why facts (str "service:" name) proc)]]
      (row (cond-> {:id (str (clojure.core/name scope) ":" name)
                    :label (if (= name "root") (str "Shepherd (" (clojure.core/name scope) ")") name)
                    :group scope :pid pid
                    :running (or (short-name (apply first-item (:exe proc) (:argv proc))) command)}
             why (assoc :state :stale :why why :fix (service-fix svc)
                        :current (some->> (old-items facts proc) first (current-of (:closure facts)))))))))

(defn- component-procs [facts spec]
  (let [svc (some #(when (= (:name %) (:service spec)) %) (:services facts))]
    (distinct (concat (when svc (keep #(when (= (:pid %) (:pid svc)) %) (:procs facts)))
                      (filter #(matches? spec %) (:procs facts))))))

(defn component-row [facts spec procs-of]
  (let [{:keys [id label of of-service inputs fix note]} spec]
    (cond
      ;; Config a running process reads: stale when an input changed after it last loaded.
      (or of of-service)
      (let [host (if of (first (procs-of of))
                     (some (fn [s] (when (= (:name s) of-service) (some #(when (= (:pid %) (:pid s)) %) (:procs facts))))
                           (:services facts)))]
        (when host
          (let [since (loaded-ms facts id (:start-ms host))
                [path _] (newer facts id since)]
            (row (cond-> {:id id :label label :group :session :running (when note note)}
                   path (assoc :state :stale :why (str path " changed after it was loaded") :fix fix))))))
      :else
      (let [procs (procs-of id)
            stale (keep #(when-let [why (proc-why facts id %)] [% why]) procs)]
        (when (seq procs)
          (row (cond-> {:id id :label label :group :session :pid (str/join "," (map :pid procs))
                        :running (short-name (apply first-item (mapcat #(cons (:exe %) (:argv %)) procs)))}
                 (seq stale) (assoc :state :stale :why (second (first stale))
                                    :fix (fix-for spec (ffirst stale))
                                    :current (some->> (mapcat #(old-items facts %) procs) first (current-of (:closure facts)))))))))))

(defn script-rows
  "Other processes running a script from the repo that changed after they started."
  [facts claimed-pids]
  (for [proc (:procs facts)
        :when (and (seq (:files proc)) (not (claimed-pids (:pid proc))))
        :let [why (proc-why facts nil proc)]]
    (row (cond-> {:id (str "script:" (:pid proc)) :label (str/join " " (take 2 (map #(str/replace % #".*/" "") (rest (:argv proc)))))
                  :group :session :pid (:pid proc)}
           why (assoc :state :stale :why why
                      :fix {:cmd (str "restart: " (str/join " " (:argv proc))) :manual "restart it"})))))

(def session-var
  "Variables that name one login or one terminal pane, so a saved copy goes stale
  (desktop/configs/doom/cli.el keeps them out of doom env)."
  #"^(HYPRLAND_|HL_|HERDR_|GHOSTTY_|REX_(SESSION|BLOCK|PANE)|TERM_PROGRAM)")

(defn doom-rows [{:keys [doom closure]}]
  (when doom
    (let [{:keys [synced-ms sources-ms sources env-vars env-ms env-text deny-ms]} doom
          old (sort (remove closure (store-items env-text)))
          bad (sort (filter #(re-find session-var %) env-vars))
          why (cond (seq bad) (str "it saved " (str/join ", " bad) ", which name one login or pane")
                    (seq old) (str "it names " (str/join ", " (map short-name old)) ", gone from the current generation")
                    (and env-ms deny-ms (> deny-ms env-ms)) "cli.el changed after it was written")]
      [(row (cond-> {:id "doom-sync" :label "Doom packages" :group :home}
              (and sources-ms synced-ms (> sources-ms synced-ms))
              (assoc :state :stale :why (str sources " changed after the last doom sync")
                     :fix {:cmd "doom sync, then restart Emacs" :manual "doom sync and an Emacs restart"})))
       (row (cond-> {:id "doom-env" :label "Doom env file" :group :home}
              why (assoc :state :stale :why why
                         :fix {:cmd "doom env, then restart Emacs" :run [:doom "env"]})))])))

(defn links-row [{:keys [links]}]
  (when links
    (let [{:keys [missing]} links]
      (row (cond-> {:id "links" :label "config links in Home" :group :home}
             (seq missing)
             (assoc :state :stale
                    :why (str (count missing) " repo file(s) have no link, such as " (first missing))
                    :fix {:cmd "bb desktop/stow-home" :run [:stow]}))))))

(defn extension-rows [{:keys [extensions] :as facts}]
  (for [{:keys [name src built-ms]} extensions
        :let [[path t] src
              since (max (or built-ms 0) (get-in facts [:stamps (str "ext:" name)] 0))]]
    (row (cond-> {:id (str "ext:" name) :label (str "Chrome extension " name) :group :session}
           (and t (> t since))
           (assoc :state :stale :why (str path " changed after the last build")
                  :fix {:cmd (str "ext-reload " name) :run [:bin "ext-reload" name] :stamp (str "ext:" name)})))))

(defn assess
  "Rows for every component FACTS describe, in display order."
  [facts]
  (let [specs (remove #(or (:of %) (:of-service %)) components)
        procs-of (into {} (for [s specs] [(:id s) (component-procs facts s)]))
        claimed (set (keep :service components))
        claimed-pids (into (set (keep :pid (:services facts))) (map :pid (apply concat (vals procs-of))))]
    (vec (concat [(boot-row facts) (system-row facts) (home-row facts)]
                 (keep identity [(links-row facts)])
                 (service-rows facts claimed)
                 (keep #(component-row facts % procs-of) components)
                 (doom-rows facts)
                 (extension-rows facts)
                 (script-rows facts claimed-pids)))))

(defn summary
  "Counts and the stale rows split by whether refresh can fix them."
  [rows]
  (let [stale (filter #(= :stale (:state %)) rows)]
    {:total (count rows) :stale (count stale)
     :auto (filter #(get-in % [:fix :run]) stale)
     :manual (remove #(get-in % [:fix :run]) stale)}))
