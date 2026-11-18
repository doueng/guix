(ns rexmux.schema-test
  (:require [clojure.test :refer [deftest is]]
            [rexmux.schema :as s]))

(def split {:type "object"
            :properties {:session_id {:type "string"}
                         :direction {:type "string" :enum ["left" "right"]}
                         :ratio {:type "integer" :minimum 1 :maximum 99}
                         :layout {:type ["null" "object"]}}
            :required ["session_id" "direction"]
            :additionalProperties false})

(deftest valid-arguments-pass
  (is (= [] (s/errors split {:session_id "s" :direction "left" :ratio 30 :layout nil}))))

(deftest each-rule-is-enforced
  (is (= [[:direction]] (map :path (s/errors split {:session_id "s"}))))
  (is (= [[:direction]] (map :path (s/errors split {:session_id "s" :direction "up"}))))
  (is (= [[:ratio]] (map :path (s/errors split {:session_id "s" :direction "left" :ratio 0}))))
  (is (= [[:ratio]] (map :path (s/errors split {:session_id "s" :direction "left" :ratio "30"}))))
  (is (= [[:bogus]] (map :path (s/errors split {:session_id "s" :direction "left" :bogus 1})))))

(deftest minibuffer-text-is-read-as-the-argument-type
  (is (= 30 (s/coerce {:type "integer"} "30")))
  (is (true? (s/coerce {:type "boolean"} "yes")))
  (is (= "left" (s/coerce {:type "string"} "left"))))
