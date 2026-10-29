#!/usr/bin/env bb
;; dictate transcribes the LibriSpeech fixtures within 10% word error rate.
;; A session fed a fixture at real speed types words before it ends, finishes
;; within 10% word error rate, attaches to the resident Neural Engine daemon,
;; and the live microphone session starts and stops cleanly.  A stand-in
;; wtype records what would have been typed.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
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
              (:out (p/shell {:out :string} "guix" "build" "-L" "modules" "-e" expr))))
       "/bin"))

(def runtime-bin (build-bin "(@ (engstrand packages omarchy-mlx) omarchy-mlx-parakeet)"))
(def scratch (str (fs/create-temp-dir {:prefix "dictate-check-"})))
(def state (str scratch "/dictate"))
(def fake-bin (str scratch "/bin"))
(def typed (str scratch "/typed"))
(def env {"DICTATE_STATE_DIR" state
          "PATH" (str fake-bin ":" runtime-bin ":"
                      (build-bin "(@ (gnu packages freedesktop) wtype)") ":"
                      (System/getenv "PATH"))})
(defn run [& args] (:out (apply p/shell {:out :string :extra-env env} args)))
(def clips (sort (map str (fs/glob "desktop/build/ane/fixtures" "*.flac"))))
(defn reference [clip] (slurp (str/replace clip #"\.flac$" ".txt")))

;; The runtime's Python decodes a fixture and plays it as raw 16 kHz s16 at
;; real speed, standing in for pw-record.
(def wrapper (slurp (str runtime-bin "/mlx-omarchy-parakeet")))
(defn player [clip]
  [(second (re-find #"exec (\S+/python3\.14)" wrapper)) "-P" "-c"
   (str "import sys, time\n"
        "sys.path.insert(0, '" (second (re-find #"PYTHONPATH=([^$]+)\$" wrapper)) "')\n"
        "import soundfile\n"
        "w, _ = soundfile.read(sys.argv[1], dtype='int16')\n"
        "for i in range(0, len(w), 1600):\n"
        "    sys.stdout.buffer.write(w[i:i+1600].astype('<i2').tobytes()); sys.stdout.flush(); time.sleep(0.1)\n"
        "time.sleep(600)\n")
   clip])

(defn typed-calls []
  (if (fs/exists? typed)
    (map edn/read-string (str/split-lines (slurp typed)))
    []))

(try
  (fs/create-dirs fake-bin)
  (spit (str fake-bin "/wtype")
        (str "#!/usr/bin/env bb\n(spit " (pr-str typed)
             " (str (pr-str [(System/currentTimeMillis) (last *command-line-args*)]) \"\\n\") :append true)\n"))
  (fs/set-posix-file-permissions (str fake-bin "/wtype") "rwxr-xr-x")

  (doseq [clip clips]
    (let [wer (wer/word-error-rate (reference clip) (run dictate "--file" clip "--print"))]
      (check! (<= wer max-wer) (format "dictate --file %s word error rate %.3f" (fs/file-name clip) wer))))

  (doseq [clip clips]
    (fs/delete-if-exists typed)
    (let [clip-ms (quot (parse-long (second (re-find #"(\d+) samples" (run "file" clip)))) 16)
          session (p/process {:extra-env (assoc env "DICTATE_RECORDER" (pr-str (player clip)))
                              :out :string :err :string}
                             dictate "start")
          ]
      ;; Release just after the clip ends, like letting go of Cmd+M.
      (Thread/sleep (+ clip-ms 400))
      (let [before-stop (count (typed-calls))]
        (run dictate "stop")
        (let [result (deref session 20000 :timeout)
              text (str/join "" (map second (typed-calls)))
              wer (wer/word-error-rate (reference clip) text)]
          (check! (and (map? result) (zero? (:exit result))) (str "session for " (fs/file-name clip) " exits cleanly"))
          (check! (>= before-stop 2) (format "%s: typed %d times while still listening" (fs/file-name clip) before-stop))
          (check! (<= wer max-wer) (format "%s streamed word error rate %.3f: %s" (fs/file-name clip) wer text))))))

  (check! (re-find #"daemon ready" (slurp (str state "/daemon.log"))) "dictate started the resident daemon")

  (fs/delete-if-exists typed)
  (let [session (p/process {:extra-env env :out :string :err :string} dictate "start")]
    (Thread/sleep 2500)
    (check! (fs/exists? (str state "/session.pid")) "a microphone session is running")
    (run dictate "stop")
    (let [result (deref session 20000 :timeout)]
      (check! (and (map? result) (zero? (:exit result))) "the microphone session stops cleanly")))
  (check! (not-any? #(fs/exists? (str state "/" %)) ["session.pid" "stop"]) "dictate is idle again")
  (finally
    (p/shell {:continue true :out :string :err :string}
             (str runtime-bin "/mlx-omarchy-ane-worker") "--stop" "--socket" (str state "/ane.sock"))
    (fs/delete-tree scratch)))

(when (seq @failures)
  (println (count @failures) "check(s) failed")
  (System/exit 1))
