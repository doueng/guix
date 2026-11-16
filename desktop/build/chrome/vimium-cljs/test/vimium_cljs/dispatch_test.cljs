(ns vimium-cljs.dispatch-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer-macros [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [vimium-cljs.commands :as commands]
            [vimium-cljs.config :as config]
            [vimium-cljs.dispatch :as d]
            [vimium-cljs.keys :as k]))

;; The key map the content script uses: Vimium's defaults with config/key-mappings over them.
(def mappings (k/effective-mappings (k/mappings-text config/key-mappings)))
(def trie (k/trie mappings))
(def ctx {:trie trie :pass-keys "" :enabled true})

(defn key-of
  "What the content script hands decide for a keydown EV."
  [ev]
  (let [ks (k/key-string ev (:mapkeys mappings) false)]
    {:ks ks :esc? (k/escape? ev ks (not (contains? trie "<c-[>"))) :text? (d/edits-text? ev)}))

(def gen-event
  "Keydowns as the browser reports them: characters, shifted ones, editing and named keys,
  a bare modifier, Ctrl/Alt/Meta chords, Escape and Ctrl+[."
  (gen/frequency
   [[6 (gen/fmap (fn [c] {:key (str c)}) gen/char-alphanumeric)]
    [2 (gen/fmap (fn [c] {:key (str c) :shift true}) (gen/elements "ABGJKVXYZ?:!{"))]
    [2 (gen/elements [{:key " "} {:key "Enter"} {:key "Backspace"} {:key "Delete"} {:key "Tab"}
                      {:key "ArrowLeft"} {:key "ArrowDown" :shift true} {:key "F5"} {:key "Shift" :shift true}])]
    [2 (gen/fmap (fn [[c m]] {:key (str c) m true}) (gen/tuple gen/char-alpha (gen/elements [:ctrl :alt :meta])))]
    [2 (gen/elements [{:key "Escape"} {:key "[" :ctrl true}])]]))

(def gen-focus (gen/elements [:page :field :catcher]))

(def modes [:normal :insert :hints :visual :marks-create :marks-goto :find :focus-input :pass-next])

(defn run
  "Feeds [focus event] pairs through decide from ST; returns [[state effect] ...]."
  [st steps]
  (rest (reductions (fn [[s _] [focus ev]]
                      (let [{:keys [state effect]} (d/decide ctx s focus (key-of ev))] [state effect]))
                    [st nil] steps)))

(def gen-state
  "A state reached by a random run from any mode, typing suspended or not."
  (gen/let [mode (gen/elements modes)
            suspended gen/boolean
            left (gen/choose 1 3)
            prefix (gen/vector (gen/tuple gen-focus gen-event) 0 6)]
    (let [st (assoc d/initial :mode mode :suspended suspended :left left)]
      (or (first (last (run st prefix))) st))))

(defspec typing-in-an-editor-is-never-taken 300
  (prop/for-all [st gen-state
                 focus (gen/elements [:field :catcher])
                 evs (gen/vector (gen/such-that #(not (:esc? (key-of %))) gen-event) 1 20)]
    (let [st (assoc st :mode :normal :suspended false)]
      (every? (fn [[_ effect]] (and (#{[:type] [:pass]} effect) (not (d/consume? effect))))
              (run st (map vector (repeat focus) evs))))))

(defspec escape-always-lands-in-normal-mode 500
  (prop/for-all [st gen-state
                 focus gen-focus
                 ev (gen/elements [{:key "Escape"} {:key "[" :ctrl true}])]
    (let [{:keys [state effect]} (d/decide ctx st focus (key-of ev))]
      (if (= :pass-next (:mode st))
        (= :pass-next (first effect))
        (and (= :normal (:mode state)) (k/reset-state? (:keys state))
             (case focus
               :field (or (= [:blur] effect) (not= :normal (:mode st)))
               :catcher (:suspended state)
               true))))))

(defspec escape-over-an-editor-stops-typing-without-blurring 300
  (prop/for-all [prefix (gen/vector (gen/such-that #(not (:esc? (key-of %))) gen-event) 0 10)]
    (let [steps (concat (map #(vector :catcher %) prefix) [[:catcher {:key "Escape"}]])
          [st effect] (last (run d/initial steps))]
      (and (= [:suspend] effect) (:suspended st) (d/consume? effect)))))

(defspec a-suspended-editor-never-gets-text 500
  (prop/for-all [st gen-state
                 evs (gen/vector (gen/such-that #(:text? (key-of %)) gen-event) 1 20)]
    (let [st (assoc st :mode :normal :suspended true)]
      (every? (fn [[_ effect]] (d/consume? effect))
              ;; i (focusInput) resumes typing, after which text belongs to the editor
              (take-while (fn [[_ effect]] (not= [:resume] effect))
                          (run st (map vector (repeat :catcher) evs)))))))

(defspec i-resumes-typing-and-the-editor-gets-keys-again 300
  (prop/for-all [evs (gen/vector (gen/such-that #(not (:esc? (key-of %))) gen-event) 1 15)]
    (let [st (assoc d/initial :suspended true)
          [[s1 e1] & more] (run st (map vector (repeat :catcher) (cons {:key "i"} evs)))]
      (and (= [:resume] e1) (not (:suspended s1))
           (every? (fn [[_ effect]] (#{[:type] [:pass]} effect)) more)))))

(defspec chords-pass-to-a-suspended-editor-unless-mapped 300
  (prop/for-all [c gen/char-alpha m (gen/elements [:ctrl :alt :meta])]
    (let [ev {:key (str c) m true}
          {:keys [effect]} (d/decide ctx (assoc d/initial :suspended true) :catcher (key-of ev))]
      (if (contains? trie (:ks (key-of ev)))
        (d/consume? effect)
        (= [:pass] effect)))))

(defspec a-hidden-frame-runs-frame-commands-at-the-top 200
  (prop/for-all [name (gen/elements (keys commands/by-name))]
    (let [info (commands/by-name name)
          where (d/command-frame name info false true)]
      (and (= (if (:bg info) :bg :here) (d/command-frame name info true false))
           (case where
             :bg (boolean (:bg info))
             :top (not (:bg info))
             ;; only commands about the keyboard itself stay in the frame that has it
             :here (boolean (or (#{"passNextKey" "enterInsertMode"} name) (re-find #"^LinkHints\." name))))))))

(deftest docs-scrolls-the-top-frame
  (is (= :top (d/command-frame "scrollDown" (commands/by-name "scrollDown") false true)))
  (is (= :here (d/command-frame "scrollDown" (commands/by-name "scrollDown") false false)) "a visible frame scrolls itself"))

(deftest examples
  (testing "on a page, mapped keys run commands and others pass"
    (is (= :command (first (:effect (d/decide ctx d/initial :page (key-of {:key "j"}))))))
    (is (= [:pass] (:effect (d/decide ctx d/initial :page (key-of {:key "q"}))))))
  (testing "a visible field: Escape blurs it, keys type"
    (is (= [:blur] (:effect (d/decide ctx d/initial :field (key-of {:key "Escape"})))))
    (is (= [:type] (:effect (d/decide ctx d/initial :field (key-of {:key "j"}))))))
  (testing "a suspended Docs editor scrolls on j and swallows q"
    (let [st (assoc d/initial :suspended true)]
      (is (= "scrollDown" (get-in (d/decide ctx st :catcher (key-of {:key "j"})) [:effect 1 :command])))
      (is (= [:swallow] (:effect (d/decide ctx st :catcher (key-of {:key "q"})))))
      (is (= [:pass] (:effect (d/decide ctx st :catcher (key-of {:key "ArrowDown"})))) "arrows move the caret")))
  (testing "pass keys of an exclusion still reach a suspended editor"
    (is (= [:pass] (:effect (d/decide (assoc ctx :pass-keys "j") (assoc d/initial :suspended true) :catcher (key-of {:key "j"}))))))
  (testing "an excluded page's editor is left alone"
    (is (= [:pass] (:effect (d/decide (assoc ctx :enabled false) d/initial :catcher (key-of {:key "Escape"}))))))
  (testing "passNextKey hands Escape to the page"
    (is (= [:pass-next 0] (:effect (d/decide ctx (assoc d/initial :mode :pass-next :left 1) :page (key-of {:key "Escape"}))))))
  (testing "what counts as text"
    (is (d/edits-text? {:key "a"}))
    (is (d/edits-text? {:key "Enter"}))
    (is (not (d/edits-text? {:key "a" :ctrl true})))
    (is (not (d/edits-text? {:key "ArrowLeft"})))))
