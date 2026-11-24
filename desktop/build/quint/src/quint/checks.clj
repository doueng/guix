(ns quint.checks
  "Check a directory of Quint models against its checks.edn: typecheck every
  .qnt file, run each model's tests and a fixed-seed simulation of its
  invariants, then (unless quick) TLC over every state of each model, one rule
  per run. checks.edn is a vector of models, each {:file :main :states
  :invariants :properties}; :states is the number of distinct states the
  model has, so a change to the state space fails until :states is updated
  with it."
  (:refer-clojure :exclude [test])
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [quint.core :as quint]))

(defn models
  "The models of DIR's checks.edn, each with :path, those whose :main is in
  ONLY when ONLY is not empty."
  [dir only]
  (cond->> (map #(assoc % :path (str (fs/path dir (:file %)))) (edn/read-string (slurp (str (fs/path dir "checks.edn")))))
    (seq only) (filter #(contains? (set only) (:main %)))))

(defn- line [ok? & words]
  (str (if ok? "ok   " "FAIL ") (str/join " " (remove str/blank? words))))

(defn- typecheck [path]
  (let [{:keys [ok? errors]} (quint/typecheck path)]
    {:check :typecheck :path path :ok? ok? :errors errors
     :report (cons (line ok? "typecheck" (fs/file-name path))
                   (for [{:keys [message file line col]} errors] (str "  " file ":" line ":" col " " message)))}))

(defn- test [path main]
  (let [{:keys [ok? passed failed errors traces]} (quint/test path {:main main :seed "0x1" :max-samples 20})]
    {:check :test :path path :main main :ok? ok? :failed failed :traces traces
     :report (concat [(line ok? "test" (fs/file-name path) main
                            (str "(" (count passed) " passed" (when (seq failed) (str ", failed: " (str/join " " failed))) ")"))]
                     (map #(str "  " (:message %)) errors)
                     (for [[t tr] traces] (str "  " t " ended in " (pr-str (last tr)))))}))

(defn- simulate [path main invariants]
  (let [{:keys [ok? status trace errors]} (quint/run path {:main main :invariants invariants :seed "0x1"
                                                           :max-samples 1000 :max-steps 30})]
    {:check :run :path path :main main :ok? ok? :status status :trace trace
     :report (concat [(line ok? "run" (fs/file-name path) main (name status))]
                     (map #(str "  " (:message %)) errors)
                     (when trace [(str "  ended in " (pr-str (last trace)))]))}))

(defn quick
  "Typecheck, test and simulate MODELS, in parallel since each quint call
  starts slowly: [{:check :ok? :report [line ...]}]."
  [models]
  (->> (concat (for [path (distinct (map :path models))] #(typecheck path))
               (for [{:keys [path main]} (distinct (map #(select-keys % [:path :main]) models))] #(test path main))
               (for [{:keys [path main invariants]} models :when (seq invariants)] #(simulate path main invariants)))
       (mapv future-call)
       (mapv deref)))

(defn model-check
  "TLC over every state of MODELS, each rule checked and its state count
  compared with :states: [{:check :model-check ...}]."
  [models]
  (for [[{:keys [path main states] :as m} tla]
        ;; Each compile starts an Apalache JVM, so they run in parallel.
        (map vector models (mapv deref (mapv (fn [{:keys [path main invariants properties]}]
                                               (future (quint/compile-tla path main (concat invariants properties))))
                                             models)))
        r (quint/model-check path main (assoc m :tla tla))
        :let [counted? (or (nil? states) (= states (:states r)))
              ok? (and (:ok? r) counted?)]]
    (assoc r :check :model-check :path path :main main :ok? ok?
           :report (concat [(line ok? (fs/file-name path) main (:rule r)
                                  (cond (not (:states r)) ""
                                        counted? (str " (" (:states r) " states)")
                                        :else (str " (" (:states r) " states, checks.edn says " states ")")))]
                           (when-not (:ok? r) [(or (:out r) "")])
                           (when (:trace r) [(pr-str (:trace r))])))))

(defn -main
  "bb verify:quint [--quick] [--mc] [MAIN...] in a project, with DIR its
  verify/quint. --quick skips TLC, --mc runs only TLC. Exits 1 when a check
  fails."
  [dir & args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        ms (models dir (remove #(str/starts-with? % "--") args))
        report! (fn [results] (doseq [r results l (:report r)] (println l)) results)
        results (concat (when-not (flags "--mc") (report! (quick ms)))
                        ;; An entry with no rules is there for its tests only.
                        (when-not (flags "--quick")
                          (doall (map #(first (report! [%])) (model-check (filter #(seq (concat (:invariants %) (:properties %))) ms))))))]
    (System/exit (if (every? :ok? results) 0 1))))
