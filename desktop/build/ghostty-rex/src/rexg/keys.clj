(ns rexg.keys
  "Keys, as the ghostty-rex client reads them from Ghostty and names them for
  Rex: a key is {:key \"b\" :ctrl true :alt :shift :super :text}, named the way
  Rex keymaps are (ctrl+b, alt+x, shift+n, enter). rexg-render turns on the
  Kitty keyboard protocol (flags 1, 8 and 16), so every chord arrives as an
  escape code with its text; plain bytes are read too, for terminals without
  it. A port of rexmux's keys.rs and tty.rs parse_keys."
  (:require [clojure.string :as str]))

(def ^:private shifted
  {\1 \! \2 \@ \3 \# \4 \$ \5 \% \6 \^ \7 \& \8 \* \9 \( \0 \)
   \- \_ \= \+ \[ \{ \] \} \\ \| \; \: \' \" \, \< \. \> \/ \? \` \~})
(def ^:private unshifted-of (into {} (map (fn [[a b]] [b a])) shifted))

(defn typed
  "The text KEY types on a US layout, shifted when SHIFT."
  [key shift]
  (case key
    "space" " "
    "enter" "\r"
    "tab" "\t"
    (when (= 1 (count key))
      (let [c (first key)]
        (str (if shift (if (Character/isLetter c) (Character/toUpperCase c) (get shifted c c)) c))))))

(defn unshifted
  "The key that types symbol KEY with shift, such as 5 for %."
  [key]
  (when (= 1 (count key)) (some-> (unshifted-of (first key)) str)))

(defn parse
  "The key of a chord such as ctrl+b or shift+n or %."
  [chord]
  (let [plus? (or (= chord "+") (str/ends-with? chord "++"))
        parts (str/split chord #"\+")
        [mods key] (if plus? [(drop-last 1 parts) "+"] [(butlast parts) (last parts)])
        k (reduce (fn [k m] (case m "ctrl" (assoc k :ctrl true) "alt" (assoc k :alt true)
                                 "shift" (assoc k :shift true) ("super" "cmd") (assoc k :super true) k))
                  {:key (str/lower-case (str key))} mods)
        k (if-let [base (unshifted (:key k))] (assoc k :key base :shift true) k)]
    (cond-> k (not (or (:ctrl k) (:alt k) (:super k))) (assoc :text (typed (:key k) (:shift k))))))

(defn chord
  "K's name in Rex's notation; a shifted digit or symbol is named by what it
  types, so shift+5 is %."
  [{:keys [key ctrl alt shift super]}]
  (let [symbol (when (and shift (= 1 (count key)) (not (Character/isLetter (first key)))) (typed key true))]
    (str (when ctrl "ctrl+") (when alt "alt+") (when (and shift (not symbol)) "shift+") (when super "super+")
         (or symbol key))))

(defn code
  "The W3C KeyboardEvent code of KEY on a US layout, in snake case."
  [key]
  (or ({"enter" "enter" "return" "enter" "escape" "escape" "backspace" "backspace" "tab" "tab"
        "space" "space" " " "space" "up" "arrow_up" "down" "arrow_down" "left" "arrow_left"
        "right" "arrow_right" "home" "home" "end" "end" "pageup" "page_up" "pagedown" "page_down"
        "delete" "delete" "insert" "insert" "-" "minus" "=" "equal" "[" "bracket_left"
        "]" "bracket_right" "\\" "backslash" ";" "semicolon" "'" "quote" "," "comma"
        "." "period" "/" "slash" "`" "backquote"} key)
      (when (re-matches #"[a-z]" key) (str "key_" key))
      (when (re-matches #"[0-9]" key) (str "digit_" key))
      (when (re-matches #"f[0-9]{1,2}" key) key)
      "unidentified"))

(defn to-rex
  "The key object of a client.key request."
  [{:keys [key shift ctrl alt super text]}]
  (let [text (or text "")]
    {:key (code key)
     :mods (str/join "+" (keep identity [(when shift "shift") (when ctrl "ctrl") (when alt "alt") (when super "super")]))
     :consumed_mods (if (and shift (seq text)) "shift" "")
     :text text
     :unshifted_codepoint (or (some-> (typed key false) first int) 0)
     :composing false
     :action "press"}))

;; ---- reading the terminal ----

(def ^:private kitty-named
  {13 "enter" 9 "tab" 127 "backspace" 27 "escape" 32 "space"
   57399 "0" 57400 "1" 57401 "2" 57402 "3" 57403 "4" 57404 "5" 57405 "6" 57406 "7" 57407 "8" 57408 "9"
   57409 "." 57410 "/" 57411 "*" 57412 "-" 57413 "+" 57414 "enter" 57415 "="})

(def ^:private csi-named
  {"A" "up" "B" "down" "C" "right" "D" "left" "H" "home" "F" "end" "P" "f1" "Q" "f2" "S" "f4"
   "2~" "insert" "3~" "delete" "5~" "pageup" "6~" "pagedown" "7~" "home" "8~" "end" "13~" "f3"
   "15~" "f5" "17~" "f6" "18~" "f7" "19~" "f8" "20~" "f9" "21~" "f10" "23~" "f11" "24~" "f12"})

(defn- with-mods [k m]
  (let [m (dec (max 1 m))]
    (cond-> k
      (pos? (bit-and m 1)) (assoc :shift true)
      (pos? (bit-and m 2)) (assoc :alt true)
      (pos? (bit-and m 4)) (assoc :ctrl true)
      (pos? (bit-and m 8)) (assoc :super true))))

(defn- finish
  "K with :text derived when the terminal sent none and no modifier eats it."
  [k]
  (cond-> k
    (and (nil? (:text k)) (not (or (:ctrl k) (:alt k) (:super k)))) (assoc :text (typed (:key k) (:shift k)))
    (or (:ctrl k) (:alt k) (:super k)) (dissoc :text)))

(defn- kitty-key
  "The key of CSI PARAMS u, or nil for one to ignore (a lone modifier, a
  release, a key we do not name)."
  [params]
  (let [[kp mp tp] (str/split params #";" -1)
        code (parse-long (first (str/split (or kp "") #":")))
        [m ev] (map parse-long (str/split (or (not-empty mp) "1") #":"))
        text (when (not-empty tp) (apply str (map #(String. (Character/toChars (parse-long %))) (str/split tp #":"))))]
    (when (and code (not= 3 ev))
      (let [key (or (kitty-named code)
                    (when (and (< code 57344) (> code 32)) (str/lower-case (String. (Character/toChars code)))))]
        (when key
          (let [k (with-mods {:key key} (or m 1))
                ;; A shifted symbol arrives as its base key with shift; a
                ;; symbol typed directly (no shift on this layout) stays itself.
                k (if-let [base (unshifted key)] (assoc k :key base :shift true) k)]
            (finish (cond-> k text (assoc :text text)))))))))

(defn- char-key [c]
  (let [i (int c)]
    (cond (#{\return \newline} c) {:key "enter" :text "\r"}
          (= c \tab) {:key "tab" :text "\t"}
          (#{127 8} i) {:key "backspace"}
          (zero? i) {:key "space" :ctrl true}
          (< i 27) {:key (str (char (+ 96 i))) :ctrl true}
          (= c \space) {:key "space" :text " "}
          (Character/isUpperCase c) {:key (str (Character/toLowerCase c)) :shift true :text (str c)}
          (unshifted (str c)) {:key (unshifted (str c)) :shift true :text (str c)}
          :else {:key (str c) :text (str c)})))

(defn read-input
  "Events in the terminal's input S: [:key k], [:mouse {:button :x :y :kind}],
  [:paste text], [:focus bool]. Returns [events rest], REST being an
  unfinished escape sequence to prepend to the next read."
  [^String s]
  (loop [i 0 out []]
    (if (>= i (count s))
      [out ""]
      (let [c (.charAt s i)]
        (if (not= c \u001b)
          (let [cp (.codePointAt s i) n (Character/charCount cp)]
            (recur (+ i n) (conj out [:key (char-key (if (= n 1) c (char 0xFFFD)))])))
          (cond
            (= (inc i) (count s)) [(conj out [:key {:key "escape"}]) ""]
            (str/starts-with? (subs s i) "\u001b[200~")
            (if-let [e (str/index-of s "\u001b[201~" i)]
              (recur (+ e 6) (conj out [:paste (subs s (+ i 6) e)]))
              [out (subs s i)])
            (= \[ (.charAt s (inc i)))
            (let [m (re-find #"^\u001b\[([<?>]?)([0-9;:]*)([A-Za-z~])" (subs s i))]
              (if-not m
                (if (> (- (count s) i) 32) (recur (+ i 2) out) [out (subs s i)])
                (let [[whole lead params fin] m
                      ev (cond
                           (= lead "<") (let [[b x y] (map parse-long (str/split params #";"))]
                                           [:mouse {:button b :x (dec x) :y (dec y) :kind (if (= fin "M") :press :release)}])
                           (and (= fin "u") (= lead "")) (some->> (kitty-key params) (vector :key))
                           (= fin "I") [:focus true]
                           (= fin "O") [:focus false]
                           (= lead "") (let [[a b] (str/split params #";")
                                               nm (csi-named (if (= fin "~") (str a "~") fin))]
                                           (when nm [:key (with-mods {:key nm} (or (some-> b parse-long) 1))]))
                           :else nil)]
                  (recur (+ i (count whole)) (cond-> out ev (conj ev))))))
            (= \O (.charAt s (inc i)))
            (if (< (+ i 2) (count s))
              (recur (+ i 3) (if-let [nm ({\A "up" \B "down" \C "right" \D "left" \H "home" \F "end" \P "f1" \Q "f2" \R "f3" \S "f4"} (.charAt s (+ i 2)))]
                               (conj out [:key {:key nm}]) out))
              [out (subs s i)])
            :else (let [k (char-key (.charAt s (inc i)))]
                    (recur (+ i 2) (conj out [:key (-> k (assoc :alt true) (dissoc :text))])))))))))
