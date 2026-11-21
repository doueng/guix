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

(defn resolve-target
  "Which thread spoken TEXT is for, and its words: the thread its handle
  names, else, only for TARGET :selected, the thread the Inbox cursor
  SELECTED ({:thread :ask :at}) in the minute before NOW, so a cursor resting
  on a row cannot redirect what you say. {:text :handle :thread :words}."
  [text {:keys [selected now target]}]
  (let [text (str/trim text)
        [handle rest] (model/parse-handle text)
        age (- now (or (:at selected) 0))]
    {:text text :handle handle
     :thread (or handle (when (and (= :selected target) (<= 0 age 60000)) (:thread selected)))
     :words (plain (if handle rest text))}))

(defn interpret
  "What to do for TARGET (from resolve-target) given VIEW, its thread as the
  controller shows it (nil when there is none), and SELECTED, the Inbox
  cursor. {:do [effect ...] :result r} or {:refuse message :data {...}}. An
  effect is [:pick thread n ask-id], [:send thread] or [:say text]. Voice
  never approves: not with words, and no pick on an action or review ask."
  [{:keys [text handle thread words]} view selected]
  (let [[_ pick-words] (re-matches #"(.*?)\s*(?:and )?send" words)
        send? (some? pick-words)
        pick-words (if send? pick-words words)
        open (sort-by :seq (filter #(= :open (:state %)) (:asks view)))
        ask (or (when (= thread (:thread selected)) (some #(when (= (:ask selected) (:id %)) %) open)) (last open))
        n (when (and thread (seq pick-words)) (pick-number ask pick-words))
        refuse (fn [message data] {:refuse message :data data})]
    (cond
      (and handle (nil? view)) (refuse "No such thread" {:thread handle})
      (and thread (re-matches #"(?:i )?approved?(?: .*)?" words))
      (refuse "Voice cannot approve; press A in the inbox or tap Approve" {:thread thread})

      (and thread n)
      (cond (or (nil? ask) (#{:action :review} (:kind ask))) (refuse "Voice cannot approve action or review asks" {:thread thread})
            (not (<= 1 n (count (:options ask)))) (refuse "No such option" {:thread thread})
            :else {:do (cond-> [[:pick thread n (:id ask)]] send? (conj [:send thread]))
                   :result (cond-> {:thread thread :draft true :option n} send? (assoc :sent true))})

      (and thread send? (str/blank? pick-words))
      {:do [[:send thread]] :result {:thread thread :sent true}}

      :else {:do [[:say (if (and thread (not handle)) (str thread " " text) text)]]})))

(defn send-refusal
  "Why VIEW's draft may not be sent by voice, or nil: there is none, or it
  picks an option of an action or review ask, which needs the keyboard, as
  voice never approves."
  [{:keys [draft asks]}]
  (if-let [kind (some #(when (and (#{:action :review} (:kind %)) (contains? (:picks draft) (:id %))) (:kind %)) asks)]
    (str (str/capitalize (name kind)) " asks require keyboard confirmation")
    (when-not draft "No draft to send")))

(defn- send-draft!
  "Send thread THREAD's draft, as it is after any pick, unless send-refusal
  says no."
  [thread]
  (when-let [why (send-refusal (call! {:command :thread :args {:id thread}}))]
    (throw (ex-info why {:thread thread})))
  (pane/send! thread))

(defn submit!
  "Act on one spoken utterance TEXT for the thread it names: pick an option,
  send the draft, or both in one breath (\"option two, send\"); anything else
  is a message. With no spoken handle the message goes to the Router. Only
  TARGET :selected uses the thread your Inbox cursor selected in the last
  minute; resolve-target and interpret decide, this performs."
  [text & {:keys [target] :or {target :router}}]
  (when-not (str/blank? text)
    (pane/with-state-lock!
      (fn []
        (let [{:keys [selected]} (pane/client-state)
              tgt (resolve-target text {:selected selected :now (System/currentTimeMillis) :target target})
              view (when (:thread tgt) (call! {:command :thread :args {:id (:thread tgt)}}))
              {:keys [refuse data result] effects :do} (interpret tgt view selected)]
          (when refuse (throw (ex-info refuse data)))
          (reduce (fn [r [kind & xs]]
                    (case kind
                      :pick (let [[thread n ask] xs] (pane/pick! thread n :ask ask) r)
                      :send (do (send-draft! (first xs)) r)
                      :say (call! {:command :say! :args {:origin :voice :text (first xs)}})))
                  result effects))))))

(defn hex! [& args]
  (apply p/shell {:out :string :err :string :continue true} "hex" args))

(defn stop! [& {:keys [target]}]
  (let [{:keys [exit out err]} (hex! "dictation" "stop" "--print")]
    (case exit
      0 (submit! out :target target)
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
  [take & {:keys [dir poll-ms wait-ms target] :or {dir (say-dir) poll-ms 20 wait-ms 600000}}]
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
    (stop! :target target)))

(defn vocabulary! []
  (doseq [{:keys [id resources]} (call! {:command :threads})
          term (cons id resources)]
    (let [args (cond-> ["vocabulary" "add" term]
                 (= term id) (into ["--sounds-like" (str "tee " (subs id 1) ",tea " (subs id 1))]))
          {:keys [exit err]} (apply hex! args)]
      (when-not (zero? exit) (throw (ex-info (str "Hex vocabulary: " err) {:exit exit})))))
  {:synced true})

(defn typed!
  "Typed TEXT from FROM, a say! origin such as :rex: a message to the thread
  it names, else the Router, with none of the spoken-word handling."
  [from text]
  (when-not (str/blank? text)
    (call! {:command :say! :args {:origin from :text (str/trim text)}})))

(defn -main
  "bbh say [--selected] start|stop [TAKE] | vocabulary | TEXT, or bbh say
  --from ORIGIN TEXT for typed text, such as rexmux's. Words go to the thread
  they name or the Router; --selected acts on the Inbox selection instead."
  [& args]
  (try
    (let [target (when (some #{"--selected"} args) :selected)
          args (remove #{"--selected"} args)
          [flag from & typed] args]
      (prn (case (if (= "--from" flag) "--from" (first args))
             "--from" (typed! (keyword from) (str/join " " typed))
             "start" (if-let [take (second args)]
                       (take! take :target target)
                       (let [{:keys [exit err]} (hex! "dictation" "start")]
                         (when-not (zero? exit) (throw (ex-info err {:exit exit}))) {:recording true}))
             "stop" (if-let [take (second args)] (request-stop! take) (stop! :target target))
             "vocabulary" (vocabulary!)
             (submit! (if (seq args) (str/join " " args) (slurp *in*)) :target target))))
    (catch Exception e
      (binding [*out* *err*] (println "bbh say:" (ex-message e)))
      (System/exit 1))))
