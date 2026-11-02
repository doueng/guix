#!/usr/bin/env bb
;; Build Hex, check that it sees the Apple GPU through Vulkan, and transcribe
;; LibriSpeech clips with the installed model, failing above 10% word error rate.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])
(load-file (str (fs/parent *file*) "/wer.clj"))

(def max-wer 0.10)

(defn out [& args]
  (:out (apply shell {:out :string :err :inherit} args)))

(def failures (atom []))
(defn check! [ok? msg]
  (println (if ok? "ok  " "FAIL") msg)
  (when-not ok? (swap! failures conj msg)))

(def hex
  (str (last (str/split-lines
              (out "guix" "build" "-L" "modules" "-e" "(@ (engstrand packages hex) hex)")))
       "/bin/hex"))

(check! (re-find #"vulkan.*Apple" (out hex "model" "devices")) "Hex sees the Apple GPU through Vulkan")

(when-not (re-find #"^installed\s+verified" (out hex "model" "status"))
  (println "FAIL no verified model; run: hex model install")
  (System/exit 1))

(def scratch (fs/create-temp-dir {:prefix "hex-check-"}))
(doseq [audio (sort (map str (fs/glob "desktop/build/hex/fixtures" "*.flac")))]
  (let [wav (str scratch "/" (fs/file-name (fs/strip-ext audio)) ".wav")
        _ (shell "guix" "shell" "sox" "--" "sox" audio "-r" "16000" "-c" "1" "-b" "16" wav)
        hyp (out hex "model" "transcribe" wav)
        wer (wer/word-error-rate (slurp (str/replace audio #"\.flac$" ".txt")) hyp)]
    (println "    " (str/trim hyp))
    (check! (<= wer max-wer) (format "%s word error rate %.3f" (fs/file-name audio) wer))))

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
