(ns rexmux.sidecar-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [rexmux.props :refer [trials]]
            [rexmux.proto :as proto]
            [rexmux.sidecar :as sc]))

(def help (json/parse-string (slurp "test/fixtures/rex-api-rev18.json") true))
(def events (map #(json/parse-string % true) (remove str/blank? (str/split-lines (slurp "test/fixtures/events-blocked.ndjson")))))
(def io {:load-help (constantly help)
         :rex {:sessions (constantly [{:session_id "session:1" :label "work"}])
               :capture (constantly "line")}})
(def ctx {:session-id "session:1" :block-id "block:9"})

(defn run
  "Feed MSGS (already numbered) to a fresh sidecar; [state outgoing]."
  [msgs]
  (reduce (fn [[s out] m] (let [[s o] (if (= :rex/event (:op m)) (sc/deliver s io m) (sc/handle s io m))] [s (into out o)]))
          [(sc/initial-state sc/default-config) []] msgs))

(defn numbered [msgs] (map-indexed (fn [i m] (assoc m :seq (inc i))) msgs))

(deftest hello-sends-the-catalogue-and-keymap
  (let [[_ out] (run (numbered [{:op :hello :client-id "client:1" :ctx ctx}]))
        cat (first (filter #(= :commands/changed (:op %)) out))]
    (is (< 90 (count (:commands cat))))
    (is (= "mx" (get-in cat [:keymap :name])))
    (is (= [] (:conflicts cat)))
    (is (every? #(proto/op-ok? :to-rust (:op %)) out))))

(deftest invoke-replies-once-with-its-id
  (let [[_ out] (run (numbered [{:op :hello :ctx ctx}
                                {:op :commands/invoke :id 7 :command :rex/session.focus_direction :args {:direction "left"}}
                                {:op :commands/invoke :id 8 :command :session/switch :args {}}]))
        replies (filter #(= :reply (:op %)) out)]
    (is (= [7 8] (map :id replies)))
    (is (= [[:rex/call "session.focus_direction" {:session_id "session:1" :direction "left"}]]
           (:effects (first replies))))
    (is (= :ui/pick (ffirst (:effects (second replies)))))))

(deftest a-blocked-agent-event-reaches-the-window-and-next-blocked
  (let [[_ out] (run (concat (numbered [{:op :hello :ctx {:session-id "s"}}])
                             (for [e events] {:op :rex/event :event e :server "local"})
                             [{:op :commands/invoke :seq 2 :id 3 :command :agent/next-blocked}]))
        agents (last (filter #(= :agents (:op %)) out))
        reply (first (filter #(= :reply (:op %)) out))]
    (is (= ["blocked"] (map :state (:rows agents))))
    (is (= :focus/block (ffirst (:effects reply))))
    (is (= "local" (:server (second (first (:effects reply))))))))

(deftest hello-reads-the-agents-rex-already-has
  ;; A sidecar that restarts must agree with Rex before it reports (TLA SidecarAgrees).
  (let [io (assoc io :resync (constantly [{:session "s" :block "b7" :server "local" :record {:state "blocked" :app "pi"}}]))
        [_ out] (reduce (fn [[s o] m] (let [[s o2] (sc/handle s io m)] [s (into o o2)]))
                        [(sc/initial-state sc/default-config) []] (numbered [{:op :hello :ctx ctx}]))
        agents (first (filter #(= :agents (:op %)) out))]
    (is (= ["b7"] (:attention agents)))))

(deftest repeats-are-dropped-and-gaps-reported
  (let [[_ out] (run [{:op :hello :seq 1 :ctx ctx} {:op :ping :seq 2 :id 1} {:op :ping :seq 2 :id 1} {:op :ping :seq 5 :id 2}])]
    (is (= [1 2] (map :id (filter #(= :pong (:op %)) out))))
    (is (some #(re-find #"missed messages \[3 4\]" (str (:text %))) out))))

(defn- window-msgs [ops]
  (cons {:op :hello :ctx ctx}
        (map-indexed (fn [i op]
                       (case op
                         :ping {:op :ping :id i}
                         :context {:op :context :ctx (assoc ctx :block-id (str "block:" (mod i 3)))}
                         :invoke {:op :commands/invoke :id i :command :agent/next-blocked}
                         :event {:op :rex/event :event (nth events (mod i (count events))) :server "local"}))
                     ops)))

(defn- number-window [msgs]
  (second (reduce (fn [[n acc] m] (if (= :rex/event (:op m)) [n (conj acc m)] [(inc n) (conj acc (assoc m :seq n))])) [1 []] msgs)))

(deftest outgoing-numbering-has-no-holes-or-repeats
  (is (:pass? (tc/quick-check
               (trials 100)
               (prop/for-all [ops (gen/vector (gen/elements [:ping :context :invoke :event]) 0 25)]
                 (let [msgs (number-window (window-msgs ops))
                       [_ out] (run msgs)
                       invokes (keep #(when (= :commands/invoke (:op %)) (:id %)) msgs)]
                   (and (= (map :seq out) (range 1 (inc (count out))))
                        (= invokes (keep #(when (= :reply (:op %)) (:id %)) out)))))))))
