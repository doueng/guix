(ns rexg.gens.connection
  "Generators for rexg.connection-test. Agents may rewrite this file to
  explore more of the control connection (desktop/build/pbt, bb try
  rexg-connection); the property and the simulated server stay in the test
  file."
  (:require [clojure.test.check.generators :as gen]))

(def gen-move
  "One move: the client handles an event, the server answers or dies, a
  timer fires, the network drops, a late answer."
  (gen/frequency [[6 (gen/return :process)] [3 (gen/return :answer)] [2 (gen/return :fire)]
                  [1 (gen/return :die)] [1 (gen/return :answer-die)] [1 (gen/return :revive)] [1 (gen/return :hangup)]
                  [1 (gen/return :replay)]]))

(def gen-burst
  "A move once, or a burst of the same move: late answers that arrive
  together and pile up in the client's queue before it handles them."
  (gen/bind gen-move
            (fn [mv] (gen/fmap #(repeat % mv) (gen/frequency [[8 (gen/return 1)] [1 (gen/choose 2 4)]])))))

(def gen-moves
  "What happens next, a move or a burst at a time, at most 80 moves."
  (gen/fmap #(vec (take 80 (apply concat %))) (gen/vector gen-burst 0 80)))

(def gen-case
  "[kind starts-work? moves]: the server the client talks to, whether starting
  a local one brings it up, and the moves."
  (gen/tuple (gen/elements [:local :rex-server :remote]) gen/boolean gen-moves))
