#!/usr/bin/env bb
;; Isolated runtime/config regressions: no IPC, windows, or session changes.
(ns dusk-tests
  (:require [clojure.test :refer [deftest is testing run-tests use-fixtures]]
            [config :as config]
            [dusk.runtime :as runtime]
            [dusk.ui :as ui]))

(use-fixtures :each
  (fn [f]
    (let [before @config/launcher]
      (try (f) (finally (reset! config/launcher before))))))

(defn open-launcher! [items]
  (reset! config/launcher {:items items :query "" :selected 0 :prompt "" :respond identity}))

(defn rows [tree]
  (vec (drop 1 (get-in tree [:children 1 :children]))))

(defn serialize [tree]
  (#'runtime/serialize tree "launcher" (volatile! {})))

(deftest keyboard-selection-is-visible
  (open-launcher! (mapv #(str "item-" %) (range 12)))
  (dotimes [_ 25]
    (let [rs (rows (config/launcher-panel))
          selected (:selected @config/launcher)
          highlighted (filter #(get-in % [:props :bg]) rs)]
      (is (= 8 (count rs)))
      (is (= 1 (count highlighted)))
      (is (= (str "item-" selected) (get-in (first highlighted) [:children 0 :children 0]))))
    (config/launcher-key {:key "down"}))
  (testing "Enter chooses the highlighted item beyond the first page"
    (let [answer (atom nil)]
      (swap! config/launcher assoc :selected 10 :respond #(reset! answer %))
      (config/launcher-key {:key "enter"})
      (is (= "item-10" @answer)))))

(deftest empty-and-short-lists
  (doseq [items [[] ["one"] ["one" "two"]]]
    (open-launcher! items)
    (config/launcher-key {:key "up"})
    (is (= (count items) (count (rows (config/launcher-panel)))))))

(deftest duplicate-display-text-has-distinct-stable-identities
  (open-launcher! ["same" "other" "same"])
  (let [before (rows (serialize (config/launcher-panel)))]
    (is (= 3 (count (distinct (map :path before)))))
    (is (= 3 (count (distinct (map #(get-in % [:props :on-click]) before)))))
    (swap! config/launcher assoc :query "same")
    (let [after (rows (serialize (config/launcher-panel)))]
      (is (= (mapv :path [(first before) (last before)]) (mapv :path after))))))

(deftest matching-retains-string-api
  (is (= ["chrome-incognito" "chrome"]
         (config/matches ["ghostty" "chrome-incognito" "chrome"] "ch"))))

(deftest sibling-keys-are-validated
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate sibling key"
        (serialize (ui/column (ui/box {:key "same"}) (ui/box {:key "same"}))))))

(deftest key-types-and-path-delimiters-do-not-collide
  (let [tree (serialize (ui/column (ui/box {:key 1}) (ui/box {:key "1"})
                                  (ui/box {:key "a/b"}) (ui/box {:key "a%2Fb"})))
        paths (map :path (:children tree))]
    (is (= 4 (count (distinct paths))))
    (is (every? #(= 1 (count (filter #{\/} %))) paths))))

(let [{:keys [fail error]} (run-tests 'dusk-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
