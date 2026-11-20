(ns rexg.keys-test
  (:require [clojure.test :refer [deftest is]]
            [rexg.keys :as k]))

(defn- keys-of [s] (mapv second (first (k/read-input s))))

(deftest chords-round-trip
  (doseq [c ["ctrl+b" "alt+x" "shift+n" "%" "enter" "ctrl+shift+u" "super+y" "?"]]
    (is (= c (k/chord (k/parse c))))))

(deftest kitty-keys-read-as-rex-names
  (is (= ["ctrl+b" "%" "shift+u" "alt+x" "enter" "escape" "a"]
         (map k/chord (keys-of "\u001b[98;5u\u001b[37;;37u\u001b[117;2;85u\u001b[120;3u\u001b[13u\u001b[27u\u001b[97;;97u"))))
  (is (= "A" (:text (first (keys-of "\u001b[97;2;65u")))) "the terminal's text wins")
  (is (= ["up" "ctrl+left" "pagedown" "f5"] (map k/chord (keys-of "\u001b[A\u001b[1;5D\u001b[6~\u001b[15~"))))
  (is (empty? (keys-of "\u001b[57441;2u")) "a lone modifier is ignored")
  (is (= ["shift+o" "shift+l"] (map k/chord (keys-of "\u001b[79;;79u\u001b[108;2;76u")))
      "an uppercase key code, as wtype sends it, is the letter with shift"))

(deftest plain-bytes-read-too
  (is (= ["ctrl+b" "%" "enter" "alt+x" "backspace" "A"] (map #(if (= "a" (:key %)) (:text %) (k/chord %)) (keys-of "\u0002%\r\u001bx\u007fA")))))

(deftest mouse-paste-and-focus
  (let [[evs more] (k/read-input "\u001b[<0;10;5M\u001b[200~hi\nthere\u001b[201~\u001b[I\u001b[")]
    (is (= [[:mouse {:button 0 :x 9 :y 4 :kind :press}] [:paste "hi\nthere"] [:focus true]] evs))
    (is (= "\u001b[" more) "an unfinished sequence waits for the next read")))

(deftest client-key-names-the-physical-key
  (is (= {:key "digit_5" :mods "shift" :text "%" :consumed_mods "shift"}
         (select-keys (k/to-rex (k/parse "%")) [:key :mods :text :consumed_mods])))
  (is (= {:key "key_b" :mods "ctrl" :text ""} (select-keys (k/to-rex (k/parse "ctrl+b")) [:key :mods :text]))))
