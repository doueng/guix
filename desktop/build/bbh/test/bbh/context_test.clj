(ns bbh.context-test
  (:require [bbh.context :as context]
            [clojure.test :refer [deftest is]]))

(def ^:private call
  {:role "assistant" :content ""
   :tool_calls [{:id "c1" :type "function" :function {:name "clojure" :arguments "{\"code\":\"(slurp f)\"}"}}]})

(def ^:private transcript
  [{:role "user" :content "read f"}
   call
   {:role "tool" :tool_call_id "c1" :content "0123456789abcdefghij"}
   {:role "assistant" :content "done"}
   {:role "user" :content "again"}
   call
   {:role "tool" :tool_call_id "c1" :content "0123456789abcdefghij"}])

(deftest view-trims-tool-output-before-the-current-turn
  (is (= [{:role "user" :content "read f"}
          call
          {:role "tool" :tool_call_id "c1" :content "01234\n[... 10 chars elided ...]\nfghij"}
          {:role "assistant" :content "done"}
          {:role "user" :content "again"}
          call
          {:role "tool" :tool_call_id "c1" :content "0123456789abcdefghij"}]
         (context/view transcript nil {:trim-tool-chars 10}))))

(deftest view-replaces-compacted-messages-with-the-summary
  (is (= [{:role "user" :content "[bbh] Earlier messages in this conversation were compacted into this summary:\n\nRead f."}
          {:role "user" :content "again"}
          call
          {:role "tool" :tool_call_id "c1" :content "0123456789abcdefghij"}]
         (context/view transcript {:first-kept 4 :summary "Read f."} {:trim-tool-chars 10}))))

(deftest cuts-keep-recent-messages-and-never-orphan-tool-results
  (is (= 5 (context/cut-point transcript 0 (reduce + (map context/tokens (subvec transcript 5))))))
  (is (= 5 (context/cut-point transcript 0 0)))
  (is (= 1 (context/cut-point transcript 0 1000000)))
  (is (nil? (context/cut-point transcript 5 0))))
