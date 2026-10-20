(ns bbh.delimiters
  "Heal unbalanced delimiters in the model's Clojure before it runs, as
  pi-sci's src/delimiters.ts does for codemode. Only two shapes are repaired:
  a form truncated near the end, and extra closers at the very end."
  (:require [clojure.string :as str]))

(def ^:private closer {\( \) \[ \] \{ \}})
(def ^:private opener {\) \( \] \[ \} \{})
(def ^:private max-trailing-nonblank-lines 10)
(def ^:private max-removed-closers 256)

(defn scan
  "The delimiter balance of S, ignoring strings, comments and character literals,
  and the lines where a form opens in column 1 inside another form."
  [^String s]
  (let [n (count s)]
    (loop [i 0 line 1 column 1 stack [] tops []]
      (if (>= i n)
        {:balance (if (seq stack) {:kind :unclosed :stack stack} {:kind :balanced}) :tops tops}
        (let [c (.charAt s i)]
          (case c
            \newline (recur (inc i) (inc line) 1 stack tops)
            \; (let [end (or (str/index-of s "\n" i) n)]
                 (recur end line (+ column (- end i)) stack tops))
            \" (let [end (loop [j (inc i)]
                            (cond (>= j n) n
                                  (= \\ (.charAt s j)) (recur (+ j 2))
                                  (= \" (.charAt s j)) (inc j)
                                  :else (recur (inc j))))
                      end (min end n)
                      text (subs s i end)
                      newlines (count (filter #{\newline} text))]
                  (recur end (+ line newlines)
                         (if (pos? newlines) (- (count text) (str/last-index-of text "\n")) (+ column (count text)))
                         stack tops))
            \\ (if (and (< (inc i) n) (= \newline (.charAt s (inc i))))
                  (recur (+ i 2) (inc line) 1 stack tops)
                  (recur (+ i 2) line (+ column 2) stack tops))
            (\( \[ \{) (recur (inc i) line (inc column)
                             (conj stack {:char c :line line :column column})
                             (if (and (= 1 column) (seq stack)) (conj tops line) tops))
            (\) \] \}) (if (= (opener c) (:char (peek stack)))
                         (recur (inc i) line (inc column) (pop stack) tops)
                         {:balance {:kind :stray :char c :line line :column column} :tops tops})
            (recur (inc i) line (inc column) stack tops)))))))

(defn- balanced? [s] (= :balanced (get-in (scan s) [:balance :kind])))

(defn- close-truncation [s {:keys [balance tops]}]
  (when (= :unclosed (:kind balance))
    (let [{:keys [line] :as outer} (first (:stack balance))
          trailing (->> (str/split s #"\n" -1) (drop line) (remove str/blank?) count)]
      ;; A form in column 1 inside the open region is a top-level form that
      ;; closing at the end would silently re-parent.
      (when (and (<= trailing max-trailing-nonblank-lines) (not-any? #(>= % line) tops))
        (let [closers (apply str (map (comp closer :char) (rseq (:stack balance))))]
          {:content (str s closers)
           :note (format "appended `%s` to close the `%s` opened at line %d, column %d."
                         closers (:char outer) line (:column outer))})))))

(defn- remove-overclose [s]
  (loop [current s removed 0]
    (case (get-in (scan current) [:balance :kind])
      :balanced (when (pos? removed)
                  {:content current :note (str "removed " removed " extra trailing closing delimiter(s).")})
      :unclosed nil
      :stray (let [end (count (str/trimr current))]
               (when (and (< removed max-removed-closers) (pos? end)
                          (#{\) \] \}} (.charAt ^String current (dec end))))
                 (recur (str (subs current 0 (dec end)) (subs current end)) (inc removed)))))))

(defn repair
  "{:kind :clean} for balanced S, {:kind :repaired :content :note} when one of
  the two repairs balances it, and {:kind :unbalanced} otherwise."
  [s]
  (let [{:keys [balance] :as result} (scan s)]
    (if (= :balanced (:kind balance))
      {:kind :clean}
      (let [fix (or (close-truncation s result)
                    (when (= :stray (:kind balance)) (remove-overclose s)))]
        (if (and fix (balanced? (:content fix)))
          {:kind :repaired
           :content (:content fix)
           :note (str "[auto-repair] Unbalanced delimiters: " (:note fix)
                      " If that placement is wrong, resend the corrected text.")}
          {:kind :unbalanced})))))
