(ns rexmux.proto-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [rexmux.props :refer [trials]]
            [rexmux.proto :as proto]))

(def gen-text (gen/fmap #(apply str %) (gen/vector (gen/elements [\a \" \newline \\ \space \é \{ \]]) 0 12)))

(def gen-msg
  (gen/let [op (gen/elements (vec proto/rust->bb))
            id gen/nat
            text gen-text
            args (gen/map gen/keyword (gen/one-of [gen/small-integer gen-text gen/boolean]) {:max-elements 4})]
    {:op op :id id :text text :args args}))

(deftest encode-is-one-line-and-round-trips
  (is (:pass? (tc/quick-check (trials 300)
                              (prop/for-all [m gen-msg]
                                (let [line (proto/encode m)]
                                  (and (not (re-find #"\n" line)) (= m (proto/decode line)))))))))

(deftest decode-refuses-what-is-not-a-message
  (is (= :invalid (:op (proto/decode "[1 2]"))))
  (is (= :invalid (:op (proto/decode "{:op"))))
  (is (= :invalid (:op (proto/decode "{:no-op 1}")))))

(defn- run-link
  "Send N stamped messages through a channel that may drop, repeat or swap
  neighbours (ACTIONS), and return what the receiver did."
  [n actions]
  (let [sent (second (reduce (fn [[s acc] i] (let [[s m] (proto/stamp s {:op :ping :id i})] [s (conj acc m)])) [proto/sender []] (range n)))
        wire (reduce (fn [w [kind i]]
                       (let [i (mod i (max 1 (count w)))]
                         (case kind
                           :drop (if (seq w) (vec (concat (subvec w 0 i) (subvec w (inc i)))) w)
                           :dup (if (seq w) (vec (concat (subvec w 0 (inc i)) [(w i)] (subvec w (inc i)))) w)
                           :swap (if (< (inc i) (count w)) (assoc w i (w (inc i)) (inc i) (w i)) w))))
                     sent actions)]
    (reduce (fn [acc m]
              (let [[r v] (proto/accept (:r acc) m)]
                (cond-> (assoc acc :r r)
                  (= :deliver v) (update :delivered conj (:seq m))
                  (map? v) (-> (update :delivered conj (:seq m)) (update :gaps conj (:gap v))))))
            {:r proto/receiver :delivered [] :gaps []} wire)))

(deftest no-duplicates-and-no-silent-loss
  (is (:pass? (tc/quick-check
               (trials 500)
               (prop/for-all [n (gen/choose 0 30)
                              actions (gen/vector (gen/tuple (gen/elements [:drop :dup :swap]) gen/nat) 0 6)]
                 (let [{:keys [delivered gaps]} (run-link n actions)
                       missing (remove (set delivered) (range 1 (inc (if (seq delivered) (apply max delivered) 0))))
                       reported (set (mapcat (fn [[a b]] (range a (inc b))) gaps))]
                   (and (apply distinct? 0 delivered)          ; never twice
                        (= delivered (sort delivered))          ; in order
                        (every? reported missing))))))))       ; every hole is reported

(deftest a-clean-link-delivers-everything-in-order
  (let [{:keys [delivered gaps]} (run-link 10 [])]
    (is (= (range 1 11) delivered))
    (is (empty? gaps))))

(deftest exactly-one-reply-per-request
  (let [p (-> {} (proto/await-reply 1) (proto/await-reply 2))
        [p v1] (proto/settle p 1)
        [p v2] (proto/settle p 1)
        [_ v3] (proto/settle p 9)]
    (is (= [:ok :unexpected :unexpected] [v1 v2 v3]))
    (is (= {2 :pending} p))))
