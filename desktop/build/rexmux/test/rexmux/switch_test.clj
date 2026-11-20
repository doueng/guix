(ns rexmux.switch-test
  "make mux edits one line of config.scm; the rest stays byte for byte."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(load-file "switch.bb")

(def real (slurp "../../../modules/engstrand/config.scm"))

(deftest switching-changes-only-the-mux-line-and-rolls-back
  (let [rex (set-mux real "rex")
        back (set-mux rex "herdr")
        diff (remove (fn [[a b]] (= a b)) (map vector (str/split-lines real) (str/split-lines rex)))]
    (is (= 1 (count diff)))
    (is (= "(define %terminal-mux 'rex)" (second (first diff))))
    (is (= real back) "herdr is the default the repo ships")
    (is (thrown? Exception (set-mux "(define x 1)" "rex")))))

(deftest a-hyphenated-mux-switches-and-rolls-back
  (let [g (set-mux real "ghostty-rex")]
    (is (str/includes? g "(define %terminal-mux 'ghostty-rex)"))
    (is (= real (set-mux g "herdr")))))
