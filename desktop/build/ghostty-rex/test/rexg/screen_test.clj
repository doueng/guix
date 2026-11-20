(ns rexg.screen-test
  "The layout rexmux's screen.rs drew, which the Bombadil specs read."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rexg.main :as main]
            [rexg.model :as m]
            [rexg.screen :as s]))

(deftest side-by-side-tiles-share-the-width-with-one-divider
  (let [[pw ph] [101 30]
        a (s/tile-cells [0 0 0.5 1] pw ph)
        b (s/tile-cells [0.5 0 0.5 1] pw ph)]
    (is (= 0 (a 0)))
    (is (= (b 0) (+ (a 0) (a 2) 1)) "one column between them for the divider")
    (is (= pw (+ (b 0) (b 2))) "together they fill the width")
    (is (= [1 ph] [(a 1) (a 3)]))))

(def view {:session "session:s" :label "main" :active "w1" :revision 1
           :windows [{:id "w1" :label "shell" :focused "block:aaaaaa"
                      :tiles [{:block "block:aaaaaa" :rect [0 0 0.5 1]} {:block "block:bbbbbb" :rect [0.5 0 0.5 1]}]}
                     {:id "w2" :label "pi" :tiles []}]})

(defn- row-text [spans row w]
  (let [cells (char-array (repeat w \space))]
    (doseq [{r :row c :col t :text f :flags} spans :when (and (= r row) (not= 128 f))
            [i ch] (map-indexed vector t) :when (< (+ c i) w)]
      (aset cells (+ c i) ch))
    (str/trimr (String. cells))))

(deftest the-tab-bar-titles-and-status-line-are-where-the-specs-look
  (let [model (-> (m/new-model [{:label "local"}]) (assoc-in [:servers 0 :view] view) (assoc :sidecar-up true))
        {:keys [spans layout cursor]} (s/render model 100 30 {})]
    (is (str/includes? (row-text spans 0 100) "[1 shell]"))
    (is (str/includes? (row-text spans 0 100) "2 pi"))
    (is (str/starts-with? (row-text spans 1 100) "▶ aaaaaa") "the focused pane's title")
    (is (str/includes? (row-text spans 28 100) "focus aaaaaa") "the status line")
    (is (= [:focus "block:aaaaaa"] cursor))
    (is (= [{:block "block:aaaaaa" :x 0 :y 2 :w 50 :h 26} {:block "block:bbbbbb" :x 51 :y 2 :w 49 :h 26}] layout))))

(deftest which-key-shows-under-a-prefix
  (let [model (-> (m/new-model [{:label "local"}]) (assoc-in [:servers 0 :view] view)
                  (assoc :prefix ["ctrl+b"] :catalogue [{:id "tab/new" :title "Tabs: new" :action "mx.tab.new"}]
                         :bindings [[["ctrl+b" "c"] "mx.tab.new"]]))
        {:keys [spans cursor]} (s/render model 100 30 {})]
    (is (= "ctrl+b-" (row-text spans 29 100)))
    (is (str/includes? (row-text spans 27 100) "c  Tabs: new"))
    (is (= [:hide] cursor))))

(deftest overlay-text-never-carries-a-tab-or-newline
  (is (= "1 2 - - 0\ta b" (main/spans-text [{:row 1 :col 2 :flags 0 :text "a\tb"}]))))
