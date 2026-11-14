(ns vimium-cljs.hints-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vimium-cljs.hints :as h]))

(defn- prefix-free? [hs]
  (every? (fn [a] (not-any? #(and (not= a %) (str/starts-with? % a)) hs)) hs))

(deftest hint-strings
  (is (= [] (h/hint-strings "ab" 0)))
  (is (= ["a"] (h/hint-strings "ab" 1)))
  (is (= ["a" "b"] (h/hint-strings "ab" 2)))
  (is (= ["aa" "b" "ab"] (h/hint-strings "ab" 3)) "same as Vimium")
  (is (= (h/hint-strings "sadfjklewcmpgh" 3) ["a" "d" "s"]))
  (testing "any count over the user's characters"
    (doseq [n [1 5 13 14 15 100 196 197 1000]]
      (let [hs (h/hint-strings "sadfjklewcmpgh" n)]
        (is (= n (count hs)))
        (is (= n (count (set hs))) "unique")
        (is (prefix-free? hs) "no hint is a prefix of another")
        (is (every? #(re-matches #"[sadfjklewcmpgh]+" %) hs))
        (is (<= (- (apply max (map count hs)) (apply min (map count hs))) 1) "lengths differ by at most one"))))
  (is (= 14 (count (h/hint-strings "SADFJKLEWCMPGH" 14))) "case folds")
  (is (thrown? js/Error (h/hint-strings "aaa" 3)) "one distinct character cannot work"))

(deftest matching
  (let [hs ["sa" "sd" "d"]]
    (is (= [0 1] (h/matching hs "s")))
    (is (= [1] (h/matching hs "sd")))
    (is (= [] (h/matching hs "x")))))

(deftest assign-over-frames
  (let [a (h/assign "ab" [[0 2] [7 3] [9 0]])]
    (is (= 2 (count (a 0))))
    (is (= 3 (count (a 7))))
    (is (= [] (a 9)))
    (is (prefix-free? (concat (a 0) (a 7))) "hints are unique across frames")))
