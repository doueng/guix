(ns pi.commands
  (:require [clojure.string :as str]))

(defn ok? [result]
  (and (map? result) (not (false? (:ok result))) (= 0 (:exit_code result))
       (not (:timed_out result)) (nil? (:signal result))))

(defn check-result [result]
  (when-not (ok? result)
    (let [message (or (not-empty (:stderr result)) (not-empty (:stdout result)) "")]
      (throw (ex-info (str "Host command failed (exit " (:exit_code result)
                           ", signal " (:signal result) ", timed out " (boolean (:timed_out result)) ")"
                           (when (seq message) (str "\n" (subs message 0 (min 2000 (count message)))))) result))))
  result)

(def result-bindings
  {'ok? ok?
   'check check-result
   'stdout! (fn [result]
              (check-result result)
              (when-not (string? (:stdout result))
                (throw (ex-info "Command result has no stdout string; use its structured fields" result)))
              (:stdout result))})

(defn usage [fname keys positional]
  (str fname " takes "
       (if positional (str "a " (name positional) " or an options map") "an options map")
       ". Accepted keys: " (str/join ", " (sort (map #(str (keyword %)) keys))) "."))

(defn options [fname keys value positional array?]
  (cond
    (nil? value) {}
    (map? value) value
    (and positional array? (string? value)) {positional [value]}
    (and positional (or (string? value) (vector? value))) {positional value}
    :else (throw (ex-info (usage fname keys positional) {}))))

(defn check-keys [fname accepted required opts]
  (let [accepted (set (map keyword accepted))
        given (set (map keyword (keys opts)))
        unknown (remove accepted given)
        missing (remove given (map keyword required))]
    (when (or (seq unknown) (seq missing))
      (throw (ex-info (str fname " "
                           (str/join " and " (remove nil? [(when (seq unknown) (str "does not accept " (str/join ", " unknown)))
                                                            (when (seq missing) (str "requires " (str/join ", " missing)))]))
                           ". Accepted keys: " (str/join ", " (sort (map str accepted))) ".")
                      {})))))

(defn rename-aliases [fname aliases opts]
  (reduce (fn [opts [alias canonical]]
            (let [alias (keyword alias)
                  canonical (keyword canonical)]
              (cond
                (not (contains? opts alias)) opts
                (contains? opts canonical)
                (throw (ex-info (str fname " takes " canonical "; " alias " is an alias for it. Pass only one.") {}))
                :else (-> opts (dissoc alias) (assoc canonical (get opts alias))))))
          opts
          aliases))

(defn shape-hint [fname shapes error]
  (let [message (or (.-message error) (str error))]
    (if (re-find #"Validation failed for tool" message)
      (js/Error. (str message "\nHint: " fname " takes "
                      (str/join ", " (map (fn [[k v]] (str ":" (name k) " " v)) shapes)) "."))
      error)))

(defn bindings [specs tools]
  (into {}
        (keep (fn [{:keys [id namespace alias functions]}]
                (when-let [invoke (get tools (symbol id))]
                  [(symbol namespace)
                   {:alias (symbol alias)
                    :bindings (into {}
                               (map (fn [[name {:keys [operation positional positional_array select keys required shapes aliases]}]]
                                      (let [positional (when positional (keyword positional))
                                            fname (str alias "/" (clojure.core/name name))
                                            dispatch (fn [opts]
                                                       (when-not (string? operation)
                                                         (throw (ex-info "SCI capability bindings are incompatible; run make pi-sci and /reload" {})))
                                                       (let [opts (rename-aliases fname aliases opts)
                                                             _ (when keys (check-keys fname keys required opts))
                                                             result (.catch (invoke (assoc opts :operation operation))
                                                                            #(throw (shape-hint fname shapes %)))]
                                                         (if select (.then result #(get % (keyword select))) result)))
                                            call (fn [value] (dispatch (options fname keys value positional positional_array)))]
                                        [(symbol (clojure.core/name name))
                                         (fn
                                           ([] (call nil))
                                           ([value] (call value))
                                           ([value opts]
                                            (when-not (map? opts) (throw (ex-info (usage fname keys positional) {})))
                                            (dispatch (merge opts (options fname keys value positional positional_array)))))]))
                                    functions))}]))
              specs)))
