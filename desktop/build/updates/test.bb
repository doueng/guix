#!/usr/bin/env bb
;; Runs desktop/bin/updates apply with a seeded cache and a stub make, and checks
;; the revision each selection locks.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def updates (str (fs/path (fs/parent *file*) "../../bin/updates")))
(def lock (str (fs/path (fs/parent *file*) "../../../modules/engstrand/nixpkgs/lock.sexp")))
(def locked (second (re-find #"\(revision \"([0-9a-f]+)\"\)" (slurp lock))))

(defn run-apply
  "Run updates apply SELECTOR with a stub make that exits EXIT; return the
make arguments, the exit code and stderr."
  [revision selector exit]
  (let [dir (str (fs/create-temp-dir))
        make (str dir "/bin/make")
        log (str dir "/make.log")]
    (fs/create-dirs (fs/parent make))
    (fs/create-dirs (str dir "/updates"))
    (spit make (str "#!/bin/sh\necho \"$@\" >> " log "\nexit " exit "\n"))
    (fs/set-posix-file-permissions make "rwxr-xr-x")
    (spit (str dir "/updates/latest.edn")
          (pr-str {:latest {["nixpkgs" "nixpkgs"] {:latest revision}
                            ["nixpkgs" "ghostty"] {:latest "99.0.0"}}}))
    (let [result (p/shell {:out :string :err :string :continue true
              :extra-env {"XDG_CACHE_HOME" dir
                          "PATH" (str (fs/parent make) ":" (System/getenv "PATH"))}}
             updates "apply" selector)]
      (try {:make (str/trim (slurp log)) :exit (:exit result) :err (str/trim (:err result))}
           (finally (fs/delete-tree dir))))))

(defn locked-revision [revision selector]
  (let [{:keys [make exit err]} (run-apply revision selector 0)]
    (if (zero? exit) make (str "exit " exit ": " err))))

(def cases
  [["new revision, whole source" "bbbb" "nixpkgs" "nixpkgs-lock NIXPKGS_REV=bbbb"]
   ["new revision, one package" "bbbb" "nixpkgs/ghostty" "nixpkgs-lock NIXPKGS_REV=bbbb"]
   ["same revision, whole source" locked "nixpkgs" (str "nixpkgs-lock NIXPKGS_REV=" locked)]])

(let [failures (for [[label revision selector expected] cases
                     :let [actual (locked-revision revision selector)]
                     :when (not= expected actual)]
                 (str label ": expected " expected ", got " actual))]
  (doseq [failure failures] (println failure))
  ;; A failed step prints one line naming the source and command, not a trace.
  (let [{:keys [exit err]} (run-apply "bbbb" "nixpkgs" 2)]
    (when-not (and (= 1 exit)
                   (= 1 (count (str/split-lines err)))
                   (str/includes? err "nixpkgs")
                   (str/includes? err "make nixpkgs-lock NIXPKGS_REV=bbbb"))
      (println (str "failed make: expected exit 1 and one line naming nixpkgs and the make command, got exit "
                    exit " and " (pr-str err)))
      (System/exit 1)))
  (when (seq failures) (System/exit 1))
  (println "updates apply locks the cached revision"))
