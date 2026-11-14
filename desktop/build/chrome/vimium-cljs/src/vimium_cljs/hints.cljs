;; Link hint strings. Port of Vimium's AlphabetHints (content_scripts/link_hints.js,
;; MIT, Copyright (c) Phil Crosby and Ilya Sukhar).
(ns vimium-cljs.hints
  (:require [clojure.string :as str]))

(def default-chars "sadfjklewcmpgh")

(defn hint-strings
  "N hint strings over CHARS, none a prefix of another. Short hints first in
  breadth, then sorted and reversed so that hints sharing a first character
  are spread over the page, as in Vimium."
  [chars n]
  (let [chars (distinct (str/lower-case chars))]
    (cond
      (< (count chars) 2) (throw (ex-info "Link hint characters need at least 2 distinct characters." {:chars chars}))
      (<= n 0) []
      :else
      (loop [hints [""] offset 0]
        (if (or (< (- (count hints) offset) n) (= 1 (count hints)))
          (let [h (nth hints offset)]
            (recur (into hints (map #(str % h)) chars) (inc offset)))
          (->> (subvec hints offset (+ offset n))
               sort
               (mapv #(apply str (reverse %)))))))))

(defn matching
  "Indexes of HINTS that start with TYPED."
  [hints typed]
  (into [] (keep-indexed (fn [i h] (when (str/starts-with? h typed) i))) hints))

(defn assign
  "Splits hint strings over frames. COUNTS is [[frame-id n] ...] in frame order;
  returns {frame-id [hint ...]}."
  [chars counts]
  (let [all (hint-strings chars (reduce + 0 (map second counts)))]
    (loop [[[f n] & more] counts i 0 acc {}]
      (if f
        (recur more (+ i n) (assoc acc f (subvec all i (+ i n))))
        acc))))
