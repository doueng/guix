(ns vimium-cljs.rank-test
  (:require [cljs.test :refer [deftest is]]
            [vimium-cljs.rank :as r]))

(deftest matching
  (is (r/matches? ["git" "hub"] "https://github.com" "GitHub"))
  (is (not (r/matches? ["git" "lab"] "https://github.com" "GitHub")))
  (is (not (r/matches? ["Git"] "https://github.com" "github")) "smart case")
  (is (r/matches? ["a.b"] "x a.b y")))

(deftest ranking
  (let [items [{:url "https://example.com/news" :title "Example news"}
               {:url "https://news.example.org/" :title "News"}
               {:url "https://other.org/" :title "Other"}]]
    (is (= 2 (count (r/rank "news" items 0))))
    (is (= "News" (:title (first (r/rank "news" items 0)))) "a whole-word title match ranks first")
    (is (= items (r/rank "" items 0)))
    (is (> (r/recency-score 1000000 999999) (r/recency-score 1000000 0)))))

(deftest typed-urls
  (let [engines {"w" {:url "https://w.example/?s=%s"}}
        s "https://www.google.com/search?q="]
    (is (= "https://example.com" (r/->url "example.com" engines s)))
    (is (= "https://example.com/a?b=1" (r/->url "example.com/a?b=1" engines s)))
    (is (= "http://localhost:8080/x" (r/->url "localhost:8080/x" engines s)))
    (is (= "http://127.0.0.1:3000" (r/->url "127.0.0.1:3000" engines s)))
    (is (= "https://x.org/" (r/->url "https://x.org/" engines s)))
    (is (= "about:blank" (r/->url "about:blank" engines s)))
    (is (= "https://www.google.com/search?q=hello%20world" (r/->url "hello world" engines s)))
    (is (= "https://www.google.com/search?q=clojure" (r/->url "clojure" engines s)))
    (is (= "https://w.example/?s=rich%20hickey" (r/->url "w rich hickey" engines s)))
    (is (= "https://www.google.com/search?q=w" (r/->url "w" engines s)) "a keyword alone is a search")
    (is (= "https://www.google.com/search?q=javascript%3Aalert(1)" (r/->url "javascript:alert(1)" engines s)) "javascript: is never a URL")))
