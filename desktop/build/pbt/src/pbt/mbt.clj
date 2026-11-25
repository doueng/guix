(ns pbt.mbt
  "Model-based testing against the Quint specs: `quint run --mbt` writes
  random traces whose states name the action taken and its nondeterministic
  picks; a driver (the suite's :mbt :replay) performs each on the real code and
  compares the states. A trace state is {:action \"tryConnect\" :picks {:g 1}
  <var> value ...}, values as quint.core reads ITF."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [quint.core :as quint]))

(defn- picks [m]
  (into {} (for [[k v] m :when (= "Some" (get v "tag"))] [(keyword k) (quint/itf-value (get v "value"))])))

(defn trace-states
  "The states of ITF trace TEXT written with --mbt."
  [text]
  (vec (for [st (get (json/parse-string text) "states")]
         (into {:action (get st "mbt::actionTaken") :picks (picks (get st "mbt::nondetPicks"))}
               (for [[k v] (dissoc st "#meta" "mbt::actionTaken" "mbt::nondetPicks")]
                 [(keyword (last (str/split k #"::"))) (quint/itf-value v)])))))

(defn traces
  "N random traces of MAIN in spec FILE, MAX-STEPS long, from SEED."
  [file main n & {:keys [seed max-steps] :or {seed 1 max-steps 30}}]
  (let [dir (fs/create-temp-dir {:prefix "pbt-mbt"})]
    (try
      (let [{:keys [exit out err]} (p/sh {:dir (str dir)} "quint" "run" "--main" main "--mbt" "--max-samples" (str n)
                                         "--n-traces" (str n) "--max-steps" (str max-steps) "--seed" (str seed)
                                         "--out-itf" "t{seq}.itf.json" (str (fs/absolutize file)))]
        (when-not (zero? exit) (throw (ex-info (str "quint run failed: " out err) {})))
        (vec (for [f (sort-by #(parse-long (re-find #"\d+" (fs/file-name %))) (fs/glob dir "t*.itf.json"))]
               (trace-states (slurp (str f))))))
      (finally (fs/delete-tree dir)))))

(defn divergence-signature
  "What tells one divergence from another: the action and the fields of the
  state that differ (or that the real code refused)."
  [{:keys [action problem quint real]}]
  [(first action) problem
   (vec (sort (for [k (keys quint) :when (not= (get quint k) (get real k))] k)))])

(defn replay
  "Replay N traces of the suite's spec through its driver. Returns {:traces
  :steps :actions {name n} :divergences [..] :signatures {sig n}}."
  [{:keys [file main replay]} n & opts]
  (let [f @(requiring-resolve replay)
        ts (apply traces file main n opts)
        ds (vec (keep-indexed (fn [i t] (some-> (f t) (assoc :trace i))) ts))]
    {:traces (count ts)
     :steps (reduce + (map (comp dec count) ts))
     :actions (into (sorted-map) (frequencies (keep :action (mapcat rest ts))))
     :divergences ds
     :signatures (frequencies (map divergence-signature ds))}))
