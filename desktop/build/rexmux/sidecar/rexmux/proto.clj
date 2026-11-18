(ns rexmux.proto
  "The sidecar protocol between the rexmux window (Rust) and this bb process:
  one EDN map per line on stdin and stdout. Every message has :op and :seq.
  Each direction numbers its messages 1, 2, 3, ... The receiver delivers a
  message only when its :seq is the next one expected, drops a repeat, and
  reports a gap, so nothing is lost or delivered twice without notice. A
  request has an :id, and gets exactly one :reply with that :id."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def rust->bb #{:hello :context :commands/invoke :effect/result :panel/event :eval :bye :ping})
(def bb->rust #{:ready :commands/changed :reply :effects :render :agents :log :pong})

(defn encode
  "One line of EDN for MSG. pr-str escapes newlines inside strings, so the
  result never contains a raw newline."
  [msg]
  (binding [*print-length* nil *print-level* nil *print-namespace-maps* false]
    (pr-str msg)))

(defn decode
  "The message on LINE, or {:op :invalid :line LINE :error why}."
  [line]
  (try
    (let [m (edn/read-string {:readers {} :default tagged-literal} line)]
      (if (and (map? m) (keyword? (:op m)))
        m
        {:op :invalid :line line :error "not a map with an :op"}))
    (catch Exception e {:op :invalid :line line :error (ex-message e)})))

;; Sender: {:next n}. Receiver: {:expect n}.

(def sender {:next 1})
(def receiver {:expect 1})

(defn stamp
  "[sender' msg-with-seq]."
  [s msg]
  [(update s :next inc) (assoc msg :seq (:next s))])

(defn accept
  "[receiver' verdict] for MSG: :deliver when :seq is the one expected,
  :duplicate when it is older, or {:gap [first-missing last-missing]} when
  messages were skipped. A gap still delivers MSG and moves on, so one lost
  message does not stall the link; the gap is reported, never hidden."
  [r {:keys [seq]}]
  (let [e (:expect r)]
    (cond (not (integer? seq)) [r :invalid]
          (= seq e) [(assoc r :expect (inc seq)) :deliver]
          (< seq e) [r :duplicate]
          :else [(assoc r :expect (inc seq)) {:gap [e (dec seq)]}])))

;; Pending requests: {id :pending}. A reply to an unknown id is an error.

(defn await-reply [pending id] (assoc pending id :pending))

(defn settle
  "[pending' verdict]: :ok for the first reply to a pending ID, :unexpected
  otherwise (a second reply, or one nobody asked for)."
  [pending id]
  (if (contains? pending id) [(dissoc pending id) :ok] [pending :unexpected]))

(defn op-ok?
  "True when OP may travel in DIRECTION (:to-bb or :to-rust)."
  [direction op]
  (contains? (if (= direction :to-bb) rust->bb bb->rust) op))

(defn lines
  "Messages on the text of several lines, skipping blank ones."
  [text]
  (map decode (remove str/blank? (str/split-lines text))))
