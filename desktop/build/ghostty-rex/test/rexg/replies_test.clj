(ns rexg.replies-test
  "Properties of the popped-out pane's reply filter (rexg.replies). An abstract model says what the program and Rex should get:
  each of Ghostty's answers is dropped when a question of its kind is open
  and not expired, and every other byte, typed keys above all, goes on. The
  filter works on bytes cut anywhere; the model on events."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [rexg.gens.replies :as gens]
            [rexg.replies :as r]))

(def questions
  "Each kind: the program's question and Ghostty's answer."
  {:da1 ["\u001b[c" "\u001b[?62;22c"]
   :da2 ["\u001b[>c" "\u001b[>1;10;0c"]
   :da3 ["\u001b[=c" "\u001bP!|00000000\u001b\\"]
   :dsr ["\u001b[5n" "\u001b[0n"]
   :cpr ["\u001b[6n" "\u001b[12;40R"]
   :decxcpr ["\u001b[?6n" "\u001b[?12;40;1R"]
   :decrqm ["\u001b[?2026$p" "\u001b[?2026;2$y"]
   :osc11 ["\u001b]11;?\u0007" "\u001b]11;rgb:0000/0000/0000\u001b\\"]
   :osc4 ["\u001b]4;1;?\u001b\\" "\u001b]4;1;rgb:cccc/0000/0000\u001b\\"]
   :xtversion ["\u001b[>q" "\u001bP>|ghostty 1.2\u001b\\"]
   :kitty ["\u001b[?u" "\u001b[?0u"]
   :decrqss ["\u001bP$qm\u001b\\" "\u001bP1$r0m\u001b\\"]
   :winops ["\u001b[18t" "\u001b[8;30;100t"]
   :xtgettcap ["\u001bP+q544e\u001b\\" "\u001bP1+r544e=787465726d\u001b\\"]
   :scheme ["\u001b[?996n" "\u001b[?997;1n"]})

(def typed
  "Each key: its bytes and the kind of answer it looks like, if any."
  {:a ["a" nil] :hello ["hello" nil] :esc ["\u001b" nil] :up ["\u001b[A" nil] :f1 ["\u001bOP" nil]
   :paste ["\u001b[200~pasted text\u001b[201~" nil] :utf8 ["é ✓" nil] :kitty-enter ["\u001b[13u" nil]
   :shift-f3 ["\u001b[1;2R" :cpr]})

(def output "Other output: text, colours, a title." "hi \u001b[1;31mred\u001b[0m \u001b]2;title\u0007 \u001b[?25h")

(def ^:private latin1 java.nio.charset.StandardCharsets/ISO_8859_1)
(defn- b [^String s] (.getBytes (String. (.getBytes s "UTF-8") latin1) latin1))
(defn- s [^bytes x] (String. x latin1))

(def expire-ms "A question Ghostty has not answered in 2 s is closed." 2000)

(defn- expire [open now] (into {} (keep (fn [[k ts]] (let [ts (vec (drop-while #(> (- now %) expire-ms) ts))] (when (seq ts) [k ts])))) open))

(defn- model-in
  "The model after the person or Ghostty sends ITEMS ([text kind] each), and
  what goes on."
  [{:keys [open now] :as md} items]
  (reduce (fn [[md sent] [text kind]]
            (if (and kind (seq (get-in md [:open kind])))
              [(update md :open (fn [o] (let [ts (subvec (o kind) 1)] (if (seq ts) (assoc o kind ts) (dissoc o kind))))) sent]
              [md (str sent text)]))
          [(assoc md :open (expire open now)) ""]
          items))

(defn run
  "Run INPUT's events through the filter and the model side by side.
  Returns [{:event :sent :want :open} ...], one per event."
  [{:keys [events]}]
  (loop [[e & more] events st (r/new-state) md {:open {} :now 0} acc []]
    (if-not e
      acc
      (let [now (:now md)
            [st md sent want]
            (case (first e)
              :query (let [[_ k cut?] e
                           q (first (questions k))
                           parts (if cut? [(subs q 0 (quot (count q) 2)) (subs q (quot (count q) 2))] [q])
                           st (reduce #(r/out %1 (b %2) now) st parts)]
                       [st (-> md (update :open expire now) (update-in [:open k] (fnil conj []) now)) "" ""])
              :out [(r/out st (b output) now) (update md :open expire now) "" ""]
              :wait [st (update md :now + (second e)) "" ""]
              (let [items (case (first e)
                            :answer [[(second (questions (second e))) (second e)]]
                            :key [(typed (second e))]
                            :answer+key [[(second (questions (second e))) (second e)] (typed (nth e 2))])
                    [st2 out] (r/in st (b (apply str (map first items))) now)
                    [md2 want] (model-in md items)]
                [st2 md2 (s out) (s (b want))]))]
        (recur more st md (conj acc {:event e :sent sent :want want :open (set (keys (:open md)))}))))))

(defn check
  "What INPUT breaks: the filter must send on exactly what the model does,
  after every event; so a typed key is never eaten unless it looks like an
  answer to a question still open, and an answer to an open question never
  reaches the program."
  [input]
  (vec (for [{:keys [event sent want] :as step} (run input) :when (not= sent want)]
         [:sent-differs (assoc step :sent (pr-str sent) :want (pr-str want)) event])))

(defn observe
  "The open kinds (how many) and the event, after each step; labels for the
  paths that matter."
  [input]
  (let [steps (run input)]
    {:steps (for [{:keys [event open]} steps] {:state (min 3 (count open)) :action (first event)})
     :labels (cond-> #{}
               (some #(and (= :answer (first (:event %))) (= "" (:sent %))) steps) (conj :answer-dropped)
               (some #(and (= :answer (first (:event %))) (seq (:sent %))) steps) (conj :answer-passed)
               (some #(and (= [:key :shift-f3] (:event %)) (= "" (:sent %))) steps) (conj :lookalike-key-taken)
               (some #(and (= [:key :shift-f3] (:event %)) (seq (:sent %))) steps) (conj :lookalike-key-passed)
               (some #(and (= :query (first (:event %))) (nth (:event %) 2)) steps) (conj :question-cut)
               (some #(= :answer+key (first (:event %))) steps) (conj :answer-and-key-in-one-read)
               (some #(= [:wait 2500] (:event %)) steps) (conj :question-expired))}))

(deftest property-the-filter-drops-only-answers-to-open-questions
  (let [res (tc/quick-check 300 (prop/for-all [input gens/gen-input] (empty? (check input))))]
    (is (:pass? res) (pr-str (select-keys res [:seed :shrunk])))))

(deftest a-key-like-a-cursor-report-passes-when-no-question-is-open
  (is (empty? (check {:events [[:key :shift-f3] [:query :cpr false] [:answer :cpr] [:key :shift-f3]]})))
  (is (= ["\u001b[1;2R" "" "" "\u001b[1;2R"] (map :sent (run {:events [[:key :shift-f3] [:query :cpr false] [:answer :cpr] [:key :shift-f3]]})))))

(deftest an-unanswered-question-expires
  (is (= ["" "" "\u001b[12;40R"] (map :sent (run {:events [[:query :cpr false] [:wait 2500] [:answer :cpr]]})))))
