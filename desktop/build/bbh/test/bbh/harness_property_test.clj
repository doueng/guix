(ns bbh.harness-property-test
  "Random inputs for the four stores outside super that keep a session's state
  across a crash: the wf journal, the mailbox, the REPL checkpoint and the
  transcript. Each property drives the real functions on temp directories and
  checks the rule the store promises."
  (:require [babashka.fs :as fs]
            [bbh.checkpoint :as checkpoint]
            [bbh.hermetic :as hermetic]
            [bbh.inbox :as inbox]
            [bbh.repl :as repl]
            [bbh.session :as session]
            [bbh.super.model :as model]
            [bbh.transcript :as transcript]
            [bbh.workflow :as wf]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(use-fixtures :once hermetic/fixture)

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "harness-property"})))

(defn- passes [trials property]
  (let [result (tc/quick-check trials property)]
    (is (:pass? result) (pr-str (:shrunk result)))))

;; ---- wf journal --------------------------------------------------------------

(def ^:private journal-step
  (gen/one-of [(gen/tuple (gen/return :attest) (gen/elements [{:pass? true} {:pass? false} {:pending? true}]) gen/string)
               (gen/tuple (gen/return :set) gen/string)
               (gen/tuple (gen/return :verdict) (gen/elements [:pass :fail]) gen/string)
               (gen/tuple (gen/return :abandon) gen/string)]))

