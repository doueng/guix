#!/usr/bin/env bb
;; Every keybinding test: kanata chords, generated binds, Hyprland's config, the
;; launcher, leader, coach and Neovim. Tests must not write the real keybinding
;; log; the Neovim one gets its own.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def env {"KEYLOG_FILE" "/dev/null"})
(def ert ["-f" "ert-run-tests-batch-and-exit"])

(defn step! [& argv]
  (println "==>" (str/join " " argv))
  (let [{:keys [exit]} (apply p/shell {:continue true :extra-env env} argv)]
    (when-not (zero? exit) (System/exit exit))))

(step! "bb" "desktop/build/keybindings/kanata-check.bb")
(step! "bb" "desktop/build/keybindings/generate.bb" "--check")
(step! "Hyprland" "--verify-config" "-c" "desktop/configs/hypr/hyprland.lua")
(doseq [t ["test" "leader-test" "review-test" "palette-test" "winhint-test" "usage-test"]]
  (step! "bb" (str "desktop/build/keybindings/" t ".clj")))
(doseq [t ["navigate-test" "coach-test" "palette-test"]]
  (apply step! "emacs" "--batch" "-Q" "-l" (str "desktop/build/keybindings/" t ".el") ert))
(let [log (str (fs/create-temp-file))
      {:keys [exit]} (p/shell {:continue true :extra-env {"KEYLOG_FILE" log}
                               :env (apply dissoc (into {} (System/getenv)) ["HERDR_PANE_ID" "HERDR_ENV" "HERDR_SOCKET_PATH"])}
                             "nvim" "--headless"
                             "+lua local ok, err = pcall(require(\"fennel\").dofile, \"desktop/build/keybindings/nvim-leader-test.fnl\"); if not ok then print(err); vim.cmd(\"cquit 1\") end")]
  (fs/delete-if-exists log)
  (System/exit exit))
