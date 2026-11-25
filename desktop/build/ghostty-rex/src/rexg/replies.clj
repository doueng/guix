(ns rexg.replies
  "The reply filter of a popped-out pane (ghostty-rex pane). The pane's
  program asks its terminal things (DA1, a cursor report, a colour) and Rex's
  terminal already answers. The pane's own Ghostty window sees the same
  bytes and answers too, into the relay's stdin, so without a filter a second
  answer would reach the program as typed junk. The filter counts the
  questions in the output and drops the next answer of each kind in the
  input; anything else passes. A key that looks like an answer (Shift+F3 is
  CSI 1;2R, like a cursor report) passes unless that question is open.
  Questions Ghostty never answers expire after a while.

  Pure: (out state bytes now) and (in state bytes now) -> [state bytes]."
  (:require [clojure.string :as str]))

(def expire-ms
  "How long a question waits for Ghostty's answer. Ghostty answers within a
  few ms; one it does not know it never answers."
  2000)

(def ^:private latin1 java.nio.charset.StandardCharsets/ISO_8859_1)

(defn new-state [] {:open {} :tail ""})

;; One escape sequence, as a regex over latin1 text: CSI, OSC (BEL or ST) and DCS.
(def ^:private seq-re #"\u001b\[([\x30-\x3f]*)([\x20-\x2f]*)([\x40-\x7e])|\u001b\]([^\u0007\u001b]*)(?:\u0007|\u001b\\)|\u001bP([^\u001b]*)\u001b\\")

(defn- osc-kind [body]
  (let [[n] (str/split body #";" 2)] [:osc n]))

(defn query-kinds
  "The answers one escape sequence of the program's output asks for, as
  kinds (a question may ask several, as OSC 4 does)."
  [[_ p i f osc dcs]]
  (cond
    f (case [p i f]
        (["" "" "c"] ["0" "" "c"]) [:da1]
        ([">" "" "c"] [">0" "" "c"]) [:da2]
        (["=" "" "c"] ["=0" "" "c"]) [:da3]
        ["5" "" "n"] [:dsr]
        ["6" "" "n"] [:cpr]
        ["?6" "" "n"] [:decxcpr]
        ["?996" "" "n"] [:scheme]
        ["?" "" "u"] [:kitty]
        ([">" "" "q"] [">0" "" "q"]) [:xtversion]
        ["14" "" "t"] [[:winops "4"]]
        ["16" "" "t"] [[:winops "6"]]
        ["18" "" "t"] [[:winops "8"]]
        (if (and (= i "$") (= f "p")) [:decrqm] []))
    osc (if (str/ends-with? osc "?")
          (let [[n & args] (str/split osc #";")]
            (if (= n "4")
              (vec (repeat (count (filter #{"?"} args)) [:osc "4"]))
              [(osc-kind osc)]))
          [])
    dcs (cond (str/starts-with? dcs "$q") [:decrqss]
              (str/starts-with? dcs "+q") [:xtgettcap]
              :else [])
    :else []))

(defn reply-kind
  "The kind of answer one escape sequence of the input is, or nil when no
  question asks for it."
  [[_ p i f osc dcs]]
  (cond
    f (cond (and (= f "c") (str/starts-with? p "?")) :da1
            (and (= f "c") (str/starts-with? p ">")) :da2
            (and (= f "n") (= p "0")) :dsr
            (and (= f "R") (re-matches #"\d+;\d+" p)) :cpr
            (and (= f "R") (re-matches #"\?\d+;\d+(;\d+)?" p)) :decxcpr
            (and (= f "n") (re-matches #"\?997;\d+" p)) :scheme
            (and (= f "u") (re-matches #"\?\d*" p)) :kitty
            (and (= f "y") (= i "$")) :decrqm
            (and (= f "t") (re-matches #"[468];\d+;\d+" p)) [:winops (subs p 0 1)]
            :else nil)
    osc (when (re-find #"rgb:|#" osc) (osc-kind osc))
    dcs (cond (re-find #"^[01]\$r" dcs) :decrqss
              (re-find #"^[01]\+r" dcs) :xtgettcap
              (str/starts-with? dcs ">|") :xtversion
              (str/starts-with? dcs "!|") :da3
              :else nil)
    :else nil))

(defn- expire [state now]
  (update state :open #(into {} (keep (fn [[k ts]] (let [ts (vec (drop-while (fn [t] (> (- now t) expire-ms)) ts))]
                                                     (when (seq ts) [k ts])))) %)))

(defn- unfinished
  "Where a CSI, OSC or DCS that TEXT cuts off starts, after its last whole
  sequence; nil when none is cut, or when one has run on past 4 KB."
  [^String text]
  (let [m (re-matcher seq-re text)
        end (loop [e 0] (if (.find m) (recur (.end m)) e))
        j (.matcher #"\u001b(?:[\[\]P]|$)" text)]
    (when (.find j end)
      (when (< (- (count text) (.start j)) 4096) (.start j)))))

(defn out
  "STATE after the program wrote BYTES at NOW (ms): each question in them
  opens one answer of its kind. A sequence cut at the end waits for the rest."
  [state ^bytes bytes now]
  (let [text (str (:tail state) (String. bytes latin1))
        cut (or (unfinished text) (count text))
        kinds (mapcat query-kinds (re-seq seq-re (subs text 0 cut)))]
    (-> (expire state now)
        (assoc :tail (subs text cut))
        (update :open #(reduce (fn [o k] (update o k (fnil conj []) now)) % kinds)))))

(defn in
  "[state bytes] for BYTES typed at NOW (Ghostty's input to the relay): the
  bytes to send on, without one answer per open question of its kind."
  [state ^bytes bytes now]
  (let [state (expire state now)
        text (String. bytes latin1)
        m (re-matcher seq-re text)]
    (loop [state state pos 0 kept (StringBuilder.)]
      (if (.find m)
        (let [k (reply-kind (re-groups m))]
          (if (and k (seq (get-in state [:open k])))
            (recur (update state :open (fn [o] (let [ts (subvec (o k) 1)] (if (seq ts) (assoc o k ts) (dissoc o k)))))
                   (.end m)
                   (.append kept (subs text pos (.start m))))
            (recur state pos kept)))
        [state (.getBytes (str (.append kept (subs text pos))) latin1)]))))
