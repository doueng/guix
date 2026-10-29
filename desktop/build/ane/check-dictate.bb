#!/usr/bin/env bb
;; dictate transcribes the LibriSpeech fixtures within 10% word error rate, and
;; two presses record the microphone, then hand the transcript to wtype and
;; return to idle.  A stand-in wtype records what would have been typed.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])
(load-file (str (fs/parent *file*) "/wer.clj"))

(def max-wer 0.10)
(def dictate (str (fs/absolutize "desktop/bin/dictate")))

(def failures (atom []))
(defn check! [ok? msg]
  (println (if ok? "ok  " "FAIL") msg)
  (when-not ok? (swap! failures conj msg)))

(defn build-bin [expr]
  (str (last (str/split-lines
              (:out (shell {:out :string} "guix" "build" "-L" "modules" "-e" expr))))
       "/bin"))

(def path (str (build-bin "(@ (engstrand packages omarchy-mlx) omarchy-mlx-parakeet)") ":"
               (build-bin "(@ (gnu packages freedesktop) wtype)") ":"
               (System/getenv "PATH")))

(doseq [audio (sort (map str (fs/glob "desktop/build/ane/fixtures" "*.flac")))]
  (let [text (:out (shell {:out :string :extra-env {"PATH" path}} dictate "--file" audio "--print"))
        wer (wer/word-error-rate (slurp (str/replace audio #"\.flac$" ".txt")) text)]
    (check! (<= wer max-wer) (format "dictate --file %s word error rate %.3f" (fs/file-name audio) wer))))

(let [runtime-dir (str (fs/create-temp-dir {:prefix "dictate-check-"}))
      fake-bin (str runtime-dir "/bin")
      typed (str runtime-dir "/typed")
      state (str runtime-dir "/dictate")
      env {"PATH" (str fake-bin ":" path) "DICTATE_STATE_DIR" state}]
  (fs/create-dirs fake-bin)
  (spit (str fake-bin "/wtype")
        (str "#!/usr/bin/env bb\n(spit " (pr-str typed) " (last *command-line-args*))\n"))
  (fs/set-posix-file-permissions (str fake-bin "/wtype") "rwxr-xr-x")
  (shell {:extra-env env} dictate)
  (Thread/sleep 2000)
  (let [pid-file (str state "/recorder.pid")
        pid (when (fs/exists? pid-file) (str/trim (slurp pid-file)))
        wav (str state "/speech.wav")]
    (check! (and pid (fs/exists? (str "/proc/" pid))) "first press starts the recorder")
    (when pid
      (shell {:extra-env env} dictate)
      (check! (not (fs/exists? (str "/proc/" pid))) "second press stops the recorder")
      (check! (and (fs/exists? wav) (> (fs/size wav) 44)) "the recording has audio frames")))
  (check! (not-any? #(fs/exists? (str state "/" %)) ["recorder.pid" "busy"]) "dictate is idle again")
  (println "     microphone transcript:" (pr-str (when (fs/exists? typed) (slurp typed))))
  (fs/delete-tree runtime-dir))

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
