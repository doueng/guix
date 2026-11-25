(ns rexg.gens.replies
  "Generators for rexg.replies-test: what happens around a popped-out pane,
  as event ids the test turns into bytes. Agents may rewrite this file
  (desktop/build/pbt, bb try rexg-replies); the encodings, the model and the
  properties stay in the test file."
  (:require [clojure.test.check.generators :as gen]))

(def kinds
  "The questions a program asks (rexg.replies-test/questions)."
  [:da1 :da2 :da3 :dsr :cpr :decxcpr :decrqm :osc11 :osc4 :xtversion :kitty :decrqss :winops :xtgettcap :scheme])

(def keys*
  "What a person types (rexg.replies-test/keys): text, Esc, arrows, a paste,
  and Shift+F3, which is CSI 1;2R like a cursor report."
  [:a :hello :esc :up :f1 :paste :utf8 :kitty-enter :shift-f3])

(def gen-event
  "[:query k cut?] the program asks (its bytes cut in two when cut?), [:answer
  k] Ghostty answers, [:key id], [:answer+key k id] both in one read, [:out]
  other output, [:wait ms]."
  (gen/frequency
   [[4 (gen/fmap (fn [[k c]] [:query k c]) (gen/tuple (gen/elements kinds) gen/boolean))]
    [3 (gen/fmap (fn [k] [:answer k]) (gen/elements kinds))]
    [5 (gen/fmap (fn [k] [:key k]) (gen/elements keys*))]
    [1 (gen/fmap (fn [[k id]] [:answer+key k id]) (gen/tuple (gen/elements kinds) (gen/elements keys*)))]
    [2 (gen/return [:out])]
    [1 (gen/fmap (fn [ms] [:wait ms]) (gen/elements [5 500 2500]))]]))

(def gen-answered
  "A question and Ghostty's answer right after it, as it happens."
  (gen/fmap (fn [[k c]] [[:query k c] [:answer k]]) (gen/tuple (gen/elements kinds) gen/boolean)))

(def gen-input
  "{:events [...]}: mostly questions Ghostty answers at once, with keys,
  output, waits and stray events between."
  (gen/hash-map :events (gen/fmap #(vec (take 60 (apply concat %)))
                                  (gen/vector (gen/one-of [gen-answered (gen/fmap vector gen-event)]) 0 30))))
