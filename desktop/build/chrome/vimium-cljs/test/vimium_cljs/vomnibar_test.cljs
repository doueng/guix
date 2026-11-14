(ns vimium-cljs.vomnibar-test
  (:require [cljs.test :refer [deftest is testing]]
            [vimium-cljs.vomnibar :as v]))

(def now 1800000000000)
(def opts {:engines {} :search-url "https://s/?q="})

(def cands
  (v/candidates
   {:tabs [{:id 1 :title "Hacker News" :url "https://news.ycombinator.com/" :windowId 1 :lastAccessed (- now 1000)}
           {:id 2 :title "Notes" :url "https://notes.example/" :windowId 1 :lastAccessed (- now 5000)}
           {:id 3 :title "GitHub" :url "https://github.com/" :windowId 1 :lastAccessed (- now 9000)}]
    :recency [2 1 3]
    :cur {:id 1 :url "https://news.ycombinator.com/"}
    :history [{:type "history" :title "Hacker News" :url "https://news.ycombinator.com/" :time (- now 100) :visits 50}
              {:type "history" :title "Notes" :url "https://notes.example/" :time (- now 200) :visits 9}
              {:type "history" :title "Vomnibar" :url "chrome-extension://me/ui.html?newtab" :time now :visits 3}
              {:type "history" :title "Clojure" :url "https://clojure.org/" :time (- now 300) :visits 2}]
    :bookmarks [{:type "bookmark" :title "Clojure docs" :url "https://clojure.org/api" :added 5}]
    :picks {}
    :own "chrome-extension://me/"}))

(defn- urls [rows] (map :url rows))

(deftest omni
  (testing "never offers the page you are on, nor our own pages"
    (doseq [q ["" "news" "hacker news" "vomnibar"]]
      (is (not-any? #{"https://news.ycombinator.com/" "chrome-extension://me/ui.html?newtab"} (urls (v/rows cands "omni" q now opts))) q)))
  (testing "an open page is offered as its tab, with its history's visits"
    (let [[row] (filter #(= "https://notes.example/" (:url %)) (v/rows cands "omni" "notes" now opts))]
      (is (= ["tab" 2 9] [(:type row) (:tabId row) (:visits row)]))))
  (testing "the typed text is a row of its own"
    (is (some #(= "search" (:type %)) (v/rows cands "omni" "zzz qqq" now opts))))
  (is (<= (count (v/rows cands "omni" "" now opts)) v/max-rows)))

(deftest tabs
  (testing "by recency, the current tab last"
    (is (= [2 3 1] (map :tabId (v/rows cands "tabs" "" now opts)))))
  (is (= [3] (map :tabId (v/rows cands "tabs" "git" now opts)))))

(deftest bookmarks
  (is (= ["https://clojure.org/api"] (urls (v/rows cands "bookmarks" "clo" now opts)))))
