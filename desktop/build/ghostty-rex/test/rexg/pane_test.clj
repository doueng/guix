(ns rexg.pane-test
  "The popped-out pane's relay: what it sends of Ghostty's
  input. The reply filter's properties are rexg.replies-test."
  (:require [clojure.test :refer [deftest is]]
            [rexg.pane :as pane]))

(defn- bytes-of [& xs] (byte-array (map unchecked-byte xs)))

(deftest a-character-cut-at-the-end-waits-for-the-next-read
  (is (= 3 (pane/utf8-cut (bytes-of 0x61 0x62 0x63) 3)))
  ;; é is C3 A9: with only C3 read, it waits.
  (is (= 2 (pane/utf8-cut (bytes-of 0x61 0x62 0xc3) 3)))
  (is (= 4 (pane/utf8-cut (bytes-of 0x61 0x62 0xc3 0xa9) 4)))
  ;; ✓ is E2 9C 93: two of three read.
  (is (= 1 (pane/utf8-cut (bytes-of 0x61 0xe2 0x9c) 3)))
  (is (= 4 (pane/utf8-cut (bytes-of 0x61 0xe2 0x9c 0x93) 4)))
  (is (= 0 (pane/utf8-cut (bytes-of) 0))))
