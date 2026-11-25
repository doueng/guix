(ns rexg.gens.connect-actions
  "Generators for rexg.connect-mbt-test: action sequences in rexmux.qnt's
  connect vocabulary. Agents may rewrite this file (desktop/build/pbt, bb try
  rexg-connect-mbt); the driver and the spec's rules stay in the test file."
  (:require [clojure.test.check.generators :as gen]))

(def base-weights
  "The default mix: how often each action is chosen."
  {:tryConnect 4 :answer 4 :late 1 :deliver 5 :startLocal 2 :timer 2
   :hangup 1 :serverStops 1 :serverStarts 1})

(def action-gens
  "[name arg]: arg picks an attempt (answer), a queued answer (deliver), or
  is a flag (startLocal ok, hangup dies)."
  {:tryConnect (gen/return [:tryConnect nil])
   :answer (gen/tuple (gen/return :answer) (gen/choose 0 3))
   :late (gen/return [:late nil])
   :deliver (gen/tuple (gen/return :deliver) (gen/choose 0 5))
   :startLocal (gen/tuple (gen/return :startLocal) gen/boolean)
   :timer (gen/return [:timer nil])
   :hangup (gen/tuple (gen/return :hangup) gen/boolean)
   :serverStops (gen/return [:serverStops nil])
   :serverStarts (gen/return [:serverStarts nil])})

(defn gen-action-with
  "An action drawn with WEIGHTS (name -> weight, 0 drops it)."
  [weights]
  (gen/frequency (vec (for [[k w] weights :when (pos? w)] [w (action-gens k)]))))

(def gen-action (gen-action-with base-weights))

(def gen-weights
  "A per-input mix: the default, or one that holds deliveries back so
  answers and sockets pile up, or one that favours server losses and late
  answers so stale ones race the live connection."
  (gen/frequency
   [[2 (gen/return base-weights)]
    [1 (gen/return (assoc base-weights :deliver 1 :answer 5 :late 2))]
    [1 (gen/return (assoc base-weights :hangup 3 :serverStops 3 :serverStarts 3 :late 2))]]))

(def gen-input
  "{:up :local :actions}: Quint's init choices and the actions after."
  (gen/hash-map :up gen/boolean :local gen/boolean
                :actions (gen/bind gen-weights #(gen/vector (gen-action-with %) 0 60))))
