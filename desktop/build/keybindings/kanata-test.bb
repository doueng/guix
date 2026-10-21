;; Usage: bb desktop/build/keybindings/kanata-test.bb <kanata_simulated_input>
;; Replays chords through desktop/kanata.kbd in kanata's simulator and compares
;; the emitted events with the chords each binding promises.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/real-path *file*)))))))
(def config (str root "/desktop/kanata.kbd"))
(def sim (or (first *command-line-args*)
             (throw (ex-info "Pass the kanata_simulated_input binary" {}))))

(defn emitted
  "Key events kanata emits for INPUT, a space-separated list of sim events."
  [input]
  (let [file (str (fs/create-temp-file {:suffix ".txt"}))
        events (str (str/join " t:10 " (str/split input #" ")) " t:600")]
    (try
      (spit file events)
      (let [{:keys [exit out err]} @(p/process [sim "-c" config "-s" file] {:out :string :err :string})]
        (when-not (zero? exit) (throw (ex-info "Simulation failed" {:input input :error err})))
        (->> (str/split-lines out) (keep #(second (re-matches #"out:(.*)" %))) (str/join " ")))
      (finally (fs/delete-if-exists file)))))

(def cases
  [["Cmd+C copies with Ctrl+Insert" "d:lmet d:c u:c u:lmet"
    "↓LGui ↑LGui ↓LCtrl ↓Insert ↑LCtrl ↑Insert ↓LGui ↑LGui"]
   ["Unmapped Cmd chords reach Hyprland as Super" "d:lmet d:b u:b u:lmet"
    "↓LGui ↓B ↑B ↑LGui"]
   ["Cmd+Shift+Z redoes with Ctrl+Y" "d:lmet d:lsft d:z u:z u:lsft u:lmet"
    "↓LGui ↓LShift ↑LGui ↑LShift ↓LCtrl ↓Y ↑LCtrl ↑Y ↓LGui ↓LShift ↑LShift ↑LGui"]
   ["Cmd+Shift+T keeps Shift" "d:lmet d:lsft d:t u:t u:lsft u:lmet"
    "↓LGui ↓LShift ↑LGui ↓LCtrl ↓T ↑LCtrl ↑T ↓LGui ↑LShift ↑LGui"]
   ["Cmd+Backspace deletes to the line start" "d:lmet d:bspc t:100 u:bspc u:lmet"
    "↓LGui ↑LGui ↓LShift ↓Home ↑LShift ↑Home ↓LGui ↑LGui ↓BSpace ↑BSpace ↓LGui ↑LGui"]
   ["Cmd+Shift+H selects left" "d:lmet d:lsft d:h u:h u:lsft u:lmet"
    "↓LGui ↓LShift ↑LGui ↓Left ↑Left ↓LGui ↑LShift ↑LGui"]
   ["Right Cmd+5 sends Ctrl+5" "d:rmet d:5 u:5 u:rmet"
    "↓RGui ↑RGui ↓LCtrl ↓Kb5 ↑LCtrl ↑Kb5 ↓RGui ↑RGui"]
   ["Caps tapped is Escape" "d:caps u:caps" "↓Escape ↑Escape"]
   ["Caps held alone becomes Ctrl" "d:caps t:600 u:caps" "↓LCtrl ↑LCtrl"]
   ["Caps+Alt+H is Ctrl+Alt+H" "d:caps d:lalt d:h u:h u:lalt u:caps"
    "↓LCtrl ↓LAlt ↓H ↑H ↑LAlt ↑LCtrl"]
   ["Caps+Alt+Shift+H is Ctrl+Alt+Shift+H" "d:caps d:lalt d:lsft d:h u:h u:lsft u:lalt u:caps"
    "↓LCtrl ↓LAlt ↓LShift ↓H ↑H ↑LShift ↑LAlt ↑LCtrl"]
   ["Alt+Space reaches the desktop leader" "d:lalt d:spc u:spc u:lalt"
    "↓LAlt ↓Space ↑Space ↑LAlt"]
   ["Alt+Backspace deletes a word" "d:lalt d:bspc u:bspc u:lalt"
    "↓LAlt ↑LAlt ↓LCtrl ↓BSpace ↑LCtrl ↑BSpace ↓LAlt ↑LAlt"]
   ["Alt+Right moves a word" "d:ralt d:right u:right u:ralt"
    "↓RAlt ↑RAlt ↓LCtrl ↓Right ↑LCtrl ↑Right ↓RAlt ↑RAlt"]
   ["Left Shift tapped types (" "d:lsft u:lsft" "↓LShift ↓Kb9 ↑LShift ↑Kb9"]
   ["Right Shift tapped types )" "d:rsft u:rsft" "↓LShift ↓Kb0 ↑LShift ↑Kb0"]
   ["Shift+A types A" "d:lsft d:a u:a u:lsft" "↓LShift ↓A ↑A ↑LShift"]
   ;; keyberon names code 248 MediaMute. Linux reads it as KEY_MICMUTE.
   ["Sleep mutes the microphone" "d:sleep u:sleep" "↓MediaMute ↑MediaMute"]
   ["IntelliJ Cmd+Shift+Z redoes with Ctrl+Shift+Z" "ls:intellij d:lmet d:lsft d:z u:z u:lsft u:lmet"
    "↓LGui ↓LShift ↑LGui ↓LCtrl ↓Z ↑LCtrl ↑Z ↓LGui ↑LShift ↑LGui"]
   ["IntelliJ Cmd+C still copies" "ls:intellij d:lmet d:c u:c u:lmet"
    "↓LGui ↑LGui ↓LCtrl ↓Insert ↑LCtrl ↑Insert ↓LGui ↑LGui"]
   ["IntelliJ keeps Caps as Escape" "ls:intellij d:caps u:caps" "↓Escape ↑Escape"]])

(def defined-layers
  (set (map second (re-seq #"\(deflayermap \(([^)\s]+)\)" (slurp config)))))
(def app-layers (vals (edn/read-string (slurp (str root "/desktop/configs/kanata/apps.edn")))))

(def failures
  (concat
   (for [[label input expected] cases
         :let [actual (emitted input)]
         :when (not= expected actual)]
     (str label "\n  expected " expected "\n  actual   " actual))
   (for [layer app-layers :when (not (defined-layers layer))]
     (str "apps.edn names layer " layer ", which kanata.kbd does not define"))))

(if (seq failures)
  (do (run! println failures) (System/exit 1))
  (println (count cases) "kanata cases and" (count app-layers) "app layers passed"))
