(ns bbh.super.voice
  "Spoken input shares the Inbox's selection and versioned drafts. No paste."
  (:require [bbh.sysenv :as sysenv]
            [bbh.super.client :as client] [bbh.super.model :as model]
            [bbh.super.pane :as pane] [babashka.fs :as fs] [babashka.process :as p] [clojure.string :as str]))

(defn- call! [request]
  (let [{:keys [ok error message] :as response} (client/call! request)]
    (when error (throw (ex-info (or message (name error)) response)))
    ok))

(defn- plain
  "S as spoken words: lower case, without punctuation or extra spaces."
  [s]
  (-> (str/lower-case (str s)) (str/replace #"[^\p{L}\p{N}\s]" " ") (str/replace #"\s+" " ") str/trim))

(defn- pick-number
  "The 1-based option of ASK that WORDS name: \"option two\", \"pick 3\", an
  option's label, or the recommended one. Nil when WORDS name none."
  [ask words]
  (let [options (map-indexed vector (:options ask))
        [_ n] (re-matches #"(?:option|pick) (one|two|three|four|[1-9][0-9]*)" words)]
    (cond n (or ({"one" 1 "two" 2 "three" 3 "four" 4} n) (parse-long n))
          (#{"recommended" "the recommended one"} words)
          (or (some (fn [[i o]] (when (= (:recommended ask) (:id o)) (inc i))) options) 0)
          :else (some (fn [[i o]] (when (= words (plain (:label o))) (inc i))) options))))

(defn- send-draft!
  "Send thread THREAD's saved draft, refusing a pick on an action ask."
  [thread t]
  (let [{:keys [picks notes]} (pane/client-state)]
    (when (some #(and (= :action (:kind %)) (contains? (get picks thread {}) (:id %))) (:asks t))
      (throw (ex-info "Action asks require keyboard confirmation" {:thread thread})))
    (when-not (or (seq (get picks thread)) (get notes thread))
      (throw (ex-info "No draft to send" {:thread thread})))
    (pane/send! thread)))

(defn submit!
  "Act on one spoken utterance TEXT for the thread it names or the one your
  Inbox cursor selected in the last minute: pick an option, send the draft, or
  both in one breath (\"option two, send\"); anything else is a message."
  [text]
  (when-not (str/blank? text)
    (pane/with-state-lock!
      (fn []
        (let [text (str/trim text)
              [handle rest] (model/parse-handle text)
              {:keys [selected]} (pane/client-state)
              age (- (System/currentTimeMillis) (or (:at selected) 0))
              thread (or handle (when (<= 0 age 60000) (:thread selected)))
              words (plain (if handle rest text))
              [_ pick-words] (re-matches #"(.*?)\s*(?:and )?send" words)
              send? (some? pick-words)
              pick-words (if send? pick-words words)
              t (when thread (call! {:command :thread :args {:id thread}}))
              ask (last (sort-by :seq (filter #(= :open (:state %)) (:asks t))))
              n (when (and thread (seq pick-words)) (pick-number ask pick-words))]
          (when (and handle (nil? t)) (throw (ex-info "No such thread" {:thread handle})))
          (when (and thread (re-matches #"(?:i )?approved?(?: .*)?" words))
            (throw (ex-info "Voice cannot approve; press A in the inbox or tap Approve" {:thread thread})))
          (cond
            (and thread n)
            (do
              (when (or (nil? ask) (#{:action :review} (:kind ask))) (throw (ex-info "Voice cannot approve action or review asks" {:thread thread})))
              (when-not (<= 1 n (count (:options ask))) (throw (ex-info "No such option" {:thread thread})))
              (pane/pick! thread n :ask (:id ask))
              (when send? (send-draft! thread t))
              (cond-> {:thread thread :draft true :option n} send? (assoc :sent true)))

            (and thread send? (str/blank? pick-words))
            (do (send-draft! thread t) {:thread thread :sent true})

            :else (call! {:command :say! :args {:origin :voice :text (if (and thread (not handle)) (str thread " " text) text)}})))))))

(defn hex! [& args]
  (apply p/shell {:out :string :err :string :continue true} "hex" args))

(defn stop! []
  (let [{:keys [exit out err]} (hex! "dictation" "stop" "--print")]
    (case exit
      0 (submit! out)
      4 {:silence true}
      (throw (ex-info (str "Hex failed: " err) {:exit exit})))))

(defn- say-dir []
  (str (fs/path (or (sysenv/getenv "XDG_RUNTIME_DIR") (str (fs/temp-dir))) "bbh-say")))

(defn request-stop!
  "End dictation TAKE: leave a stop request for the process recording it,
  which stops Hex only after its own start returned. Hyprland runs the press
  and the release as two processes, and on a quick tap the release can run
  first."
  [take & {:keys [dir] :or {dir (say-dir)}}]
  (fs/create-dirs dir)
  (spit (str (fs/path dir (str (fs/file-name take) ".stop"))) "")
  {:stopping take})

(defn take!
  "Record dictation TAKE: start Hex, wait for its stop request, then stop and
  submit what was said. Requests older than a day are left from takes whose
  start failed, and are removed."
  [take & {:keys [dir poll-ms wait-ms] :or {dir (say-dir) poll-ms 20 wait-ms 600000}}]
  (fs/create-dirs dir)
  (doseq [old (fs/glob dir "*.stop")
          :when (< (.toMillis (fs/last-modified-time old)) (- (System/currentTimeMillis) 86400000))]
    (fs/delete-if-exists old))
  (let [stop-file (fs/path dir (str (fs/file-name take) ".stop"))
        {:keys [exit err]} (hex! "dictation" "start")]
    (when-not (zero? exit)
      (fs/delete-if-exists stop-file)
      (throw (ex-info err {:exit exit})))
    (loop [waited 0]
      (when (and (not (fs/exists? stop-file)) (< waited wait-ms))
        (Thread/sleep (long poll-ms))
        (recur (+ waited poll-ms))))
    (fs/delete-if-exists stop-file)
    (stop!)))

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
           ;; With a take id, the press records and the release only asks it to stop.
           "start" (if-let [take (second args)]
                     (take! take)
                     (let [{:keys [exit err]} (hex! "dictation" "start")]
                       (when-not (zero? exit) (throw (ex-info err {:exit exit}))) {:recording true}))
           "stop" (if-let [take (second args)] (request-stop! take) (stop!))
           "vocabulary" (vocabulary!)
           (submit! (if (seq args) (str/join " " args) (slurp *in*)))))
    (catch Exception e
      (binding [*out* *err*] (println "bbh say:" (ex-message e)))
      (System/exit 1))))
