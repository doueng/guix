#!/usr/bin/env bb
;; Runs desktop/bin/updates apply with a seeded cache and a stub make, and checks
;; the revision each selection locks.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def updates (str (fs/path (fs/parent *file*) "../../bin/updates")))
(def lock (str (fs/path (fs/parent *file*) "../../../modules/engstrand/nixpkgs/lock.sexp")))
(def locked (second (re-find #"\(revision \"([0-9a-f]+)\"\)" (slurp lock))))

(defn locked-revision [revision selector]
  (let [dir (str (fs/create-temp-dir))
        make (str dir "/bin/make")
        log (str dir "/make.log")]
    (fs/create-dirs (fs/parent make))
    (fs/create-dirs (str dir "/updates"))
    (spit make (str "#!/bin/sh\necho \"$@\" >> " log "\n"))
    (fs/set-posix-file-permissions make "rwxr-xr-x")
    (spit (str dir "/updates/latest.edn")
          (pr-str {:latest {["nixpkgs" "nixpkgs"] {:latest revision}
                            ["nixpkgs" "ghostty"] {:latest "99.0.0"}}}))
    (p/shell {:out :string
              :extra-env {"XDG_CACHE_HOME" dir
                          "PATH" (str (fs/parent make) ":" (System/getenv "PATH"))}}
             updates "apply" selector)
    (try (str/trim (slurp log))
         (finally (fs/delete-tree dir)))))

(def cases
  [["new revision, whole source" "bbbb" "nixpkgs" "nixpkgs-lock NIXPKGS_REV=bbbb"]
   ["new revision, one package" "bbbb" "nixpkgs/ghostty" "nixpkgs-lock NIXPKGS_REV=bbbb"]
   ["same revision, whole source" locked "nixpkgs" (str "nixpkgs-lock NIXPKGS_REV=" locked)]])

(let [failures (for [[label revision selector expected] cases
                     :let [actual (locked-revision revision selector)]
                     :when (not= expected actual)]
                 (str label ": expected " expected ", got " actual))]
  (doseq [failure failures] (println failure))
  (when (seq failures) (System/exit 1))
  (println "updates apply locks the cached revision"))
