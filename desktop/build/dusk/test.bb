#!/usr/bin/env bb
;; Isolated runtime/config regressions: no IPC, windows, or session changes.
(ns dusk-tests
  (:require [clojure.test :refer [deftest is testing run-tests use-fixtures]]
            [config :as config]
            [dusk.runtime :as runtime]
            [dusk.core :as core]
            [dusk.ui :as ui]))

(use-fixtures :each
  (fn [f]
    (let [before @config/launcher]
      (try (f) (finally (reset! config/launcher before))))))

(defn open-launcher! [items]
  ((get @core/request-handlers :dmenu) {:items items :prompt ""} identity))

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
    (config/search-launcher! "same")
    (let [after (rows (serialize (config/launcher-panel)))]
      (is (= (mapv :path [(first before) (last before)]) (mapv :path after))))))

(deftest matching-retains-string-api
  (is (= ["chrome-incognito" "chrome"]
         (config/matches ["ghostty" "chrome-incognito" "chrome"] "ch"))))

(deftest palette-entries-return-their-value-and-match-their-keys
  (open-launcher! [{:label "Rename symbol" :keys "SPC c r" :source "Neovim" :value "map:<leader>cr"}
                   {:label "Save file" :keys "SPC f s" :source "Neovim" :value "map:<leader>fs"}])
  (let [answer (atom nil)]
    (swap! config/launcher assoc :respond #(reset! answer %))
    (config/search-launcher! "spc f")
    (is (= ["Save file"] (map #(get-in % [:children 0 :children 0]) (rows (config/launcher-panel))))
        "a query that matches only the keys finds the entry")
    (config/launcher-key {:key "enter"})
    (is (= "map:<leader>fs" @answer) "Enter returns the entry's value, not its label")))

(deftest launcher-uses-compact-text-and-rows
  (open-launcher! [{:label "Save file" :keys "SPC f s" :source "Emacs" :value "save-buffer"}])
  (let [tree (config/launcher-panel)
        input (get-in tree [:children 1 :children 0])
        row (first (rows tree))]
    (is (= 14 (get-in input [:props :font-size])))
    (is (= 12 (get-in row [:props :font-size])))
    (is (= 28 (get-in row [:props :height])))
    (is (= 11 (get-in row [:children 1 :props :font-size])))
    (is (= 10 (get-in row [:children 2 :props :font-size])))))

(deftest label-matches-rank-above-key-matches
  (is (= ["spc mode" "Save file"]
         (config/matches [{:label "Save file" :keys "SPC f s" :value 1} {:label "spc mode" :value 2}] "spc"))))

(deftest compact-rows-preserve-labels-keys-source-and-values
  (let [answer (atom nil)]
    ((get @core/request-handlers :palette)
     {:prompt "M-x" :source "Emacs" :entries [["save-buffer" "C-s"] ["other-command"]]}
     #(reset! answer %))
    (let [row (first (rows (config/launcher-panel)))]
      (is (= "save-buffer" (get-in row [:children 0 :children 0])))
      (is (= "C-s" (get-in row [:children 1 :children 0])))
      (is (= "Emacs" (get-in row [:children 2 :children 0]))))
    (config/launcher-key {:key "enter"})
    (is (= "save-buffer" @answer))))

(deftest navigation-and-redraw-reuse-the-current-search
  (let [rank-var (ns-resolve 'config 'ranked-items)
        rank @rank-var
        calls (atom 0)]
    (with-redefs-fn {rank-var (fn [items query] (swap! calls inc) (rank items query))}
      (fn []
        (open-launcher! ["save-buffer" "save-other" "unrelated"])
        (config/search-launcher! "save")
        (dotimes [_ 3] (config/launcher-panel) (config/launcher-key {:key "down"}))
        (config/search-launcher! "save")
        (is (= 2 @calls) "only opening and a changed query compute results")
        (config/search-launcher! "other")
        (is (= 3 @calls))
        (is (= 0 (:selected @config/launcher)))
        (is (= "save-other" (get-in (first (rows (config/launcher-panel))) [:children 0 :children 0])))))))

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
