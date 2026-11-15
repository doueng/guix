(ns bbh.input-property-test
  "Random tiny inputs where text from voice, the phone and agents' REPLs
  becomes a command: a thread handle at the start of what was said or typed,
  a super-* helper call in any of the shapes it accepts (T120), and a string
  sent to Emacs and read back."
  (:require [bbh.props :as props]
            [bbh.super.emacs :as emacs]
            [bbh.super.model :as model]
            [bbh.super.verbs :as verbs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private units
  ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten" "eleven" "twelve"
   "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen" "nineteen"])

(def ^:private tens {2 "twenty" 3 "thirty" 4 "forty" 5 "fifty" 6 "sixty" 7 "seventy" 8 "eighty" 9 "ninety"})

(defn- below-100 [n]
  (cond (< n 20) [(units n)]
        (zero? (mod n 10)) [(tens (quot n 10))]
        :else [(tens (quot n 10)) (units (mod n 10))]))

(defn- spoken-forms
  "The ways a person or Hex says thread number N, as words: 170, 1 7 0,
  one hundred seventy, one hundred and seventy, one seventy."
  [n]
  (let [h (quot n 100) r (mod n 100)]
    (cond-> [[(str n)] (mapv str (str n))]
      (< n 100) (conj (below-100 n))
      (pos? h) (conj (into [(units h) "hundred"] (when (pos? r) (below-100 r))))
      (and (pos? h) (pos? r)) (conj (into [(units h) "hundred" "and"] (below-100 r)))
      (and (pos? h) (>= r 10)) (conj (into [(units h)] (below-100 r))))))

(def ^:private message
  "A message after a handle, with numbers and number words that must not join the handle."
  (gen/fmap #(str/join " " %)
            (gen/vector (gen/elements ["go" "on" "3" "files" "two" "options" "hundred" "and" "use" "plan" "B"
                                       "twenty" "T12" "send" "Ünïcode" "a,b"])
                        0 5)))

(deftest property-a-spoken-handle-names-its-thread-and-leaves-the-message
  (props/check "spoken handle" 300
               (prop/for-all [n (gen/choose 1 999)
                              form-i gen/nat
                              prefix (gen/elements ["tee" "tea" "Tee" "T" "t"])
                              sep (gen/elements [", " ". " ": " " - " ","])
                              msg message]
                 (let [forms (cond->> (spoken-forms n)
                               (#{"T" "t"} prefix) (remove #(and (next %) (every? (fn [w] (re-matches #"\d" w)) %))))
                       form (nth forms (mod form-i (count forms)))]
                   (= [(str "T" n) (str/trim msg)]
                      (model/parse-handle (str prefix " " (str/join " " form) sep msg)))))))

(deftest handles-people-have-said-or-typed
  (is (= ["T170" "go"] (model/parse-handle "tee one seventy, go")))
  (is (= ["T170" "go"] (model/parse-handle "tee one hundred and seventy: go")))
  (is (= ["T170" "go"] (model/parse-handle "tee 170, go")))
  (is (= ["T12" "3 files"] (model/parse-handle "tee twelve, 3 files")))
  (is (= ["T22" "go"] (model/parse-handle "tee twenty-two go")))
  (is (= ["T1" "two"] (model/parse-handle "tee one two")) "two single numbers are a handle and a word")
  (is (= ["T12" "use plan B"] (model/parse-handle "t12: use plan B")))
  (is (= ["T22" "go"] (model/parse-handle "Tee twenty two go")))
  (is (= ["T7" ""] (model/parse-handle "T seven")))
  (is (nil? (model/parse-handle "Make the page show cost per session")))
  (is (nil? (model/parse-handle "tea for two")) "a word that is not a number is not a handle")
  (is (nil? (model/parse-handle "tea time"))))

(deftest property-a-typed-handle-names-its-thread-and-any-text-parses
  (props/check "typed handle" 300
               (prop/for-all [n gen/nat
                              sep (gen/elements [" " ", " ": " " - " ". "])
                              msg gen/string-alphanumeric]
                 (= [(str "T" n) (str/trim msg)] (model/parse-handle (str "T" n sep msg)))))
  (props/check "any text" 300
               (prop/for-all [s (gen/one-of [gen/string
                                             (gen/fmap #(str "tee " %) gen/string-ascii)
                                             (gen/fmap #(apply str %) (gen/vector (gen/elements ["t" "tee" " " "," "-" "1" "9" "one" "hundred" "and" "twenty" "." "ü"]) 0 12))])]
                 (let [r (model/parse-handle s)]
                   (or (nil? r) (and (re-matches #"T\d+" (first r)) (string? (second r))))))))

(def ^:private verb-call
  "[table sym positional options form]: a verb, values for its arguments, a
  subset of its options with values, and how they are passed."
  (gen/bind (gen/elements (sort-by str (concat (for [s (keys verbs/router)] [:router s]) (for [s (keys verbs/worker)] [:worker s]))))
            (fn [[table sym]]
              (let [spec (get (if (= :router table) verbs/router verbs/worker) sym)]
                (gen/let [positional (gen/vector gen/string-alphanumeric (count (:args spec)))
                          options (gen/fmap #(into {} %) (gen/vector (gen/tuple (gen/elements (vec (sort (or (:options spec) [:none])))) gen/string-alphanumeric)))
                          form (gen/elements [:pairs :map :nil])]
                  [table sym positional (if (:options spec) options {}) form])))))

(defn- call
  "What SYM's verb in TABLE sends for ARGS, as [command args], or the error it refuses with."
  [table sym args]
  (let [sent (atom nil)
        f (get (verbs/bind (fn [command m] (reset! sent [command m]) :ok) (if (= :router table) verbs/router verbs/worker)) sym)]
    (try (apply f args) @sent
         (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))

(deftest property-each-helper-call-shape-sends-one-command
  (props/check "verb call shapes" 300
               (prop/for-all [[table sym positional options form] verb-call]
                 (let [spec (get (if (= :router table) verbs/router verbs/worker) sym)
                       pass (fn [opts] (case form
                                         :pairs (concat positional (apply concat opts))
                                         :map (concat positional [opts])
                                         :nil (concat positional (when (seq opts) [opts]))))
                       expected [(:command spec) (merge options (zipmap (:args spec) positional) (:fixed spec))]]
                   (if (:map spec)
                     (= [(:command spec) (merge options (:fixed spec))] (call table sym [options]))
                     (and (= expected (call table sym (pass options)))
                          (= :invalid (call table sym (pass (assoc options :no-such-option "x"))))
                          (or (empty? positional) (= :invalid (call table sym (butlast positional))))))))))

(deftest property-a-string-sent-to-emacs-reads-back-the-same
  (props/check "elisp string" 500
               (prop/for-all [s (gen/one-of [gen/string (gen/fmap #(apply str %) (gen/vector (gen/elements ["\\" "\"" "a" "\n" "ü" " "]) 0 12))])]
                 (= s (emacs/read-string* (emacs/elisp s))))))
