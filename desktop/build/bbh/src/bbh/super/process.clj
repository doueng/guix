(ns bbh.super.process
  "Linux process identity and detached-job observation. Never signal by pid alone."
  (:require [babashka.fs :as fs] [babashka.process :as p] [clojure.string :as str]))

(defn identity-of [pid]
  (when (and (integer? pid) (pos? pid))
    (try
      (let [s (:out (p/shell {:out :string :err :string :continue true} "cat" (str "/proc/" pid "/stat")))
            fields (str/split (subs s (+ 2 (str/last-index-of s ")"))) #"\s+")]
        (when-not (#{"Z" "X"} (first fields))
          {:pid pid :start (nth fields 19)}))
      (catch Exception _ nil))))

(defn matches? [{:keys [pid start]}]
  (and start (= {:pid pid :start start} (identity-of pid))))

(defn read-text [path]
  (try (str/trim (slurp (str path))) (catch java.io.IOException _ nil)))

(defn jobs [root]
  (mapv (fn [f]
          (let [base (str/replace (str f) #"\.cmd$" "")
                pid (some-> (read-text (str base ".pid")) parse-long)
                start (read-text (str base ".start"))
                exit (some-> (read-text (str base ".exit")) parse-long)
                identity {:pid pid :start start}]
            (merge identity {:id (str/replace (fs/file-name f) #"\.cmd$" "")
                             :base base :exit exit
                             :state (cond (some? exit) :done
                                          (matches? identity) :running
                                          (nil? start) :unknown
                                          :else :lost)})))
        (fs/glob (fs/path root "jobs") "*.cmd")))

(defn stop-job!
  "Kill only the verified detached session group. Return false on missing identity."
  [{:keys [pid] :as job}]
  (if (and (matches? job)
           (= (str pid) (str/trim (:out (p/shell {:out :string :err :string :continue true} "ps" "-o" "pgid=" "-p" (str pid))))))
    (let [r (p/shell {:out :string :err :string :continue true} "kill" "-KILL" "--" (str "-" pid))]
      (zero? (:exit r)))
    false))
