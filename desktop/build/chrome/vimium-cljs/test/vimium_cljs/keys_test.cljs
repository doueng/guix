(ns vimium-cljs.keys-test
  (:require [cljs.test :refer [deftest is testing]]
            [vimium-cljs.keys :as k]))

(defn- ev [key & {:as m}] (merge {:key key} m))

(deftest key-strings
  (is (= "a" (k/key-string (ev "a"))))
  (is (= "A" (k/key-string (ev "A" :shift true))))
  (is (= "<c-o>" (k/key-string (ev "o" :ctrl true))))
  (is (= "<a-f>" (k/key-string (ev "f" :alt true))))
  (is (= "<c-A>" (k/key-string (ev "A" :ctrl true :shift true))) "shift folds into the letter")
  (is (= "<s-left>" (k/key-string (ev "ArrowLeft" :shift true))))
  (is (= "<space>" (k/key-string (ev " "))))
  (is (= "<f12>" (k/key-string (ev "F12"))))
  (is (nil? (k/key-string (ev "Shift" :shift true))) "a bare modifier is no key")
  (is (= "?" (k/key-string (ev "?" :shift true))))
  (testing "mapkey translates"
    (is (= "j" (k/key-string (ev "n") {"n" "j"} false))))
  (testing "ignoreKeyboardLayout reads the physical key"
    (is (= "f" (k/key-string (ev "u" :code "KeyF") {} true)))
    (is (= "?" (k/key-string (ev "_" :code "Slash" :shift true) {} true)))))

(deftest escapes
  (is (k/escape? (ev "Escape") "<escape>" true))
  (is (not (k/escape? (ev "Escape" :key-code 229) "<escape>" true)) "IME escape")
  (is (k/escape? (ev "[" :ctrl true) "<c-[>" true))
  (is (not (k/escape? (ev "[" :ctrl true) "<c-[>" false))))

(deftest key-sequences
  (is (= ["g" "g"] (k/parse-key-sequence "gg")))
  (is (= ["<c-o>"] (k/parse-key-sequence "<c-o>")))
  (is (= ["<c-A>" "b"] (k/parse-key-sequence "<C-A>b")))
  (is (= ["<a-c-x>"] (k/parse-key-sequence "<c-a-x>")) "modifiers sorted")
  (is (= ["<left>"] (k/parse-key-sequence "<Left>")))
  (is (= ["<space>" "x"] (k/parse-key-sequence "<Space>x")))
  (is (= ["<" "<"] (k/parse-key-sequence "<<")) "<< is two keys"))

(deftest config-entries-as-text
  (is (= "map j scrollDown count=3\nmap r reload hard\nunmap J"
         (k/mappings-text [["j" "scrollDown" {"count" 3}] ["r" "reload" {:hard true}] ["J" nil]]))))

(deftest options
  (is (= {} (k/parse-options "")))
  (is (= {"hard" true} (k/parse-options "hard")))
  (is (= {"count" 3 "x" "a b"} (k/parse-options "count=3 x=\"a b\"")))
  (is (= {} (k/parse-options "count=zz")) "a non-numeric count is dropped"))

;; The user's mapping text, as in their Vimium export.
(def user-text "# Insert your preferred key mappings here.\nmap i focusInput\nmap w previousTab\nmap e nextTab\nunmap J\nunmap K\nmap h goBack\nmap l goForward\nmap p Vomnibar.activateTabSelection\nmap <c-o> visitPreviousTab\nunmap N")

(deftest user-mappings
  (let [m (k/effective-mappings user-text)
        cmd #(:command ((:entries m) %))]
    (is (= [] (:errors m)))
    (is (= "focusInput" (cmd "i")))
    (is (= "previousTab" (cmd "w")))
    (is (= "nextTab" (cmd "e")))
    (is (= "goBack" (cmd "h")))
    (is (= "goForward" (cmd "l")))
    (is (= "Vomnibar.activateTabSelection" (cmd "p")))
    (is (= "visitPreviousTab" (cmd "<c-o>")))
    (is (nil? (cmd "J")))
    (is (nil? (cmd "K")))
    (is (nil? (cmd "N")))
    (is (= "scrollDown" (cmd "j")) "defaults stay")
    (is (= "reload" (cmd "R")))
    (is (= {"hard" true} (:options ((:entries m) "R"))))))

(deftest mapping-errors
  (let [m (k/parse-mappings "map x noSuchCommand\nmap <q-x> scrollDown\nmap\nfrobnicate x\nmap gg scrollDown bogus=1\nmapkey ab c")]
    (is (= 6 (count (:errors m))))
    (is (empty? (:entries m))))
  (is (= {"n" "j"} (:mapkeys (k/parse-mappings "mapkey n j"))))
  (is (empty? (:entries (k/parse-mappings "map j scrollDown\nunmapAll"))))
  (is (= ["j"] (:order (k/parse-mappings "\" comment\nmap j \\\nscrollDown"))) "comments and continuations"))

(defn- run [trie keys & {:keys [pass]}]
  (reduce (fn [[st out] key]
            (let [{:keys [state result] :as r} (k/step trie st key (= key "<esc>") pass)]
              [state (conj out (if (= result :command) [(:command (:command r)) (:count r)] result))]))
          [k/initial []] keys))

(deftest state-machine
  (let [t (k/trie (k/effective-mappings user-text))]
    (is (= [["scrollDown" nil]] (second (run t ["j"]))))
    (is (= [:pending ["scrollToTop" nil]] (second (run t ["g" "g"]))))
    (is (= [:pending :pending ["scrollDown" 12]] (second (run t ["1" "2" "j"]))))
    (is (= [:pending :pending ["scrollDown" nil]] (second (run t ["3" "g" "j"]))) "a count dies when the sequence leaves its prefix")
    (is (= [:pending ["nextTab" 3]] (second (run t ["3" "e"]))))
    (is (= [:pending :reset] (second (run t ["g" "<esc>"]))))
    (is (= [:pass] (second (run t ["<esc>"]))))
    (is (= [:pass] (second (run t ["Q"]))) "unmapped keys go to the page")
    (is (= [:pass] (second (run t ["K"]))) "the user's unmap")
    (is (= [:pending :pass] (second (run t ["g" "Q"]))))
    (is (= [["visitPreviousTab" nil]] (second (run t ["<c-o>"]))))
    (is (= [:pass] (second (run t ["0"]))) "0 starts no count")
    (is (= [:pending :pending ["scrollDown" 10]] (second (run t ["1" "0" "j"])))))
  (testing "pass keys"
    (let [t (k/trie (k/effective-mappings ""))]
      (is (= [:pass] (second (run t ["j"] :pass "jk")))))
    (let [t (k/trie (k/effective-mappings ""))]
      (is (= [:pending ["nextTab" nil]] (second (run t ["g" "t"] :pass "t"))) "never inside a sequence"))))

(deftest trie-priority
  (let [t (k/trie (k/parse-mappings "map g scrollDown\nmap gg scrollToTop"))]
    (is (= "scrollDown" (:command (get t "g"))) "an earlier single key wins over a longer sequence")))

(deftest command-keys
  (is (= ["j" "<c-e>"] (get (k/command-keys (k/effective-mappings "")) "scrollDown"))))
