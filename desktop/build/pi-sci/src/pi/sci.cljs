(ns pi.sci
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [clojure.set :as set]
            [pi.library :as library]
            [pi.commands :as commands]))

(defn wire-key [k]
  (cond
    (string? k) k
    (keyword? k) (subs (str k) 1)
    :else (throw (ex-info "JSON map keys must be strings or keywords" {}))))

(defn value-kind [x]
  (cond
    (set? x) "a set"
    (symbol? x) "a symbol"
    (fn? x) "a function"
    (regexp? x) "a regex"
    (instance? js/Promise x) "a promise (missing await?)"
    :else "an unsupported value"))

(defn to-wire
  ([x] (to-wire x []))
  ([x path]
   (cond
     (or (nil? x) (string? x) (boolean? x)) x
     (number? x) (if (js/Number.isFinite x) x
                  (throw (ex-info "JSON numbers must be finite" {})))
     (keyword? x) (subs (str x) 1)
     (map? x) (let [obj (js/Object.create nil)]
                (doseq [[k v] x]
                  (let [key (wire-key k)]
                    (when (js/Object.prototype.hasOwnProperty.call obj key)
                      (throw (ex-info (str "Duplicate JSON key " key) {})))
                    (aset obj key (to-wire v (conj path k)))))
                obj)
     (sequential? x) (into-array (map-indexed (fn [i v] (to-wire v (conj path i))) x))
     :else (throw (ex-info (str "Value " (when (seq path) (str "at " (pr-str path) " "))
                                "is " (value-kind x) ", which is not JSON-compatible") {})))))

(def command-keys [:ok :exit_code :signal :stderr :stdout :timed_out :truncated :full_output_path :duration_ms])

(defn status-line [{:keys [exit_code signal timed_out truncated full_output_path] :as r} elapsed]
  (str "[exit " (if (nil? exit_code) "none" exit_code)
       (when signal (str ", signal " signal))
       (when timed_out ", timed out")
       (when elapsed (str ", " elapsed))
       (when truncated (str ", truncated" (when full_output_path (str "; full output " full_output_path))))
       "]"))

(def renderers
  [{:shape #(and (map? %) (string? (:output %)) (contains? % :exit_code))
    :render (fn [r]
              (str (status-line r (some-> (:wall_time_seconds r) (str " s")))
                   "\n" (str/trimr (:output r))))}
   {:shape #(and (map? %) (contains? % :exit_code) (contains? % :duration_ms))
    :render (fn [r]
              (let [extra (apply dissoc r command-keys)]
                (str/join "\n"
                          (remove str/blank?
                                  [(status-line r (str (:duration_ms r) " ms"))
                                   (when (seq extra) (pr-str extra))
                                   (some-> (:stdout r) str/trimr)
                                   (when-not (str/blank? (:stderr r)) (str "stderr:\n" (str/trimr (:stderr r))))]))))}])

(defn display [x]
  (if (string? x)
    x
    (if-let [render (some (fn [{:keys [shape render]}] (when (shape x) render)) renderers)]
      (render x)
      (pr-str x))))

(defn from-wire [x]
  (js->clj x :keywordize-keys true))

(defn promised [f]
  (fn [& args]
    (.then (js/Promise.resolve (apply f (map to-wire args))) from-wire)))

