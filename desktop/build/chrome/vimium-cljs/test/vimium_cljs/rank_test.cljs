(ns vimium-cljs.rank-test
  (:require [cljs.test :refer [deftest is testing]]
            [vimium-cljs.rank :as r]))

(def now 1800000000000)
(def day (* 1000 60 60 24))

(def items
  [{:type "history" :url "https://news.ycombinator.com/" :title "Hacker News" :time (- now day) :visits 40}
   {:type "history" :url "https://github.com/philc/vimium" :title "GitHub - philc/vimium: The hacker's browser." :time (- now (* 3 day)) :visits 5}
   {:type "bookmark" :url "https://clojure.org/guides/getting_started" :title "Clojure - Getting Started"}
   {:type "history" :url "https://en.wikipedia.org/wiki/Vim_(text_editor)" :title "Vim (text editor) - Wikipedia" :time (- now (* 40 day)) :visits 1}
   {:type "tab" :url "https://example.com/news" :title "Example news" :tabId 3}])

(defn- top [q & [picks]] (:title (first (r/rank q items now (or picks {})))))

(deftest matching
  (testing "substring, smart case"
    (is (r/match-term "git" "GitHub"))
    (is (nil? (r/match-term "Git" "github")) "a capital makes it case-sensitive")
    (is (= [[0 3]] (second (r/match-term "git" "GitHub")))))
  (testing "acronym"
    (is (= [[0 1] [7 8]] (second (r/match-term "hn" "Hacker News")))))
  (testing "typo tolerance"
    (is (r/match-term "vimuim" "philc/vimium") "transposition")
    (is (r/match-term "githib" "GitHub") "substitution")
    (is (nil? (r/match-term "xyzw" "GitHub"))))
  (testing "every term must match"
    (is (r/match-item ["git" "vim"] (items 1)))
    (is (nil? (r/match-item ["git" "lab"] (items 1))))))

(deftest edit-distance
  (is (= 0 (r/edit-distance "abc" "abc")))
  (is (= 1 (r/edit-distance "abc" "acb")))
  (is (= 1 (r/edit-distance "abc" "abd")))
  (is (= 3 (r/edit-distance "" "abc"))))

(defn- rand-str [n] (apply str (repeatedly n #(rand-nth "abcde-"))))

(deftest typo-length-agrees-with-edit-distance
  ;; The table typo-length fills (one pass, given up early) must give what edit-distance
  ;; does for each of the lengths n, n-1, n+1, first that fits.
  (let [typo-length @#'r/typo-length]
    (dotimes [_ 3000]
      (let [t (rand-str (+ 4 (rand-int 6)))
            s (rand-str (+ 1 (rand-int 14)))
            i (rand-int (count s))
            budget (if (>= (count t) 8) 2 1)
            n (count t)
            want (some (fn [l] (when (and (pos? l) (<= (+ i l) (count s)) (<= (r/edit-distance t (subs s i (+ i l))) budget)) l))
                       [n (dec n) (inc n)])]
        (is (= want (typo-length t s i budget)) (pr-str t s i))))))

(deftest cached-ranking-matches-uncached
  ;; Ranking a prepared vector again caches each term's matches; the rows must be the same
  ;; as ranking the same items with nothing cached, keystroke after keystroke.
  (let [prepared (r/prepare items)
        strip (fn [rows] (map #(select-keys % [:title :score :match]) rows))]
    (doseq [q ["g" "gi" "git" "git v" "git vi" "git vim" "vim" "vimuim" "hn" "news ex" "zz" ""]]
      (is (= (strip (r/rank q (seq (r/prepare items)) now {})) (strip (r/rank q prepared now {}))) q))))

(deftest ranking
  (is (= "Hacker News" (top "hn")) "acronym")
  (is (= "Hacker News" (top "hacker news")) "exact title")
  (is (= "Hacker News" (top "news.ycombinator.com")) "exact URL")
  (is (= "Vim (text editor) - Wikipedia" (top "vim text")))
  (is (= "GitHub - philc/vimium: The hacker's browser." (top "vimuim")) "typo")
  (is (= "Hacker News" (top "")) "empty query: frecency")
  (testing "an earlier pick for the query wins"
    (is (= "Example news" (top "news" {"example.com/news" {:n 1 :t now :q ["news"]}}))))
  (is (every? :match (r/rank "git" items now {}))))

(deftest omni-rows
  (let [ranked (r/rank "hack" items now {})
        search {:type "search" :title "hack" :url "https://s/?q=hack"}]
    (is (= "Hacker News" (:title (first (r/omni-list "hack" search ranked 10)))) "a strong match beats searching")
    (is (= search (second (r/omni-list "hack" search ranked 10)))))
  (let [ranked (r/rank "ker" items now {})
        search {:type "search" :title "ker" :url "https://s/?q=ker"}]
    (is (= search (first (r/omni-list "ker" search ranked 10))) "a weak match does not"))
  (let [ranked (r/rank "hack" items now {})
        url {:type "url" :title "news.ycombinator.com" :url "https://news.ycombinator.com"}]
    (is (= [url] (filter #(= "news.ycombinator.com" (r/display-url (:url %))) (r/omni-list "x" url ranked 10)))
        "a typed URL replaces its duplicate"))
  (is (= 2 (count (r/dedupe-urls [{:url "https://a.com/"} {:url "http://www.a.com#x"} {:url "https://b.com"}]))))
  (is (= ["tab" "bookmark"]
         (map :type (r/dedupe-urls [{:type "history" :url "https://a.com" :score 3} {:type "history" :url "https://b.com"}
                                    {:type "bookmark" :url "https://b.com"} {:type "tab" :url "https://a.com/" :tabId 1 :score 2}])))
      "an open tab or bookmark wins, at the best item's place"))

(deftest highlighting
  (is (= [["Git" true] ["Hub" false]] (r/highlight "GitHub" [[0 3]])))
  (is (= [["a" false] ["b" true] ["c" false] ["d" true]] (r/highlight "abcd" [[3 4] [1 2]])))
  (is (= [["abc" false]] (r/highlight "abc" []))))

(deftest engines
  (let [engines {"w" {:url "https://w.example/?s=%s" :name "Wikipedia"}}]
    (is (= ["w" (engines "w") "cats"] (r/engine-of "w cats" engines)))
    (is (nil? (r/engine-of "w" engines)))
    (is (nil? (r/engine-of "wiki cats" engines)))))

(deftest latency
  ;; Ranking runs on every keystroke over up to 3000 history entries.
  (let [words ["github" "clojure" "news" "docs" "guix" "manual" "reference" "api" "tabs" "wiki" "blog" "issue"]
        many (r/prepare (for [i (range 3000)]
                         {:type "history" :title (str (words (mod i 12)) " " (words (mod (* 7 i) 12)) " page " i)
                          :url (str "https://" (words (mod (* 5 i) 12)) ".example.org/" (words (mod i 11)) "/" i)
                          :time (- now (* i 60000)) :visits (mod i 17)}))]
    (r/rank "warm up" many now {})
    (doseq [q ["g" "gith" "githb" "clj docs" "zzzz"]]
      (let [t0 (js/Date.now) n (count (r/rank q many now {})) ms (- (js/Date.now) t0)]
        (println "rank" (pr-str q) "over 3000:" n "matches," ms "ms")
        (is (< ms 400))))))

(deftest display
  (is (= "github.com/philc/vimium" (r/display-url "https://www.github.com/philc/vimium/"))))
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
