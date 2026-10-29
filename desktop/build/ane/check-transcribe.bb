#!/usr/bin/env bb
;; Build the Parakeet runtime, run its golden check on the Neural Engine, and
;; transcribe LibriSpeech clips it was not pinned to, failing above 10% word
;; error rate.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def max-wer 0.10)

(defn out [& args]
  (:out (apply shell {:out :string :err :inherit} args)))

(def failures (atom []))
(defn check! [ok? msg]
  (println (if ok? "ok  " "FAIL") msg)
  (when-not ok? (swap! failures conj msg)))

(defn words [text]
  (-> (str/lower-case text)
      (str/replace #"[-\u2010-\u2014]" " ")
      (str/replace #"[^\p{L}\p{N}' ]" "")
      str/trim
      (str/split #"\s+")))

(defn edit-distance [a b]
  (let [b (vec b)]
    (peek (reduce (fn [prev x]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row))
                                             (inc (prev (inc j)))
                                             (+ (prev j) (if (= x (b j)) 0 1)))))
                            [(inc (first prev))]
                            (range (count b))))
                  (vec (range (inc (count b))))
                  a))))

(def runtime
  (str (last (str/split-lines
              (out "guix" "build" "-L" "modules" "-e"
                   "(@ (engstrand packages omarchy-mlx) omarchy-mlx-parakeet)")))
       "/bin/mlx-omarchy-parakeet"))

(check! (str/includes? (out runtime "verify") "\"status\": \"match\"")
        "golden fixture matches on the Neural Engine")

(doseq [audio (sort (map str (fs/glob "desktop/build/ane/fixtures" "*.flac")))]
  (let [dir (str (fs/create-temp-dir))
        _ (out runtime "transcribe" "-o" dir audio)
        hyp (words (slurp (str dir "/transcript.txt")))
        ref (words (slurp (str/replace audio #"\.flac$" ".txt")))
        wer (/ (double (edit-distance ref hyp)) (count ref))]
    (println "    " (str/join " " hyp))
    (check! (<= wer max-wer) (format "%s word error rate %.3f" (fs/file-name audio) wer))))

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
