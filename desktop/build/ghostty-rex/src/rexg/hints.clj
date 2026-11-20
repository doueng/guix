(ns rexg.hints
  "Tiny fingers: hints over what a pane shows, as Herdr's tiny-fingers plugin
  had them (Ctrl+b f, Super+Y). URLs, UUIDs, hashes, paths, jj change IDs and
  [bookmarks] get a label from the home row; typing one copies it, a web URL
  also opens, Shift and the label pastes. A port of rexmux's hints.rs."
  (:require [clojure.string :as str]))

(def alphabet "asdfqwerzxcvjklmiuopghtybn")

(def patterns
  [{:re #"(?:https?://|git@|git://|ssh://|ftp://|file:///)[^\s()\"']+" :wrap true}
   {:re #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" :wrap true}
   {:re #"[0-9a-f]{7,128}" :wrap true}
   {:re #"(?:[.\w~$@-]+)?(?:/[.\w@-]+)+/?" :wrap true}
   ;; Jujutsu change IDs use the letters k to z, unlike commit hashes.
   {:re #"[k-z]{8,32}" :alone true}
   {:re #"\[([^\[\]\r\n]+)\]" :group true}])

(defn- flatten-lines
  "[text positions]: LINES with rows that fill WIDTH joined to the next (a soft
  wrap); positions holds each character's [row col], nil for a newline."
  [lines width]
  (loop [rows (map-indexed vector lines) sb (StringBuilder.) pos (transient [])]
    (if-let [[row line] (first rows)]
      (let [join (and (pos? row) (>= (count (nth lines (dec row))) width))]
        (when (and (pos? row) (not join)) (.append sb "\n"))
        (let [pos (if (and (pos? row) (not join)) (conj! pos nil) pos)
              pos (reduce (fn [p c] (conj! p [row c])) pos (range (count line)))]
          (.append sb ^String line)
          (recur (rest rows) sb pos)))
      [(str sb) (persistent! pos)])))

(defn- alnum? [^Character c] (and c (or (Character/isLetterOrDigit c)) (< (int c) 128)))

(defn- hits [lines width]
  (let [[flat fpos] (flatten-lines lines width)]
    (for [{:keys [re wrap group alone]} patterns
          [s pos] (if wrap
                    [[flat fpos]]
                    (map-indexed (fn [row l] [l (mapv #(vector row %) (range (count l)))]) lines))
          :let [m (re-matcher re s)]
          hit (loop [out []]
                (if (.find m)
                  (let [start (.start m) end (.end m)
                        before (when (pos? start) (.charAt ^String s (dec start)))
                        after (when (< end (count s)) (.charAt ^String s end))
                        p0 (get pos start) p1 (get pos (dec end))]
                    (recur (if (or (and alone (or (alnum? before) (alnum? after)))
                                   (nil? p0) (nil? p1) (>= start end))
                             out
                             (let [[row col] p0]
                               (conj out {:index (+ (reduce + (map #(inc (count %)) (take row lines))) col)
                                          :len (- end start) :row row :col col
                                          :text (str/replace (if group (.group m 1) (.group m)) "\n" "")})))))
                  out))]
      hit)))

(defn hint [n digits]
  (let [a (count alphabet)]
    (loop [i (dec digits) n n s (vec (repeat digits \space))]
      (if (neg? i) (apply str s) (recur (dec i) (quot n a) (assoc s i (.charAt alphabet (mod n a))))))))

(defn targets
  "Every target on a screen of LINES soft-wrapping at WIDTH, in screen order,
  a match inside an earlier longer one dropped, each with its :hint."
  [lines width]
  (let [hs (sort-by (juxt :index (comp - :len)) (hits lines width))
        kept (reduce (fn [kept h] (if (some #(and (<= (:index %) (:index h)) (< (:index h) (+ (:index %) (:len %)))) kept) kept (conj kept h)))
                     [] hs)
        n (count kept)
        digits (loop [d 1 cap (count alphabet)] (if (< cap n) (recur (inc d) (* cap (count alphabet))) d))]
    (vec (map-indexed (fn [i h] (-> h (select-keys [:row :col :text]) (assoc :hint (hint i digits)))) kept))))

(defn web-url? [text] (boolean (re-matches #"https?://[^\s'\"()]+" text)))

(defn step
  "What typing INPUT picks among TARGETS: the target when exactly one hint
  equals it, :more while more could follow, :none when nothing matches."
  [targets input]
  (let [ms (filter #(str/starts-with? (:hint %) input) targets)]
    (cond (empty? ms) :none
          (and (= 1 (count ms)) (= input (:hint (first ms)))) (first ms)
          :else :more)))
