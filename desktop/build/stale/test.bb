#!/usr/bin/env bb
;; make status's staleness rules (desktop/lib/stale.clj) on made-up facts, and its
;; collector (desktop/lib/stale/collect.clj) on a made-up /proc, profiles and herd.
(require '[babashka.classpath :as classpath] '[babashka.fs :as fs] '[clojure.string :as str])
(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/real-path *file*)))))))
(classpath/add-classpath (str root "/desktop/lib"))
(require '[stale] '[stale.collect :as collect])

(defn item [name] (str "/gnu/store/" (apply str (repeat 32 "a")) "-" name))
(def old-bb (str/replace (item "babashka-1.0") "aaaa" "bbbb"))
(def new-bb (item "babashka-1.1"))

(def base
  {:boot {:current {:kernel (item "linux-7.2/Image") :initrd "i" :args "a"}
          :booted {:kernel (item "linux-7.2/Image") :initrd "i" :args "a"}}
   :system {:current (item "system") :latest (item "system") :generation-ms 5000}
   :config ["modules/a.scm" 4000]
   :home {:active (item "home") :latest (item "home")}
   :closure #{new-bb (item "kanata-1.11") (item "emacs-31")}
   :services [] :procs [] :sources {} :stamps {} :restartable #{"tailscaled" "kanata"}})

(defn row [facts id] (some #(when (= id (:id %)) %) (stale/assess facts)))
(defn stale? [r] (= :stale (:state r)))
(defn auto? [r] (some? (get-in r [:fix :run])))

(defn with-service [facts scope name & {:keys [exe start files] :or {exe new-bb start 10000}}]
  (let [pid (+ 100 (count (:procs facts)))]
    (-> facts
        (update :services conj {:scope scope :name name :pid pid :command (str exe "/bin/x")})
        (update :procs conj {:pid pid :start-ms start :argv [(str exe "/bin/x")] :exe (str exe "/bin/x") :files (or files {})}))))

(def bbh-edn "/r/desktop/build/bbh/bb.edn")
(defn bbh-proc [pid ppid & args] {:pid pid :ppid ppid :start-ms 1000 :exe (str new-bb "/bin/bb") :files {} :argv (into ["bb" "--config" bbh-edn] args)})

(defn bbh-facts
  "A super-agent: the controller under Shepherd, the Router and Worker W9 in an
  Emacs, each an acp, its REPL, a claude and its MCP server, and an MCP server
  whose claude died. CHANGED maps source files to when they changed; every
  process started at 1000."
  [changed]
  (-> base
      (update :services conj {:scope :home :name "bbh-super" :pid 200 :command "bb bbh super serve"})
      (assoc :procs [{:pid 150 :ppid 1 :argv ["/gnu/bin/guile" "--no-auto-compile" "/gnu/bin/shepherd" "--config" "c.scm"] :start-ms 10}
                     {:pid 250 :ppid 150 :argv ["emacs" "--fg-daemon=bbh-super"] :start-ms 10 :exe (str (item "emacs-31") "/bin/emacs")}
                     (bbh-proc 200 150 "-m" "bbh.main" "super" "serve")
                     (bbh-proc 300 250 "-m" "bbh.main" "acp" "--id" "router")
                     (bbh-proc 301 300 "-e" "(require '[babashka.nrepl.server :as srv])")
                     {:pid 302 :ppid 300 :argv ["claude" "--mcp-config" (str "{\"args\":[\"" bbh-edn "\"]}")] :start-ms 1000}
                     (bbh-proc 303 302 "-m" "bbh.mcp")
                     (bbh-proc 400 250 "-m" "bbh.main" "acp" "--id" "super-W9")
                     (bbh-proc 401 400 "-e" "(require '[babashka.nrepl.server :as srv])")
                     {:pid 402 :ppid 400 :argv ["claude"] :start-ms 1000}
                     (bbh-proc 403 402 "-m" "bbh.mcp")
                     (bbh-proc 500 1 "-m" "bbh.mcp")
                     (bbh-proc 501 150 "-m" "bbh.mcp")])
      (assoc :bbh {:src (merge {"heavy.clj" 500 "acp.clj" 500 "bb.edn" 100} changed)
                   :ns {"heavy.clj" 'bbh.heavy "acp.clj" 'bbh.acp} :deps {}
                   :reloadable #{"heavy.clj"}
                   :loads {:controller #{"acp.clj" "bb.edn"} :acp #{"acp.clj" "bb.edn"} :mcp #{"bb.edn"}
                           :repl #{"heavy.clj" "acp.clj" "bb.edn"}}
                   :ports {301 "/c/router/repl.port" 401 "/c/super-W9/repl.port"}})))

(def results
  [["a fresh system has no stale row"
    (not-any? stale? (stale/assess base))]
   ["a new kernel asks for a reboot, which refresh cannot do"
    (let [r (row (assoc-in base [:boot :current :kernel] (item "linux-7.3/Image")) "boot")]
      (and (stale? r) (not (auto? r)) (= "reboot" (get-in r [:fix :manual]))))]
   ["config edited after the last switch asks for make switch"
    (let [r (row (assoc base :config ["modules/a.scm" 6000]) "system")]
      (and (stale? r) (= "make switch" (get-in r [:fix :cmd])) (str/includes? (:why r) "modules/a.scm")))]
   ["a system service on a store path gone from the generation restarts when sudo allows it"
    (let [r (row (with-service base :system "tailscaled" :exe old-bb) "system:tailscaled")]
      (and (stale? r) (= [:herd :system "tailscaled"] (get-in r [:fix :run]))))]
   ["a stale service shows the version the current generation holds"
    (let [r (row (with-service base :system "tailscaled" :exe old-bb) "system:tailscaled")]
      (and (= "babashka-1.0" (:running r)) (= "babashka-1.1" (:current r))))]
   ["one sudo would ask a password for is left to you"
    (let [r (row (with-service base :system "guix-daemon" :exe old-bb) "system:guix-daemon")]
      (and (stale? r) (not (auto? r))))]
   ["a service still on the current store path is fresh"
    (not (stale? (row (with-service base :system "tailscaled") "system:tailscaled")))]
   ["a home service whose script changed after it started restarts"
    (let [f (with-service base :home "ext-reload" :files {"desktop/bin/ext-reload" 20000})
          r (row f "home:ext-reload")]
      (and (stale? r) (= [:herd :home "ext-reload"] (get-in r [:fix :run]))
           (not (stale? (row (with-service base :home "ext-reload" :files {"desktop/bin/ext-reload" 9000}) "home:ext-reload")))))]
   ["a REPL-only namespace that holds no state is hot-reloaded in each session's REPL, and its stamp ends that"
    (let [f (bbh-facts {"heavy.clj" 2000})
          r (row f "bbh:router") w (row f "bbh:super-W9")]
      (and (stale? r) (= [:nrepl-reload "/c/router/repl.port" ['bbh.heavy]] (get-in r [:fix :run]))
           (= "300,301,303" (:pid r)) (= "bbh Router session" (:label r))
           (= [:nrepl-reload "/c/super-W9/repl.port" ['bbh.heavy]] (get-in w [:fix :run]))
           (not (stale? (row f "bbh:controller")))
           (not (stale? (row (assoc-in f [:stamps (get-in r [:fix :stamp])] 3000) "bbh:router")))))]
   ["a namespace with state is never hot-reloaded: the Router restarts through the controller instead"
    (let [r (row (update-in (bbh-facts {"heavy.clj" 2000}) [:bbh :reloadable] disj "heavy.clj") "bbh:router")]
      (and (stale? r) (= :router-restart (first (get-in r [:fix :run])))))]
   ["session code the acp loads: the controller restarts itself, the Router is drained and reopened, a Worker keeps its code"
    (let [f (bbh-facts {"acp.clj" 2000})
          c (row f "bbh:controller") r (row f "bbh:router") w (row f "bbh:super-W9")
          s (stale/summary (stale/assess f))]
      (and (= :pending (:state c)) (= "restart pending until idle" (get-in c [:fix :pending]))
           (stale? r) (= :router-restart (first (get-in r [:fix :run]))) (str/includes? (:why r) "acp.clj")
           (= :pending (:state w)) (not (auto? w))
           (= #{"bbh:controller" "bbh:super-W9"} (set (map :id (:pending s))))
           (not-any? #(str/starts-with? (:id %) "bbh:") (:manual s))))]
   ["once refresh asked for it, the Router shows its restart pending until idle"
    (let [r (row (assoc-in (bbh-facts {"acp.clj" 2000}) [:bbh :router-request] {:by "make refresh"}) "bbh:router")]
      (and (= :pending (:state r)) (= "restart pending until idle" (get-in r [:fix :pending])) (not (auto? r))))]
   ["a session process whose parent died is a leftover refresh kills; live sessions' are not"
    (let [f (bbh-facts {})
          rows (filter #(str/starts-with? (:id %) "bbh:leftover:") (stale/assess f))]
      (and (= ["bbh:leftover:500" "bbh:leftover:501"] (map :id rows)) (= [:kill 500 1000] (get-in (first rows) [:fix :run]))))]
   ["a fresh super-agent has no stale bbh row, and bbh-super is no plain service row"
    (let [f (bbh-facts {})]
      (and (not-any? #(and (str/starts-with? (:id %) "bbh:") (not (str/starts-with? (:id %) "bbh:leftover:")) (not= :fresh (:state %)))
                     (stale/assess f))
           (nil? (row f "home:bbh-super"))
           (not-any? #(str/starts-with? (:id %) "script:") (stale/assess f))))]
   ["the home Shepherd keeps running when only its first config is gone"
    (let [f (-> base
                (update :services conj {:scope :home :name "root" :pid 7 :command (str new-bb "/bin/shepherd --config " (item "shepherd.conf"))})
                (update :procs conj {:pid 7 :start-ms 1 :argv [(str new-bb "/bin/shepherd") "--config" (item "shepherd.conf")]}))]
      (not (stale? (row f "home:root"))))]
   ["kanata takes a new /etc/kanata after hyprkan reload, recorded as a stamp"
    (let [f (-> (with-service base :system "kanata" :exe (item "kanata-1.11") :start 1000)
                (assoc-in [:sources "kanata-config"] ["/etc/kanata" 5000]))]
      (and (stale? (row f "kanata-config"))
           (= [:bin "hyprkan" "reload"] (get-in (row f "kanata-config") [:fix :run]))
           (not (stale? (row (assoc-in f [:stamps "kanata-config"] 6000) "kanata-config")))))]
   ["a program name inside an agent's prompt is not that program"
    (let [f (update base :procs conj {:pid 9 :start-ms 1 :exe (str old-bb "/bin/claude")
                                      :argv ["claude" "--system-prompt" "Hyprland rex server emacs --daemon herdr"]})]
      (every? nil? (map #(row f %) ["hyprland" "rex-server" "emacs" "herdr-server"])))]
   ["a missing hyprkan follower in a running Hyprland is down, and refresh starts it"
    (let [hypr {:pid 5 :start-ms 1 :exe (str new-bb "/bin/Hyprland") :argv ["Hyprland"]}
          r (row (update base :procs conj hypr) "hyprkan")]
      (and (= :down (:state r)) (= [:respawn nil ["bb" "~/.local/bin/hyprkan"]] (get-in r [:fix :run]))
           (= 1 (:stale (stale/summary [r])))
           (nil? (row base "hyprkan"))))]
   ["an Emacs daemon on an old Emacs needs a restart by hand"
    (let [f (update base :procs conj {:pid 11 :start-ms 1 :exe (str (item "emacs-30") "/bin/emacs")
                                       :argv ["emacs" "--fg-daemon=x"]})
          r (row f "emacs")]
      (and (stale? r) (not (auto? r)) (str/includes? (:why r) "emacs-30")))]
   ["a daemon's server name comes from its --daemon flag"
    (= ["bbh-super" "server" "y" nil]
       (map stale/daemon-name [["emacs" "--fg-daemon=bbh-super"] ["emacs" "--daemon"] ["emacs" "--bg-daemon=y"] ["emacs" "-nw"]]))]
   ["Doom config edited after a daemon started reloads live through emacsclient, and its stamp ends that"
    (let [f (-> base
                (update :procs conj {:pid 11 :start-ms 100 :exe (str (item "emacs-31") "/bin/emacs") :argv ["emacs" "--fg-daemon=bbh-super"]})
                (assoc :doom-config {"desktop/configs/doom/config.el" 200 "desktop/configs/doom/init.el" 50}))
          r (row f "emacs-config:bbh-super")]
      (and (stale? r) (= [:emacs "bbh-super" :config] (get-in r [:fix :run])) (str/includes? (:why r) "config.el")
           (not (stale? (row f "emacs")))
           (not (stale? (row (assoc-in f [:stamps "emacs-config:bbh-super"] 300) "emacs-config:bbh-super")))))]
   ["init.el or packages.el edited after a daemon started needs doom sync and a restart"
    (let [f (-> base
                (update :procs conj {:pid 11 :start-ms 100 :exe (str (item "emacs-31") "/bin/emacs") :argv ["emacs" "--daemon"]})
                (assoc :doom-config {"desktop/configs/doom/config.el" 200 "desktop/configs/doom/packages.el" 200}))
          r (row f "emacs-config:server")]
      (and (stale? r) (not (auto? r)) (str/includes? (:why r) "packages.el")))]
   ["a daemon started before the env file was rewritten loads it again, once the file is fresh"
    (let [f (-> base
                (update :procs conj {:pid 11 :start-ms 100 :exe (str (item "emacs-31") "/bin/emacs") :argv ["emacs" "--fg-daemon=x"]})
                (assoc :doom {:env-file "/e.el" :env-ms 200 :env-vars #{"PATH"} :env-text ""}))
          r (row f "emacs-env:x")]
      (and (stale? r) (= [:emacs "x" :env "/e.el"] (get-in r [:fix :run]))
           (not (stale? (row (assoc-in f [:doom :env-vars] #{"PATH" "REX_BLOCK"}) "emacs-env:x")))
           (not (stale? (row (assoc-in f [:stamps "emacs-env:x"] 300) "emacs-env:x")))))]
   ["a Doom env file naming one Hyprland login, pane or Worker is rewritten by doom env"
    (every? (fn [v] (let [r (row (assoc base :doom {:env-file "/e.el" :env-vars #{"PATH" v} :env-text ""}) "doom-env")]
                      (and (stale? r) (= [:doom-env "/e.el"] (get-in r [:fix :run])))))
            ["HYPRLAND_INSTANCE_SIGNATURE" "REX_BLOCK" "REX_SERVER" "TERM_PROGRAM_VERSION" "HERDR_PANE_ID" "BBH_SUPER_TOKEN" "WF_RUN"])]
   ["a Doom env file naming an old store path is stale too"
    (stale? (row (assoc base :doom {:env-vars #{"EMACSLOADPATH"} :env-text (str "EMACSLOADPATH=" (item "emacs-30") "/lisp")}) "doom-env"))]
   ["packages.el newer than the last doom sync needs doom sync by hand"
    (let [r (row (assoc base :doom {:synced-ms 1 :sources "packages.el" :sources-ms 2 :env-vars #{}}) "doom-sync")]
      (and (stale? r) (not (auto? r))))]
   ["a Ghostty whose config changed after it started reloads on SIGUSR2, and its stamp ends that"
    (let [g {:pid 30 :start-ms 100 :class "dev.engstrand.Rex" :usr2? true :files {"/h/.config/ghostty/config" 200} :text "a = 1"}
          f (assoc base :ghostty [g])
          r (row f "ghostty:30")]
      (and (stale? r) (= :ghostty-reload (first (get-in r [:fix :run]))) (str/includes? (:label r) "Rex")
           (not (stale? (row (assoc-in f [:stamps "ghostty:30"] 300) "ghostty:30")))
           (not (auto? (row (assoc base :ghostty [(assoc g :usr2? false)]) "ghostty:30")))))]
   ["a reload that changes command or window-padding-x keeps that Ghostty listed until it restarts"
    (let [g {:pid 30 :start-ms 100 :usr2? true :files {"/c" 200}
             :snapshot "command = fish\nfont-size = 9\n" :text "command = bash\nfont-size = 10\nwindow-padding-x = 4\n"}
          r (row (assoc base :ghostty [g]) "ghostty:30")
          after (row (assoc base :ghostty [(assoc g :files {"/c" 50} :pending (last (get-in r [:fix :run])))]) "ghostty:30")]
      (and (= ["command" "window-padding-x"] (last (get-in r [:fix :run])))
           (stale? after) (not (auto? after)) (str/includes? (:why after) "command")
           (not (stale? (row (assoc base :ghostty [(dissoc g :snapshot)]) "ghostty:31")))))]
   ["an extension edited after its build rebuilds, and its stamp ends that"
    (let [f (assoc base :extensions [{:name "vim" :src ["src/a.cljs" 300] :built-ms 200}])]
      (and (= [:bin "ext-reload" "vim"] (get-in (row f "ext:vim") [:fix :run]))
           (not (stale? (row (assoc-in f [:stamps "ext:vim"] 400) "ext:vim")))))]
   ["a Rex server without SSL_CERT_FILE is stale and names how to fix it; with it, fresh"
    (let [r (row (assoc base :ca-env [{:label "Rex server" :pid 7 :ok? false} {:label "Emacs daemon" :pid 8 :ok? true}]) "ca-env")]
      (and (stale? r) (not (auto? r)) (str/includes? (:why r) "Rex server 7")
           (str/includes? (get-in r [:fix :cmd]) "rex server restart")
           (not (stale? (row (assoc base :ca-env [{:label "Rex server" :pid 7 :ok? true}]) "ca-env")))))]
   ["the parents of panes, buffers and services are told apart by argv"
    (= ["Rex server" "Emacs daemon" "Home Shepherd" nil]
       (map stale/env-holder [["/gnu/store/x-rex/bin/rex" "server" "run" "--listen" "u"]
                              ["/h/.guix-home/profile/bin/emacs" "--fg-daemon=bbh-super"]
                              ["guile" "/gnu/store/x-shepherd-for-home-1.0.9/bin/shepherd" "--silent"]
                              ["rex" "ls"]]))]
   ["summary splits what refresh fixes from what needs you"
    (let [s (stale/summary (stale/assess (-> (with-service base :home "md-view" :exe old-bb)
                                             (assoc-in [:boot :current :kernel] "k2"))))]
      (and (= 2 (:stale s)) (= ["home:md-view"] (map :id (:auto s))) (= ["boot"] (map :id (:manual s)))))]])

;; The collector on a made-up root; argv paths are relative to it, as /proc shows them.
(def fake (str (fs/create-temp-dir {:prefix "stale-test"})))
(def repo (str fake "/repo"))
(defn put! [p s] (fs/create-dirs (fs/parent p)) (spit p s))
(put! (str fake "/proc/stat") "cpu 1 2 3\nbtime 1000\n")
(defn proc! [pid argv ticks exe]
  (put! (str fake "/proc/" pid "/cmdline") (str (str/join "\u0000" argv) "\u0000"))
  (put! (str fake "/proc/" pid "/stat") (str pid " (x y) S " (str/join " " (concat (repeat 18 "0") [ticks "0"]))))
  (fs/create-sym-link (str fake "/proc/" pid "/exe") exe))
(put! (str repo "/desktop/bin/ext-reload") "")
(put! (str repo "/desktop/configs/hypr/hyprland.lua") "")
(put! (str repo "/desktop/configs/hypr/README.md") "")
(put! (str fake "/home/me/.config/hypr/old.lua") "")
(put! (str repo "/desktop/bin/md-view") "")
(put! (str repo "/desktop/configs/doom/config.el") "")
(put! (str repo "/desktop/configs/doom/cli.el") "")
(fs/set-last-modified-time (str repo "/desktop/bin/md-view") (java.nio.file.attribute.FileTime/fromMillis 1000500))
(proc! 41 ["bb" "/repo/desktop/bin/ext-reload" "serve"] 100 (str new-bb "/bin/bb"))
(proc! 42 ["bb" "/repo/desktop/bin/md-view" "serve"] 100 (str new-bb "/bin/bb"))
(proc! 43 [(str old-bb "/bin/bb") "--version"] 100 (str old-bb "/bin/bb"))
(proc! 44 [] 1 "/nonexistent")
(put! (str fake "/home/me/.config/ghostty/config") "font-size = 9\n")
(put! (str fake "/home/me/.config/rex.ghostty") "shell-integration = none\n")
(proc! 45 ["/gnu/bin/ghostty" "--class=dev.engstrand.Rex" "--config-file=/home/me/.config/rex.ghostty"] 100 "/gnu/bin/ghostty")
(put! (str fake "/proc/45/status") "Name:\tghostty\nSigCgt:\t0000000180014a07\n")
(proc! 46 ["ghostty" "+show-config"] 100 "/gnu/bin/ghostty")
(proc! 47 ["/gnu/store/x-rex/bin/rex" "server" "run"] 100 "/nonexistent")
(put! (str fake "/proc/47/environ") "HOME=/home/me\u0000PATH=/bin\u0000")
(proc! 48 ["/bin/emacs" "--fg-daemon=x"] 100 "/nonexistent")
(put! (str fake "/proc/48/environ") "HOME=/home/me\u0000SSL_CERT_FILE=/c.crt\u0000")
(put! (str repo "/desktop/build/bbh/bb.edn") "{:paths [\"src\"]}")
(put! (str repo "/desktop/build/bbh/src/bbh/main.clj") "(ns bbh.main (:require [bbh.acp :as acp]))\n(defn web [] ((requiring-resolve 'bbh.lazy/f)))")
(put! (str repo "/desktop/build/bbh/src/bbh/acp.clj") "(ns bbh.acp \"Not bbh.heavy.\")\n(def sessions (atom {}))")
(put! (str repo "/desktop/build/bbh/src/bbh/lazy.clj") "(ns bbh.lazy)\n(defn f [] 1)")
(put! (str repo "/desktop/build/bbh/src/bbh/mcp.clj") "(ns bbh.mcp)")
(put! (str repo "/desktop/build/bbh/src/bbh/heavy.clj") "(ns bbh.heavy)\n(defn command [c] c)")
(put! (str repo "/desktop/configs/bbh/init.clj") "(ns bbh.env (:require [bbh.mcp]))\n(require '[clojure.string :as str]\n         [bbh.lazy :as lazy])\n(def run (requiring-resolve 'bbh.heavy/command))")
(proc! 49 ["bb" "--config" (str repo "/desktop/build/bbh/bb.edn") "-e" "(require '[babashka.nrepl.server :as srv])"] 100 (str new-bb "/bin/bb"))
(put! (str fake "/proc/49/environ") "HOME=/home/me\u0000BBH_PORT_FILE=/home/me/.local/share/bbh/conversations/router/repl.port\u0000")
(put! (str fake "/proc/49/status") "Name:\tbb\nPPid:\t46\n")
(put! (str fake "/super/restart-router") "{:by \"make refresh\"}")
(fs/create-dirs (str fake "/gnu/store/sys-1"))
(fs/create-dirs (str fake "/run"))
(fs/create-sym-link (str fake "/run/current-system") (str fake "/gnu/store/sys-1"))
(def herd
  {:home {["status"] "Started:\n + ext-reload\n + md-view\n + root\nStopped:\n - x\n"
          ["status" "ext-reload" "-n" "0"] "  Main PID: 41\n  Command: bb ext-reload serve\n"
          ["status" "md-view" "-n" "0"] "  Main PID: 42\n  Command: bb md-view serve\n"
          ["status" "root" "-n" "0"] "  It is started.\n"}})
(def ctx {:root fake :repo repo :home "/home/me" :user "me" :stamps (str fake "/stamps") :super-dir (str fake "/super")
          :herd (fn [scope & args] (get-in herd [scope (vec args)]))
          :requisites (fn [_] [new-bb])})
(def facts (collect/facts ctx))
(def collected (stale/assess facts))
(defn crow [id] (some #(when (= id (:id %)) %) collected))

(def collect-results
  [["a process's start time comes from /proc/PID/stat and the boot time"
    (= 1001000 (:start-ms (some #(when (= 41 (:pid %)) %) (:procs facts))))]
   ["Shepherd services with a process are found, the rest are not"
    (= #{"ext-reload" "md-view"} (set (map :name (:services facts))))]
   ["a service whose script changed after it started is stale"
    (and (stale? (crow "home:ext-reload")) (str/includes? (:why (crow "home:ext-reload")) "desktop/bin/ext-reload"))]
   ["a service whose script is older than the process is fresh"
    (not (stale? (crow "home:md-view")))]
   ["kernel threads, with no command line, are skipped"
    (not-any? #(= 44 (:pid %)) (:procs facts))]
   ["the services sudo may restart come from agent-control.scm"
    (contains? (collect/restartable root) "kanata")]
   ["a repo config with no link in Home is linked by refresh; stow's README is not missed"
    (and (= ["desktop/configs/hypr/hyprland.lua"] (filter #(str/includes? % "hypr") (:missing (:links facts))))
         (= [:stow] (get-in (crow "links") [:fix :run])))]
   ["cli.el is no Doom config a running Emacs loads"
    (= ["desktop/configs/doom/config.el"] (keys (:doom-config facts)))]
   ["a Ghostty window reads the default config and its --config-file; ghostty +actions are no window"
    (let [[g & more] (:ghostty facts)]
      (and (empty? more) (= 45 (:pid g)) (:usr2? g) (= "dev.engstrand.Rex" (:class g))
           (= #{"/home/me/.config/ghostty/config" "/home/me/.config/rex.ghostty"} (set (keys (:files g))))
           (str/includes? (:text g) "shell-integration = none")))]
   ["what refresh saves for a reloaded Ghostty comes back as its snapshot"
    (do (collect/ghostty-reloaded! ctx 45 1001000 "font-size = 9" ["command"])
        (let [g (first (collect/ghostty ctx (:procs facts)))]
          (and (= "font-size = 9" (:snapshot g)) (= ["command"] (:pending g)))))]
   ["a pane parent's environ without SSL_CERT_FILE makes the ca-env row stale"
    (and (= #{[47 false] [48 true]} (set (map (juxt :pid :ok?) (:ca-env facts))))
         (stale? (crow "ca-env")))]
   ["bbh code is mapped to what loads it: requires, requiring-resolve and init.clj's requires"
    (let [{:keys [loads]} (:bbh facts)
          f #(str "desktop/build/bbh/src/bbh/" % ".clj")]
      (and (= #{(f "main") (f "acp") (f "lazy") "desktop/build/bbh/bb.edn"} (:acp loads) (:controller loads))
           (= #{(f "mcp") "desktop/build/bbh/bb.edn"} (:mcp loads))
           (= #{(f "mcp") (f "lazy") (f "heavy") "desktop/build/bbh/bb.edn" "desktop/configs/bbh/init.clj"} (:repl loads))))]
   ["a namespace that keeps an atom is not reloadable; one of plain functions is"
    (let [{:keys [reloadable]} (:bbh facts)]
      (and (reloadable "desktop/build/bbh/src/bbh/heavy.clj") (not (reloadable "desktop/build/bbh/src/bbh/acp.clj"))))]
   ["a REPL's port file comes from its environment, a process's parent from its status"
    (and (= {49 "/home/me/.local/share/bbh/conversations/router/repl.port"} (:ports (:bbh facts)))
         (= 46 (:ppid (some #(when (= 49 (:pid %)) %) (:procs facts)))))]
   ["a Router restart that refresh asked for is read back"
    (= {:by "make refresh"} (:router-request (:bbh facts)))]
   ["a stamp written by refresh is read back"
    (do (collect/stamp! ctx "ext:vim") (contains? (collect/stamps ctx) "ext:vim"))]])

(fs/delete-tree fake)
(doseq [[label ok] (concat results collect-results)] (println (if ok "ok  " "FAIL") label))
(System/exit (if (every? second (concat results collect-results)) 0 1))
