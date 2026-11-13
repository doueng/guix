(ns bbh.models-test
  (:require [bbh.models :as models]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- reply [steps]
  (let [file (str (fs/create-temp-file {:suffix ".edn"}))
        pieces (atom [])]
    (try
      (spit file (pr-str steps))
      (let [r ((:sample (models/engine (str "script/" file))) nil {:on-text #(swap! pieces conj %)})]
        {:pieces @pieces :content (get-in r [:message :content])})
      (finally (fs/delete-if-exists file)))))

(deftest a-script-step-streams-its-text-in-the-chunks-it-names
  ;; Bombadil's Worker view needs output that arrives mid-scroll, as a model's does.
  (let [{:keys [pieces content]} (reply [{:text "one two three four five" :chunks 3}])]
    (is (= 3 (count pieces)))
    (is (= "one two three four five" (str/join pieces) content)))
  (is (= ["hello"] (:pieces (reply ["hello"]))))
  (is (= ["ab"] (:pieces (reply [{:text "ab" :chunks 1}])))))
