(ns pbt.fixture
  "Code for pbt.harness-test to instrument: each kind of branch the
  coverage rewriting counts, with recur in tail positions.")

(defmulti shape (fn [x] (if (vector? x) :vec :other)))
(defmethod shape :vec [x] (count x))
(defmethod shape :other [_] -1)

(defn classify [n]
  (cond (neg? n) :neg
        (zero? n) :zero
        (< n 10) (case n 1 :one 2 :two :small)
        :else (when (even? n) :big-even)))

(defn sum-to [n]
  (loop [i 0 acc 0]
    (if (> i n) acc (recur (inc i) (+ acc i)))))

(defn flags [m]
  (cond-> []
    (and (:a m) (:b m)) (conj :ab)
    (or (:c m) (:d m)) (conj :cd)
    (when-let [e (:e m)] (pos? e)) (conj :e)))

(defn twice [f] #(f (f %)))
