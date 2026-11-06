(ns bbh.doc-test
  "doc/super-state-machine.html cites the function behind every transition, as
  `file › name, name; file › name`. Each cited name must still be defined, so
  the page cannot quietly describe code that is gone."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private bbh (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))
(def ^:private repo (str (fs/parent (fs/parent (fs/parent bbh)))))

(defn- path
  "The file a citation's short name means, as the page's own key says."
  [file]
  (get {"agent-shell.el" (str repo "/desktop/configs/doom/agent-shell.el")
        "init.clj" (str repo "/desktop/configs/bbh/init.clj")
        "bbh-web.scm" (str repo "/modules/engstrand/features/bbh-web.scm")
        "model.clj" (str bbh "/src/bbh/super/model.clj")
        "client.clj" (str bbh "/src/bbh/super/client.clj")
        "store.clj" (str bbh "/src/bbh/super/store.clj")}
       file (str bbh "/src/bbh/" file)))

(defn citations
  "[file name] for every name the page HTML cites. A name that is only a
  dispatch value, as `:handback!` after `decide* :takeover!`, takes its
  method from the name before it, and a parenthesized remark is dropped."
  [html]
  (distinct
   (for [[_ inner] (re-seq #"<span class='cite'>((?:(?!</span></span>).)*)</span>" html)
         :let [text (-> inner (str/replace #"<[^>]+>" "") (str/replace "&gt;" ">") (str/replace "&lt;" "<"))]
         group (str/split text #";")
         :let [[head & more] (map str/trim (str/split (str/trim group) #","))
               [file first-name] (map str/trim (str/split head #"›" 2))]
         :when first-name
         n (reductions (fn [prev n] (if (str/starts-with? n ":") (str (first (str/split prev #" ")) " " n) n))
                       first-name more)]
     [file (str/trim (str/replace n #"\(.*\)" ""))])))

(defn defined?
  "Whether SRC defines NAME: a def form of it, a defmethod for `multi :value`,
  or for `handle :x`, a definition of handle whose file mentions :x."
  [src n]
  (let [[_ head dispatch] (re-matches #"(\S+) (:\S+)" n)
        q #(java.util.regex.Pattern/quote %)
        def-of #(re-pattern (str "\\((?:defn-?|def|defonce|defmacro|defmulti|defun|defvar|defcustom|defvar-local|define|define-public)\\s+(?:\\^\\S+\\s+)*\\(?" (q %) "[\\s)]"))]
    (boolean (cond (and head (str/ends-with? head "*")) (re-find (re-pattern (str "\\(defmethod " (q head) " " (q dispatch) "[\\s\\]]")) src)
                   head (and (re-find (def-of head) src) (str/includes? src dispatch))
                   :else (re-find (def-of n) src)))))

(deftest the-checker-tells-a-defined-name-from-a-gone-one
  (let [src "(defn- ^:private retire! [x])\n(defmethod decide* :ask! [s c])\n(defn handle [c r] (case r :sweep! 1))"]
    (is (= [true true true false false]
           (mapv #(defined? src %) ["retire!" "decide* :ask!" "handle :sweep!" "decide* :link!" "triage!"]))))
  (is (= [["model.clj" "decide* :takeover!"] ["model.clj" "decide* :handback!"] ["super.clj" "reconcile!"]]
         (citations "<span class='cite'><span class='nw'>model.clj › decide* :takeover!</span>, <span class='nw'>:handback!</span>; <span class='nw'>super.clj › reconcile! (cancelling branch)</span></span>"))))

(deftest every-function-the-state-machine-page-cites-exists
  (let [cites (citations (slurp (str bbh "/doc/super-state-machine.html")))]
    (is (< 50 (count cites)) "the page still cites its sources")
    (is (= [] (vec (for [[file n] cites
                         :let [p (path file)]
                         :when (not (and (fs/exists? p) (defined? (slurp p) n)))]
                     (str file " › " n)))))))
