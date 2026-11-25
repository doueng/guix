(ns rexg.stream-test
  "Properties of a pane's data stream on its way to rexg-render (T225): what
  rexg.rex/feed! and rexg.rex/stream-out make of Rex's bytes, cut into any
  chunks, is the same VT and the same messages in the same order, and a size
  never trades places with the bytes around it. The resize race that left
  Emacs's mode line in the old size came from a size taking another path than
  the bytes; resize.qnt models it and rexg.resize-mbt-test replays it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [rexg.gens.stream :as gens]
            [rexg.rex :as rex]))

(def block "The pane's block number in the frames." 3)
(def ^:private latin1 java.nio.charset.StandardCharsets/ISO_8859_1)

(defn marker [nonce] (str "\u001b_rex1;" nonce ";"))

(defn- item-text
  "ITEM as the stream carries it, a latin1 string."
  [nonce item]
  (let [m (marker nonce)
        apc (fn [payload] (str m (String. (.getBytes ^String (json/generate-string payload) "UTF-8") latin1) "\u001b\\"))]
    (case (first item)
      :vt (second item)
      :vt-partial (let [[_ t k] item] (str t (subs m 0 (min k (dec (count m))))))
      :resize (apc {:type "resize" :cols (nth item 1) :rows (nth item 2)})
      :msg (apc {:type "title" :text (second item)}))))

(defn- expected
  "What the stream means: VT runs merged, empty ones dropped, and each
  message as stream-out routes it."
  [nonce items]
  (->> items
       (map (fn [it] (case (first it)
                       (:vt :vt-partial) [:vt (item-text nonce it)]
                       :resize [:r (str block " " (nth it 1) " " (nth it 2))]
                       :msg [:msg {:type "title" :text (second it)}])))
       (partition-by #(= :vt (first %)))
       (mapcat (fn [run] (if (= :vt (ffirst run)) [[:vt (apply str (map second run))]] run)))
       (remove #(= [:vt ""] %))
       vec))

(defn valid?
  "Rex's nonce is secret, so no program's VT holds its marker: the VT,
  messages left out, never contains it."
  [{:keys [nonce items]}]
  (not (str/includes? (apply str (for [it items :when (#{:vt :vt-partial} (first it))] (item-text nonce it)))
                      (marker nonce))))

(defn chunks
  "The stream's text cut at CUTS."
  [text cuts]
  (let [n (count text)
        at (if (zero? n) [] (sort (distinct (map #(mod % (inc n)) cuts))))]
    (map #(subs text %1 %2) (cons 0 at) (concat at [n]))))

(defn run-stream
  "Feed INPUT's stream through a splitter chunk by chunk. Returns {:feeds
  [{:chunk :parts :pending}] :out [frame ...] :pending}, each frame as
  stream-out made it, bytes as latin1 text without the block prefix."
  [{:keys [nonce items cuts]}]
  (let [sp (rex/splitter nonce)
        text (apply str (map #(item-text nonce %) items))
        feeds (vec (for [c (chunks text cuts)]
                     (let [parts (rex/feed! sp (.getBytes ^String c latin1))]
                       {:chunk c :parts parts :pending @(:pending sp)})))
        prefix (str block "\u0000")
        frame (fn [part] (let [[k x] (rex/stream-out block part)]
                           (case k
                             \d [:vt (let [s (String. ^bytes x latin1)] (when (str/starts-with? s prefix) (subs s (count prefix))))]
                             \r [:r x]
                             :msg [:msg x])))]
    {:feeds feeds
     :out (vec (for [f feeds p (:parts f)] (frame p)))
     :pending @(:pending sp)}))

(defn- merged [frames]
  (vec (mapcat (fn [run] (if (= :vt (ffirst run)) [[:vt (apply str (map second run))]] run))
               (partition-by #(= :vt (first %)) frames))))

(defn check
  "What INPUT breaks: the frames plus what the splitter still holds must be
  the stream's meaning in order, and it may hold back only the start of a
  message (a prefix of the marker, or a message not yet ended)."
  [input]
  (if-not (valid? input)
    []
    (let [{:keys [out pending]} (run-stream input)
          m (marker (:nonce input))
          got (merged (cond-> out (seq pending) (conj [:vt pending])))
          want (expected (:nonce input) (:items input))]
      (cond-> []
        (not= want got) (conj [:order-or-content {:want want :got got}])
        (some #(nil? (second %)) out) (conj [:bytes-without-block out])
        (not (or (= "" pending)
                 (and (< (count pending) (count m)) (str/starts-with? m pending))
                 (and (str/starts-with? pending m) (not (str/includes? pending "\u001b\\")))))
        (conj [:held-back-too-much pending])))))

(defn observe
  "The splitter's state after each chunk, and labels for the paths that
  matter: a message cut across chunks, a marker's start held back and then
  let go as VT, a size between bytes, a message in UTF-8."
  [input]
  (let [{:keys [feeds out]} (run-stream input)
        m (marker (:nonce input))
        held (fn [p] (cond (= "" p) :none (str/starts-with? p m) :open-message :else :marker-prefix))
        kinds (mapv first out)]
    {:steps (for [{:keys [chunk parts pending]} feeds]
              {:state [(held pending) (min 3 (count parts))]
               :action (cond (str/includes? chunk m) :chunk-with-marker
                             (str/includes? chunk "\u001b") :chunk-with-esc
                             :else :chunk-plain)})
     :labels (cond-> #{}
               (some #(= :open-message (held (:pending %))) feeds) (conj :message-across-chunks)
               (some (fn [[a b]] (and (= :marker-prefix (held (:pending a))) (some bytes? (:parts b)))) (partition 2 1 feeds))
               (conj :prefix-released-as-vt)
               (some #(= [:vt :r :vt] %) (partition 3 1 kinds)) (conj :size-between-bytes)
               (some #(= [:r :r] %) (partition 2 1 kinds)) (conj :sizes-back-to-back)
               (some #(and (= :msg (first %)) (re-find #"[^\x00-\x7f]" (str (:text (second %))))) out) (conj :utf8-message))}))

(deftest property-the-stream-keeps-its-order-across-any-chunks
  (let [r (tc/quick-check 300 (prop/for-all [input gens/gen-input] (empty? (check input))))]
    (is (:pass? r) (pr-str (select-keys r [:seed :shrunk])))))
