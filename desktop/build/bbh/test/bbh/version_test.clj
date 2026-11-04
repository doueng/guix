(ns bbh.version-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [bbh.version :as version]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 10) (recur))))))

(defn- source-tree []
  (let [root (fs/create-temp-dir {:prefix "bbh-version"})]
    (fs/create-dirs (fs/path root "src" "bbh"))
    (spit (str (fs/path root "bb.edn")) "{}")
    (spit (str (fs/path root "src" "bbh" "a.clj")) "(ns a)")
    (str root)))

(deftest the-fingerprint-follows-the-source-only
  (let [root (source-tree)
        v1 (version/fingerprint root)]
    (spit (str (fs/path root "notes.txt")) "not code")
    (is (= v1 (version/fingerprint root)))
    (spit (str (fs/path root "src" "bbh" "a.clj")) "(ns a) 1")
    (is (not= v1 (version/fingerprint root)))
    (is (re-matches #"[0-9a-f]{12}" v1))))

(defn- follow [root & {:as opts}]
  (let [exited (promise) loads (atom 0)]
    (version/follow! (merge {:root root :interval-ms 20 :idle? (constantly true)
                             :loads? (fn [_] (swap! loads inc) true)
                             :exit! #(deliver exited %)}
                            opts))
    {:exited exited :loads loads}))

(deftest a-process-exits-only-when-idle-on-new-code-that-loads
  (testing "unchanged source: it keeps running"
    (let [{:keys [exited]} (follow (source-tree))]
      (is (= ::running (deref exited 200 ::running)))))
  (testing "busy: it waits, then exits once idle"
    (let [root (source-tree) idle (atom false)
          {:keys [exited loads]} (follow root :idle? #(deref idle))]
      (Thread/sleep 50)
      (spit (str (fs/path root "src" "bbh" "a.clj")) "(ns a) 2")
      (is (= ::running (deref exited 200 ::running)))
      (is (= 0 @loads))
      (reset! idle true)
      (is (re-find #"^the source changed from [0-9a-f]{12} to [0-9a-f]{12}$" (str (deref exited 1000 ::running))))))
  (testing "its state directory is gone: it exits at once"
    (let [{:keys [exited]} (follow (source-tree) :gone? (constantly true))]
      (is (= "its state directory is gone" (deref exited 1000 ::running))))))

(deftest a-version-that-does-not-load-is-tried-once
  (let [root (source-tree)
        loads (atom 0)
        {:keys [exited]} (follow root :loads? (fn [_] (swap! loads inc) false))]
    (Thread/sleep 50)
    (spit (str (fs/path root "src" "bbh" "a.clj")) "(ns a")
    (Thread/sleep 300)
    (is (= 1 @loads))
    (is (= ::running (deref exited 0 ::running)))))

(deftest stale-processes-are-named
  (let [data (str (fs/create-temp-dir {:prefix "bbh-version-data"}))]
    (version/record! data "controller" "aaaaaaaaaaaa")
    ;; A record whose process is gone says nothing.
    (fs/create-dirs (fs/path data "versions"))
    (spit (str (fs/path data "versions" "web.edn")) (pr-str {:name "web" :version "bbbbbbbbbbbb" :pid 999999999}))
    (is (= [] (version/stale data "aaaaaaaaaaaa")))
    (is (= ["controller"] (version/stale data "cccccccccccc")))
    (is (= "code aaaaaaaaaaaa, controller aaaaaaaaaaaa" (version/status-line data "aaaaaaaaaaaa")))
    (is (= "code cccccccccccc, controller aaaaaaaaaaaa (stale: restarts when idle)" (version/status-line data "cccccccccccc")))))

(deftest super-serve-records-its-version-and-exits-when-its-directory-goes
  (let [data-home (str (fs/create-temp-dir {:prefix "bbh-serve"}))
        proc (p/process {:out :string :err :string :dir (str (fs/path version/root))
                         :extra-env {"XDG_DATA_HOME" data-home "BBH_SUPER_FOLLOW_MS" "100" "BBH_SUPER_TOKEN" ""
                                     ;; It manages a Router: keep it away from your Emacs daemon.
                                     "BBH_EMACS_SERVER" "bbh-test-none"}}
                        "bb" "-cp" "src" "-m" "bbh.main" "super" "serve")
        record (fs/path data-home "bbh" "versions" "controller.edn")]
    (try
      (is (wait-for #(fs/exists? record) 20000))
      (is (= {:name "controller" :version (version/fingerprint) :pid (.pid (:proc proc))}
             (select-keys (edn/read-string (slurp (str record))) [:name :version :pid])))
      (fs/delete-tree (fs/path data-home "bbh" "super"))
      (is (wait-for #(not (p/alive? proc)) 10000))
      (finally (p/destroy-tree proc)))))
