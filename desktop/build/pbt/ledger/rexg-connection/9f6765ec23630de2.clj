(ns rexg.gens.connection
  "Generators for rexg.connection-test. Agents may rewrite this file to
  explore more of the control connection (desktop/build/pbt, bb try
  rexg-connection); the property and the simulated server stay in the test
  file."
  (:require [clojure.test.check.generators :as gen]))

(def gen-moves
  "What happens next, one move at a time: the client handles an event, the
  server answers or dies, a timer fires, the network drops, a late answer."
  (gen/vector (gen/frequency [[6 (gen/return :process)] [3 (gen/return :answer)] [2 (gen/return :fire)]
                              [1 (gen/return :die)] [1 (gen/return :answer-die)] [1 (gen/return :revive)] [1 (gen/return :hangup)]
                              [1 (gen/return :replay)]])
              0 80))

(def gen-case
  "[kind starts-work? moves]: the server the client talks to, whether starting
  a local one brings it up, and the moves."
  (gen/tuple (gen/elements [:local :rex-server :remote]) gen/boolean gen-moves))
