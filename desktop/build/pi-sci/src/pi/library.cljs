(ns pi.library
  (:require [sci.core :as sci]
            [sci.impl.vars :as vars]))

(def reserved
  #{"definitions" "source" "forget" "text" "println" "prn" "image" "exit"
    "store" "load" "unstore" "all" "all-settled"})

(def allowed-namespaces
  #{"session" "tools" "catalog" "models" "json" "str" "set" "clojure.core"
    "cljs.core" "clojure.string" "clojure.set" "pi.tools" "pi.catalog"
    "pi.models" "pi.json" "pi.runtime" "pi.result" "result"})

(def mutations
  #{"def" "defn" "defmacro" "defsession" "declare" "ns" "in-ns"
    "require" "alias" "refer" "refer-clojure" "with-redefs" "with-redefs-fn"})

(defn declaration? [form]
  (and (seq? form) (= 'defsession (first form))))

(defn declaration-name [value]
  (let [sym (cond (symbol? value) value
                  (string? value) (symbol value)
                  :else (throw (ex-info "Session definition names must be symbols or strings" {})))
        n (name sym)]
    (when (or (and (namespace sym) (not= "session" (namespace sym)))
              (or (> (count n) 128) (not (re-matches #"[A-Za-z_][A-Za-z0-9_!?*+<>=$%.-]*" n)))
              (contains? reserved n))
      (throw (ex-info "Invalid or reserved session definition name" {})))
    n))

(defn literal? [x]
  (cond
    (or (nil? x) (string? x) (boolean? x) (keyword? x)) true
    (number? x) (js/Number.isFinite x)
    (vector? x) (every? literal? x)
    (map? x) (and
               (every? (fn [[k v]] (and (or (string? k) (keyword? k)) (literal? v))) x)
               (= (count x) (count (distinct (map #(if (keyword? %) (subs (str %) 1) %) (keys x))))))
    :else false))

(defn validate-function [form allowed-ns]
  (doseq [node (tree-seq coll? seq form)]
    (when (and (symbol? node) (namespace node)
               (not (contains? allowed-ns (namespace node))))
      (throw (ex-info "Persistent functions cannot reference invocation-local or private namespaces" {})))
    (when (and (seq? node) (symbol? (first node)) (contains? mutations (name (first node))))
      (throw (ex-info "Persistent functions cannot define vars, macros, or mutate namespaces" {})))
    (when (or (:macro (meta node)) (:sci/macro (meta node)) (:dynamic (meta node)))
      (throw (ex-info "Persistent macros and dynamic vars are unsupported" {})))))

(defn descriptor [form source allowed-ns]
  (when-not (and (declaration? form) (<= 3 (count form)))
    (throw (ex-info "Expected a top-level defsession declaration" {})))
  (when (> (count source) 16384)
    (throw (ex-info "Session definition source exceeds 16384 characters" {})))
  (let [[_ original-name & forms] form
        n (declaration-name original-name)
        sym (with-meta (symbol n) (meta original-name))
        function? (and (<= 2 (count forms)) (vector? (first forms)))
        fn-value? (and (= 1 (count forms)) (seq? (first forms))
                       (#{'fn 'clojure.core/fn} (ffirst forms)))
        async? (true? (:async (meta original-name)))
        value (first forms)]
    (when (or (:macro (meta original-name)) (:dynamic (meta original-name))
              (:sci/macro (meta original-name)))
      (throw (ex-info "Persistent macros and dynamic vars are unsupported" {})))
    (cond
      function?
      (do (validate-function forms allowed-ns)
          {:operation {:op :define :name n :source source :kind :function
                       :parameters (pr-str (first forms)) :async async?}
           :form (with-meta (list* 'clojure.core/defn sym forms) (meta form))})
      fn-value?
      (let [function (with-meta (cons 'clojure.core/fn (rest value)) (merge (meta value) (select-keys (meta original-name) [:async])))
            parameters (if (symbol? (second value)) (nth value 2) (second value))]
        (when-not (vector? parameters)
          (throw (ex-info "Persistent functions require one explicit parameter vector" {})))
        (validate-function function allowed-ns)
        {:operation {:op :define :name n :source source :kind :function
                     :parameters (pr-str parameters) :async (true? (:async (meta function)))}
         :form (with-meta (list 'clojure.core/def sym function) (meta form))})
      (and (= 1 (count forms)) (literal? value) (not async?))
      {:operation {:op :define :name n :source source :kind :value :parameters nil :async false}
       :form (with-meta (list 'clojure.core/def sym value) (meta form))}
      :else (throw (ex-info "defsession accepts a literal value or a function, not a computed initializer" {})))))

(defn parse-declaration [ctx operation allowed-ns]
  (let [reader (sci/source-reader (:source operation))
        [form source] (sci/parse-next+string ctx reader)
        eof (sci/parse-next ctx reader)
        parsed (descriptor form source allowed-ns)]
    (when-not (= :sci.core/eof eof)
      (throw (ex-info "A session definition must contain exactly one declaration" {})))
    (when-not (= (dissoc (:operation parsed) :source) (dissoc operation :source))
      (throw (ex-info "Session declaration metadata does not match its source" {})))
    parsed))

(defn create-library [context phase emit allowed-ns]
  (let [definitions (atom {})
        delta (atom [])
        history-count (atom 0)
        history-chars (atom 0)
        live! (fn []
                (when-not (= :live @phase)
                  (throw (ex-info "Effects are disabled during SCI declaration replay" {}))))
        check! (fn [operation]
                 (let [chars (if (= :define (:op operation)) (count (:source operation)) 0)]
                   (when (or (>= @history-count 512) (> (+ @history-chars chars) 131072))
                     (throw (ex-info "SCI library limit exceeded; use /sci-library reset" {})))
                   (swap! history-count inc)
                   (swap! history-chars + chars)))
        apply-definition! (fn [{:keys [operation form]}]
                            (check! operation)
                            (let [before @phase]
                              (reset! phase :declaration)
                              (try
                                (sci/binding [sci/ns (sci/find-ns @context 'session)
                                              sci/file (str "session/" (:name operation))]
                                  (sci/eval-form @context form))
                                (finally (reset! phase before))))
                            (swap! definitions assoc (:name operation) operation))
        forget! (fn [n]
                  (when-let [v (sci/resolve @context (symbol "session" n))]
                    (vars/unbind v))
                  (swap! definitions dissoc n))
        commit! (fn [operation]
                  (swap! delta conj operation)
                  (emit @delta))]
    {:declare! (fn [form source]
                 (live!)
                 (let [parsed (descriptor form source allowed-ns)]
                   (apply-definition! parsed)
                   (commit! (:operation parsed)))
                 nil)
     :replay! (fn [operations]
                (doseq [operation operations]
                  (case (:op operation)
                    "define" (apply-definition! (parse-declaration @context (-> operation (update :op keyword) (update :kind keyword)) allowed-ns))
                    "forget" (do (check! operation) (forget! (declaration-name (:name operation))))
                    (throw (ex-info "Unknown SCI library operation" {})))))
     :names (fn [] (set (keys @definitions)))
     :bindings {'definitions (fn []
                               (mapv #(-> % (dissoc :op :source) (update :name (fn [n] (str "session/" n))))
                                     (sort-by :name (vals @definitions))))
                'source (fn [n] (:source (get @definitions (declaration-name n))))
                'forget (fn [n]
                          (live!)
                          (let [n (declaration-name n)]
                            (if (contains? @definitions n)
                              (let [operation {:op :forget :name n}]
                                (check! operation)
                                (forget! n)
                                (commit! operation)
                                true)
                              false)))}}))
