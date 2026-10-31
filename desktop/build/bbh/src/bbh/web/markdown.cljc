(ns bbh.web.markdown
  "Markdown to hiccup. A transcript carries text from anywhere, and the page can
  drive an agent, so nothing here ever produces raw HTML, and only http links do
  anything."
  (:require [clojure.string :as str]))

(def ^:private inline-token
  #"`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\(https?://[^)\s]+\)|https?://[^\s)>]+")

(defn- link [href text]
  [:a {:href href :target "_blank" :rel "noopener noreferrer"} text])

(defn- token [t]
  (cond (str/starts-with? t "`") [:code (subs t 1 (dec (count t)))]
        (str/starts-with? t "**") [:strong (subs t 2 (- (count t) 2))]
        (str/starts-with? t "[") (let [[_ text href] (re-matches #"\[(.+)\]\((.+)\)" t)] (link href text))
        :else (link t t)))

(defn inline
  "S with its code spans, bold and links as hiccup, as a vector of children."
  [s]
  (loop [s s out []]
    (if-let [t (re-find inline-token s)]
      (let [at (str/index-of s t)]
        (recur (subs s (+ at (count t)))
               (cond-> out (pos? at) (conj (subs s 0 at)) :always (conj (token t)))))
      (cond-> out (seq s) (conj s)))))

(defn- rule? [line]
  (and (str/includes? line "-") (re-matches #"\s*\|?[\s:|-]+\|?\s*" line)))

(defn- cells [line]
  (mapv str/trim (-> (str/trim line) (str/replace #"^\||\|$" "") (str/split #"\|" -1))))

(defn- list-item [line]
  (re-matches #"\s*(?:[-*+]|(\d+)[.)])\s+(.*)" line))

(defn- block-start? [lines]
  (let [[line following] lines]
    (or (str/starts-with? line "```")
        (re-matches #"#{1,6}\s.*" line)
        (str/starts-with? line ">")
        (list-item line)
        (and following (str/includes? line "|") (rule? following)))))

(defn- take-block
  "Split LINES after the leading lines that satisfy PRED."
  [pred lines]
  (let [n (count (take-while pred lines))]
    [(subvec lines 0 n) (subvec lines n)]))

(defn- fence [highlight lines]
  (let [lang (str/trim (subs (first lines) 3))
        [body more] (take-block #(not (str/starts-with? % "```")) (subvec lines 1))]
    [[:pre (into [:code] (highlight lang (str/join "\n" body)))]
     (if (seq more) (subvec more 1) more)]))

(defn- table [lines]
  (let [[rows more] (take-block #(str/includes? % "|") (subvec lines 2))
        row (fn [tag line] (into [:tr] (map (fn [cell] (into [tag] (inline cell)))) (cells line)))]
    [[:div.table [:table [:thead (row :th (first lines))]
                  (into [:tbody] (map #(row :td %)) rows)]]
     more]))

(defn- item-lines [lines]
  (take-block #(or (list-item %) (re-matches #"\s+\S.*" %)) lines))

(defn- items [lines]
  (let [[block more] (item-lines lines)
        ordered? (second (list-item (first block)))
        grouped (reduce (fn [groups line]
                          (if-let [[_ _ text] (list-item line)]
                            (conj groups text)
                            (conj (pop groups) (str (peek groups) " " (str/trim line)))))
                        [] block)]
    [(into [(if ordered? :ol :ul)] (map (fn [text] (into [:li] (inline text)))) grouped) more]))

(defn- quoted [lines]
  (let [[block more] (take-block #(str/starts-with? % ">") lines)]
    [(into [:blockquote] (inline (str/join " " (map #(str/trim (subs % 1)) block)))) more]))

(defn- paragraph [lines]
  (let [ends? (fn [[line :as ls]] (or (str/blank? line) (block-start? ls)))
        n (inc (count (take-while #(not (ends? (subvec lines %))) (range 1 (count lines)))))]
    [(into [:p] (inline (str/join "\n" (subvec lines 0 n)))) (subvec lines n)]))

(defn- block [highlight [line following :as lines]]
  (cond (str/starts-with? line "```") (fence highlight lines)
        (re-matches #"#{1,6}\s.*" line) [(into [:h3] (inline (str/replace line #"^#+\s+" ""))) (subvec lines 1)]
        (str/starts-with? line ">") (quoted lines)
        (list-item line) (items lines)
        (and following (str/includes? line "|") (rule? following)) (table lines)
        :else (paragraph lines)))

(defn render
  "TEXT's Markdown as a vector of hiccup blocks. HIGHLIGHT turns a fence's language
  and code into the children of its code element."
  ([text] (render text (fn [_ code] [code])))
  ([text highlight]
   (loop [lines (vec (str/split-lines (str text))) out []]
     (cond (empty? lines) out
           (str/blank? (first lines)) (recur (subvec lines 1) out)
           :else (let [[hiccup more] (block highlight lines)]
                   (recur more (conj out hiccup)))))))
