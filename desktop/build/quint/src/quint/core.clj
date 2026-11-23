(ns quint.core
  "Quint from Babashka. typecheck, test, run and verify run the quint CLI and
  return EDN; model-check runs TLC over every state, one rule at a time, and
  reports each broken rule with its shortest counterexample. Traces are
  vectors of states, each a map from variable keyword to a Clojure value:
  sets are sets, maps are maps, tuples and lists vectors, records maps with
  keyword keys, and a sum type's value is a keyword such as :Up or, with a
  payload, a vector such as [:EnterEvent [:Implement 2]]."
  (:refer-clojure :exclude [test])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def modules
  "This repo's Guix modules, for the quint package when Home has none."
  (str (fs/normalize (fs/path (fs/parent (fs/absolutize *file*)) ".." ".." ".." ".." ".." "modules"))))

(defn- guix-build [pkg]
  (let [{:keys [out exit err]} (p/sh "guix" "build" "-L" modules "-e"
                                     (str "(@ (engstrand packages quint) " pkg ")"))]
    (when-not (zero? exit) (throw (ex-info (str "guix build " pkg " failed") {:err err})))
    (last (str/split-lines (str/trim out)))))

(def ^:private tools
  (delay
    (let [quint (some-> (fs/which "quint") str)
          quint (or quint (str (guix-build "quint") "/bin/quint"))
          apalache (or (some-> (fs/which "apalache-mc") fs/real-path)
                       (fs/path (guix-build "apalache") "share" "apalache" "bin" "apalache-mc"))
          java (second (re-find #"exec (\S+/bin/java) " (slurp (str apalache))))]
      {:quint quint
       :java java
       :jar (str (fs/normalize (fs/path apalache ".." ".." "lib" "apalache.jar")))})))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- quint!
  "Run quint ARGS in a scratch directory, where Apalache writes its
  _apalache-out. FILES, a map of option to file name, name files the call
  writes there; returns {:exit :out :files {option text}}."
  [args & {:keys [files]}]
  (let [dir (fs/create-temp-dir {:prefix "quint-"})]
    (try
      (let [paths (update-vals files #(str (fs/path dir %)))
            argv (concat [(:quint @tools)] args (mapcat (fn [[opt path]] [opt path]) paths))
            {:keys [exit out err]} (apply p/sh {:dir (str dir) :continue true} (map str argv))]
        {:exit exit :out (str out err)
         :dir (str dir)
         :files (into {} (for [[opt path] paths]
                           [opt (cond (str/includes? path "{")
                                      (let [glob (str/replace (fs/file-name path) #"\{[^}]*\}" "*")]
                                        (into {} (for [f (fs/glob dir glob)] [(str (fs/file-name f)) (slurp (str f))])))
                                      (fs/exists? path) (slurp path))]))})
      (finally (fs/delete-tree dir)))))

;; Values

(defn- unit? [v] (or (= v {"#tup" []}) (= v {"tag" "UNIT"})))

(defn itf-value
  "A Clojure value of an ITF value."
  [v]
  (cond (map? v)
        (let [[k x] (first v)]
          (cond (= 1 (count v))
                (case k
                  "#bigint" (parse-long x)
                  "#tup" (mapv itf-value x)
                  "#set" (into #{} (map itf-value) x)
                  "#map" (into {} (map (fn [[a b]] [(itf-value a) (itf-value b)])) x)
                  "#unserializable" x
                  {(keyword k) (itf-value x)})
                (and (= #{"tag" "value"} (set (keys v))))
                (if (unit? (v "value")) (keyword (v "tag")) [(keyword (v "tag")) (itf-value (v "value"))])
                :else (into {} (map (fn [[a b]] [(keyword a) (itf-value b)])) v)))
        (vector? v) (mapv itf-value v)
        :else v))

(defn- short-name
  "status of change::workflow::status."
  [s]
  (keyword (last (str/split s #"::"))))

(defn itf-trace
  "[state ...] of ITF trace TEXT, each state {var value}."
  [text]
  (let [{:strs [states]} (json/parse-string text)]
    (mapv (fn [st] (into {} (for [[k v] (dissoc st "#meta")] [(short-name k) (itf-value v)]))) states)))

;; quint typecheck, test, run, verify

(defn- errors [json-text]
  (vec (for [{:strs [explanation locs]} (get (json/parse-string json-text) "errors")
             :let [{:strs [source start]} (first locs)]]
         (cond-> {:message explanation}
           source (assoc :file source :line (inc (get start "line")) :col (inc (get start "col")))))))

(defn- opts->args [opts]
  (mapcat (fn [[k v]]
            (cond (true? v) [(str "--" (name k))]
                  (or (nil? v) (false? v)) []
                  (sequential? v) (into [(str "--" (name k))] (map str v))
                  :else [(str "--" (name k)) (str v)]))
          opts))

(defn typecheck
  "{:ok? :errors [{:message :file :line :col}]} of quint typecheck FILE."
  [file]
  (let [{:keys [exit files out]} (quint! ["typecheck" (str (fs/absolutize file))] :files {"--out" "out.json"})
        errs (some-> (files "--out") errors)]
    {:ok? (zero? exit) :errors (or errs (when-not (zero? exit) [{:message out}]))}))

(defn test
  "quint test FILE with OPTS such as {:main \"connect3\" :match \"close\" :seed
  \"0x1\"}: {:ok? :passed [names] :failed [names] :errors :traces {name trace}},
  with a trace for each failed test."
  [file & [opts]]
  (let [{:keys [exit files out]} (quint! (concat ["test" (str (fs/absolutize file))] (opts->args opts))
                                         :files {"--out" "out.json" "--out-itf" "{test}_{seq}.itf.json"})
        res (some-> (files "--out") json/parse-string)
        failed (vec (get res "failed"))
        itfs (files "--out-itf")]
    {:ok? (zero? exit)
     :passed (vec (get res "passed"))
     :failed failed
     :errors (or (some-> (files "--out") errors not-empty) (when (and (not (zero? exit)) (empty? failed)) [{:message out}]) [])
     :traces (into {} (for [t failed
                            :let [[_ text] (first (filter #(str/starts-with? (key %) (str t "_")) itfs))]
                            :when text]
                        [t (itf-trace text)]))}))

(defn run
  "quint run FILE, a random simulation, with OPTS such as {:main :invariant
  :invariants :seed :max-samples :max-steps}: {:ok? :status :seed :trace},
  :status :ok, :violation or :error, and the violating trace."
  [file & [opts]]
  (let [{:keys [exit files out]} (quint! (concat ["run" (str (fs/absolutize file))] (opts->args opts))
                                         :files {"--out" "out.json" "--out-itf" "trace.itf.json"})
        res (some-> (files "--out") json/parse-string)
        status (keyword (get res "status" "error"))]
    (cond-> {:ok? (zero? exit) :status status :seed (get res "seed")}
      (= :violation status) (assoc :trace (some-> (files "--out-itf") itf-trace))
      (= :error status) (assoc :errors (or (some-> (files "--out") errors not-empty) [{:message out}])))))

(defn verify
  "quint verify FILE through Apalache, a bounded symbolic check, with OPTS
  such as {:main :invariant :max-steps}: {:ok? :trace :out}. Each call
  starts its own Apalache server, so parallel calls do not share one."
  [file & [opts]]
  (let [{:keys [exit files out]} (quint! (concat ["verify" (str (fs/absolutize file))
                                                  "--server-endpoint" (str "localhost:" (free-port))]
                                                 (opts->args opts))
                                         :files {"--out-itf" "trace.itf.json"})]
    (cond-> {:ok? (zero? exit) :out out}
      (files "--out-itf") (assoc :trace (itf-trace (files "--out-itf"))))))

;; TLC over every state

(defn unparen-box
  "TLA+ text with each []([A]_v) written [][A]_v. Quint prints an action
  property always(orKeep(A, v)) with the parentheses, which TLC refuses."
  [tla]
  (loop [s tla]
    (if-let [i (str/index-of s "[]([")]
      (let [open (+ i 2)
            close (loop [j (inc open) depth 1]
                    (case (.charAt ^String s j)
                      \( (recur (inc j) (inc depth))
                      \) (if (= depth 1) j (recur (inc j) (dec depth)))
                      (recur (inc j) depth)))]
        (recur (str (subs s 0 open) (subs s (inc open) close) (subs s (inc close)))))
      s)))

(defn compile-tla
  "The TLA+ module that quint compiles MAIN of FILE to, for TLC, with RULES
  (definition names) kept in it."
  [file main rules]
  (let [{:keys [exit out]} (quint! ["compile" "--target" "tlaplus" "--main" main
                                    "--temporal" (str/join "," rules)
                                    "--server-endpoint" (str "localhost:" (free-port))
                                    (str (fs/absolutize file))])
        start (some->> (re-find #"(?m)^-{4,} MODULE" out) (str/index-of out))]
    (when-not (and (zero? exit) start) (throw (ex-info (str "quint compile failed: " out) {:out out})))
    (unparen-box (subs out start))))

(defn- tla-name
  "The name quint gave definition N in TLA text, such as change_workflow_N."
  [tla n]
  (or (second (re-find (re-pattern (str "(?m)^(\\w*?" n ")(?:\\(| ==)")) tla))
      (throw (ex-info (str "no definition " n " in the compiled module") {:name n}))))

;; TLC's text traces

(defn- tla-reader
  "A reader of the TLA+ values TLC prints in a trace."
  [^String s]
  (let [pos (volatile! 0)
        ws! (fn [] (while (and (< @pos (count s)) (Character/isWhitespace (.charAt s @pos))) (vswap! pos inc)))
        looking? (fn [t] (ws!) (.startsWith s t @pos))
        eat! (fn [t] (if (looking? t) (vswap! pos + (count t)) (throw (ex-info (str "expected " t " at " @pos) {:text s}))))
        token! (fn [re] (ws!) (let [m (re-matcher re s)]
                                (when (and (.find m @pos) (= (.start m) @pos)) (vswap! pos + (count (.group m))) (.group m))))]
    (letfn [(items [close]
              (if (looking? close) (do (eat! close) [])
                  (loop [acc [(value)]]
                    (if (looking? ",") (do (eat! ",") (recur (conj acc (value)))) (do (eat! close) acc)))))
            (field []
              (let [k (token! #"\w+")] (eat! "|->") [k (value)]))
            (value []
              (cond (looking? "<<") (do (eat! "<<") (vec (items ">>")))
                    (looking? "{") (do (eat! "{") (set (items "}")))
                    (looking? "[") (do (eat! "[")
                                       (if (looking? "]") (do (eat! "]") {})
                                           (loop [acc [(field)]]
                                             (if (looking? ",") (do (eat! ",") (recur (conj acc (field))))
                                                 (do (eat! "]") (record acc))))))
                    (looking? "(") (do (eat! "(")
                                       (loop [acc {}]
                                         (let [k (value) _ (eat! ":>") v (value) acc (assoc acc k v)]
                                           (if (looking? "@@") (do (eat! "@@") (recur acc)) (do (eat! ")") acc)))))
                    (looking? "\"") (let [t (token! #"\"(?:[^\"\\]|\\.)*\"")]
                                        (str/replace (subs t 1 (dec (count t))) #"\\(.)" "$1"))
                    (looking? "TRUE") (do (eat! "TRUE") true)
                    (looking? "FALSE") (do (eat! "FALSE") false)
                    :else (if-let [n (token! #"-?\d+")] (parse-long n)
                                  (throw (ex-info (str "unreadable TLA+ value at " @pos) {:text s})))))
            (record [fields]
              (let [[[k v] & more] fields]
                (cond (and (nil? more) (= k "tag") (= v "UNIT")) ::unit
                      ;; Apalache's Variant(\"Up\", x) is the record [Up |-> x].
                      (and (nil? more) (re-matches #"[A-Z]\w*" k)) (if (= v ::unit) (keyword k) [(keyword k) v])
                      :else (into {} (map (fn [[k v]] [(keyword k) v])) fields))))]
      (let [v (value)] (ws!) v))))

(defn tla-value
  "The Clojure value of a TLA+ value TLC printed, read as Quint wrote it:
  Apalache's encoding of a sum type back to a keyword or [tag payload]."
  [text]
  (tla-reader text))

(defn tlc-trace
  "{:states [state ...] :loop i} of the counterexample in TLC output TEXT,
  each state {var value} with variable names unprefixed by PREFIX; :loop is
  the index the lasso of a liveness counterexample goes back to."
  [text prefix]
  (let [lines (->> (str/split-lines text)
                   (drop-while #(not (re-find #"^State \d+:" %)))
                   (take-while #(not (re-find #"states generated|^Finished in" %))))
        blocks (reduce (fn [acc line]
                         (cond (re-find #"^State \d+:" line) (conj acc [])
                               (re-find #"^Back to state|Stuttering" line) (conj acc [line])
                               :else (update acc (dec (count acc)) conj line)))
                       [] lines)
        state (fn [ls]
                (let [defs (reduce (fn [acc l]
                                     (if-let [[_ v rest] (re-find #"^(?:/\\ )?(\w+) = (.*)$" l)]
                                       (conj acc [v rest])
                                       (if (seq acc) (update-in acc [(dec (count acc)) 1] str " " (str/trim l)) acc)))
                                   [] ls)]
                  (into {} (for [[v t] defs] [(keyword (str/replace-first v prefix "")) (tla-value t)]))))
        loop-to (some #(some->> % first (re-find #"Back to state (\d+)") second parse-long) blocks)]
    (cond-> {:states (vec (for [b blocks :when (not (re-find #"^Back to state|Stuttering" (str (first b))))] (state b)))}
      loop-to (assoc :loop (dec loop-to)))))

(defn- tlc!
  "TLC on TLA text with config CFG in a scratch directory: {:text :trace}."
  [tla cfg prefix]
  (let [dir (fs/create-temp-dir {:prefix "quint-tlc-"})
        {:keys [java jar]} @tools]
    (try
      (spit (str (fs/path dir "check.tla")) (str/replace-first tla #"MODULE \w+" "MODULE check"))
      (spit (str (fs/path dir "check.cfg")) cfg)
      (let [{:keys [out err]} (p/sh {:dir (str dir) :continue true}
                                    java "-XX:+UseParallelGC" "-cp" jar "tlc2.TLC"
                                    "-deadlock" "-workers" "auto"
                                    "-config" "check.cfg" "check.tla")
            text (str out err)]
        {:text text
         :trace (when (re-find #"(?m)^State 1:" text) (tlc-trace text prefix))})
      (finally (fs/delete-tree dir)))))

(defn- states-of [text]
  (some->> (re-seq #"([\d,]+) distinct states found" text) last second (remove #{\,}) (apply str) parse-long))

(defn model-check
  "TLC over every reachable state of MAIN in FILE, from init by step.
  INVARIANTS and PROPERTIES are definition names; a property is a temporal
  definition, such as always(orKeep(next(x) >= x, x)) for an action
  property, or fairness implies eventually(...) for liveness. TLC stops at
  the first broken rule, so it runs again without it until none breaks.
  Returns [{:rule :kind :ok? :states :trace :out}], :states the distinct
  states TLC found, :trace the shortest counterexample as data and :out its
  text. :tla is the module compile-tla made, to skip compiling again."
  [file main {:keys [invariants properties tla]}]
  (let [rules (concat (map vector (repeat :invariant) invariants) (map vector (repeat :property) properties))
        tla (or tla (compile-tla file main (concat invariants properties)))
        named (into {} (for [[_ r] rules] [r (tla-name tla r)]))
        prefix (let [[r n] (first named)] (subs n 0 (- (count n) (count r))))]
    (loop [left rules acc []]
      (if (empty? left)
        acc
        (let [check (fn [rs] (tlc! tla (str "INIT q_init\nNEXT q_step\n"
                                            (str/join (for [[k r] rs] (str (if (= k :invariant) "INVARIANT " "PROPERTY ") (named r) "\n"))))
                                   prefix))
              {:keys [text] :as res} (check left)
              named-broken (some->> (re-find #"Error: (?:Invariant (\S+) is|Action property (\S+) is|Temporal property (\S+) was) violated" text)
                                    rest (some identity))
              ;; A broken liveness rule goes unnamed: find it by checking each alone.
              broken (or (some (fn [[_ r]] (when (= named-broken (named r)) [r res])) left)
                         (when (str/includes? text "Temporal properties were violated")
                           (some (fn [[k r :as rule]] (when (= k :property)
                                                        (let [one (check [rule])]
                                                          (when (re-find #"is violated|Temporal properties were violated" (:text one)) [r one]))))
                                 left)))
              kind (into {} (map (fn [[k r]] [r k])) left)]
          (cond broken (let [[r {:keys [text trace]}] broken]
                         (recur (remove #(= r (second %)) left)
                                (conj acc {:rule r :kind (kind r) :ok? false :states (states-of text) :trace trace
                                           :out (->> (str/split-lines text)
                                                     (drop-while #(not (str/starts-with? % "Error:")))
                                                     (take-while #(not (re-find #"states generated" %)))
                                                     (str/join "\n"))})))
                (str/includes? text "No error has been found")
                (into acc (for [[k r] left] {:rule r :kind k :ok? true :states (states-of text)}))
                :else (conj acc {:rule "TLC" :ok? false :out text})))))))
