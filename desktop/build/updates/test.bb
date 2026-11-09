#!/usr/bin/env bb
;; Runs desktop/bin/updates apply nixpkgs against a scratch nixpkgs-bridge repo
;; and channels.scm, with a seeded cache and stub make and jj, and checks what
;; it locks, commits and pins.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def updates (str (fs/path (fs/parent *file*) "../../bin/updates")))
(def channels-src (str (fs/path (fs/parent *file*) "../../../channels.scm")))
(def pin-re #"(?s)\(name 'nixpkgs-bridge\).*?\(commit \"([0-9a-f]+)\"")
(def locked "aaaa")
(def published "cccccccccccccccccccccccccccccccccccccccc")

(defn run-apply
  "Run updates apply SELECTOR.  The stub make logs its arguments and exits
EXIT, and `make lock' leaves a change in the bridge unless the bridge starts
DIRTY.  The stub jj logs commits and reports main at `published'.  Return
the make and jj logs, the pinned commit, the exit code and stderr."
  [revision selector & {:keys [exit dirty] :or {exit 0}}]
  (let [dir (str (fs/create-temp-dir))
        bin (str dir "/bin")
        log (str dir "/make.log")
        jj-log (str dir "/jj.log")
        changed (str dir "/changed")
        channels (str dir "/channels.scm")
        bridge (str dir "/bridge")]
    (fs/create-dirs bin)
    (fs/create-dirs (str dir "/updates"))
    (fs/create-dirs (str bridge "/modules/nixpkgs-bridge"))
    (fs/copy channels-src channels)
    (when dirty (spit changed ""))
    (spit (str bridge "/modules/nixpkgs-bridge/lock.sexp")
          (str "(nixpkgs-lock (revision \"" locked "\") (system \"aarch64-linux\")"
               " (packages (package (attr \"ghostty\") (version \"1.0.0\"))) (items))"))
    (spit (str bin "/make") (str "#!/bin/sh\necho \"$@\" >> " log "\n"
                                 "[ \"$1\" = lock ] && touch " changed "\nexit " exit "\n"))
    (spit (str bin "/jj") (str "#!/bin/sh\ncase \"$1\" in\n"
                               "  diff) [ -e " changed " ] && echo modules/nixpkgs-bridge/lock.sexp ;;\n"
                               "  commit) echo \"$@\" >> " jj-log "; rm -f " changed " ;;\n"
                               "  log) printf " published " ;;\nesac\nexit 0\n"))
    (doseq [f ["make" "jj"]] (fs/set-posix-file-permissions (str bin "/" f) "rwxr-xr-x"))
    (spit (str dir "/updates/latest.edn")
          (pr-str {:latest {["nixpkgs" "nixpkgs"] {:latest revision}
                            ["nixpkgs" "ghostty"] {:latest "99.0.0"}}}))
    (let [result (p/shell {:out :string :err :string :continue true
              :extra-env {"XDG_CACHE_HOME" dir
                          "NIXPKGS_BRIDGE_REPO" bridge
                          "UPDATES_CHANNELS" channels
                          "PATH" (str bin ":" (System/getenv "PATH"))}}
             updates "apply" selector)
          slurp? #(if (fs/exists? %) (str/trim (slurp %)) "")]
      (try {:make (slurp? log) :jj (slurp? jj-log) :pin (second (re-find pin-re (slurp channels)))
            :exit (:exit result) :err (str/trim (:err result))}
           (finally (fs/delete-tree dir))))))

(def lock-and-pin {:make "lock NIXPKGS_REV=bbbb\npublish" :jj "commit -m lock: nixpkgs bbbb" :pin published :exit 0})

(def cases
  [["new revision, whole source" (run-apply "bbbb" "nixpkgs") lock-and-pin]
   ["new revision, one package" (run-apply "bbbb" "nixpkgs/ghostty") lock-and-pin]
   ["same revision, whole source" (run-apply locked "nixpkgs")
    {:make (str "lock NIXPKGS_REV=" locked "\npublish") :jj (str "commit -m lock: nixpkgs " locked) :pin published :exit 0}]
   ["uncommitted bridge changes" (run-apply "bbbb" "nixpkgs" :dirty true)
    {:make "" :jj "" :pin (second (re-find pin-re (slurp channels-src))) :exit 1}]])

(let [failures (for [[label actual expected] cases
                     :let [actual (select-keys actual (keys expected))]
                     :when (not= expected actual)]
                 (str label ": expected " (pr-str expected) ", got " (pr-str actual)))]
  (doseq [failure failures] (println failure))
  ;; A failed step prints one line naming the source and command, not a trace.
  (let [{:keys [exit err]} (run-apply "bbbb" "nixpkgs" :exit 2)]
    (when-not (and (= 1 exit)
                   (= 1 (count (str/split-lines err)))
                   (str/includes? err "nixpkgs")
                   (str/includes? err "make lock NIXPKGS_REV=bbbb"))
      (println (str "failed make: expected exit 1 and one line naming nixpkgs and the make command, got exit "
                    exit " and " (pr-str err)))
      (System/exit 1)))
  (when (seq failures) (System/exit 1))
  (println "updates apply locks and commits in the bridge repo and pins the published commit"))
