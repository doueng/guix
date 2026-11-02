(ns bbh.super.voice
  "Spoken input shares the Inbox's selection and versioned drafts. No paste."
  (:require [bbh.super.client :as client] [bbh.super.model :as model]
            [bbh.super.pane :as pane] [babashka.process :as p] [clojure.string :as str]))

(defn- call! [request]
  (let [{:keys [ok error message] :as response} (client/call! request)]
    (when error (throw (ex-info (or message (name error)) response)))
    ok))

(defn submit!
  [text]
  (when-not (str/blank? text)
    (pane/with-state-lock!
      (fn []
        (let [text (str/trim text)
              [handle rest] (model/parse-handle text)
              {:keys [selected picks]} (pane/client-state)
              age (- (System/currentTimeMillis) (or (:at selected) 0))
              thread (or handle (when (<= 0 age 60000) (:thread selected)))
              words (str/lower-case (str/replace (if handle rest text) #"[.!?]+$" ""))
              t (when thread (call! {:command :thread :args {:id thread}}))
              ask (last (sort-by :seq (filter #(= :open (:state %)) (:asks t))))
              [_ n] (re-matches #"(?:option|pick) (one|two|three|four|[1-9][0-9]*)" words)
              n (when n (or ({"one" 1 "two" 2 "three" 3 "four" 4} n) (parse-long n)))
              recommended? (#{"recommended" "the recommended one"} words)
              label-index (when ask (some (fn [[i o]] (when (= words (str/lower-case (str (:label o)))) (inc i))) (map-indexed vector (:options ask))))
              n (or n label-index (when recommended? (some (fn [[i o]] (when (= (:recommended ask) (:id o)) (inc i))) (map-indexed vector (:options ask)))))]
          (when (and handle (nil? t)) (throw (ex-info "No such thread" {:thread handle})))
          (cond
            (and thread (or n recommended?))
            (do
              (when (or (nil? ask) (= :action (:kind ask))) (throw (ex-info "Voice cannot approve action asks" {:thread thread})))
              (when-not (and n (<= 1 n (count (:options ask)))) (throw (ex-info "No such option" {:thread thread})))
              (pane/pick! thread n)
              {:thread thread :draft true :option n})

            (and thread (= "send" words))
            (do
              (when (some #(and (= :action (:kind %)) (contains? (get picks thread {}) (:id %))) (:asks t))
                (throw (ex-info "Action asks require keyboard confirmation" {:thread thread})))
              (when-not (or (seq (get picks thread)) (get-in (pane/client-state) [:notes thread]))
                (throw (ex-info "No draft to send" {:thread thread})))
              (pane/send! thread)
              {:thread thread :sent true})

            :else (call! {:command :say! :args {:origin :voice :text (if (and thread (not handle)) (str thread " " text) text)}})))))))

(defn hex! [& args]
  (apply p/shell {:out :string :err :string :continue true} "hex" args))

(defn stop! []
  (let [{:keys [exit out err]} (hex! "dictation" "stop" "--print")]
    (case exit
      0 (submit! out)
      4 {:silence true}
      (throw (ex-info (str "Hex failed: " err) {:exit exit})))))

(defn vocabulary! []
  (doseq [{:keys [id resources]} (call! {:command :threads})
          term (cons id resources)]
    (let [args (cond-> ["vocabulary" "add" term]
                 (= term id) (into ["--sounds-like" (str "tee " (subs id 1) ",tea " (subs id 1))]))
          {:keys [exit err]} (apply hex! args)]
      (when-not (zero? exit) (throw (ex-info (str "Hex vocabulary: " err) {:exit exit})))))
  {:synced true})

(defn -main [& args]
  (try
    (prn (case (first args)
           "start" (let [{:keys [exit err]} (hex! "dictation" "start")]
                     (when-not (zero? exit) (throw (ex-info err {:exit exit}))) {:recording true})
           "stop" (stop!)
           "vocabulary" (vocabulary!)
           (submit! (if (seq args) (str/join " " args) (slurp *in*)))))
    (catch Exception e
      (binding [*out* *err*] (println "bbh say:" (ex-message e)))
      (System/exit 1))))
