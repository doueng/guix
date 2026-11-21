(ns bbh.voice-property-test
  "voice/resolve-target and voice/interpret over generated utterances, threads and
  Inbox selections. Voice never approves: no utterance yields a pick on an
  :action or :review ask, nor any effect when it says approve, and a pick
  always names an option the ask has."
  (:require [bbh.props :as props]
            [bbh.super.voice :as voice]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private phrases
  ["approve" "approved" "i approve" "I approve it" "Approve, send" "option one" "option two" "Option 3"
   "pick 1" "pick four" "option 12" "the recommended one" "recommended" "Yes, for a week" "No" "send"
   "and send" ", send." "don't approve until CI passes" "look at the logs" "" "tee one" "T1" "T2" "T9"])

(def ^:private gen-text
  (gen/fmap #(str/join " " %) (gen/vector (gen/elements phrases) 1 4)))

(defn- gen-ask
  "An ask numbered I: its id is a1, a2 ..., unique in a thread as bbh makes them."
  [i]
  (gen/let [kind (gen/elements [:question :question :action :review])
            n (gen/choose 0 3)
            rec (gen/choose 0 3)
            state (gen/elements [:open :open :answered])]
    (let [options (vec (take n [{:id "y" :label "Yes, for a week"} {:id "n" :label "No"} {:id "m" :label "Maybe"}]))]
      {:id (str "a" i) :seq i :kind kind :state state :options options
       :recommended (:id (get options rec))})))

(def ^:private gen-asks
  (gen/bind (gen/choose 0 3) (fn [n] (apply gen/tuple (map gen-ask (range 1 (inc n)))))))

(def ^:private gen-case
  (gen/hash-map
   :text gen-text
   :view (gen/one-of [(gen/return nil)
                      (gen/hash-map :asks (gen/fmap vec gen-asks)
                                    :draft (gen/one-of [(gen/return nil) (gen/return {:picks {"a1" "y"}})]))])
   :selected (gen/hash-map :thread (gen/elements ["T1" "T2" nil]) :ask (gen/elements ["a1" "a2" nil])
                           :at (gen/elements [0 1000 100000]))
   :mode (gen/elements [:router :selected])))

(defn- problems [{:keys [text view selected mode]}]
  (let [tgt (voice/resolve-target text {:selected selected :now 30000 :target mode})
        view (when (:thread tgt) view)
        out (voice/interpret tgt view selected)
        asks (into {} (map (juxt :id identity) (:asks view)))
        picks (filter #(= :pick (first %)) (:do out))]
    (cond-> []
      (and (:refuse out) (:do out)) (conj [:refused-and-acted out])
      (and (:thread tgt) (re-matches #"(?:i )?approved?(?: .*)?" (:words tgt)) (seq (:do out)))
      (conj [:approve-said-and-acted tgt out])
      (some (fn [[_ _ _ a]] (#{:action :review} (:kind (asks a)))) picks) (conj [:picked-an-action-or-review-ask out])
      (some (fn [[_ _ n a]] (not (<= 1 n (count (:options (asks a)))))) picks) (conj [:picked-no-such-option out])
      (some (fn [[_ t]] (not= t (:thread tgt))) picks) (conj [:picked-on-another-thread out])
      (and (not (:thread tgt)) (not= [[:say (:text tgt)]] (:do out))) (conj [:no-thread-not-to-router tgt out])
      (and (= :router mode) (not (:handle tgt)) (:thread tgt)) (conj [:the-cursor-redirected-words tgt]))))

(deftest property-voice-never-approves
  (props/check "voice interpret" 500
               (prop/for-all [c gen-case] (empty? (problems c)))
               :explain problems))

(deftest a-draft-with-an-action-or-review-pick-needs-the-keyboard
  (is (= "Action asks require keyboard confirmation"
         (voice/send-refusal {:draft {:picks {"a1" "y"}} :asks [{:id "a1" :kind :action}]})))
  (is (= "Review asks require keyboard confirmation"
         (voice/send-refusal {:draft {:picks {"a1" "y"}} :asks [{:id "a1" :kind :review}]}))
      "voice may not send a review pick a keyboard drafted: sending it would approve")
  (is (nil? (voice/send-refusal {:draft {:picks {"a2" "y"}} :asks [{:id "a1" :kind :review} {:id "a2" :kind :question}]}))
      "an open review ask the draft does not pick is no reason")
  (is (= "No draft to send" (voice/send-refusal {:draft nil :asks []})))
  (is (nil? (voice/send-refusal {:draft {:picks {"a1" "y"}} :asks [{:id "a1" :kind :question}]}))))
