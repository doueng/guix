;; Vomnibar matching and ranking, ported from Vimium's
;; background_scripts/completion/ranking.js (MIT), and the URL-or-search rule
;; for typed text (after lib/url_utils.js).
(ns vimium-cljs.rank
  (:require [clojure.string :as str]))

(defn- escape-re [s] (str/replace s #"[.*+?^${}()|\[\]\\/]" "\\$&"))

(defn term-re
  "Smart case: case-insensitive unless TERM has a capital letter."
  ([term] (term-re term "" ""))
  ([term prefix suffix]
   (js/RegExp. (str prefix (escape-re term) suffix) (if (= term (str/lower-case term)) "i" ""))))

(defn terms [query] (vec (remove str/blank? (str/split (str/trim (or query "")) #"\s+"))))

(defn matches?
  "Every term matches at least one of THINGS."
  [ts & things]
  (every? (fn [t] (let [re (term-re t)] (some #(and % (.test re %)) things))) ts))

(defn- score-term [term s]
  (let [parts (.split s (term-re term))]
    (if (> (.-length parts) 1)
      (let [cnt (reduce (fn [p ^js c] (- p (.-length c))) (count s) parts)
            score (cond-> 1
                    (.test (term-re term "\\b" "") s) inc
                    (.test (term-re term "\\b" "\\b") s) inc)]
        [score (min cnt (count s))])
      [0 0])))

(defn- normalize-difference [a b]
  (let [mx (max a b)] (if (zero? mx) 0 (/ (- mx (js/Math.abs (- a b))) mx))))

(defn word-relevancy
  "0..1: how strongly the terms appear in URL and TITLE."
  [ts url title]
  (let [mx (* 3 (count ts))
        sum (fn [s] (reduce (fn [[a b] t] (let [[x y] (score-term t s)] [(+ a x) (+ b y)])) [0 0] ts))
        [us uc] (sum url)
        url-score (* (/ us mx) (normalize-difference uc (count url)))
        title-score (if (seq title)
                      (let [[ts* tc] (sum title)] (* (/ ts* mx) (normalize-difference tc (count title))))
                      url-score)]
    (/ (+ (max url-score title-score) title-score) 2)))

(def ^:private month-ms (* 1000 60 60 24 30))

(defn recency-score [now t]
  (let [d (/ (max 0 (- month-ms (- now t))) month-ms)]
    (* d d d (/ 2 3))))

(defn rank
  "Items {:url :title :time} matching QUERY, best first. An empty query keeps
  the given order."
  [query items now]
  (let [ts (terms query)]
    (if (empty? ts)
      (vec items)
      (->> items
           (filter #(matches? ts (:url %) (:title %)))
           (map (fn [i] [(+ (word-relevancy ts (:url i) (:title i))
                            (if (:time i) (recency-score now (:time i)) 0))
                         i]))
           (sort-by (comp - first))
           (mapv second)))))

;; ---- typed text -> URL ---------------------------------------------------------

(def ^:private schemes #{"http" "https" "ftp" "file" "chrome" "chrome-extension" "about" "view-source" "data"})

(defn url-like?
  "Whether S is meant as a URL rather than a search."
  [s]
  (let [s (str/trim s)]
    (and (seq s)
         (not (re-find #"\s" s))
         (or (when-let [[_ scheme] (re-find #"^([a-zA-Z][a-zA-Z0-9+.-]*):" s)]
               (and (schemes (str/lower-case scheme))
                    (or (not (#{"http" "https" "ftp"} (str/lower-case scheme))) (str/includes? s "//"))))
             (re-find #"^(localhost|\[[0-9a-fA-F:]+\]|\d{1,3}(\.\d{1,3}){3})(:\d+)?(/.*)?$" s)
             (re-find #"^[\w-]+(\.[\w-]+)*\.[a-zA-Z]{2,}(:\d+)?([/?#].*)?$" s)))))

(defn ->url
  "The URL for typed text Q: a URL as is (https:// added when it has no
  scheme), a search-engine keyword search, else a search with SEARCH-URL."
  [q engines search-url]
  (let [q (str/trim q)
        [kw & more] (str/split q #"\s+")
        engine (when (seq more) (get engines kw))]
    (cond
      engine (str/replace (:url engine) "%s" (js/encodeURIComponent (str/join " " more)))
      (url-like? q) (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" q)
                      (if (re-find #"^(localhost|\d)" q) (str "http://" q) q)
                      (str (if (re-find #"^(localhost|\d|\[)" q) "http://" "https://") q))
      (str/includes? search-url "%s") (str/replace search-url "%s" (js/encodeURIComponent q))
      :else (str search-url (js/encodeURIComponent q)))))
