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
   :services [] :procs [] :sources {} :stamps {}})

(defn row [facts id] (some #(when (= id (:id %)) %) (stale/assess facts)))
(defn stale? [r] (= :stale (:state r)))
(defn auto? [r] (some? (get-in r [:fix :run])))

(defn with-service [facts scope name & {:keys [exe start files] :or {exe new-bb start 10000}}]
  (let [pid (+ 100 (count (:procs facts)))]
    (-> facts
        (update :services conj {:scope scope :name name :pid pid :command (str exe "/bin/x")})
        (update :procs conj {:pid pid :start-ms start :argv [(str exe "/bin/x")] :exe (str exe "/bin/x") :files (or files {})}))))

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
   ["the super-agent is never restarted by refresh"
    (let [f (-> (with-service base :home "bbh-super")
                (assoc :sources {"bbh" ["desktop/build/bbh/src/bbh/super.clj" 20000]}))
          r (row f "bbh")]
      (and (stale? r) (not (auto? r)) (nil? (row f "home:bbh-super"))))]
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
   ["an Emacs daemon on an old Emacs needs a restart by hand"
    (let [f (update base :procs conj {:pid 11 :start-ms 1 :exe (str (item "emacs-30") "/bin/emacs")
                                       :argv ["emacs" "--fg-daemon=x"]})
          r (row f "emacs")]
      (and (stale? r) (not (auto? r)) (str/includes? (:why r) "emacs-30")))]
   ["a Doom env file naming one Hyprland login is rewritten by refresh"
    (let [r (row (assoc base :doom {:env-vars #{"PATH" "HYPRLAND_INSTANCE_SIGNATURE"} :env-text ""}) "doom-env")]
      (and (stale? r) (auto? r)))]
   ["a Doom env file naming an old store path is rewritten too"
    (stale? (row (assoc base :doom {:env-vars #{"EMACSLOADPATH"} :env-text (str "EMACSLOADPATH=" (item "emacs-30") "/lisp")}) "doom-env"))]
   ["packages.el newer than the last doom sync needs doom sync by hand"
    (let [r (row (assoc base :doom {:synced-ms 1 :sources "packages.el" :sources-ms 2 :env-vars #{}}) "doom-sync")]
      (and (stale? r) (not (auto? r))))]
   ["an extension edited after its build rebuilds, and its stamp ends that"
    (let [f (assoc base :extensions [{:name "vim" :src ["src/a.cljs" 300] :built-ms 200}])]
      (and (= [:bin "ext-reload" "vim"] (get-in (row f "ext:vim") [:fix :run]))
           (not (stale? (row (assoc-in f [:stamps "ext:vim"] 400) "ext:vim")))))]
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
(put! (str repo "/desktop/bin/md-view") "")
(fs/set-last-modified-time (str repo "/desktop/bin/md-view") (java.nio.file.attribute.FileTime/fromMillis 1000500))
(proc! 41 ["bb" "/repo/desktop/bin/ext-reload" "serve"] 100 (str new-bb "/bin/bb"))
(proc! 42 ["bb" "/repo/desktop/bin/md-view" "serve"] 100 (str new-bb "/bin/bb"))
(proc! 43 [(str old-bb "/bin/bb") "--version"] 100 (str old-bb "/bin/bb"))
(proc! 44 [] 1 "/nonexistent")
(fs/create-dirs (str fake "/gnu/store/sys-1"))
(fs/create-dirs (str fake "/run"))
(fs/create-sym-link (str fake "/run/current-system") (str fake "/gnu/store/sys-1"))
(def herd
  {:home {["status"] "Started:\n + ext-reload\n + md-view\n + root\nStopped:\n - x\n"
          ["status" "ext-reload" "-n" "0"] "  Main PID: 41\n  Command: bb ext-reload serve\n"
          ["status" "md-view" "-n" "0"] "  Main PID: 42\n  Command: bb md-view serve\n"
          ["status" "root" "-n" "0"] "  It is started.\n"}})
(def ctx {:root fake :repo repo :home "/home/me" :user "me" :stamps (str fake "/stamps")
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
   ["a stamp written by refresh is read back"
    (do (collect/stamp! ctx "ext:vim") (contains? (collect/stamps ctx) "ext:vim"))]])

(fs/delete-tree fake)
(doseq [[label ok] (concat results collect-results)] (println (if ok "ok  " "FAIL") label))
(System/exit (if (every? second (concat results collect-results)) 0 1))
