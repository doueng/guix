;; Vimium's exclusions.js (MIT).
(ns vimium-cljs.exclusions
  (:require [clojure.string :as str]))

(defn pattern->regex [pattern]
  (try (js/RegExp. (str "^" (str/replace pattern "*" ".*") "$"))
       (catch :default _ #"^$")))

(defn rule-for-url
  "Nil when no rule matches URL; {:enabled false} for an absolute exclusion;
  else {:enabled true :pass-keys \"...\"}."
  [rules url]
  (let [hits (filter (fn [{:keys [pattern]}]
                       (and (seq pattern) (re-find (pattern->regex pattern) url)))
                     rules)]
    (cond
      (empty? hits) nil
      (some #(str/blank? (:pass-keys %)) hits) {:enabled false :pass-keys ""}
      :else {:enabled true
             :pass-keys (apply str (distinct (str/replace (apply str (map :pass-keys hits)) #"\s+" "")))})))
