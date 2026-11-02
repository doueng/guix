(ns bbh.checkpoint
  "Keep the model's definitions across a REPL kill. After each successful
  evaluation the definition forms in it are journaled to checkpoint.clj in the
  conversation's directory, one entry per defined name, and a restarted REPL replays
  them after init.clj. A def whose value prints readably is stored as that
  literal, so replaying it does not redo the work or side effects that made it.
  Only successful evaluations are journaled, so the form that got the REPL
  killed is never replayed. Each entry remembers the namespace it was defined
  in, and the file switches namespaces with in-ns lines, so a replay puts every
  definition where it was."
  (:require [babashka.fs :as fs]
            [bbh.repl :as repl]
            [clojure.string :as str]))

(def ^:private value-heads #{'def 'defonce})
(def ^:private definition-heads
  (into value-heads '[defn defn- defmacro defmulti defmethod defrecord defprotocol deftype declare
                      ns require import alias]))
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

(defn- switched-ns
  "The namespace FORM moves to, when it is an ns or in-ns form."
  [form]
  (when (and (seq? form) ('#{ns in-ns} (first form)))
    (let [target (second form)]
      (cond (symbol? target) target
            (and (seq? target) (= 'quote (first target)) (symbol? (second target))) (second target)))))

(defn- entry
  "The checkpoint entry of a top-level FORM defined in namespace NS, or nil when it defines nothing."
  [{:keys [form source]} ns]
  (when (and (seq? form) (symbol? (first form)) (definition-heads (first form)))
    (let [head (first form)
          target (second form)
          name (cond (= 'defmethod head) (when (symbol? target) (symbol (str target " " (pr-str (nth form 2 nil)))))
                     (= 'ns head) (symbol (str "ns " (switched-ns form)))
                     ('#{require import alias} head) nil
                     (symbol? target) target)]
      {:name name :ns (when-not (= 'ns head) ns) :source source
       :value? (and (value-heads head) (symbol? target) (> (count form) 2))})))

(defn definitions
  "The definition entries in CODE, which starts in namespace START-NS, in order.
  {:name symbol-or-nil :ns symbol :source string :value? bool}. An in-ns form is
  not an entry; it and an ns form set the namespace of the entries after them."
  [code start-ns]
  (loop [forms (read-forms code) ns start-ns acc []]
    (if-let [{:keys [form] :as f} (first forms)]
      (recur (rest forms) (or (switched-ns form) ns) (cond-> acc (entry f ns) (conj (entry f ns))))
      acc)))

(defn- snapshot-code
  "Code that maps each [ns name] of VARS to its value's readable literal, or nil when it has none.
  Printing is bounded, so an infinite seq or a huge value yields an elided string that fails the
  read-back check instead of stalling the control session, and a value whose printing throws is skipped."
  [vars]
  (str "(into {} (for [[n s] '" (pr-str (vec vars)) "] "
       "[[n s] (when-let [v (some-> (find-ns n) (ns-resolve s))] (try (let [x @v s (binding [*print-length* 10000 *print-level* 50] (pr-str x))] "
       "(when (and (< (count s) " max-literal-chars ") (not (str/includes? s \"#object\")) "
       "(= x (try (read-string s) (catch Exception _ ::no)))) s)) (catch Exception _ nil)))]))"))

(defn- with-literals
  "ENTRIES with each def of a readable value replaced by a def of its literal."
  [repl entries]
  (let [vars (map (juxt :ns :name) (filter :value? entries))
        literals (when (seq vars)
                   (:value (repl/call repl (snapshot-code vars) 5000)))]
    (mapv (fn [{:keys [ns name value?] :as e}]
            (if-let [lit (and value? (get literals [ns name]))]
              (assoc e :source (str "(def " name " " lit ")"))
              e))
          entries)))

(defn- read-entries [path]
  (when (fs/exists? path) (mapv #(dissoc % :value?) (definitions (slurp path) 'user))))

(defn- merge-entries
  "OLD with each NEW entry replacing the entry of the same name and namespace in place, or appended."
  [old new]
  (let [key (juxt :ns :name)]
    (reduce (fn [acc e]
              (let [i (when (:name e) (first (keep-indexed #(when (= (key %2) (key e)) %1) acc)))]
                (if i (assoc acc i e) (conj acc e))))
            (vec old) new)))

(defn- rendered
  "ENTRIES as the forms to write or replay, in order: each entry's source, with an
  in-ns form before every change of namespace. [{:source :entry index-or-nil}]"
  [entries]
  (loop [[e & more :as es] (seq entries) i 0 ns 'user acc []]
    (if-not es
      acc
      (let [switch (when (and (:ns e) (not= (:ns e) ns)) {:source (str "(in-ns '" (:ns e) ")")})
            after (or (:ns e) (when (str/starts-with? (str (:name e)) "ns ") (symbol (subs (str (:name e)) 3))) ns)]
        (recur more (inc i) after (cond-> acc switch (conj switch) true (conj {:source (:source e) :entry i})))))))

(defn- write-entries! [path entries]
  (spit path (str/join "\n\n" (map :source (rendered entries)))))

(defn record!
  "Journal the definitions in CODE, which REPL has just evaluated successfully
  starting in namespace START-NS, to PATH."
  [path repl code start-ns]
  (let [new (with-literals repl (definitions code start-ns))]
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
    (let [forms (rendered entries)
          {:keys [value error]} (repl/call repl (replay-code (map :source forms)) 60000)]
      (if error
        (let [failed-path (str path ".failed")]
          (fs/move path failed-path {:replace-existing true})
          {:quarantined failed-path :error error})
        (let [failed (into {} (keep (fn [[i err]] (when-let [e (:entry (nth forms i))] [e err])) value))
              kept (vec (keep-indexed (fn [i e] (when-not (failed i) e)) entries))]
          (when (seq failed) (write-entries! path kept))
          {:restored (count kept)
           :failed (mapv (fn [[i err]] [(:source (nth entries i)) err]) (sort-by first failed))})))))
