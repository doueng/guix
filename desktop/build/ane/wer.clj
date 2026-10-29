(ns wer (:require [clojure.string :as str]))

(defn words [text]
  (-> (str/lower-case text)
      (str/replace #"[-\u2010-\u2014]" " ")
      (str/replace #"[^\p{L}\p{N}' ]" "")
      str/trim
      (str/split #"\s+")))

(defn edit-distance [a b]
  (let [b (vec b)]
    (peek (reduce (fn [prev x]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row))
                                             (inc (prev (inc j)))
                                             (+ (prev j) (if (= x (b j)) 0 1)))))
                            [(inc (first prev))]
                            (range (count b))))
                  (vec (range (inc (count b))))
                  a))))

(defn word-error-rate
  "Word error rate of HYPOTHESIS against REFERENCE, ignoring case and punctuation."
  [reference hypothesis]
  (let [ref (words reference)]
    (/ (double (edit-distance ref (words hypothesis))) (count ref))))
