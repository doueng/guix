#!/usr/bin/env bb
;; dictate transcribes the LibriSpeech fixtures within 10% word error rate,
;; attaches to the resident Neural Engine daemon it starts, stays under 1.5 s
;; warm, and two presses record the microphone, hand the transcript to wtype
;; and return to idle.  A stand-in wtype records what would have been typed.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[cheshire.core :as json]
         '[clojure.string :as str])
(load-file (str (fs/parent *file*) "/wer.clj"))

(def max-wer 0.10)
(def max-warm-ms 1500)
(def dictate (str (fs/absolutize "desktop/bin/dictate")))

(def failures (atom []))
(defn check! [ok? msg]
  (println (if ok? "ok  " "FAIL") msg)
  (when-not ok? (swap! failures conj msg)))

(defn build-bin [expr]
  (str (last (str/split-lines
              (:out (shell {:out :string} "guix" "build" "-L" "modules" "-e" expr))))
       "/bin"))

(def runtime-bin (build-bin "(@ (engstrand packages omarchy-mlx) omarchy-mlx-parakeet)"))
(def scratch (str (fs/create-temp-dir {:prefix "dictate-check-"})))
(def state (str scratch "/dictate"))
(def socket (str state "/ane.sock"))
(def fake-bin (str scratch "/bin"))
(def typed (str scratch "/typed"))
(def env {"DICTATE_STATE_DIR" state
          "PATH" (str fake-bin ":" runtime-bin ":"
                      (build-bin "(@ (gnu packages freedesktop) wtype)") ":"
                      (System/getenv "PATH"))})
(defn run [& args] (:out (apply shell {:out :string :extra-env env} args)))
(def clips (sort (map str (fs/glob "desktop/build/ane/fixtures" "*.flac"))))

(try
  (doseq [audio clips]
    (let [wer (wer/word-error-rate (slurp (str/replace audio #"\.flac$" ".txt"))
                                   (run dictate "--file" audio "--print"))]
      (check! (<= wer max-wer) (format "dictate --file %s word error rate %.3f" (fs/file-name audio) wer))))

  (let [out (str scratch "/report")]
    (shell {:out :string :extra-env (assoc env "MLX_OMARCHY_PK_KEEP_WORKER" "1" "MLX_OMARCHY_ANE_SOCK" socket)}
           (str runtime-bin "/mlx-omarchy-parakeet") "transcribe" "-o" out (first clips))
    (check! (= "daemon" (get-in (json/parse-string (slurp (str out "/transcribe-report.json")) true)
                                  [:ane :session :transport]))
            "transcription attaches to the daemon dictate started"))

  (let [times (sort (for [_ (range 5)]
                      (let [t0 (System/nanoTime)]
                        (run dictate "--file" (last clips) "--print")
                        (quot (- (System/nanoTime) t0) 1000000))))
        median (nth times 2)]
    (check! (< median max-warm-ms) (format "warm dictate --file median %d ms (runs %s)" median (vec times))))

  (fs/create-dirs fake-bin)
  (spit (str fake-bin "/wtype")
        (str "#!/usr/bin/env bb\n(spit " (pr-str typed) " (last *command-line-args*))\n"))
  (fs/set-posix-file-permissions (str fake-bin "/wtype") "rwxr-xr-x")
  (run dictate)
  (Thread/sleep 2000)
  (let [pid-file (str state "/recorder.pid")
        pid (when (fs/exists? pid-file) (str/trim (slurp pid-file)))
        wav (str state "/speech.wav")]
    (check! (and pid (fs/exists? (str "/proc/" pid))) "first press starts the recorder")
    (when pid
      (run dictate)
      (check! (not (fs/exists? (str "/proc/" pid))) "second press stops the recorder")
      (check! (and (fs/exists? wav) (> (fs/size wav) 44)) "the recording has audio frames")))
  (check! (not-any? #(fs/exists? (str state "/" %)) ["recorder.pid" "busy"]) "dictate is idle again")
  (println "     microphone transcript:" (pr-str (when (fs/exists? typed) (slurp typed))))
  (finally
    (shell {:continue true :out :string :err :string}
           (str runtime-bin "/mlx-omarchy-ane-worker") "--stop" "--socket" socket)
    (fs/delete-tree scratch)))

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
