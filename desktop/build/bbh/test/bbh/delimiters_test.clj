(ns bbh.delimiters-test
  (:require [bbh.delimiters :as d]
            [clojure.test :refer [deftest is]]))

(deftest a-form-truncated-at-the-end-gets-its-closers-appended
  (is (= {:kind :repaired
          :content "(defn greet [name]\n  (println (str \"Hello \" name)))"
          :note "[auto-repair] Unbalanced delimiters: appended `)` to close the `(` opened at line 1, column 1. If that placement is wrong, resend the corrected text."}
         (d/repair "(defn greet [name]\n  (println (str \"Hello \" name))"))))

(deftest closing-at-the-end-never-swallows-a-later-top-level-form
  (is (= {:kind :unbalanced}
         (d/repair "(defn first-fn []\n  (println \"first\")\n(defn second-fn []\n  (println \"second\"))"))))

(deftest a-long-open-form-is-left-alone
  (is (= {:kind :unbalanced}
         (d/repair (apply str "(do" (for [i (range 11)] (str "\n  (step " i ")")))))))

(deftest only-trailing-extra-closers-are-removed
  (is (= {:kind :repaired
          :content "(+ 1 2)\n"
          :note "[auto-repair] Unbalanced delimiters: removed 2 extra trailing closing delimiter(s). If that placement is wrong, resend the corrected text."}
         (d/repair "(+ 1 2)))\n")))
  (is (= {:kind :unbalanced} (d/repair "(a))\n(b)")))
  (is (= {:kind :unbalanced} (d/repair "(let [x (+ 1 2]\n  x)"))))

(deftest delimiters-inside-strings-comments-and-characters-do-not-count
  (is (= {:kind :clean} (d/repair "(str \"a)\" \\( #\"\\)\" \\;) ; ) comment")))
  (is (= {:kind :clean} (d/repair "(str \"a\nb)\" \\newline)"))))
