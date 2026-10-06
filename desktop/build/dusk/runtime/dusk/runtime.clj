(ns dusk.runtime
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dusk.core :as core]))

(defonce ^:private config-path (atom nil))
(defonce ^:private handler-table (atom {}))
(defonce ^:private last-sent (atom {}))
(defonce ^:private failures (atom #{}))

(defn- log [& xs]
  (binding [*out* *err*]
    (apply println "dusk(bb):" xs)))

(defn- describe-error [^Throwable e]
  (let [chain (take-while some? (iterate #(.getCause ^Throwable %) e))
        located (some #(let [d (ex-data %)] (when (:line d) d)) chain)
        messages (distinct (keep #(.getMessage ^Throwable %) chain))]
    (str (str/join "\n" messages)
         (when located
           (str "\n  at " (or (:file located) "?") ":" (:line located)
                (when (:column located) (str ":" (:column located))))))))

(defn- report! [source context e]
  (let [message (str context ": " (describe-error e))]
    (log message)
    (when source
      (swap! failures conj source))
    (core/send! {:op :error :message message})))

(defn- recovered! [source]
  (let [[before after] (swap-vals! failures disj source)]
    (when (and (seq before) (empty? after))
      (core/send! {:op :reloaded}))))


(defn- sanitize [x]
  (cond
    (or (nil? x) (string? x) (keyword? x) (number? x) (boolean? x)
        (symbol? x) (char? x)) x
    (map? x) (into {} (map (fn [[k v]] [k (sanitize v)])) x)
    (set? x) (into #{} (map sanitize) x)
    (sequential? x) (mapv sanitize x)
    :else (str x)))

(defn- child-path [path i child]
  (let [k (when (map? child) (get-in child [:props :key]))]
    ;; Preserve key types and escape path delimiters, so a keyed child cannot
    ;; collide with an indexed child or with another child's descendants.
    (str path "/" (if (some? k)
                    (str "k" (-> (pr-str k) (str/replace "%" "%25") (str/replace "/" "%2F")))
                    i))))

(defn- serialize [node path handlers]
  (cond
    (map? node)
    (let [children (:children node)
          paths (mapv (fn [i child] (child-path path i child)) (range) children)
          _ (when-not (= (count paths) (count (distinct paths)))
              (throw (ex-info (str "duplicate sibling key under " path) {:path path})))
          props (reduce-kv (fn [m k v]
                             (if (fn? v)
                               (let [id (str path ":" (name k))]
                                 (vswap! handlers assoc id v)
                                 (assoc m k id))
                               (assoc m k (sanitize v))))
                           {} (:props node))]
      {:type (:type node)
       :path path
       :props (dissoc props :key)
       :children (mapv (fn [c p] (serialize c p handlers)) children paths)})
    (string? node) node
    (nil? node) nil
    :else (str node)))

(defn- surface-id [layer]
  (let [id (get-in layer [:props :id])]
    (if (keyword? id) (subs (str id) 1) (str id))))

(defn render! []
  (when-let [root @core/root]
    (try
      (let [handlers (volatile! {})
            layers (filter #(and (map? %) (= :layer (:type %))) (flatten [(root)]))
            tree (into {} (map (fn [l] (let [id (surface-id l)] [id (serialize l id handlers)])))
                       layers)
            prev @last-sent
            changed (merge (into {} (remove (fn [[id t]] (= t (get prev id)))) tree)
                           (into {} (for [id (keys prev) :when (not (contains? tree id))] [id nil])))]
        (reset! handler-table @handlers)
        (reset! last-sent tree)
        (when (seq changed)
          (core/send! {:op :render :surfaces changed}))
        (recovered! :render))
      (catch Throwable e
        (report! :render "render failed" e)))))


(defn- load-config! [files]
  (try
    (let [config @config-path
          others (->> files
                      (filter #(str/ends-with? % ".clj"))
                      (remove #(= (str (.getCanonicalFile (java.io.File. ^String %)))
                                  (str (.getCanonicalFile (java.io.File. ^String config))))))]
      (doseq [f others :when (.exists (java.io.File. ^String f))]
        (load-file f))
      (load-file config)
      (log "loaded" config)
      (recovered! :load))
    (catch Throwable e
      (report! :load "config failed to load" e))))

(defn- invoke-handler [f args]
  (if (seq args)
    (try
      (apply f args)
      (catch clojure.lang.ArityException _
        (f)))
    (f)))

(defmulti ^:private handle :op)

(defmethod handle :init [{:keys [config signals]}]
  (reset! config-path config)
  (doseq [[k v] signals]
    (core/set-signal! k v))
  (load-config! [config]))

(defmethod handle :signal [{:keys [id value]}]
  (core/set-signal! id value))

(defmethod handle :event [{:keys [handler args]}]
  (if-let [f (get @handler-table handler)]
    (invoke-handler f args)
    (log "stale handler" handler)))

(defmethod handle :reload [{:keys [files]}]
  (load-config! (or (seq files) [@config-path])))

(defmethod handle :eval [{:keys [id code]}]
  (try
    (let [result (binding [*ns* (or (find-ns 'config) (the-ns 'user))]
                   (load-string code))]
      (core/send! {:op :reply :id id :value (pr-str result)}))
    (catch Throwable e
      (core/send! {:op :reply :id id :error (describe-error e)}))))

(defmethod handle :request [{:keys [id name args]}]
  (if-let [f (get @core/request-handlers name)]
    (let [done (atom false)
          respond (fn [value]
                    (when (compare-and-set! done false true)
                      (core/send! {:op :reply :id id :value (sanitize value)})))]
      (try
        (f args respond)
        (catch Throwable e
          (when (compare-and-set! done false true)
            (core/send! {:op :reply :id id :error (describe-error e)})))))
    (core/send! {:op :reply :id id :error (str "no handler for request " name)})))

(defmethod handle :default [msg]
  (log "unknown message" (pr-str msg)))


(defn- start-reader! []
  (let [in (java.io.BufferedReader. (java.io.InputStreamReader. System/in "UTF-8"))]
    (doto (Thread.
           ^Runnable
           (fn []
             (loop []
               (if-let [line (.readLine in)]
                 (do
                   (when-not (str/blank? line)
                     (try
                       (let [msg (edn/read-string line)]
                         (core/enqueue! #(handle msg)))
                       (catch Throwable e
                         (log "bad message:" (ex-message e)))))
                   (recur))
                 (System/exit 0)))))
      (.setDaemon true)
      (.start))))

(defn- main-loop []
  (loop []
    (let [task (.take core/queue)]
      (try
        (task)
        (catch Throwable e
          (report! nil "handler failed" e)))
      (when (.isEmpty core/queue)
        (render!))
      (recur))))

(defn -main [& _]
  (reset! core/out-writer
          (java.io.BufferedWriter. (java.io.OutputStreamWriter. System/out "UTF-8")))
  (binding [*out* *err*]
    (start-reader!)
    (core/send! {:op :ready})
    (main-loop)))
