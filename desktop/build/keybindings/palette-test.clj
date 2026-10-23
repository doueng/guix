(ns palette-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]))
(load-file "desktop/bin/palette")
(require '[dusk.palette :as dusk])

(def grammar (keyleader/load-grammar))

(deftest editors-win-over-the-shell-that-started-them
  (is (= {:id :emacs :pid 7} (palette/program-of [{:name "fish" :pid 1} {:name ".emacs-31.1-rea" :pid 7}])))
  (is (= {:id :nvim :pid 3} (palette/program-of [{:name "nvim" :pid 3}])))
  (is (= {:id :fish :pid 1} (palette/program-of [{:name "fish" :pid 1}])))
  (is (nil? (palette/program-of [{:name "pi" :pid 9}]))))

(deftest each-context-lists-its-own-commands-before-the-desktop
  (let [sources (fn [ctx] (distinct (map :source (palette/entries grammar ctx))))]
    (with-redefs [palette/fish-entries (constantly [{:label "ls" :source "History" :value "text:ls"}])]
      (is (= ["History" "Herdr" "Desktop" "Guide"] (sources {:kind :herdr :program {:id :fish}})))
      (is (= ["Herdr" "Desktop" "Guide"] (sources {:kind :herdr :program nil}))))
    (is (= "Chrome" (first (sources {:kind :chrome}))))
    (is (= "IntelliJ" (first (sources {:kind :idea}))))
    (is (= ["Desktop" "Guide"] (sources {:kind :desktop})))))

(defn run-with [value ctx]
  (let [calls (atom [])]
    (with-redefs [keyleader/run! (fn [argv & _] (swap! calls conj (vec argv)) "")
                  keyleader/log! (fn [& _] nil)
                  keyleader/herdr-api! (fn [m params] (swap! calls conj [m params]))
                  palette/type-keys! (fn [k] (swap! calls conj k))]
      (palette/run-entry! grammar ctx value))
    @calls))

(deftest choices-run-where-they-came-from
  (is (= [["herdr" "pane" "send-text" "w1:p2" "git status"]] (run-with "text:git status" {:pane "w1:p2"}))
      "shell text is inserted, never run")
  (is (= [["herdr" "tab" "close" "w1:t3"]] (run-with "herdr:leader:b d" {:pane "w1:p2" :tab "w1:t3" :workspace "w1"}))
      "Herdr actions use the focused tab, not the palette's environment")
  (is (= [{:chord "esc"} {:type ":action ReformatCode"} {:chord "enter"}] (run-with "idea:ReformatCode" {})))
  (is (= [{:label "New tab" :keys "Ctrl+T" :chord "ctrl+t"}]
         (run-with (str "chrome:" (first (keep-indexed #(when (= "New tab" (:label %2)) %1) (palette/config :chrome)))) {}))))

(deftest chords-release-in-reverse
  (is (= ["29:1" "42:1" "20:1" "20:0" "42:0" "29:0"] (palette/chord-events "ctrl+shift+t"))))

(deftest an-editor-that-does-not-answer-falls-back-to-the-list
  (let [shown (atom nil)]
    (with-redefs [keyleader/load-grammar (constantly grammar)
                  palette/context (constantly {:kind :herdr :pane "w1:p2" :program {:id :emacs :pid 42}})
                  palette/sh-out (constantly nil)
                  palette/notify! (fn [_] nil)
                  dusk/pick! (fn [prompt entries] (reset! shown [prompt (count entries)]) nil)]
      (palette/open!))
    (is (= "Emacs" (first @shown)))
    (is (pos? (second @shown)))))

(deftest an-editor-that-answers-opens-its-own-palette
  (let [asked (atom [])]
    (with-redefs [keyleader/load-grammar (constantly grammar)
                  palette/context (constantly {:kind :herdr :program {:id :nvim :pid 42}})
                  palette/process-sockets (constantly ["/tmp/custom.nvim"])
                  palette/sh-out (fn [& argv]
                                    (swap! asked conj (vec argv))
                                    (if (= "getpid()" (last argv)) "42" "1"))
                  dusk/pick! (fn [& _] (throw (ex-info "must not show the list" {})))]
      (palette/open!))
    (is (= [["nvim" "--server" "/tmp/custom.nvim" "--remote-expr" "getpid()"]
            ["nvim" "--server" "/tmp/custom.nvim" "--remote-expr"
             "luaeval(\"require('config.palette').open() and 1 or 0\")"]] @asked))))

(deftest nvim-must-acknowledge-opening-and-match-the-target-pid
  (doseq [reply [nil "0"]]
    (with-redefs [palette/process-sockets (constantly ["/tmp/nvim"])
                  palette/sh-out (fn [& args] (if (= "getpid()" (last args)) "42" reply))]
      (is (not (palette/open-nvim! 42)))))
  (let [calls (atom [])]
    (with-redefs [palette/process-sockets (constantly ["/tmp/wrong-instance"])
                  palette/sh-out (fn [& args] (swap! calls conj (last args)) "99")]
      (is (not (palette/open-nvim! 42)))
      (is (= ["getpid()"] @calls)))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