(defn all [xs]
  (.then (js/Promise.all (into-array xs)) #(vec (array-seq %))))

(defn all-settled [xs]
  (.then (js/Promise.allSettled (into-array xs))
         (fn [results]
           (mapv (fn [r]
                   (if (= "fulfilled" (.-status r))
                     {:status :fulfilled :value (.-value r)}
                     {:status :rejected :error {:message (str (or (some-> r .-reason .-message)
                                                                 (.-reason r)))}}))
                 (array-seq results)))))

(def forbidden
  '[eval load-string load-file read read-string resolve requiring-resolve
    ns-resolve find-var var-get alter-var-root intern in-ns create-ns ns alias refer refer-clojure
    find-ns all-ns ns-map ns-publics ns-interns ns-imports ns-unmap
    import add-class! add-js-lib! enable-unrestricted-access!
    slurp spit future future-call pmap agent send send-off shutdown-agents])

(defn excerpt [source line column label]
  (when-let [text (and source line (get (str/split-lines source) (dec line)))]
    (let [column (max 1 (or column 1))
          start (max 0 (- column 80))
          gutter (str "  " line " | ")]
      (str "\n" gutter (subs text start (min (count text) (+ start 160)))
           "\n" (apply str (repeat (+ (count gutter) (- column 1 start)) " ")) "^ " label))))

(def dotted-alias #"(?:^|[\s(\[{'@])(tools|catalog|models|json|str|set|fs|search|jj|guix|make|repo|sys|result|session)\.([A-Za-z][\w?!*-]*)")

(defn dotted-hint [[_ alias n]]
  (str "Use " alias "/" n "; Clojure namespaces use a slash, not a dot."))

(def symbol-hints
  [[(re-pattern (str "^" (.-source dotted-alias) "$")) dotted-hint]
   [#"^tools/(grep|rg)$" (constantly "Use search/text.")]
   [#"^tools/(find|ls)$" (constantly "Use search/files, fs/list, or fs/glob.")]
   [#"^git/" (constantly "There is no git namespace. Use jj/ functions in this repository.")]
   [#"^rg/" (constantly "Use search/text.")]
   [#"^(process|shell|sh)/" (constantly "There is no generic process runner. Use a semantic namespace, or tools/bash as the escape hatch.")]])

(def file-command #"(?:^|;|&&|\|\||\n|\$\()\s*(?:sudo\s+)?(grep|rg|find|cat|ls)(?=\s|$)")

(defn bash-hint [command]
  (when-let [[_ program] (re-find file-command (str command))]
    (str "Hint: tools/bash ran " program "; search/text, search/files, fs/list, and fs/read return structured data without a shell.")))

(defn hint-bash [bash print!]
  (let [shown (atom false)]
    (fn [& args]
      (let [result (apply bash args)]
        (when-not @shown
          (when-let [advice (bash-hint (:command (first args)))]
            (reset! shown true)
            (print! advice)))
        result))))

(defn hint [message source session-names]
  (if-let [[_ sym] (re-find #"(?:Unable to resolve symbol|Could not resolve symbol): *(\S+)" message)]
    (if (contains? session-names sym)
      (str "Use session/" sym "; defsession definitions live in the session namespace.")
      (some (fn [[pattern advice]] (some-> (re-find pattern sym) advice)) symbol-hints))
    (when (re-find #"not a function" message)
      (some-> (re-find dotted-alias (or source "")) dotted-hint))))

(defn script-error
  ([error] (script-error error nil #{}))
  ([error source session-names]
   (let [{:keys [line column file]} (ex-data error)
         message (or (ex-message error) (.-message error) (str error))
         [_ open-line open-column] (re-find #"to match \S+ at \[(\d+)[, ]+(\d+)\]" message)
         advice (hint message source session-names)]
     (js/Error. (str message
                     (when line (str " at " (or file "codemode.clj") ":" line ":" (or column 1)))
                     (when advice (str "\nHint: " advice))
                     (when (re-find #"Unmatched delimiter|EOF while reading|Unexpected EOF" message)
                       (str (when open-line
                              (excerpt source (js/parseInt open-line) (js/parseInt open-column) "unclosed form opens here"))
                            (excerpt source line column "reader stopped here"))))))))

(defn run [source api]
  (let [phase (atom :replay)
        context (atom nil)
        live! (fn [] (when-not (= :live @phase)
                       (throw (ex-info "Effects are disabled during SCI declaration replay" {}))))
        effect (fn [f] (fn [& args] (live!) (apply f args)))
        tool-map (into {} (map (fn [id]
                                [(symbol id) (effect (promised (aget (.-tools api) id)))])
                              (js/Object.keys (.-tools api))))
        tool-map (cond-> tool-map
                   (contains? tool-map 'bash) (update 'bash hint-bash (.-text api)))
        command-namespaces (commands/bindings (from-wire (.-commands api)) tool-map)
        allowed-namespaces (into library/allowed-namespaces
                                 (mapcat (fn [[ns info]] [(str ns) (str (:alias info))]) command-namespaces))
        library (library/create-library context phase
                   (fn [operations] ((.-commitEnvironment api) (to-wire operations))) allowed-namespaces)
        catalog-map (into {} (map (fn [id]
                                   [(symbol id) (effect (promised (aget (.-catalog api) id)))])
                                 (js/Object.keys (.-catalog api))))
        model-map (into {} (map (fn [id]
                                 [(symbol id) (effect (promised (aget (.-models api) id)))])
                               (js/Object.keys (.-models api))))
        runtime {'text (fn [x] ((.-text api) (display x)) nil)
                 'println (fn [& xs] ((.-text api) (str/join " " (map str xs))) nil)
                 'prn (fn [& xs] ((.-text api) (str/join " " (map pr-str xs))) nil)
                 'image (fn [x] ((.-image api) (to-wire x)) nil)
                 'exit (fn [] ((.-exit api)))
                 'store (fn [k x] ((.-store api) k (to-wire x)) nil)
                 'load (fn
                         ([k] (from-wire ((.-load api) k)))
                         ([k default] (let [x ((.-load api) k)]
                                        (if (undefined? x) default (from-wire x)))))
                 'unstore (fn [k] ((.-unstore api) k) nil)
                 'all all
                 'all-settled all-settled}
        runtime (into {} (map (fn [[n f]] [n (if (#{'all 'all-settled} n) f (effect f))]) runtime))
        ctx (sci/init {:deny forbidden
                       :namespaces (merge {'pi.tools tool-map
                                    'pi.result commands/result-bindings
                                    'pi.catalog catalog-map
                                    'pi.models model-map
                                    'pi.runtime runtime
                                    'pi.json {'parse #(from-wire (js/JSON.parse %))
                                              'generate #(js/JSON.stringify (to-wire %))}
                                    'clojure.string (sci/copy-ns clojure.string (sci/create-ns 'clojure.string))
                                    'clojure.set (sci/copy-ns clojure.set (sci/create-ns 'clojure.set))
                                    'clojure.core (select-keys runtime '[println prn])
                                    'user runtime
                                    'session (merge runtime (:bindings library))}
                                          (into {} (map (fn [[ns info]] [ns (:bindings info)]) command-namespaces)))
                       :ns-aliases (merge {'tools 'pi.tools
                                    'result 'pi.result
                                    'catalog 'pi.catalog
                                    'models 'pi.models
                                    'json 'pi.json
                                    'str 'clojure.string
                                    'set 'clojure.set}
                                          (into {} (map (fn [[ns info]] [(:alias info) ns]) command-namespaces)))})]
    (reset! context ctx)
    (try
      ((:replay! library) (from-wire (.-environment api)))
      (reset! phase :live)
      (let [reader (sci/source-reader source)
            forms (loop [forms []]
                    (let [[form raw-source] (sci/parse-next+string ctx reader)]
                      (if (= :sci.core/eof form)
                        forms
                        (do
                          (doseq [node (tree-seq coll? seq form)]
                            (when (and (number? node) (not (js/Number.isFinite node)))
                              (throw (ex-info "JSON numbers must be finite" {}))))
                          (if (and (seq? form) (= 'require (first form)))
                            (if (empty? forms)
                              (do
                                (doseq [spec (rest form)]
                                  (let [spec (if (and (seq? spec) (= 'quote (first spec))) (second spec) spec)
                                        n (if (vector? spec) (first spec) spec)]
                                    (when-not (contains? allowed-namespaces (str n))
                                      (throw (ex-info "Only supplied namespaces may be required" {})))))
                                (sci/binding [sci/ns (sci/find-ns ctx 'user)] (sci/eval-form ctx form))
                                (recur forms))
                              (throw (ex-info "require must precede executable forms" {})))
                            (do
                              (when (library/declaration? form) (library/descriptor form raw-source allowed-namespaces))
                              (recur (conj forms [form raw-source]))))))))
            boxed-result (reduce
                    (fn [promise [form raw-source]]
                      (.then promise
                             (fn [_]
                               (if (library/declaration? form)
                                 (do ((:declare! library) form raw-source) [nil])
                                 (let [body (with-meta (list 'clojure.core/fn [] [form]) {:async true})]
                                   (sci/binding [sci/ns (sci/find-ns ctx 'user) sci/file "codemode.clj"]
                                     (sci/eval-form ctx (list body))))))))
                    (js/Promise.resolve [nil]) forms)
            result (.then boxed-result first)]
        (.then result
             (fn [value]
               (when-not (nil? value)
                 ((.-text api) (display value)))
               js/undefined)
             (fn [error] (js/Promise.reject (script-error error source ((:names library)))))))
      (catch :default e
        (js/Promise.reject (script-error e source ((:names library))))))))

(set! js/piSciRun run)
