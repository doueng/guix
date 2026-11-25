(ns pbt.cover
  "Branch coverage under babashka. cloverage does not load in bb (riddley
  needs clojure.lang.Compiler internals), so this reads a namespace's source
  with edamame, wraps each branch arm and fn body in a counter, and evaluates
  the result in place of the namespace. A site is one arm of an if, when,
  cond, case, and or or (both arms, also the one the source leaves implicit),
  or the body of a fn, defn arity or defmethod."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as e]))

(def sites
  "id -> {:ns :in :line :kind}, :in the top-level def it sits in; an id is
  :<ns>/<n>."
  (atom {}))
(def ^:private counts (atom {}))

(defn hit! [id] (swap! counts update id (fnil inc 0)) nil)

(defn- site! [{:keys [ns n line in]} kind form]
  (let [id (keyword (name ns) (str (swap! n inc)))]
    (swap! sites assoc id {:ns ns :in in :line (or (:line (meta form)) line) :kind kind})
    id))

(declare instr)

(defn- mark [ctx kind form]
  (list 'do (list 'pbt.cover/hit! (site! ctx kind form)) form))

(defn- head [form]
  (when (and (seq? form) (symbol? (first form))) (symbol (name (first form)))))

(defn- fn-tail
  "([params] body...) with the body counted, keeping a leading condition map."
  [ctx kind [params & body]]
  (let [cond-map? (and (map? (first body)) (next body))
        [pre body] (if cond-map? [[(first body)] (rest body)] [[] body])
        id (site! ctx kind params)]
    (concat [params] pre [(list 'pbt.cover/hit! id)] (map #(instr ctx %) body))))

(defn- fn-arities
  "The arities of a fn-like tail, counted: [params] body... or ([params] body...)..."
  [ctx kind tail]
  (if (vector? (first tail))
    (fn-tail ctx kind tail)
    (map #(if (seq? %) (apply list (fn-tail ctx kind %)) %) tail)))

(def ^:private expand-first '#{-> ->> some-> some->> cond-> cond->> as-> doto})

(defn- instr-seq [ctx form]
  (let [h (head form)
        ctx (assoc ctx :line (or (:line (meta form)) (:line ctx)))
        i #(instr ctx %)
        arm #(mark ctx %1 (instr ctx %2))]
    (case h
      ;; defmulti: evaluating it again keeps the old dispatch fn, so its sites could never count.
      (quote var ns defmacro defmulti comment defprotocol definterface import require) form
      (if if-not) (let [[_ t a b] form] (list (first form) (i t) (arm :then a) (arm :else b)))
      (if-let if-some) (let [[_ [b init] x y] form] (list (first form) [b (i init)] (arm :then x) (arm :else y)))
      (when when-not) (let [[_ t & body] form]
                        (list (if (= 'when h) 'if 'if-not) (i t) (arm :then (cons 'do body)) (arm :else nil)))
      (when-let when-some) (let [[_ [b init] & body] form]
                             (list (if (= 'when-let h) 'if-let 'if-some) [b (i init)] (arm :then (cons 'do body)) (arm :else nil)))
      cond (let [pairs (partition 2 (rest form))
                 total? (let [t (first (last pairs))] (or (keyword? t) (true? t)))]
             (concat ['cond] (mapcat (fn [[t x]] [(i t) (arm :cond x)]) pairs)
                     (when-not total? [:else (arm :else nil)])))
      case (let [[_ e & clauses] form
                 pairs (partition 2 clauses)
                 default (when (odd? (count clauses)) [(arm :default (last clauses))])]
             (concat ['case (i e)] (mapcat (fn [[c x]] [c (arm :case x)]) pairs) default))
      (and or) (let [[_ x & more] form] (concat [(first form) (i x)] (map #(arm :operand %) more)))
      (fn fn*) (let [[f & tail] form [nm tail] (if (symbol? (first tail)) [[(first tail)] (rest tail)] [[] tail])]
                 (concat [f] nm (fn-arities ctx :fn tail)))
      (defn defn-) (let [[d nm & tail] form
                         [pre tail] (split-with #(not (or (vector? %) (seq? %))) tail)]
                     (concat [d nm] pre (fn-arities ctx :defn tail)))
      defmethod (let [[d nm dv & tail] form] (concat [d nm dv] (fn-arities ctx :method tail)))
      (if (and h (expand-first h))
        (instr ctx (with-meta (macroexpand-1 form) (meta form)))
        (with-meta (apply list (map i form)) (meta form))))))

(defn instr
  "FORM with its branch arms and fn bodies counted; CTX names its namespace."
  [ctx form]
  (cond (seq? form) (if (empty? form) form (instr-seq ctx form))
        (vector? form) (with-meta (mapv #(instr ctx %) form) (meta form))
        (map? form) (with-meta (into {} (map (fn [[k v]] [(instr ctx k) (instr ctx v)])) form) (meta form))
        (set? form) (into #{} (map #(instr ctx %)) form)
        :else form))

(defn- top-name
  "The name a top-level form defines: foo for (defn foo ..), \"m :k\" for
  (defmethod m :k ..)."
  [f]
  (when (and (seq? f) (symbol? (second f)))
    (case (head f)
      defmethod (str (second f) " " (pr-str (nth f 2 nil)))
      (def defn defn- defonce defmulti) (second f)
      nil)))

(defn- source-of [ns-sym]
  (let [base (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (some #(io/resource (str base %)) [".clj" ".cljc" ".bb"])))

(defn instrument!
  "Evaluate namespace NS-SYM from its source with every site counted. Returns
  its number of sites."
  [ns-sym]
  (let [src (slurp (or (source-of ns-sym) (throw (ex-info (str "no source for " ns-sym) {:ns ns-sym}))))
        forms (e/parse-string-all src {:all true :row-key :line :col-key :column :auto-resolve-ns true
                                       :read-cond :allow :features #{:bb :clj}})
        ctx {:ns ns-sym :n (atom 0)}]
    (swap! sites #(into {} (remove (fn [[_ s]] (= ns-sym (:ns s)))) %))
    (require ns-sym)
    (binding [*ns* (the-ns ns-sym)]
      (doseq [f forms]
        (eval (if (= 'ns (head f)) f (instr (assoc ctx :in (top-name f)) f)))))
    @(:n ctx)))

(defn restore!
  "Reload namespaces NSS from their own source, without counters."
  [nss]
  (doseq [n nss] (require n :reload)))

(defn reset-counts! [] (reset! counts {}))

(defn selected?
  "Whether site S is in COVER: {ns :all} or {ns #{name ...}}, a name being a
  def's symbol (every method of a multimethod) or one method's
  \"multi :dispatch\"."
  [cover {:keys [ns in]}]
  (let [sel (get cover ns)]
    (or (= :all sel)
        (and (set? sel) (or (contains? sel in)
                            (and (string? in) (contains? sel (symbol (first (str/split in #" "))))))))))

(defn snapshot
  "The counts since reset-counts!, as {:hit n :total n :fraction f :by-ns
  {ns {:hit :total}} :counts {id n}} over the sites COVER selects:
  {ns :all} or {ns #{def-name ...}}."
  [cover]
  (let [ids (keep (fn [[id s]] (when (selected? cover s) id)) @sites)
        c @counts
        hit (filter #(pos? (get c % 0)) ids)
        by (group-by #(:ns (@sites %)) ids)]
    {:hit (count hit) :total (count ids)
     :fraction (if (seq ids) (/ (Math/round (* 1000.0 (/ (count hit) (count ids)))) 1000.0) 0.0)
     :by-ns (into (sorted-map) (for [[n is] by] [n {:hit (count (filter #(pos? (get c % 0)) is)) :total (count is)}]))
     :counts (select-keys c ids)}))

(defn site [id] (@sites id))
