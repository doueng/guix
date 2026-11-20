(ns rexg.hints-test
  "rexmux's hints.rs tests, ported."
  (:require [clojure.test :refer [deftest is]]
            [rexg.hints :as h]))

(deftest finds-the-plugins-kinds-in-screen-order
  (let [t (h/targets ["see https://example.com/a?b=1 and /home/u/guix/Makefile"
                      "commit 3f2a9c1d change kmnoprst [main] id 123e4567-e89b-12d3-a456-426614174000"] 200)]
    (is (= ["https://example.com/a?b=1" "/home/u/guix/Makefile" "3f2a9c1d" "kmnoprst" "main"
            "123e4567-e89b-12d3-a456-426614174000"] (map :text t)))
    (is (= [0 34] ((juxt :row :col) (t 1))))
    (is (= ["a" "s"] (map :hint (take 2 t))))))

(deftest a-url-continues-across-a-soft-wrap
  (is (= "https://example.com/p" (:text (first (h/targets ["x https://exa" "mple.com/p"] 13))))))

(deftest a-change-id-inside-a-word-is-not-one
  (is (empty? (h/targets ["Xkmnoprstu"] 80))))

(deftest many-targets-get-two-letter-hints-and-typing-picks-one
  (let [t (h/targets (mapv #(str "/tmp/f" %) (range 30)) 80)]
    (is (= 30 (count t)))
    (is (every? #(= 2 (count (:hint %))) t))
    (is (= :more (h/step t "a")))
    (is (= "/tmp/f1" (:text (h/step t "as"))))
    (is (= :none (h/step t "zz")))))

(deftest web-urls-open-and-others-do-not
  (is (h/web-url? "https://x.org/a"))
  (is (not (h/web-url? "git@github.com:a/b"))))
