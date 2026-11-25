(ns bbh.gens.pause
  "Generators for bbh.pause-mbt-test: action sequences in super.qnt's
  limits vocabulary on the Router (subject 0) and two Workers, with a clock
  the controller's timers read. Agents may rewrite this file (desktop/build/pbt,
  bb try bbh-pause); the driver and the rules stay in the test file."
  (:require [clojure.test.check.generators :as gen]))

(def subjects [0 1 2])

(def base-weights
  "The default mix: how often each action is chosen."
  {:msg 5 :limitHit 3 :clock 3 :timers 3 :resumeNow 1 :cancelPause 1 :dispatch 6 :take 6
   :cancel 1 :cancelDelivered 2 :finish 1})

(def action-gens
  "[name arg]: arg is a subject, a Worker, or for :clock how many 15 s
  steps pass (up to 25 minutes, past a probe)."
  (let [s (gen/elements subjects) w (gen/elements (rest subjects))]
    {:msg (gen/tuple (gen/return :msg) s)
     :limitHit (gen/tuple (gen/return :limitHit) s)
     :clock (gen/tuple (gen/return :clock) (gen/frequency [[3 (gen/choose 1 8)] [1 (gen/choose 60 100)]]))
     :timers (gen/return [:timers nil])
     :resumeNow (gen/tuple (gen/return :resumeNow) s)
     :cancelPause (gen/tuple (gen/return :cancelPause) s)
     :dispatch (gen/return [:dispatch nil])
     :take (gen/tuple (gen/return :take) s)
     :cancel (gen/tuple (gen/return :cancel) w)
     :cancelDelivered (gen/tuple (gen/return :cancelDelivered) w)
     :finish (gen/tuple (gen/return :finish) w)}))

(defn gen-action-with [weights]
  (gen/frequency (vec (for [[k n] weights :when (pos? n)] [n (action-gens k)]))))

(def gen-weights
  "The default mix, one where limits pile up on every session at once, or
  one where cancels and finishes race the pauses."
  (gen/frequency [[2 (gen/return base-weights)]
                  [1 (gen/return (assoc base-weights :limitHit 6 :clock 1 :timers 5))]
                  [1 (gen/return (assoc base-weights :cancel 3 :finish 2 :cancelDelivered 4))]]))

(def fragments
  "The cases the brief names: a limit during a tool call (a job exits while
  paused), a limit during a cancel, and a second limit right after resuming;
  and every session paused at once, so the timers must stagger."
  [[[:dispatch nil] [:take 1] [:limitHit 1] [:msg 1] [:dispatch nil] [:clock 70] [:timers nil] [:dispatch nil] [:take 1]]
   [[:limitHit 1] [:cancel 1] [:limitHit 1] [:dispatch nil] [:take 1] [:cancelDelivered 1] [:dispatch nil]]
   [[:limitHit 0] [:msg 0] [:clock 70] [:timers nil] [:limitHit 0] [:dispatch nil] [:clock 70] [:timers nil] [:dispatch nil] [:take 0]]
   [[:limitHit 0] [:limitHit 1] [:limitHit 2] [:clock 70] [:timers nil] [:timers nil] [:clock 1] [:timers nil] [:clock 3] [:timers nil] [:timers nil]]])

(defn gen-chunk [weights]
  (gen/frequency [[4 (gen/vector (gen-action-with weights) 2 14)]
                  [1 (gen/elements fragments)]]))

(def gen-input
  "{:actions}: the actions after the Router and two Workers start working."
  (gen/hash-map :actions (gen/bind gen-weights
                                   (fn [w] (gen/fmap #(vec (take 60 (apply concat %)))
                                                     (gen/vector (gen-chunk w) 0 8))))))
