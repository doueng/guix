(ns bbh.checkpoint
  "Keep the model's definitions across a REPL kill. After each successful
  evaluation the definition forms in it are journaled to checkpoint.clj in the
  REPL's directory, one entry per defined name, and a restarted REPL replays
  them after init.clj. A def whose value prints readably is stored as that
  literal, so replaying it does not redo the work or side effects that made it.
  Only successful evaluations are journaled, so the form that got the REPL
  killed is never replayed."
  (:require [babashka.fs :as fs]
            [bbh.repl :as repl]
            [clojure.string :as str]))

(def ^:private value-heads #{'def 'defonce})
(def ^:private definition-heads
  (into value-heads '[defn defn- defmacro defmulti defmethod defrecord defprotocol deftype declare
                      ns in-ns require import alias]))
(def ^:private max-literal-chars 32000)

(defn- read-forms
  "The top-level forms of CODE with their source slices, or nil when CODE does not read."
  [^String code]
  (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. code))
        lines (str/split code #"\n" -1)
        offset (fn [line col] (+ (reduce + 0 (map #(inc (count %)) (take (dec line) lines))) (dec col)))]
    (try
      (loop [acc []]
        (let [start (offset (.getLineNumber rdr) (.getColumnNumber rdr))
              form (binding [*read-eval* false] (read {:eof ::eof :read-cond :allow} rdr))]
          (if (= ::eof form)
            acc
            (let [slice (subs code start (min (count code) (offset (.getLineNumber rdr) (.getColumnNumber rdr))))
                  ;; The slice can start with comments or #_ forms that the reader skipped.
                  source (loop [s (str/triml slice)]
                           (if (str/starts-with? s ";")
                             (recur (str/triml (subs s (or (str/index-of s "\n") (count s)))))
                             s))
                  source (if (= (pr-str form)
                                (try (binding [*read-eval* false] (pr-str (read-string {:read-cond :allow} source)))
                                     (catch Exception _ nil)))
                           source
                           (pr-str form))]
              (recur (conj acc {:form form :source source}))))))
      (catch Exception _ nil))))

(defn- entry
  "The checkpoint entry of a top-level FORM, or nil when it defines nothing."
  [{:keys [form source]}]
  (when (and (seq? form) (symbol? (first form)) (definition-heads (first form)))
    (let [head (first form)
          target (second form)
          name (cond (= 'defmethod head) (when (symbol? target) (symbol (str target " " (pr-str (nth form 2 nil)))))
                     ('#{ns in-ns} head) (symbol (str "ns " (if (seq? target) (second target) target)))
                     ('#{require import alias} head) nil
                     (symbol? target) target)]
      {:name name :source source :value? (and (value-heads head) (symbol? target) (> (count form) 2))})))

(defn definitions
  "The definition entries in CODE, in order. {:name symbol-or-nil :source string :value? bool}"
  [code]
  (keep entry (read-forms code)))

(defn- snapshot-code
  "Code that maps each of NAMES to its value's readable literal, or nil when it has none."
  [names]
  (str "(into {} (for [s '" (pr-str (vec names)) "] "
       "[s (when-let [v (resolve s)] (let [x @v s (pr-str x)] "
       "(when (and (< (count s) " max-literal-chars ") (not (str/includes? s \"#object\")) "
       "(= x (try (read-string s) (catch Exception _ ::no)))) s)))]))"))

(defn- with-literals
  "ENTRIES with each def of a readable value replaced by a def of its literal."
  [repl entries]
  (let [names (map :name (filter :value? entries))
        literals (when (seq names)
                   (:value (repl/call repl (snapshot-code names) 5000)))]
    (mapv (fn [{:keys [name value?] :as e}]
            (if-let [lit (and value? (get literals name))]
              (assoc e :source (str "(def " name " " lit ")"))
              e))
          entries)))

(defn- read-entries [path]
  (when (fs/exists? path) (mapv #(dissoc % :value?) (definitions (slurp path)))))

(defn- merge-entries
  "OLD with each NEW entry replacing the entry of the same name in place, or appended."
  [old new]
  (reduce (fn [acc e]
            (let [i (when (:name e) (first (keep-indexed #(when (= (:name %2) (:name e)) %1) acc)))]
              (if i (assoc acc i e) (conj acc e))))
          (vec old) new))

(defn- write-entries! [path entries]
  (spit path (str/join "\n\n" (map :source entries))))

(defn record!
  "Journal the definitions in CODE, which REPL has just evaluated successfully, to PATH."
  [path repl code]
  (let [new (with-literals repl (definitions code))]
    (when (seq new)
      (write-entries! path (merge-entries (read-entries path) new)))))

(defn- replay-code [sources]
  (str "(binding [*ns* *ns*] (let [forms (mapv read-string '" (pr-str (vec sources)) ")
    eval1 (fn [form] (try (eval form) nil (catch Throwable e (ex-message e))))
    ;; The second pass covers an eager def that used a name defined after it.
    failed (vec (keep-indexed (fn [i f] (when (eval1 f) i)) forms))]
    (vec (keep (fn [i] (when-let [err (eval1 (forms i))] [i err])) failed))))"))

(defn replay!
  "Evaluate the checkpoint at PATH in REPL. Forms that fail are dropped from the
  file. Returns {:restored n :failed [[source error]]}, {:quarantined path}
  when the replay hung or killed the REPL, or nil when there is no checkpoint."
  [path repl]
  (when-let [entries (seq (read-entries path))]
    (let [{:keys [value error]} (repl/call repl (replay-code (map :source entries)) 60000)]
      (if error
        (let [failed-path (str path ".failed")]
          (fs/move path failed-path {:replace-existing true})
          {:quarantined failed-path :error error})
        (let [failed (into {} value)
              kept (vec (keep-indexed (fn [i e] (when-not (failed i) e)) entries))]
          (when (seq failed) (write-entries! path kept))
          {:restored (count kept)
           :failed (mapv (fn [[i err]] [(:source (nth entries i)) err]) (sort-by first failed))})))))