(defn- journal-events
  "The events STEP adds to a run in state ST, as attest!, set-commands!,
  verdict! and abandon! choose them, or none when the run's status refuses it."
  [st [kind a b]]
  (let [at {:phase (:phase st) :visit (get-in st [:visits (:phase st)])}
        active? (= :active (:status st))]
    (case kind
      :attest (when active?
                (let [attested (merge {:type :attest :claim b :outcome (wf/outcome [a]) :gates [a]} at)]
                  (into [attested] (wf/decide (wf/run-workflow st) (wf/fold-event st attested) [a]))))
      :set (when active? [{:type :set :check a}])
      :verdict (when active? [(merge {:type :verdict :verdict a :by "reviewer" :note b} at)])
      :abandon (when (#{:active :blocked} (:status st)) [{:type :abandon :reason a}]))))

(defn- run-steps!
  "Start a run of workflow W, apply STEPS to it and return [id states], where
  states holds, after each event written, the fold of the events written so far.
  CHECK is called with the id and that fold after every event."
  [w steps check]
  (let [id (wf/start! w {:cwd (temp-dir) :task "t\n\"quoted\"" :check "true" :bench "true"})
        append! (fn [states event]
                  (let [st (wf/fold-event (peek states) (#'wf/append! id event))]
                    (check id st)
                    (conj states st)))
        started (#'wf/current id)]
    [id (reduce (fn [states step] (reduce append! states (journal-events (peek states) step)))
                [{} started]
                steps)]))

(defn- with-wf-state [f]
  (let [root (temp-dir)]
    (with-redefs [wf/state-root (fn [] root)
                  wf/session-run-file (constantly nil)]
      (f))))

(def ^:private workflows [:bug-fix :change :investigation :performance :prototype :handoff :large-project])

(deftest property-a-wf-run-read-back-is-the-fold-of-what-was-written
  (passes 60
          (prop/for-all [w (gen/elements workflows)
                         steps (gen/vector journal-step 0 25)]
            (with-wf-state
              #(let [same (atom true)]
                 (run-steps! w steps (fn [id st] (when (not= st (#'wf/current id)) (reset! same false))))
                 @same)))))

(defn- cut-line
  "LINES as a journal's text with line I cut after a share N/1000 of its
  characters, at least one and never all, and the lines after it whole."
  [lines i n]
  (let [line (nth lines i)
        k (inc (mod n (dec (count line))))]
    (apply str (map-indexed (fn [j l] (cond (< j i) (str l "\n")
                                            (= j i) (str (subs l 0 k) (when (< i (dec (count lines))) "\n"))
                                            :else (str l "\n")))
                            lines))))

(deftest property-a-torn-last-journal-line-is-dropped-and-an-earlier-one-stops-the-read
  (passes 60
          (prop/for-all [w (gen/elements workflows)
                         steps (gen/vector journal-step 0 15)
                         n gen/nat
                         m gen/nat]
            (with-wf-state
              #(let [[id states] (run-steps! w steps (fn [_ _]))
                     file (str (#'wf/journal-file id))
                     text (slurp file)
                     lines (str/split-lines text)
                     last-i (dec (count lines))
                     before (apply str (map (fn [l] (str l "\n")) (butlast lines)))]
                 (spit file (cut-line lines last-i n))
                 (and (= (count lines) (dec (count states)))
                      (= (nth states last-i) (binding [*err* (java.io.StringWriter.)] (#'wf/current id)))
                      (= before (slurp file))
                      (= (nth states last-i) (#'wf/current id))
                      (or (zero? last-i)
                          (do (spit file (cut-line lines (mod m last-i) n))
                              (try (#'wf/current id) false
                                   (catch clojure.lang.ExceptionInfo e
                                     (and (str/includes? (ex-message e) "journal corrupt")
                                          (= (cut-line lines (mod m last-i) n) (slurp file)))))))))))))

;; ---- mailbox -----------------------------------------------------------------

(defn- mail-id [i] (model/message-id (str "o" i)))

(def ^:private mailbox-op
  (gen/one-of [(gen/tuple (gen/return :send) (gen/choose 1 12) gen/string (gen/elements [nil "steer"]))
               (gen/tuple (gen/return :ack) (gen/choose 1 12))]))

(deftest property-the-mailbox-holds-what-was-sent-and-not-taken-one-per-id-oldest-first
  (passes 100
          (prop/for-all [ops (gen/vector mailbox-op 0 30)]
            (let [root (temp-dir)]
              (loop [[[op i text kind] & more] ops held (sorted-map)]
                (cond
                  (not= (vec (vals held)) (or (inbox/pending root) [])) false
                  (not= (set (keys held)) (set (filter #(inbox/waiting-since root (mail-id %)) (range 1 13)))) false
                  (nil? op) true
                  (= :send op) (let [sent (inbox/send! root text nil (cond-> {:message-id (mail-id i)} kind (assoc :kind kind)))]
                                 (recur more (assoc held i sent)))
                  :else (do (inbox/ack! {:root root} (mail-id i))
                            (recur more (dissoc held i)))))))))

(defn- mail-session
  "What take-mail! and record! read of a session on conversation DIR, as a
  session that has just started there holds it."
  [dir]
  (atom {:dir dir :messages (transcript/load-messages dir) :taken {} :code-lock (Object.)}))

(defn- times-held
  "How many of conversation DIR's messages hold mailbox message I."
  [dir i]
  (count (filter #(re-find (re-pattern (str "\\[(Mail|Steering) " (mail-id i) "\\]")) (:content %))
                 (transcript/load-messages dir))))

(def ^:private delivery-op
  (gen/frequency [[4 (gen/tuple (gen/return :send) (gen/choose 1 5))]
                  [3 (gen/return [:take])]
                  [3 (gen/return [:record])]
                  [1 (gen/return [:crash])]]))

(defn- deliver!
  "Apply delivery OP to [session texts], where texts are the mail the session
  took and has not recorded yet. A crash loses them and the session's memory."
  [dir [s texts] [op i]]
  (case op
    :send (do (inbox/send! dir (str "text " i) nil (cond-> {:message-id (mail-id i)} (odd? i) (assoc :kind "steer")))
              [s texts])
    :take [s (if-let [text (session/take-mail! s)] (conj texts text) texts)]
    :record (if-let [text (first texts)]
              (do (session/record! s {:role "user" :content text}) [s (subvec texts 1)])
              [s texts])
    :crash [(mail-session dir) []]))

(deftest property-a-session-reads-each-mailbox-message-once-across-resends-and-crashes
  (passes 100
          (prop/for-all [ops (gen/vector delivery-op 0 40)]
            (let [dir (temp-dir)
                  sent (set (keep (fn [[op i]] (when (= :send op) i)) ops))
                  step (fn [state op]
                         (let [state (deliver! dir state op)]
                           (if (every? #(<= (times-held dir %) 1) sent) state (reduced nil))))
                  state (reduce step [(mail-session dir) []] ops)
                  caught-up (some-> state (step [:take]) (as-> st (reduce step st (repeat (count sent) [:record]))))]
              (and caught-up
                   (every? #(= 1 (times-held dir %)) sent)
                   (empty? (inbox/pending dir))
                   (nil? (session/take-mail! (first caught-up))))))))

;; ---- REPL checkpoint ---------------------------------------------------------

(def ^:private checkpoint-namespaces '[user harness.one harness.two])
(def ^:private checkpoint-names '[harness-a harness-b harness-c])

(def ^:private checkpoint-form
  (gen/frequency [[3 (gen/tuple (gen/return :def) (gen/elements checkpoint-names) gen/small-integer)]
                  [3 (gen/tuple (gen/return :defn) (gen/elements checkpoint-names) gen/small-integer)]
                  [2 (gen/tuple (gen/return :in-ns) (gen/elements checkpoint-namespaces))]]))

(defn- form-source [[kind a b]]
  (case kind
    :def (str "(def " a " " b ")")
    :defn (str "(defn " a " [] " b ")")
    :in-ns (str "(in-ns '" a ")")))

(defn- journal-evaluations!
  "Journal each of EVALUATIONS, a vector of forms, to PATH as the session does
  after evaluating it, the REPL staying in the namespace the last one left.
  Returns the value each [namespace name] should have afterwards."
  [path evaluations]
  (with-redefs [repl/call (fn [& _] {:value {}})]
    (first
     (reduce (fn [[values ns] forms]
               (checkpoint/record! path nil (str/join "\n" (map form-source forms)) ns)
               (reduce (fn [[values ns] [kind a b]]
                         (if (= :in-ns kind) [values a] [(assoc values [ns a] b) ns]))
                       [values ns]
                       forms))
             [{} 'user]
             evaluations))))

(defn- defined
  "The value of every checkpoint name that has one, by [namespace name]."
  []
  (into {} (for [n checkpoint-namespaces s checkpoint-names
                 :let [v (some-> (find-ns n) (ns-resolve s))]
                 :when v]
             [[n s] (if (fn? @v) (@v) @v)])))

(deftest property-a-checkpoint-replays-each-name-to-its-last-definition-in-its-namespace
  (passes 100
          (prop/for-all [evaluations (gen/vector (gen/vector checkpoint-form 1 5) 0 8)]
            (let [path (str (fs/path (temp-dir) "checkpoint.clj"))
                  values (journal-evaluations! path evaluations)]
              (doseq [n checkpoint-namespaces s checkpoint-names]
                (ns-unmap (create-ns n) s))
              (when (fs/exists? path)
                (binding [*ns* (the-ns 'user)] (load-string (slurp path))))
              (and (= values (defined))
                   (= (count values) (count (when (fs/exists? path) (checkpoint/definitions (slurp path) 'user)))))))))

;; ---- transcript --------------------------------------------------------------

(def ^:private message
  (gen/let [role (gen/elements ["user" "assistant" "tool"])
            content gen/string
            calls (gen/vector (gen/hash-map :id gen/string-alphanumeric
                                            :function (gen/hash-map :name gen/string-alphanumeric :arguments gen/string))
                              0 2)]
    (cond-> {:role role :content content} (seq calls) (assoc :tool_calls calls))))

(def ^:private prompt
  (gen/hash-map :at gen/nat :model gen/string-alphanumeric :system gen/string :tool-description gen/string))

(deftest property-a-transcript-loads-back-what-was-appended-in-order
  (passes 100
          (prop/for-all [entries (gen/vector (gen/one-of [(gen/tuple (gen/return :message) message)
                                                          (gen/tuple (gen/return :prompt) prompt)])
                                             0 20)]
            (let [dir (str (fs/path (temp-dir) "conversation"))]
              (loop [[[kind entry] & more] entries messages [] prompts []]
                (cond
                  (not= [messages prompts] [(transcript/load-messages dir) (transcript/load-prompts dir)]) false
                  (nil? kind) true
                  (= :message kind) (do (transcript/append! dir entry) (recur more (conj messages entry) prompts))
                  :else (do (transcript/append-prompt! dir entry) (recur more messages (conj prompts entry)))))))))
