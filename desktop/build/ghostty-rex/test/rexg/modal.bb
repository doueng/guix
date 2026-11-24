#!/usr/bin/env bb
;; A modal program for ghostty-rex's Bombadil cursor spec (T223,
;; test/bombadil/cursor.ts): i is insert mode (no cursor), Escape normal mode
;; (an underscore), a enters the alternate screen with a bar cursor, as an
;; editor or jjui does, and q leaves it, restoring the mode before. Its first
;; row says the mode, so the spec knows which cursor to expect, and the
;; cursor sits at row 3, column 5 of the pane.
(require '[babashka.process :as p])

(p/shell {:in :inherit} "stty" "raw" "-echo")

(defn- put [& s] (print (apply str s)) (flush))

(defn- draw [{:keys [mode alt]}]
  (put "\u001b[H\u001b[2JMODE=" (name mode) (when alt " ALT") "\r\n"
       (case mode
         :insert "\u001b[4 q\u001b[?25l"
         :normal "\u001b[4 q\u001b[?25h"
         :bar "\u001b[6 q\u001b[?25h")
       "\u001b[3;5H"))

(loop [st {:mode :normal :alt false}]
  (draw st)
  (let [b (.read System/in)]
    (when (>= b 0)
      (recur (case (char b)
               \i (assoc st :mode :insert)
               \u001b (assoc st :mode :normal)
               \a (if (:alt st) st (do (put "\u001b[?1049h") (assoc st :alt true :saved (:mode st) :mode :bar)))
               \q (if (:alt st) (do (put "\u001b[?1049l") (assoc st :alt false :mode (:saved st))) st)
               st)))))
