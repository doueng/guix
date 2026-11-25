(ns rexg.rex-test
  "The data stream's splitter: Rex's own messages come out in stream order
  with the bytes around them, across chunks."
  (:require [clojure.test :refer [deftest is]]
            [rexg.rex :as rex]))

(defn- parts [sp & chunks]
  (vec (for [c chunks part (rex/feed! sp (.getBytes ^String c "ISO-8859-1"))]
         (if (map? part) part (String. ^bytes part "ISO-8859-1")))))

(deftest a-resize-stays-between-the-bytes-around-it
  ;; A redraw for the new size must not reach rexg-render before the size
  ;; (T225: Emacs's mode line landed in the old size and stayed there).
  (is (= ["old" {:type "resize" :cols 90 :rows 20} "new"]
         (parts (rex/splitter "7") "old\u001b_rex1;7;{\"type\":\"resize\",\"cols\":90,\"rows\":20}\u001b\\new"))))

(deftest a-message-split-across-chunks
  (is (= ["a" {:type "resize" :cols 1 :rows 2} "b"]
         (parts (rex/splitter "7") "a\u001b_re" "x1;7;{\"type\":\"resize\",\"cols\":1," "\"rows\":2}\u001b\\b"))))
