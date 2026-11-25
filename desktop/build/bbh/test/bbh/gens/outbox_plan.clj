(ns bbh.gens.outbox-plan
  "Generators for bbh.outbox-plan-property-test. Agents may rewrite this file
  to explore more of model/outbox-plan (desktop/build/pbt, bb try
  outbox-plan); the property and what it checks stay in the test file."
  (:require [clojure.test.check.generators :as gen]))

(def gen-row
  (gen/hash-map :state (gen/elements [:pending :pending :sent :done :dropped])
                :conversation (gen/elements ["router" "c1" "c2" "c3"])
                :delivery (gen/elements [nil nil :summary :steer :cancel])
                ;; A :sent row whose file the session has not taken yet.
                :file? gen/boolean))

(def gen-round
  "One round: a pass that crashes after CRASH effects (nil: it completes),
  then the sessions that scan their mailbox (one flag per conversation)."
  ;; A send is followed by its :outbox-sent! command, so an odd count often
  ;; crashes between the two: the resend-after-crash path.
  (gen/hash-map :crash (gen/frequency [[2 (gen/return nil)] [1 (gen/choose 0 6)] [2 (gen/elements [1 3 5 7])]])
                :scans (gen/vector gen/boolean 4)))

(def gen-case
  "{:rows :workers :rounds}: the outbox, each conversation's Worker state and
  the passes run on them."
  (gen/hash-map
   :rows (gen/vector gen-row 0 8)
   :workers (gen/hash-map "c1" (gen/elements [:working :done :failed])
                          "c2" (gen/elements [:working :cancelled])
                          "c3" (gen/elements [:working :working :done]))
   :rounds (gen/vector gen-round 2 7)))
