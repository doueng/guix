(ns rexg.gens.stream
  "Generators for rexg.stream-test: a pane's data stream as Rex writes it,
  VT runs with Rex's messages between them, cut into chunks as the socket
  delivers them. Agents may rewrite this file (desktop/build/pbt, bb try
  rexg-stream-order); what the stream must come out as stays in the test file."
  (:require [clojure.test.check.generators :as gen]))

(def gen-char
  "A VT byte, as a latin1 char: mostly text, often the bytes Rex's message
  marker is made of, sometimes high bytes."
  (gen/frequency [[6 (gen/elements (seq "abc xyz\r\n"))]
                  [4 (gen/elements (seq "\u001b_rex1;\\0123456789"))]
                  [1 (gen/fmap char (gen/choose 128 255))]]))

(def gen-vt
  "[:vt text]: plain bytes, or bytes ending in the start of Rex's marker
  (the test fills in the nonce), which a splitter must hold back."
  (gen/one-of [(gen/fmap #(vector :vt (apply str %)) (gen/vector gen-char 0 12))
               (gen/fmap (fn [[t k]] [:vt-partial t k]) (gen/tuple (gen/fmap #(apply str %) (gen/vector gen-char 0 4)) (gen/choose 1 12)))]))

(def gen-msg
  "One of Rex's messages: a resize, or another with text that may need UTF-8."
  (gen/one-of [(gen/fmap (fn [[c r]] [:resize c r]) (gen/tuple (gen/choose 1 300) (gen/choose 1 100)))
               (gen/fmap #(vector :msg %) (gen/elements ["title" "bell" "exit" "é ✓"]))]))

(def gen-input
  "{:nonce :items :cuts}: the nonce, the stream's items in order, and where
  the socket cuts it into chunks (offsets, taken modulo its length)."
  (gen/hash-map :nonce (gen/fmap str (gen/choose 0 99999))
                :items (gen/vector (gen/frequency [[3 gen-vt] [2 gen-msg]]) 0 14)
                :cuts (gen/vector (gen/choose 0 400) 0 12)))
