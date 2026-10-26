(ns usage-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))
(load-file "desktop/build/keybindings/usage.clj")
(require '[keybindings-usage :as usage] '[keylog])

(def grammar
  (keyleader/validate! (edn/read-string (slurp "desktop/configs/keybindings/leader.edn"))))

(deftest program-ids-are-stable
  (is (= "emacs" (keylog/program-id ".emacs-31.1-rea")) "Linux truncates Guix's Emacs wrapper")
  (is (= "emacs" (keylog/program-id "Emacs")))
  (is (= "neovim" (keylog/program-id "nvim")))
  (is (= "neovim" (keylog/program-id "Neovim")))
  (is (= "hyprland" (keylog/program-id "Hyprland"))))

(deftest rows-round-trip-with-their-schema
  (let [line (keylog/line "2026-10-26T10:00:00" "forward" "Herdr" "Ctrl+l" "Forwarded\tto fish")]
    (is (= "2026-10-26T10:00:00\therdr\tCtrl+l\tForwarded to fish\tforward\t2\n" line))
    (is (= {:time "2026-10-26T10:00:00" :program "herdr" :keys "Ctrl+l" :effect "Forwarded to fish"
            :kind "forward" :version 2}
           (keylog/parse-line (str/trim-newline line)))))
  (is (thrown? clojure.lang.ExceptionInfo (keylog/line "t" "pressed" "x" "k" "e")))
  (is (nil? (keylog/parse-line "t\tx\tk\te\taction\t3")) "an unknown schema is not guessed at"))

(deftest schema-1-rows-are-normalised
  (let [v1 #(keylog/parse-line (str/join "\t" (cons "2026-10-21T11:35:03" %&)))]
    (is (= ["hyprland" "forward" "Forwarded to herdr"]
           ((juxt :program :kind :effect) (v1 "Hyprland" "Cmd+Space" "Herdr leader menu")))
        "Cmd+Space only passed Ctrl+b Space on to Herdr")
    (is (= ["herdr" "forward" "Forwarded to emacs"]
           ((juxt :program :kind :effect) (v1 ".emacs-31.1-rea" "Ctrl+j" "Forwarded to .emacs-31.1-rea"))))
    (is (= ["keyguide" "action"] ((juxt :program :kind) (v1 "Hyprland" "Cmd+/" "Workspace 2"))))
    (is (= ["neovim" "action" 1] ((juxt :program :kind :version) (v1 "nvim" "Space w h" "Focus left"))))))

(deftest the-shell-writer-writes-schema-2
  (let [file (str (fs/create-temp-file {:prefix "keylog"}))
        add #(apply p/shell {:continue true :out :string :err :string :extra-env {"KEYLOG_FILE" file}}
                    "desktop/bin/keylog" "add" %&)]
    (try
      (is (zero? (:exit (add "cancel" "hyprland" "Alt+Space w" "Cancelled by an unbound key"))))
      (is (= 2 (:exit (add "pressed" "hyprland" "x" "y"))) "unknown kinds are refused")
      (is (= 2 (:exit (add "action" "Hyprland" "x" "y"))) "display names are refused")
      (is (= [{:program "hyprland" :keys "Alt+Space w" :effect "Cancelled by an unbound key" :kind "cancel" :version 2}]
             (map #(dissoc (keylog/parse-line %) :time) (fs/read-all-lines file))))
      (finally (fs/delete file)))))

(deftest cost-counts-every-key-pressed
  (is (= 5 (usage/presses "Ctrl+b Space w h")))
  (is (= 3 (usage/presses "CTRL+ALT+0")))
  (is (= "1.91" (format "%.2f" (usage/klm-seconds 2)))))

(defn row [time program keys effect kind]
  {:time time :program program :keys keys :effect effect :kind kind :version 2})

(def rows
  [(row "2026-10-26T09:00:00" "hyprland" "Alt+Space" "Desktop leader menu" "leader")
   (row "2026-10-26T09:00:01" "hyprland" "Alt+Space w h" "Focus left" "action")
   (row "2026-10-26T09:00:02" "hyprland" "Alt+Space" "Desktop leader menu" "leader")
   (row "2026-10-26T09:00:03" "hyprland" "Alt+Space w" "Cancelled by an unbound key" "cancel")
   (row "2026-10-26T09:00:04" "herdr" "Ctrl+b Space" "Herdr leader menu" "leader")
   (row "2026-10-26T09:00:05" "hyprland" "Ctrl+b Space w l" "Focus right window" "action")
   (row "2026-10-26T09:00:06" "herdr" "Ctrl+b Space" "Herdr leader menu" "leader")
   (row "2026-10-26T09:00:06" "herdr" "Ctrl+b Space Space" "Edit scrollback" "action")
   (row "2026-10-26T09:00:07" "emacs" "Space w k" "Focus up" "action")
   (row "2026-10-26T09:00:08" "emacs" "Space w k" "Focus up" "action")
   (row "2026-10-26T09:00:09" "herdr" "Ctrl+l" "Forwarded to fish" "forward")])

(deftest bindings-are-matched-to-leader-edn-by-the-leader-they-came-through
  (is (= [:herdr ["w" "l"]] (usage/leader-binding (nth rows 5)))
      "Herdr's leader counts for Herdr even when Hyprland finally moved focus")
  (is (= [:doom ["w" "k"]] (usage/leader-binding (nth rows 8))))
  (is (nil? (usage/leader-binding (row "t" "fish" "Space x" "x" "action"))))
  (is (= {[:desktop ["w" "h"]] 1 [:herdr ["w" "l"]] 1 [:herdr ["e"]] 1 [:doom ["w" "k"]] 2}
         (usage/binding-uses rows grammar))
      "a Herdr palette choice counts for its binding"))

(deftest the-report-weights-cost-by-use
  (let [r (usage/report rows grammar {})]
    (is (= {:actions 5 :presses-per-action 3.8} (select-keys (:cost r) [:actions :presses-per-action])))
    (is (= {:keys ["w" "k"] :label "Focus up" :uses 2 :nav "up"} (first (:promote r)))
        "Space w k ran more often than an unused one-key binding")
    (is (= [{:program "hyprland" :keys "Alt+Space" :opens 2 :actions 1 :cancels 1 :cancel-rate 0.5}
            {:program "herdr" :keys "Ctrl+b Space" :opens 2 :actions 2 :cancels 0 :cancel-rate 0.0}]
           (:leaders r)))
    (is (= [[["herdr" "fish"] 1]] (:forwards r)))
    (is (str/includes? (usage/render r 0) "Weighted cost: 3.80 presses per action"))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
