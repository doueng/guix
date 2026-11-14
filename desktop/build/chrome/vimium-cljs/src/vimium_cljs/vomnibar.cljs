;; What the Vomnibar lists: pure functions over the candidates the Vomnibar page reads once
;; when it opens (open tabs, history, bookmarks, the user's picks, tab recency). `candidates`
;; does the per-open work (folding, frecency inputs, dropping the page you are on), so each
;; keystroke only ranks.
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
        ;; A tab's frecency is its page's: visits from history, the later of the two times.
        with-visits (fn [t] (if-let [h (visited (rank/item-key t))]
                              (assoc t :visits (:visits h) :typed (:typed h) :time (max (or (:time t) 0) (or (:time h) 0)))
                              t))
        ;; Never offer the page you are on.
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
