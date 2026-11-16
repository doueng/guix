(ns vimium-cljs.vomnibar-props-test
  "Properties of the Vomnibar view (vomnibar/step) over generated tabs, history, bookmarks,
  queries and orders of keys, frames and candidate reads."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer-macros [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [vimium-cljs.vomnibar :as v]))

(def now 1800000000000)
(def opts {:engines {"g" {:name "Google" :url "https://g/?q=%s"}} :search-url "https://s/?q="})
(def words ["hacker" "news" "notes" "clojure" "docs" "git" "hub" "mail" "vim" "tube"])
(def hosts ["news.example" "notes.example" "clojure.org" "github.com" "mail.example" "vim.org"])

(def gen-page
  (gen/let [title (gen/fmap #(str/join " " %) (gen/vector (gen/elements words) 0 3))
            host (gen/elements hosts)
            path (gen/elements ["" "a" "b/c" "docs" "x?y=1"])]
    {:title title :url (str "https://" host "/" path)}))

(def gen-cands
  "Candidates as the page prepares them from what Chrome reports."
  (gen/let [tabs (gen/vector gen-page 0 12)
            history (gen/vector gen-page 0 30)
            bookmarks (gen/vector gen-page 0 8)
            ages (gen/vector (gen/choose 0 (* 1000 60 60 24 90)) 50)
            visits (gen/vector (gen/choose 0 40) 50)]
    (let [tabs (vec (map-indexed (fn [i p] (assoc p :id (inc i) :windowId 1 :lastAccessed (- now (nth ages i)))) tabs))]
      (v/candidates
       {:tabs tabs
        :recency (reverse (map :id tabs))
        :cur (first tabs)
        :history (map-indexed (fn [i p] (assoc p :type "history" :time (- now (nth ages (mod i 50))) :visits (nth visits (mod i 50))))
                              history)
        :bookmarks (map-indexed (fn [i p] (assoc p :type "bookmark" :added i)) bookmarks)
        :picks {}
        :own "chrome-extension://me/"}))))

(def gen-query
  (gen/one-of [(gen/fmap str/join (gen/vector (gen/elements (seq "acdeghiklmnorstuvw .:/")) 0 8))
               (gen/elements (concat ["" "g clojure" "news.example/a"] words))]))

(def gen-mode (gen/elements ["omni" "edit" "tabs" "bookmarks"]))

(def gen-event
  "Events as the page sends them; candidates come from :latest or an :old read, by index."
  (gen/frequency
   [[8 (gen/fmap (fn [q] [:input q]) gen-query)]
    [6 (gen/return [:show])]
    [3 (gen/fmap (fn [[g i]] [:cands g i]) (gen/tuple (gen/elements [:latest :latest :old]) (gen/choose 0 1)))]
    [1 (gen/return [:reload])]
    [4 (gen/fmap (fn [d] [:move d]) (gen/elements [1 -1 3 -7]))]
    [1 (gen/fmap (fn [i] [:select i]) (gen/choose -1 25))]
    [1 (gen/return [:pick])]
    [1 (gen/return [:escape])]
    [1 (gen/return [:reset])]]))

(defn rows-fn [mode] (fn [c q] (v/rows c mode q now opts)))

(defn- resolve-event
  "EVENT with :cands naming a read and the candidates themselves."
  [state cand-sets [k a b :as e]]
  (if (= :cands k)
    [:cands (if (= a :latest) (:gen state) (dec (:gen state))) (nth cand-sets b)]
    e))

(defn run
  "Every [event view-before view-after] of EVENTS from the initial view."
  [mode cand-sets events]
  (let [rows (rows-fn mode)]
    (loop [s v/initial [e & more] events out []]
      (if-not e
        out
        (let [e (resolve-event s cand-sets e)
              s' (v/step s e rows)]
          (recur s' more (conj out [e s s'])))))))

(def gen-run (gen/tuple gen-mode (gen/vector gen-cands 2) (gen/vector gen-event 0 40)))

(def ^:private seen [:items :selected :for :query])

(defspec selection-stays-in-range 200
  (prop/for-all [[mode cs evs] gen-run]
    (every? (fn [[_ _ {:keys [items selected]}]]
              (if (empty? items) (zero? selected) (< -1 selected (count items))))
            (run mode cs evs))))

(defspec nothing-changes-after-a-pick-or-escape 200
  ;; The bug: on the new tab page a pick cleared the query and drew the unfiltered rows.
  (prop/for-all [[mode cs evs] gen-run]
    (every? (fn [[[k] s s']]
              (or (not (:ended s)) (= k :reset) (= (mapv s seen) (mapv s' seen))))
            (run mode cs evs))))

(defspec rows-are-always-for-their-text 200
  ;; Whatever the order of keys, frames and reads (old ones arriving late included), the
  ;; rows are the ranking of read candidates for :for, and after a pick :for is what was typed.
  (prop/for-all [[mode cs evs] gen-run]
    (let [rows (rows-fn mode)]
      (every? (fn [[[k] s {:keys [items for query ended] :as s'}]]
                (and (or (nil? for) (some #(= items (rows % for)) cs))
                     (or (not= k :pick) (:ended s) (nil? (:cands s')) (and (= ended :picked) (= for query)))))
              (run mode cs evs)))))

(defspec old-reads-never-replace-newer-ones 200
  (prop/for-all [[mode cs evs] gen-run]
    (every? (fn [[[k g] s s']]
              (or (= (:cands s) (:cands s')) (and (= k :cands) (= g (:gen s)))))
            (run mode cs evs))))

(defspec enter-opens-the-highlighted-row 200
  (prop/for-all [[mode cs evs] gen-run]
    (every? (fn [[[k] s s']]
              (or (not= k :pick) (:ended s) (not= (:query s) (:for s))
                  (and (= :picked (:ended s')) (= (v/picked s) (v/picked s')))))
            (run mode cs evs))))

(defspec escape-closes-without-a-pick 200
  (prop/for-all [[mode cs evs] gen-run]
    (every? (fn [[[k] s s']]
              (or (not= k :escape) (:ended s) (and (= :closed (:ended s')) (= (dissoc s :ended) (dissoc s' :ended)))))
            (run mode cs evs))))

(defspec a-frame-catches-up-with-the-typing 200
  ;; Unless a pick or Escape ended it, the next frame shows the latest read for the typed text.
  (prop/for-all [[mode cs evs] gen-run]
    (let [{:keys [cands query ended] :as s} (v/step (last (last (cons [nil nil v/initial] (run mode cs evs)))) [:show] (rows-fn mode))]
      (or ended (nil? cands)
          (and (= query (:for s)) (not (:stale s)) (= (:items s) ((rows-fn mode) cands query)))))))

(defspec ranking-is-deterministic-bounded-and-filtered 200
  (prop/for-all [mode gen-mode c gen-cands q gen-query]
    (let [rows ((rows-fn mode) c q)
          typed (v/typed-item q (:engines opts) (:search-url opts))]
      (and (= rows ((rows-fn mode) c q) (v/rows c mode q now opts))
           (<= (count rows) (if (#{"tabs" "bookmarks"} mode) 20 v/max-rows))
           (or (str/blank? q)
               (every? #(or (= % typed) (number? (:relevance %))) rows))))))

(deftest a-pick-freezes-the-rows
  ;; The new tab page cleared its query after a pick and drew the unfiltered rows.
  (let [c (v/candidates {:history [{:type "history" :title "Notes" :url "https://notes.example/" :time now :visits 3}
                                   {:type "history" :title "Mail" :url "https://mail.example/" :time now :visits 3}]})
        go #(reduce (fn [s e] (v/step s e (rows-fn "omni"))) %1 %2)
        picked (go v/initial [[:cands 0 c] [:input "notes"] [:show] [:pick]])
        later (go picked [[:input ""] [:show] [:cands 0 c] [:show] [:move 1]])]
    (is (= "https://notes.example/" (:url (v/picked picked))))
    (is (= ["notes" (:items picked) 0] [(:for later) (:items later) (:selected later)]))))
