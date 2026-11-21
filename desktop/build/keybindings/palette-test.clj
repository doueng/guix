(ns palette-test
  (:require [babashka.classpath :as cp]
            [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]))
(load-file "desktop/bin/palette")
(require '[dusk.palette :as dusk])
(cp/add-classpath "desktop/build/rexmux/sidecar")
(require '[rexmux.registry :as reg])

;; This checkout's tables, not the ones linked into ~/.config.
(alter-var-root #'keyleader/config-path (constantly (constantly "desktop/configs/keybindings/leader.edn")))
(alter-var-root #'keyguide/config-path (constantly (constantly "desktop/configs/keybindings/bindings.edn")))
(def grammar (keyleader/load-grammar))

(deftest editors-win-over-the-shell-that-started-them
  (let [id (fn [ps] (select-keys (palette/program-of ps) [:id :pid]))]
    (is (= {:id :emacs :pid 7} (id [{:name "fish" :pid 1} {:name ".emacs-31.1-rea" :pid 7}])))
    (is (= {:id :nvim :pid 3} (id [{:name "nvim" :pid 3}])))
    (is (= {:id :jjui :pid 4} (id [{:name "jjui" :pid 4}])))
    (is (= {:id :fish :pid 1} (id [{:name "fish" :pid 1}])))
    (is (nil? (palette/program-of [{:name "pi" :pid 9}])))))

(def rex-actions
  [{:name "mx.leader.t_n" :title "Leader: New tab"} {:name "mx.leader.t_shift_h" :title "Leader: Move tab left"}
   {:name "mx.tab.new" :title "Tabs: new"} {:name "mx.agent.pick" :title "Agents: pick"}])

(defn entries [ctx & [history]]
  (with-redefs [dusk/history (constantly (or history []))]
    (palette/launcher-entries grammar ctx)))

(deftest the-focused-programs-commands-come-before-the-global-ones
  (let [sources (fn [ctx] (distinct (map :source (entries ctx))))]
    (with-redefs [palette/fish-entries (constantly [{:label "ls" :source "History" :value "text:ls"}])]
      (is (= ["History" "Herdr" "Desktop" "Guide"] (sources {:kind :herdr :program {:id :fish}})))
      (is (= ["Herdr" "Desktop" "Guide"] (sources {:kind :herdr :program nil}))))
    (is (= ["jjui" "Herdr" "Desktop" "Guide"] (sources {:kind :herdr :program {:id :jjui}})))
    (is (= ["jjui" "Rex" "Desktop" "Guide"] (sources {:kind :rex :program {:id :jjui} :actions rex-actions})))
    (is (= ["Chrome" "Desktop" "Guide"] (sources {:kind :chrome})))
    (is (= "IntelliJ" (first (sources {:kind :idea}))))
    (is (= ["Desktop" "Guide"] (sources {:kind :desktop}))))
  (testing "a recent global choice stays below the local rows"
    (let [es (entries {:kind :chrome} ["desktop:leader:b b"])]
      (is (= "Chrome" (:source (first es))))
      (is (= "desktop:leader:b b" (:value (first (filter #(= "Desktop" (:source %)) es))))))))

(deftest each-row-shows-its-keys
  (let [chrome (entries {:kind :chrome})
        rex (entries {:kind :rex :actions rex-actions})
        herdr (entries {:kind :herdr})]
    (is (some #(= {:label "New tab" :keys "Alt+Space t n" :value "chord:ctrl+shift+y"} (select-keys % [:label :keys :value])) chrome))
    (is (some #(= {:label "Find in page" :keys "/"} (select-keys % [:label :keys])) chrome) "Vimium keys too")
    (is (= [{:label "New tab" :keys "Alt+Space t n" :value "rex:mx.leader.t_n"}
            {:label "Move tab left" :keys "Alt+Space t H" :value "rex:mx.leader.t_shift_h"}]
           (map #(select-keys % [:label :keys :value]) (filter :keys (filter #(= "Rex" (:source %)) rex))))
        "Rex rows the client declares, Alt+Space keys first")
    (is (= ["Tabs: new" "Agents: pick"] (map :label (remove :keys (filter #(= "Rex" (:source %)) rex))))
        "then the rest of the client's M-x")
    (is (some #(= {:label "New tab" :keys "Alt+Space t n"} (select-keys % [:label :keys])) herdr))
    (is (some #(= {:label "Choose buffer, tab or window" :keys "Cmd+Space b b" :shortcut "bb"}
                  (select-keys % [:label :keys :shortcut]))
              herdr)
        "old Cmd+Space sequences show their keys and are typed as shortcuts")))

(deftest rex-rows-name-the-actions-the-sidecar-declares
  (let [declared (into {} (for [c (reg/leader-commands grammar ["alt+space"]) :when (not= :leader/palette (:id c))]
                            [(first (:keys c)) (reg/action-name (:id c))]))]
    (is (seq declared))
    (doseq [[ks action] declared
            :let [keys (map #(cond (= "tab" %) "TAB" (re-matches #"shift\+([a-z])" %) (str/upper-case (subs % 6)) :else %) (rest ks))]]
      (is (= action (palette/rex-action keys)) (str/join " " keys)))))

(defn run-with [value ctx]
  (let [calls (atom [])]
    (with-redefs [keyleader/run! (fn [argv & _] (swap! calls conj (vec argv)) "")
                  keylog/log! (fn [& _] nil)
                  palette/type-keys! (fn [k] (swap! calls conj k))]
      (palette/run-entry! grammar ctx value))
    @calls))

(deftest choices-run-where-they-came-from
  (is (= [["herdr" "pane" "send-text" "w1:p2" "git status"]] (run-with "text:git status" {:kind :herdr :pane "w1:p2"}))
      "shell text is inserted, never run")
  (is (= [["rex" "send" "-s" "session:1" "-b" "block:1" "n"]] (run-with "text:n" {:kind :rex :session "session:1" :block "block:1"}))
      "a jjui key is typed into the Rex pane")
  (is (= [["herdr" "tab" "close" "w1:t3"]] (run-with "herdr:leader:b d" {:pane "w1:p2" :tab "w1:t3" :workspace "w1"}))
      "Herdr actions use the focused tab, not the palette's environment")
  (is (= [["rex" "-C" "ghostty-rex" "do" "mx.leader.t_n"]] (run-with "rex:mx.leader.t_n" {:kind :rex :client "ghostty-rex"})))
  (is (= [{:chord "ctrl+shift+y"}] (run-with "chord:ctrl+shift+y" {:kind :chrome})))
  (is (= [{:chord "esc"} {:type ":action ReformatCode"} {:chord "enter"}] (run-with "idea:ReformatCode" {})))
  (is (= [{:label "New tab" :keys "Ctrl+T" :chord "ctrl+t"}]
         (run-with (str "chrome:" (first (keep-indexed #(when (= "New tab" (:label %2)) %1) (palette/config :chrome)))) {}))))

(deftest power-asks-before-it-acts
  (let [ran (atom [])
        entry (some #(when (= "Power off" (get-in % [:palette :label])) %) (keyleader/guide-entries))]
    (is entry)
    (with-redefs [keyguide/run-entry! (fn [e] (swap! ran conj (:label e)))
                  keylog/log! (fn [& _] nil)]
      (with-redefs [dusk/pick! (fn [_ _] "no")]
        (keyleader/run-desktop-entry! grammar (get-in entry [:palette :value])))
      (is (= [] @ran) "Cancel runs nothing")
      (with-redefs [dusk/pick! (fn [_ _] "yes")]
        (keyleader/run-desktop-entry! grammar (get-in entry [:palette :value])))
      (is (= ["Power off"] @ran)))))

(deftest chords-release-in-reverse
  (is (= ["29:1" "42:1" "20:1" "20:0" "42:0" "29:0"] (keyleader/chord-events "ctrl+shift+t")))
  (is (= ["29:1" "109:1" "109:0" "29:0"] (keyleader/chord-events "ctrl+pagedown"))))

(deftest an-editor-that-does-not-answer-falls-back-to-the-list
  (let [shown (atom nil) rows (atom [])]
    (with-redefs [keyleader/load-grammar (constantly grammar)
                  keylog/log! (fn [& row] (swap! rows conj (vec row)))
                  palette/context (constantly {:kind :herdr :pane "w1:p2" :program {:id :emacs :pid 42}})
                  palette/sh-out (constantly nil)
                  palette/notify! (fn [_] nil)
                  dusk/pick! (fn [prompt entries] (reset! shown [prompt (count entries)]) nil)]
      (palette/open!))
    (is (= "Emacs" (first @shown)))
    (is (pos? (second @shown)))
    (is (= [["cancel" "palette" "Cmd+Space" "Closed the launcher"]] @rows)
        "closing the list logs a cancel")))

(deftest emacs-lists-its-commands-with-the-launcher-rows-after-them
  (let [asked (atom [])]
    (with-redefs [keyleader/load-grammar (constantly grammar)
                  palette/context (constantly {:kind :herdr :pane "w1:p2" :program {:id :emacs :pid 42 :name ".emacs-31.1-rea"}})
                  palette/fish-entries (constantly [{:label "ls" :source "History" :value "text:ls"}])
                  palette/sh-out (fn [& argv] (swap! asked conj (vec argv)) "t")
                  dusk/pick! (fn [& _] (throw (ex-info "must not show the list" {})))]
      (palette/open!))
    (let [[[cmd s server eval form]] @asked
          extra (read-string (second (re-matches #"(?s)\(eng/palette-M-x nil (\".*\")\)" form)))
          rows (clojure.edn/read-string extra)]
      (is (= ["emacsclient" "-s" "eng-42" "--eval"] [cmd s server eval]))
      (is (some #(= {:label "New tab" :keys "Alt+Space t n" :value "ext:herdr:leader:t n"} (select-keys % [:label :keys :value])) rows)
          "Herdr's rows come back to palette run")
      (is (some #(= "Desktop" (:source %)) rows))
      (is (not-any? #(str/starts-with? (:value %) "ext:text:") rows) "no shell text is typed into Emacs"))))

(deftest an-editor-that-answers-opens-its-own-palette
  (let [asked (atom [])]
    (with-redefs [keyleader/load-grammar (constantly grammar)
                  palette/context (constantly {:kind :herdr :program {:id :nvim :pid 42}})
                  palette/process-tree (constantly ["42"])
                  palette/process-sockets (constantly ["/tmp/custom.nvim"])
                  palette/sh-out (fn [& argv]
                                    (swap! asked conj (vec argv))
                                    (if (= "getpid()" (last argv)) "42" "1"))
                  dusk/pick! (fn [& _] (throw (ex-info "must not show the list" {})))]
      (palette/open!))
    (is (= [["nvim" "--server" "/tmp/custom.nvim" "--remote-expr" "getpid()"]
            ["nvim" "--server" "/tmp/custom.nvim" "--remote-expr"
             "luaeval(\"require('config.palette').open() and 1 or 0\")"]] @asked))))

(deftest nvim-must-acknowledge-opening-and-match-the-target-pid
  (doseq [reply [nil "0"]]
    (with-redefs [palette/process-tree (constantly ["42"])
                  palette/process-sockets (constantly ["/tmp/nvim"])
                  palette/sh-out (fn [& args] (if (= "getpid()" (last args)) "42" reply))]
      (is (not (palette/open-nvim! 42)))))
  (let [calls (atom [])]
    (with-redefs [palette/process-tree (constantly ["42"])
                  palette/process-sockets (constantly ["/tmp/wrong-instance"])
                  palette/sh-out (fn [& args] (swap! calls conj (last args)) "99")]
      (is (not (palette/open-nvim! 42)))
      (is (= ["getpid()"] @calls)))))

(deftest a-terminal-nvim-answers-through-its-embedded-server
  (with-redefs [palette/process-sockets (fn [pid] (if (= "42" pid) ["/run/user/1000/nvim.42.0"] []))
                palette/sh-out (fn [& argv]
                                  (case (last argv)
                                    "/proc/41/task/41/children" "42\n"
                                    "/proc/42/task/42/children" ""
                                    "getpid()" "42"
                                    "1"))]
    (is (palette/open-nvim! 41))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
