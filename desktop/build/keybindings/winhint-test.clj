(ns winhint-test
  (:require [clojure.test :refer [deftest is run-tests]]))
(load-file "desktop/bin/winhint")

(deftest labels-are-prefix-free
  (is (= ["a" "s" "d"] (winhint/labels 3)))
  (let [many (winhint/labels 30)]
    (is (= 30 (count (distinct many))))
    (is (every? #(= 2 (count %)) many))))

(deftest cells-map-into-the-window
  (is (= {:x 110 :y 220 :w 50 :h 40}
         (winhint/cell-rect {:x 100 :y 200 :w 200 :h 200} 40 10 {:x 2 :y 1 :width 10 :height 2}))))

(deftest only-the-shown-workspace-and-its-fullscreen-window
  (let [monitor {:x 0 :y 0 :activeWorkspace {:id 2} :specialWorkspace {:id 0}}
        win (fn [address ws & {:as more}]
              (merge {:address address :class "c" :mapped true :hidden false :fullscreen 0
                      :workspace {:id ws} :at [0 0] :size [10 10]} more))]
    (is (= [[[:hypr "a"]] [[:hypr "b"]]]
           (map :path (winhint/visible-windows monitor [(win "a" 2) (win "b" 2) (win "c" 3)]))))
    (is (= [[[:hypr "b"]]]
           (map :path (winhint/visible-windows monitor [(win "a" 2) (win "b" 2 :fullscreen 1)]))))))

(deftest a-pane-with-one-editor-window-stays-one-target
  (with-redefs [winhint/pane-program (constantly {:id :nvim :pid 1})
                winhint/nvim-windows (constantly [{:step [:nvim "s" 1000] :rect {:x 0 :y 0 :width 10 :height 10}}])]
    (is (= [[[:herdr "p"]]] (map :path (winhint/editor-windows {:path [[:herdr "p"]] :pane "p" :x 0 :y 0 :w 100 :h 100 :cols 10 :rows 10})))))
  (with-redefs [winhint/pane-program (constantly {:id :emacs :pid 1})
                winhint/emacs-windows (constantly [{:step [:emacs 1 0] :rect {:x 0 :y 0 :width 5 :height 10}}
                                                   {:step [:emacs 1 1] :rect {:x 5 :y 0 :width 5 :height 10}}])]
    (is (= [{:x 0 :y 0 :w 50 :h 100 :path [[:herdr "p"] [:emacs 1 0]]}
            {:x 50 :y 0 :w 50 :h 100 :path [[:herdr "p"] [:emacs 1 1]]}]
           (winhint/editor-windows {:path [[:herdr "p"]] :pane "p" :x 0 :y 0 :w 100 :h 100 :cols 10 :rows 10})))))

(let [{:keys [fail error]} (run-tests 'winhint-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
