(ns vimium-cljs.scroller-test
  (:require [cljs.test :refer [deftest is testing]]
            [vimium-cljs.scroller :as sc]))

;; A YouTube-like flat tree: <ytd-app>'s shadow root holds the masthead, a fixed guide
;; (narrow, scrolls) and the page column; the feed scrolls inside <ytd-page>'s shadow root.
(def tree
  {:body [:app] :app [:masthead :drawer :page] :masthead [:guide-button]
   :drawer [:guide] :guide [:guide-link :guide-footer] :page [:feed] :feed [:video :ad]})

(def parent (into {} (for [[p cs] tree c cs] [c p])))

(defn env
  "SCROLLS maps an element to the [axis delta] moves it can make; AREA to its visible area
  (default 100); HIDDEN is the set whose overflow is hidden."
  [{:keys [scrolls area hidden]}]
  {:root :html
   :does-scroll (fn [el axis d] (contains? (get scrolls el #{}) [axis d]))
   :should (fn [el _] (not (contains? (or hidden #{}) el)))
   :containing (fn [el] (if (= el :body) :html (parent el)))
   :children (fn [el] (get tree el))
   :area (fn [el] (get area el 100))})

(def both #{[:y 1] [:y -1]})

(deftest delta
  (is (= 1 (sc/delta 60 1)))
  (is (= -1 (sc/delta 60 -1)))
  (is (= -1 (sc/delta 0 1)) "a scroll to the start tests backwards")
  (is (= 1 (sc/delta 1 0.5))))

(deftest first-scrollable
  (testing "the page itself scrolls (YouTube's feed): the root, not the guide"
    (is (= :html (sc/first-scrollable (env {:scrolls {:html #{[:y 1]} :guide both}}) :body))))
  (testing "the page scrolled to the bottom still counts"
    (is (= :html (sc/first-scrollable (env {:scrolls {:html #{[:y -1]} :guide both}}) :body))))
  (testing "the page does not scroll: the largest visible scrollable element wins"
    (let [e {:scrolls {:guide both :feed both} :area {:drawer 240 :guide 240 :page 1000 :feed 1000}}]
      (is (= :feed (sc/first-scrollable (env e) :body)))
      (testing "even when the sidebar comes first in the document and the column scrolls only up"
        (is (= :feed (sc/first-scrollable (env (assoc-in e [:scrolls :feed] #{[:y -1]})) :body))))
      (testing "but not an invisible one"
        (is (= :guide (sc/first-scrollable (env (assoc-in e [:area :page] nil)) :body))))
      (testing "nor one whose overflow is hidden"
        (is (= :guide (sc/first-scrollable (env (assoc e :hidden #{:feed})) :body))))))
  (testing "nothing scrolls"
    (is (nil? (sc/first-scrollable (env {}) :body)))))

(deftest find-scrollable
  (let [e (env {:scrolls {:html #{[:y 1] [:y -1]} :guide #{[:y 1] [:y -1]}}})]
    (testing "from the root, the root"
      (is (= :html (sc/find-scrollable e :html :y 1))))
    (testing "clicked in the guide: the guide"
      (is (= :guide (sc/find-scrollable e :guide-link :y 1)))
      (is (= :guide (sc/find-scrollable e :guide-footer :y -1))))
    (testing "clicked on the masthead (no scrolling ancestor): the root"
      (is (= :html (sc/find-scrollable e :guide-button :y 1))))
    (testing "in the feed, across the shadow roots, up to the root"
      (is (= :html (sc/find-scrollable e :video :y 1)))))
  (testing "the guide at its end: its ancestors, then the root"
    (is (= :html (sc/find-scrollable (env {:scrolls {:html both :guide #{[:y -1]}}}) :guide-link :y 1))))
  (testing "a feed column that scrolls itself"
    (is (= :feed (sc/find-scrollable (env {:scrolls {:feed both :guide both}}) :ad :y 1))))
  (testing "overflow hidden is skipped although it moves"
    (is (= :html (sc/find-scrollable (env {:scrolls {:guide both} :hidden #{:guide}}) :guide-link :y 1))))
  (testing "a detached element ends at the root"
    (is (= :html (sc/find-scrollable (env {}) :orphan :y 1)))))
