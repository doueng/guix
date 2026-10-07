(ns pi.commands)

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

(defn options [value positional]
  (cond
    (nil? value) {}
    (map? value) value
    (and positional (= positional :targets) (string? value)) {:targets [value]}
    (and positional (= positional :targets) (vector? value)) {:targets value}
    (and positional (string? value)) {positional value}
    :else (throw (ex-info "Expected an options map or the operation's positional argument" {}))))

(defn bindings [specs tools]
  (into {}
        (keep (fn [{:keys [id namespace alias functions]}]
                (when-let [invoke (get tools (symbol id))]
                  [(symbol namespace)
                   {:alias (symbol alias)
                    :bindings (into {}
                               (map (fn [[name {:keys [operation positional select]}]]
                                      (let [positional (when positional (keyword positional))
                                            dispatch (fn [opts]
                                                       (when-not (string? operation)
                                                         (throw (ex-info "SCI capability bindings are incompatible; run make pi-sci and /reload" {})))
                                                       (let [result (invoke (assoc opts :operation operation))]
                                                         (if select (.then result #(get % (keyword select))) result)))
                                            call (fn [value] (dispatch (options value positional)))]
                                        [(symbol (clojure.core/name name))
                                         (fn
                                           ([] (call nil))
                                           ([value] (call value))
                                           ([value opts]
                                            (when-not (map? opts) (throw (ex-info "Expected an options map" {})))
                                            (dispatch (merge opts (options value positional)))))]))
                                    functions))}]))
              specs)))
