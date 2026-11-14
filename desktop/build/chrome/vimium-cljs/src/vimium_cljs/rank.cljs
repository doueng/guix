;; Vomnibar matching and ranking. Each query term matches the title or the display URL
;; as a substring, else as an acronym (hn: Hacker News), else as a subsequence, else as a
;; word prefix one typo away (vimuim: vimium). Matches score by kind and place, then by
;; frecency (visits, recency) and the user's earlier picks. The URL-or-search rule for typed
;; text follows Vimium's lib/url_utils.js (MIT).
(ns vimium-cljs.rank
  (:require [clojure.string :as str]))

(defn terms [query] (vec (remove str/blank? (str/split (str/trim (or query "")) #"\s+"))))

(defn display-url
  "URL as shown and matched: no scheme, no www., no trailing slash."
  [url]
  (-> (or url "")
      (str/replace #"^(https?|ftp)://" "")
      (str/replace #"^www\." "")
      (str/replace #"/$" "")))

;; Hot path: every keystroke matches each term against up to 3000 titles and URLs, so
;; this works on char codes and lower-cases each string once.

(defn- alnum-at? [s i]
  (let [c (.charCodeAt s i)]
    (or (<= 48 c 57) (<= 65 c 90) (<= 97 c 122) (> c 127))))

(defn- word-start? [s i]
  (or (zero? i)
      (not (alnum-at? s (dec i)))
      (let [c (.charCodeAt s i) p (.charCodeAt s (dec i))]
        (and (<= 65 c 90) (<= 97 p 122)))))

(defn- smart? [term] (not= term (str/lower-case term)))

(defn- substring-match [t s s*]
  (let [n (count t)]
    (loop [from 0 first-i nil]
      (let [i (.indexOf s* t from)]
        (cond
          (and (neg? i) (nil? first-i)) nil
          (or (neg? i) (word-start? s i))
          (let [i (if (neg? i) first-i i)
                e (+ i n)
                start? (word-start? s i)
                whole? (and start? (or (>= e (count s)) (not (alnum-at? s e))))]
            [(cond-> (if start? 1.0 0.6) whole? (+ 0.2) (zero? i) (+ 0.1)) [[i e]]])
          :else (recur (inc i) (or first-i i)))))))

(defn- ranges [^js positions]
  (reduce (fn [acc p] (if (and (seq acc) (= p (second (peek acc))))
                        (conj (pop acc) [(first (peek acc)) (inc p)])
                        (conj acc [p (inc p)])))
          [] (array-seq positions)))

(defn- chars-in-order
  "Positions (a JS array) in S* of T's characters in order, only at word starts if STARTS?."
  [t s s* starts?]
  (let [n (count s*) m (count t) acc #js []]
    (loop [ti 0 si 0]
      (cond (== ti m) acc
            (>= si n) nil
            (and (== (.charCodeAt t ti) (.charCodeAt s* si)) (or (not starts?) (word-start? s si)))
            (do (.push acc si) (recur (inc ti) (inc si)))
            :else (recur ti (inc si))))))

(defn- acronym-match [t s s*]
  (when (>= (count t) 2)
    (when-let [ps (chars-in-order t s s* true)]
      [0.8 (ranges ps)])))

(defn- subsequence-match [t s s*]
  (when (>= (count t) 3)
    (when-let [^js ps (chars-in-order t s s* false)]
      (let [span (- (inc (aget ps (dec (.-length ps)))) (aget ps 0))]
        (when (<= span (* 3 (count t)))
          [(* 0.45 (/ (count t) span)) (ranges ps)])))))

(defn edit-distance
  "Damerau-Levenshtein distance (with adjacent transpositions)."
  [a b]
  (let [n (count a) m (count b)
        w (inc m)
        d (js/Int32Array. (* (inc n) w))]
    (dotimes [j w] (aset d j j))
    (loop [i 1]
      (when (<= i n)
       (aset d (* i w) i)
       (loop [j 1]
         (when (< j w)
          (let [cost (if (== (.charCodeAt a (dec i)) (.charCodeAt b (dec j))) 0 1)
                v (min (inc (aget d (+ (* (dec i) w) j)))
                       (inc (aget d (+ (* i w) (dec j))))
                       (+ (aget d (+ (* (dec i) w) (dec j))) cost))
                v (if (and (> i 1) (> j 1)
                           (== (.charCodeAt a (dec i)) (.charCodeAt b (- j 2)))
                           (== (.charCodeAt a (- i 2)) (.charCodeAt b (dec j))))
                    (min v (inc (aget d (+ (* (- i 2) w) (- j 2)))))
                    v)]
            (aset d (+ (* i w) j) v)
            (recur (inc j)))))
       (recur (inc i))))
    (aget d (+ (* n w) m))))

(def ^:private dp (js/Int32Array. 4096))

(defn- typo-length
  "The length l (tried as N, N-1, N+1) of the text at I in S* that is within BUDGET edits of
  T (Damerau, as edit-distance), or nil. One table for all three lengths, in a reused buffer,
  given up once two rows in a row are over budget (no later row can come back under it)."
  [t s* i budget]
  (let [n (count t)
        m (min (inc n) (- (count s*) i))
        w (inc m)]
    (when (and (pos? m) (<= (* (inc n) w) (.-length dp)))
      (dotimes [j w] (aset dp j j))
      (let [done (loop [r 1 prev-over false]
                   (if (> r n)
                     true
                     (do (aset dp (* r w) r)
                         (let [over (loop [j 1 lo r]
                                      (if (> j m)
                                        (> lo budget)
                                        (let [a (.charCodeAt t (dec r)) b (.charCodeAt s* (+ i (dec j)))
                                              v (min (inc (aget dp (+ (* (dec r) w) j)))
                                                     (inc (aget dp (+ (* r w) (dec j))))
                                                     (+ (aget dp (+ (* (dec r) w) (dec j))) (if (== a b) 0 1)))
                                              v (if (and (> r 1) (> j 1)
                                                         (== a (.charCodeAt s* (+ i (- j 2))))
                                                         (== (.charCodeAt t (- r 2)) b))
                                                  (min v (inc (aget dp (+ (* (- r 2) w) (- j 2)))))
                                                  v)]
                                          (aset dp (+ (* r w) j) v)
                                          (recur (inc j) (min lo v)))))]
                           (if (and over prev-over) false (recur (inc r) over))))))]
        (when done
          (some (fn [l] (when (and (pos? l) (<= l m) (<= (aget dp (+ (* n w) l)) budget)) l))
                [n (dec n) (inc n)]))))))

(defn- typo-match
  "T (4+ characters) one edit (two from 8 characters) from the start of a word in S."
  [t s s*]
  (let [n (count t)]
    (when (>= n 4)
      (let [budget (if (>= n 8) 2 1)
            c0 (.charCodeAt t 0) c1 (.charCodeAt t 1)
            len (count s*)]
        (loop [i 0]
          (when (< i len)
            (let [c (.charCodeAt s* i)]
              (or (when (and (or (== c c0) (== c c1)) (word-start? s i))
                    (when-let [l (typo-length t s* i budget)]
                      [0.4 [[i (+ i l)]]]))
                  (recur (inc i))))))))))

(defn- field [s] (let [s (or s "")] #js [s (str/lower-case s)]))

(defn- missing
  "How many of T's characters S* lacks, stopping past LIMIT."
  [t s* limit]
  (loop [i 0 n 0]
    (cond (> n limit) n
          (== i (count t)) n
          :else (recur (inc i) (if (neg? (.indexOf s* (.charAt t i))) (inc n) n)))))

(defn- match-field
  "[score ranges] for term T (folded) in FIELD, or nil."
  [t smart ^js f]
  (let [s (aget f 0) s* (if smart s (aget f 1))
        n (count t)
        ;; Every kind but a typo needs all of T's characters; a typo lacks at most two.
        allowed (cond (>= n 8) 2 (>= n 4) 1 :else 0)]
    (when (and (seq s) (<= (missing t s* allowed) allowed))
      (or (substring-match t s s*) (acronym-match t s s*) (subsequence-match t s s*) (typo-match t s s*)))))

(defn match-term
  "[score ranges] for TERM in S, or nil. Substring 0.6..1.3, acronym 0.8,
  subsequence up to 0.45, typo 0.4. Smart case: a capital makes it case-sensitive."
  [term s]
  (let [sm (smart? term)] (match-field (if sm term (str/lower-case term)) sm (field s))))

(defn- merge-ranges [rs]
  (reduce (fn [acc [s e]] (if (and (seq acc) (<= s (second (peek acc))))
                            (conj (pop acc) [(first (peek acc)) (max e (second (peek acc)))])
                            (conj acc [s e])))
          [] (sort rs)))

(defn- prepared [ts] (mapv (fn [t] (let [sm (smart? t)] [(if sm t (str/lower-case t)) sm])) ts))

(defn- term-match
  "[:title or :url, [score ranges]] for term T where it scores best, or nil."
  [t sm ^js tf ^js uf]
  (let [tm (match-field t sm tf)
        um (match-field t sm uf)
        um (when um [(* 0.9 (first um)) (second um)])]
    (cond (and tm um) (if (>= (first tm) (first um)) [:title tm] [:url um])
          tm [:title tm]
          um [:url um])))

(defn- relevance
  "[relevance per-term-matches] when every term matches the title or the display URL.
  IPTS are [k [t sm]] for the K-th prepared term; MATCH, called as (match k [t sm]), finds a term's match (term-match, or
  a cached one)."
  [ipts q ^js tf ^js uf match]
  (let [ms (reduce (fn [acc [k pt]]
                     (if-let [m (match k pt)] (conj acc m) (reduced nil)))
                   [] ipts)]
    (when ms
      (let [lt (str/trim (aget tf 1)) lu (aget uf 1)
            exact? (or (= q lt) (= q lu) (= q (first (str/split lu #"/"))))]
        [(+ (/ (reduce + (map (comp first second) ms)) (count ms)) (if exact? 1.5 0)) ms]))))

(defn- highlights
  "Ranges to highlight in title and display URL: each term wherever it matches as text,
  not only where it scored best."
  [pts ms ^js tf ^js uf]
  (let [shown (fn [k ^js f]
                (merge-ranges (mapcat (fn [[t sm] [k* [_ r]]]
                                        (if (= k k*)
                                          r
                                          (let [s (aget f 0) s* (if sm s (aget f 1))]
                                            (second (or (substring-match t s s*) (acronym-match t s s*))))))
                                      pts ms)))]
    {:title (shown :title tf) :url (shown :url uf)}))

(defn match-item
  "{:relevance :match {:title ranges :url ranges}} when every term matches the
  title or the display URL, else nil."
  [ts {:keys [title url]}]
  (let [pts (prepared ts) tf (field title) uf (field (display-url url))]
    (when-let [[rel ms] (relevance (map-indexed vector pts) (str/lower-case (str/join " " ts)) tf uf (fn [_ [t sm]] (term-match t sm tf uf)))]
      {:relevance rel :match (highlights pts ms tf uf)})))

(def ^:private day-ms (* 1000 60 60 24))

(defn frecency
  "0..~2.5: how often (visits, typed visits, picks) and how lately ITEM was used."
  [now {:keys [time visits typed]} pick]
  (let [uses (+ (or visits 0) (* 2 (or typed 0)) (* 3 (or (:n pick) 0)))
        last (max (or time 0) (or (:t pick) 0))
        age (/ (max 0 (- now last)) day-ms)
        recent (if (pos? last) (js/Math.exp (- (/ age 14))) 0)]
    (+ (* 0.5 (js/Math.log10 (inc uses))) recent)))

(defn- pick-bonus [q pick]
  (if (and pick (seq q) (some #(str/starts-with? (str/lower-case %) q) (:q pick))) 2 0))

(defn pick-key [url] (str/lower-case (display-url url)))

(defn item-key "ITEM's pick-key, from prepare when it ran." [item] (or (::key item) (pick-key (:url item))))

;; Per-term matches for a prepared vector of items (the Vomnibar's candidates, ranked again on
;; every keystroke): item vector -> Map of term -> JS array of [where match] (false: none) by
;; item index. Held weakly, so it goes with the candidates.
(defonce ^:private term-caches (js/WeakMap.))

(defn- term-cache [items]
  (when (and (vector? items) (seq items) (::tf (first items)))
    (or (let [^js c (.get term-caches items)]
          (if (and c (> (.-size c) 64)) (do (.clear c) c) c))
        (let [c (js/Map.)] (.set term-caches items c) c))))

(defn rank
  "The best 100 items matching QUERY, best first, each with :score and :match. PICKS
  maps pick-key -> {:n :t :q}. An empty query orders by frecency alone."
  ([query items now] (rank query items now {}))
  ([query items now picks]
   (let [ts (terms query)
         ;; Terms already matched against these items (earlier keystrokes) go first: a
         ;; cached term costs a lookup, and a new term is only tried on items the others match.
         ^js cache (term-cache items)
         pts (cond->> (prepared ts) cache (sort-by (fn [[t sm]] (if (.has cache (str sm t)) 0 1))))
         arrays (when cache (mapv (fn [[t sm]] (let [k (str sm t)]
                                                 (or (.get cache k) (let [a (js/Array. (count items))] (.set cache k a) a))))
                                  pts))
         ipts (vec (map-indexed vector pts))
         q (str/lower-case (str/join " " ts))
         base (fn [i pick] (+ (frecency now i pick) (if (identical? "tab" (:type i)) 0.3 0)))
         pick-of (fn [i] (when (seq picks) (get picks (item-key i))))
         ^js found #js []]
     (reduce (fn [idx i]
               (do
                 (if (empty? ts)
                   (.push found #js [(base i (pick-of i)) i])
                   (let [tf (or (::tf i) (field (:title i))) uf (or (::uf i) (field (display-url (:url i))))
                         match (if arrays
                                 (fn [k [t sm]]
                                   (let [^js a (nth arrays k) m (aget a idx)]
                                     (if (undefined? m)
                                       (let [m (term-match t sm tf uf)] (aset a idx (or m false)) m)
                                       (when m m))))
                                 (fn [_ [t sm]] (term-match t sm tf uf)))]
                     (when-let [[rel ms] (relevance ipts q tf uf match)]
                       (let [pick (pick-of i)]
                         (.push found #js [(+ (* 3 rel) (base i pick) (pick-bonus q pick)) i rel ms tf uf])))))
                 (inc idx)))
             0 items)
     (.sort found (fn [^js a ^js b] (- (aget b 0) (aget a 0))))
     ;; Highlight ranges only for the rows that can be shown.
     (into [] (map-indexed (fn [n ^js f]
                             (let [i (dissoc (aget f 1) ::tf ::uf)]
                               (if (empty? ts)
                                 (assoc i :score (aget f 0) :match {:title [] :url []})
                                 (cond-> (assoc i :score (aget f 0) :relevance (aget f 2))
                                   (< n 40) (assoc :match (highlights pts (aget f 3) (aget f 4) (aget f 5))))))))
           (.slice found 0 100)))))

(defn prepare
  "ITEMS with their title, display URL and pick-key folded once, for items ranked again and again."
  [items]
  (mapv #(let [d (display-url (:url %))]
           (assoc % ::tf (field (:title %)) ::uf (field d) ::key (str/lower-case d)))
        items))

(defn strong?
  "Whether a ranked item beats searching the typed text: every term a substring at a
  word start, or an earlier pick for this query."
  [item]
  (or (>= (:relevance item 0) 1.0)
      (and (>= (:relevance item 0) 0.8) (>= (:score item 0) 5))))

(def ^:private type-rank {"tab" 0 "bookmark" 1 "history" 2})

(defn dedupe-urls
  "One item per URL (ignoring scheme, www., trailing slash and #fragment), placed where
  its first item was: the open tab if there is one, else a bookmark, else the first."
  [items]
  (let [k #(str/replace (item-key %) #"#.*$" "")
        best (reduce (fn [m i] (update m (k i) #(if (and % (<= (type-rank (:type %) 3) (type-rank (:type i) 3))) % i)))
                     {} items)]
    (second (reduce (fn [[seen acc] i]
                      (let [key (k i)]
                        (if (seen key)
                          [seen acc]
                          [(conj seen key) (conj acc (merge (best key) (select-keys i [:score :relevance :match])))])))
                    [#{} []] items))))

(defn omni-list
  "The Vomnibar rows for QUERY: RANKED (tabs, bookmarks, history, best first) with the
  typed row (a search, URL or engine search) on top unless the best match is strong
  and the typed text is a plain search. At most N rows."
  [query typed ranked n]
  (let [ranked (dedupe-urls ranked)
        plain-search? (and typed (= "search" (:type typed)) (not (:engine typed)))]
    (vec (take n (cond (nil? typed) ranked
                       (and plain-search? (seq ranked) (strong? (first ranked))) (concat [(first ranked) typed] (rest ranked))
                       :else (cons typed (remove #(= (item-key %) (pick-key (:url typed))) ranked)))))))

(defn highlight
  "[[text matched?] ...] covering S, from RANGES [[start end] ...]."
  [s ranges]
  (let [s (or s "")
        [acc at] (reduce (fn [[acc at] [b e]]
                           (let [b (max b at) e (min e (count s))]
                             (if (< b e)
                               [(cond-> acc (< at b) (conj [(subs s at b) false]) true (conj [(subs s b e) true])) e]
                               [acc at])))
                         [[] 0] (merge-ranges ranges))]
    (cond-> acc (< at (count s)) (conj [(subs s at) false]))))

(defn engine-of
  "[keyword engine rest] when QUERY starts with an engine keyword and a space."
  [query engines]
  (let [[_ kw more] (re-find #"^\s*(\S+)\s+(.*)$" (or query ""))]
    (when-let [e (and kw (get engines kw))] [kw e more])))

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
