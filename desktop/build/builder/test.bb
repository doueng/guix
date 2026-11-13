#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[clojure.test :refer [deftest is run-tests]])
(load-file (str (fs/path (fs/parent *file*) "../../bin/builder-prebuild")))
(alias 'bp 'builder-prebuild)

(def now (java.time.Instant/parse "2026-11-10T12:00:00Z"))
(def rev "a66eefb3964bed2cb9db7a13c0ccbfbdd5cb772c")

(deftest parse-status
  (is (= {:rev rev :ok? true} (bp/parse-status (str (pr-str {:rev rev :ok? true}) "\n"))))
  (is (nil? (bp/parse-status "{}")) "an empty status is no status")
  (is (nil? (bp/parse-status "")))
  (is (nil? (bp/parse-status "prebuild: command not found")))
  (is (nil? (bp/parse-status "{:rev")) "a truncated file"))

(deftest alert
  (is (re-find #"no status" (bp/alert nil now)))
  (is (nil? (bp/alert {:rev rev :state :ok :ok? true :started "2026-11-10T03:00:00Z"
                       :finished "2026-11-10T04:00:00Z"} now)))
  (is (nil? (bp/alert {:rev rev :ok? true :finished "2026-11-09T04:00:00Z"
                       :checked "2026-11-10T03:00:00Z"} now))
      "an unchanged revision the nightly skipped")
  (is (= "builder prebuild: a66eefb3964b failed: guix system build; log /l"
         (bp/alert {:rev rev :state :failed :ok? false :started "2026-11-10T03:00:00Z"
                    :error "guix system build" :log "/l"} now)))
  (is (re-find #"failed" (bp/alert {:rev rev :ok? false :error "x" :started "2026-11-10T03:00:00Z"} now))
      "the format before :state")
  (is (nil? (bp/alert {:rev rev :state :running :started "2026-11-10T09:00:00Z"} now)) "still running")
  (is (re-find #"killed or stuck" (bp/alert {:rev rev :state :running :started "2026-11-09T20:00:00Z" :log "/l"} now)))
  (is (re-find #"has not run since 2026-11-08T03:00:00Z"
               (bp/alert {:rev rev :ok? true :finished "2026-11-08T03:00:00Z"} now))))

(deftest ok-line
  (is (= "builder prebuild: ok, a66eefb3964b built 8h ago, nightly checked 9h ago"
         (bp/ok-line {:rev rev :state :ok :ok? true :finished "2026-11-10T04:00:00Z" :checked "2026-11-10T03:00:00Z"} now)))
  (is (= "builder prebuild: ok, a66eefb3964b built 8h ago"
         (bp/ok-line {:rev rev :ok? true :finished "2026-11-10T04:00:00Z" :checked "2026-11-10T04:00:00Z"} now)))
  (is (= "builder prebuild: building a66eefb3964b, started 3h ago"
         (bp/ok-line {:rev rev :state :running :started "2026-11-10T09:00:00Z"} now))))

(deftest prune-roots-keeps-the-newest
  ;; The links point at files with one old mtime, as store items have.
  (let [dir (fs/create-temp-dir)
        target (fs/create-file (fs/path dir "item"))
        revs (for [i (range 5)] (apply str (repeat 40 (char (+ (int \a) i)))))]
    (fs/set-last-modified-time target (java.nio.file.attribute.FileTime/fromMillis 1000))
    (fs/create-dirs (fs/path dir "roots"))
    ;; Newest first, so the directory's order is not the links' age order.
    (doseq [[i rev] (reverse (map-indexed vector revs)) kind ["system" "home"]]
      (let [link (fs/create-sym-link (fs/path dir "roots" (str kind "-" rev)) target)]
        (java.nio.file.Files/setAttribute link "basic:lastModifiedTime"
                                          (java.nio.file.attribute.FileTime/fromMillis (* (inc i) 100000))
                                          (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))))
    (bp/prune-roots (str (fs/path dir "roots")))
    (is (= (set (for [rev (drop 2 revs) kind ["system" "home"]] (str kind "-" rev)))
           (set (map (comp str fs/file-name) (fs/list-dir (fs/path dir "roots"))))))
    (fs/delete-tree dir)))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
