(ns vimium-cljs.vomnibar
  (:require [clojure.string :as str]
            [vimium-cljs.rank :as rank]))

(def max-rows 10)

(defn typed-item
  "The row for the typed text itself: a URL to go to, or a search."
  [query engines search-url]
  (when-not (str/blank? query)
    (let [url (rank/->url query engines search-url)]
      (if-let [[_ e more] (rank/engine-of query engines)]
        {:type "search" :engine (:name e) :title more :url url}
        {:type (if (rank/url-like? query) "url" "search") :title (str/trim query) :url url}))))

(defn candidates
  "Prepared candidates from what the page read: TABS (maps with :id :title :url :windowId
  :lastAccessed), RECENCY (tab ids, most recent first), HISTORY and BOOKMARKS (items),
  PICKS (pick-key -> {:n :t :q}), CUR (the tab the Vomnibar acts on, or nil) and OWN (this
  extension's URL prefix, whose pages stay out of history)."
  [{:keys [tabs recency history bookmarks picks cur own]}]
  (let [cur-id (:id cur)
        cur-key (some-> (:url cur) rank/pick-key)
        order (into {} (map-indexed (fn [i id] [id i])) recency)
        by-recency (sort-by (fn [t] [(if (= (:id t) cur-id) 1 0) (get order (:id t) 1e6)]) tabs)
        tab-item (fn [t] {:type "tab" :title (:title t) :url (:url t) :tabId (:id t) :windowId (:windowId t)
                          :time (:lastAccessed t)})
        history (rank/prepare (remove #(and own (str/starts-with? (or (:url %) "") own)) history))
        visited (into {} (map (juxt rank/item-key identity)) history)
        with-visits (fn [t] (if-let [h (visited (rank/item-key t))]
                              (assoc t :visits (:visits h) :typed (:typed h) :time (max (or (:time t) 0) (or (:time h) 0)))
                              t))
        other? #(not= cur-key (rank/item-key %))
        tab-items (rank/prepare (map tab-item (remove #(= cur-id (:id %)) by-recency)))
        bookmarks (rank/prepare bookmarks)]
    {:picks (or picks {})
     :tabs (rank/prepare (map tab-item by-recency))
     :bookmarks bookmarks
     :recent-bookmarks (vec (sort-by (comp - #(or % 0) :added) bookmarks))
     :open (into [] (comp (filter other?) (map with-visits)) tab-items)
     :recent (into [] (filter other?) (sort-by (comp - #(or % 0) :time) history))
     :pool (into [] (filter other?) (concat (map with-visits tab-items) bookmarks history))}))

(defn rows
  "The rows for QUERY in MODE (\"omni\", \"edit\", \"tabs\", \"bookmarks\") from CANDS at time NOW."
  [cands mode query now {:keys [engines search-url]}]
  (let [blank? (str/blank? query)
        picks (:picks cands)]
    (case mode
      "tabs" (vec (take 20 (if blank? (:tabs cands) (rank/rank query (:tabs cands) now picks))))
      "bookmarks" (vec (take 20 (if blank? (:recent-bookmarks cands) (rank/rank query (:bookmarks cands) now picks))))
      (if blank?
        (->> (concat (take 4 (:open cands)) (take 40 (:recent cands)))
             rank/dedupe-urls (take max-rows) vec)
        (rank/omni-list query (typed-item query engines search-url)
                        (rank/rank query (:pool cands) now picks)
                        max-rows)))))

;; The view: what is typed (:query), the rows (:items) and the text they were ranked for
;; (:for), the highlighted row (:selected), the candidates of the latest read (:cands, from
;; read :gen; :stale once they replace the ones the rows came from), and :ended, :picked or
;; :closed, after which nothing the user sees changes until a :reset.

(def initial {:query "" :for nil :items [] :selected 0 :cands nil :stale false :gen 0 :ended nil})

(defn picked
  "The highlighted row."
  [{:keys [items selected]}]
  (get items selected))

(defn- show [{:keys [cands query for stale items selected] :as v} rows-fn]
  (cond
    (or (nil? cands) (and (= query for) (not stale))) v
    (not= query for) (assoc v :for query :stale false :selected 0 :items (rows-fn cands query))
    ;; Same text, more candidates: keep the highlight on the row it was on.
    :else (let [url (:url (get items selected))
                rows (rows-fn cands query)
                i (or (first (keep-indexed (fn [i r] (when (= url (:url r)) i)) rows)) 0)]
            (assoc v :stale false :items rows :selected (if (pos? selected) i 0)))))

(defn step
  "The view V after EVENT, a vector [kind & args]:
  [:input text]  the typed text changed
  [:reload]      a new read of the candidates starts
  [:cands gen c] candidates C from read GEN arrive (dropped unless GEN is the latest)
  [:show]        a frame: rank for the typed text, unless the rows show it already
  [:move d]      move the highlight D rows, wrapping
  [:select i]    highlight row I (a click or a number key)
  [:pick]        Enter: rank first if no frame showed the typed text yet, then freeze
  [:escape]      closed without a pick
  [:reset]       shown again: unfreeze with an empty query
  ROWS-FN ranks: (rows-fn cands text)."
  [v [kind a b] rows-fn]
  (if (and (:ended v) (not (#{:reset :reload :cands} kind)))
    v
    (case kind
      :input (assoc v :query a)
      :reload (update v :gen inc)
      :cands (if (= a (:gen v)) (assoc v :cands b :stale true) v)
      :show (if (:ended v) v (show v rows-fn))
      :move (let [n (count (:items v))]
              (if (pos? n) (update v :selected #(mod (+ % a) n)) v))
      :select (if (< -1 a (count (:items v))) (assoc v :selected a) v)
      :pick (-> (if (= (:query v) (:for v)) v (show v rows-fn)) (assoc :ended :picked))
      :escape (assoc v :ended :closed)
      :reset (-> v (assoc :ended nil :query "") (show rows-fn)))))
