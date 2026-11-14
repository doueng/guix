;; Exclusion rules: Vimium's exclusions.js (MIT). A pattern is a regexp in
;; which * means any text, anchored at both ends. A matching rule with no
;; pass keys disables the extension on the page; otherwise its pass keys go
;; to the page.
(ns vimium-cljs.exclusions
  (:require [clojure.string :as str]))

(defn pattern->regex [pattern]
  (try (js/RegExp. (str "^" (str/replace pattern "*" ".*") "$"))
       (catch :default _ #"^$")))

(defn rule-for-url
  "Nil when no rule matches URL; {:enabled false} for an absolute exclusion;
  else {:enabled true :pass-keys \"...\"}."
  [rules url]
  (let [hits (filter (fn [{:strs [pattern]}]
                       (and (seq pattern) (re-find (pattern->regex pattern) url)))
                     rules)]
    (cond
      (empty? hits) nil
      (some #(str/blank? (get % "passKeys")) hits) {:enabled false :pass-keys ""}
      :else {:enabled true
             :pass-keys (apply str (distinct (str/replace (apply str (map #(get % "passKeys") hits)) #"\s+" "")))})))
