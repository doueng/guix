(ns rexg.gens.connect-actions
  "Generators for rexg.connect-mbt-test: action sequences in rexmux.qnt's
  connect vocabulary. Agents may rewrite this file (desktop/build/pbt, bb try
  rexg-connect-mbt); the driver and the spec's rules stay in the test file."
  (:require [clojure.test.check.generators :as gen]))

(def gen-action
  "[name arg]: arg picks an attempt (answer), a queued answer (deliver), or
  is a flag (startLocal ok, hangup dies)."
  (gen/frequency [[4 (gen/return [:tryConnect nil])]
                  [4 (gen/tuple (gen/return :answer) (gen/choose 0 3))]
                  [1 (gen/return [:late nil])]
                  [5 (gen/tuple (gen/return :deliver) (gen/choose 0 5))]
                  [2 (gen/tuple (gen/return :startLocal) gen/boolean)]
                  [2 (gen/return [:timer nil])]
                  [1 (gen/tuple (gen/return :hangup) gen/boolean)]
                  [1 (gen/return [:serverStops nil])]
                  [1 (gen/return [:serverStarts nil])]]))

(def gen-input
  "{:up :local :actions}: Quint's init choices and the actions after."
  (gen/hash-map :up gen/boolean :local gen/boolean :actions (gen/vector gen-action 0 60)))
