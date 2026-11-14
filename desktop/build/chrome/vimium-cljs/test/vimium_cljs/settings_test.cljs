(ns vimium-cljs.settings-test
  (:require [cljs.test :refer [deftest is testing]]
            [vimium-cljs.settings :as s]))

;; Same shape as a Vimium 2.4 export; the patterns are made up.
(def export "{\"exclusionRules\":[{\"passKeys\":\"\",\"pattern\":\"https?://a.example/*\"},{\"passKeys\":\"\",\"pattern\":\"https?://b.example/*\"},{\"passKeys\":\"\",\"pattern\":\"https?://b.example/*\"}],\"grabBackFocus\":true,\"keyMappings\":\"map i focusInput\\nunmap J\",\"settingsVersion\":\"2.4.2\",\"filterLinkHints\":false,\"madeUp\":1}")

(deftest import-export
  (let [{:keys [settings notes errors]} (s/import-vimium export)]
    (is (= [] errors))
    (is (= 2 (count (settings "exclusionRules"))) "duplicates dropped")
    (is (= true (settings "grabBackFocus")))
    (is (= "map i focusInput\nunmap J" (settings "keyMappings")))
    (is (not (contains? settings "settingsVersion")))
    (is (not (contains? settings "madeUp")))
    (is (some #(re-find #"duplicate" %) notes))
    (is (some #(re-find #"madeUp" %) notes))
    (is (some #(re-find #"filterLinkHints" %) notes))))

(deftest import-rejects
  (is (seq (:errors (s/import-vimium "not json"))))
  (is (seq (:errors (s/import-vimium "[1,2]"))))
  (is (seq (:errors (s/import-vimium "{\"scrollStepSize\":\"big\"}"))))
  (is (seq (:errors (s/import-vimium "{\"linkHintCharacters\":\"aa\"}")))))

(deftest engines
  (is (= {"w" {:url "https://w.example/?q=%s" :name "Wiki"}
          "d" {:url "https://d.example/?q=%s" :name "d"}}
         (s/search-engines "w: https://w.example/?q=%s Wiki\n# x: https://x\nd: https://d.example/?q=%s"))))

(deftest summaries
  (let [sm (s/summary {"keyMappings" "map i focusInput\nmap <c-o> visitPreviousTab\nunmap N"})]
    (is (= ["i" "<c-o>"] (map :key (:mapped sm))))
    (is (= ["N"] (:unmapped sm)))
    (is (= "sadfjklewcmpgh" (:hint-chars sm)))))
