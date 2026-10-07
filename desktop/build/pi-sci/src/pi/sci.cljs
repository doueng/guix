(ns pi.sci
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [clojure.set :as set]
            [pi.library :as library]))

(defn wire-key [k]
  (cond
    (string? k) k
    (keyword? k) (subs (str k) 1)
    :else (throw (ex-info "JSON map keys must be strings or keywords" {}))))

(defn to-wire [x]
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
                   (aset obj key (to-wire v))))
               obj)
    (sequential? x) (into-array (map to-wire x))
    :else (throw (ex-info "Value is not JSON-compatible" {}))))

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

(defn script-error [error]
  (let [{:keys [line column file]} (ex-data error)
        message (or (ex-message error) (.-message error) (str error))]
    (js/Error. (str message
                    (when line (str " at " (or file "codemode.clj") ":" line ":" (or column 1)))))))

(defn run [source api]
  (let [phase (atom :replay)
        context (atom nil)
        live! (fn [] (when-not (= :live @phase)
                       (throw (ex-info "Effects are disabled during SCI declaration replay" {}))))
        effect (fn [f] (fn [& args] (live!) (apply f args)))
        library (library/create-library context phase
                   (fn [operations] ((.-commitEnvironment api) (to-wire operations))))
        tool-map (into {} (map (fn [id]
                                [(symbol id) (effect (promised (aget (.-tools api) id)))])
                              (js/Object.keys (.-tools api))))
        catalog-map (into {} (map (fn [id]
                                   [(symbol id) (effect (promised (aget (.-catalog api) id)))])
                                 (js/Object.keys (.-catalog api))))
        model-map (into {} (map (fn [id]
                                 [(symbol id) (effect (promised (aget (.-models api) id)))])
                               (js/Object.keys (.-models api))))
        runtime {'text (fn [x] ((.-text api) (if (string? x) x (pr-str x))) nil)
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
                       :namespaces {'pi.tools tool-map
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
                       :ns-aliases {'tools 'pi.tools
                                    'catalog 'pi.catalog
                                    'models 'pi.models
                                    'json 'pi.json
                                    'str 'clojure.string
                                    'set 'clojure.set}})]
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
                                    (when-not (contains? library/allowed-namespaces (str n))
                                      (throw (ex-info "Only supplied namespaces may be required" {})))))
                                (sci/binding [sci/ns (sci/find-ns ctx 'user)] (sci/eval-form ctx form))
                                (recur forms))
                              (throw (ex-info "require must precede executable forms" {})))
                            (do
                              (when (library/declaration? form) (library/descriptor form raw-source))
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
                 ((.-text api) (if (string? value) value (pr-str value))))
               js/undefined)
             (fn [error] (js/Promise.reject (script-error error)))))
      (catch :default e
        (js/Promise.reject (script-error e))))))

(set! js/piSciRun run)
