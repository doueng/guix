(ns rexg.resize-mbt-test
  "Model-based test of a pane's size against resize.qnt's fixed module
  (desktop/build/ghostty-rex/verify/quint). The driver runs bb's half on the
  real code: Rex's stream is encoded as Rex writes it, and read feeds each
  item to rexg.rex/feed! in two chunks and routes the parts with
  rexg.rex/stream-out, as the reader thread does; the frames it makes are the
  pipe. rexg-render's half (take, apply) is C, so the driver performs it as
  the spec says, and the comparison that counts is the pipe: sizes and bytes
  in the order the program wrote them, nothing on bb's main queue."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rexg.rex :as rex]))

(def nonce "4242")
(def block 3)
(def ^:private latin1 java.nio.charset.StandardCharsets/ISO_8859_1)

(defn- cols [s] (+ 80 (* 10 s)))
(defn- rows [s] (+ 20 s))

(defn- encode
  "Quint's stream item as Rex writes it: bytes for size S as the text wS, a
  size as Rex's resize message."
  [[k s]]
  (case k
    :Bytes (str "w" s)
    :Size (str "\u001b_rex1;" nonce ";{\"type\":\"resize\",\"cols\":" (cols s) ",\"rows\":" (rows s) "}\u001b\\")))

(defn world []
  {:rexSize 1 :stream [] :mainq [] :pipe [] :paneq [] :termSize 1 :misfit false
   :sp (rex/splitter nonce)})

(defn- frames->items
  "Quint items of the frames one read made: a run of 'd' frames is the bytes
  it carries, an 'r' frame a size, a message for bb's main queue [:main m]."
  [frames]
  (let [prefix (str block "\u0000")]
    (->> frames
         (partition-by #(= \d (first %)))
         (mapcat (fn [run]
                   (if (= \d (ffirst run))
                     (let [t (apply str (map (fn [[_ b]] (subs (String. ^bytes b latin1) (count prefix))) run))]
                       [[:Bytes (parse-long (subs t 1))]])
                     (for [[k x] run]
                       (if (= \r k)
                         (let [[_ c r] (str/split x #" ")
                               s (/ (- (parse-long c) 80) 10)]
                           (if (= (rows s) (parse-long r)) [:Size s] [:bad-size x]))
                         [:main x]))))))))

(defn- bb-read
  "W after bb's reader thread takes the stream's next item."
  [w]
  (let [text (encode (first (:stream w)))
        cut (quot (count text) 2)
        parts (mapcat #(rex/feed! (:sp w) (.getBytes ^String % latin1)) [(subs text 0 cut) (subs text cut)])
        items (frames->items (map #(rex/stream-out block %) parts))]
    (-> w
        (update :stream (comp vec rest))
        (update :pipe into (remove #(= :main (first %)) items))
        (update :mainq into (filter #(= :main (first %)) items)))))

(defn step
  "W after Quint's action A, [name] or [:resize s]."
  [w [a s]]
  (case a
    :write (update w :stream conj [:Bytes (:rexSize w)])
    :resize (-> w (assoc :rexSize s) (update :stream conj [:Size s]))
    :read (bb-read w)
    ;; bb's main queue never carries a size; relay would send one.
    :relay (-> w (update :pipe conj (first (:mainq w))) (update :mainq (comp vec rest)))
    ;; rexg-render, as RenderInOrder says.
    :take (-> w (update :paneq conj (first (:pipe w))) (update :pipe (comp vec rest)))
    :apply (let [[k x] (first (:paneq w))
                 w (update w :paneq (comp vec rest))]
             (if (= :Size k) (assoc w :termSize x) (update w :misfit #(or % (not= x (:termSize w))))))))

(def vars [:rexSize :stream :mainq :pipe :paneq :termSize :misfit])

(defn replay-trace
  "Follow STATES, a quint run --mbt trace of resize.qnt's fixed module, on
  the real code. Returns nil when every state agrees, else where they first
  differ."
  [states]
  (loop [w (world) [st & more] (rest states) i 1]
    (cond
      (not st) nil
      (not= (select-keys (first states) vars) (select-keys (world) vars))
      {:step 0 :action [:init] :problem :states-differ :quint (select-keys (first states) vars) :real (select-keys (world) vars)}
      :else
      (let [a [(keyword (:action st)) (get-in st [:picks :s])]
            w2 (step w a)]
        (if (not= (select-keys st vars) (select-keys w2 vars))
          {:step i :action a :problem :states-differ :quint (select-keys st vars) :real (select-keys w2 vars)}
          (recur w2 more (inc i)))))))

(deftest a-redraw-after-a-resize-lands-at-the-new-size
  ;; resize.qnt's redrawFitsTest, with bb's half on the real splitter.
  (let [w (reduce step (world) [[:resize 2] [:write] [:read] [:read]])]
    (is (= [[:Size 2] [:Bytes 2]] (:pipe w)))
    (is (= [] (:mainq w)))
    (let [w (reduce step w [[:take] [:take] [:apply] [:apply]])]
      (is (= 2 (:termSize w)))
      (is (false? (:misfit w))))))
