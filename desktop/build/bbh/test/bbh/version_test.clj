(ns bbh.version-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [bbh.version :as version]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 10) (recur))))))

(deftest super-serve-records-its-version-and-exits-when-its-directory-goes
  (let [data-home (str (fs/create-temp-dir {:prefix "bbh-serve"}))
        proc (p/process {:out :string :err :string :dir (str (fs/path version/root))
                         :extra-env {"XDG_DATA_HOME" data-home "BBH_SUPER_FOLLOW_MS" "100" "BBH_SUPER_TOKEN" ""
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
