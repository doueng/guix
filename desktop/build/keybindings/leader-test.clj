(ns leader-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.edn :as edn]
            [clojure.string :as str]))
(load-file "desktop/bin/keyleader")
(require '[hypr.dispatch :as hypr])

(def grammar
  (keyleader/validate! (edn/read-string (slurp "desktop/configs/keybindings/leader.edn"))))

(defn nav-with [pane edges]
  (let [calls (atom [])]
    (with-redefs [keyleader/herdr-pane (constantly pane)
                  keyleader/herdr-json (fn [& _] {:result {:edges edges}})
                  keylog/log! (fn [& _] nil)
                  hypr/dispatch! (fn [d] (swap! calls conj d))
                  keyleader/run! (fn [argv & _] (swap! calls conj (vec argv)) "")]
      (keyleader/nav! "left"))
    @calls))

(deftest navigation-falls-through-from-herdr-to-hyprland
  (is (= [["herdr" "pane" "focus" "--direction" "left" "--pane" "w1:p2"]]
         (nav-with "w1:p2" {:left false :right true})))
  (is (= [["focus" {:direction "left"}]]
         (nav-with "w1:p2" {:left true :right false})))
  (is (= [["focus" {:direction "left"}]]
         (nav-with nil nil))))

(defn term-nav-with [program direction]
  (let [calls (atom [])]
    (with-redefs [keyleader/herdr-pane (constantly "w1:p2")
                  keyleader/foreground-programs (constantly [program])
                  keylog/log! (fn [& _] nil)
                  keyleader/nav! (fn [d _] (swap! calls conj [:nav d]))
                  keyleader/run! (fn [argv & _] (swap! calls conj (vec argv)) "")]
      (keyleader/term-nav! grammar direction))
    @calls))

(deftest shells-keep-ctrl-l-and-editors-keep-all-four
  (is (= [["herdr" "pane" "send-keys" "w1:p2" "ctrl+l"]] (term-nav-with "fish" "right")))
  (is (= [[:nav "left"]] (term-nav-with "fish" "left")))
  (is (= [["herdr" "pane" "send-keys" "w1:p2" "ctrl+h"]] (term-nav-with "nvim" "left")))
  (is (= [[:nav "right"]] (term-nav-with "btop" "right"))))

(deftest programs-keep-only-the-keys-they-use
  (is (= [[:nav "left"]] (term-nav-with "pi" "left")) "Pi has no Ctrl+h, so focus moves")
  (is (= [["herdr" "pane" "send-keys" "w1:p2" "ctrl+j"]] (term-nav-with "pi" "down")))
  (is (= [["herdr" "pane" "send-keys" "w1:p2" "ctrl+k"]] (term-nav-with "fzf" "up")) "fzf moves its selection with Ctrl+j/k")
  (is (= [[:nav "right"]] (term-nav-with "fzf" "right"))))

(deftest window-keys-mean-the-same-in-every-context
  (doseq [context [:desktop :herdr :nvim :doom :idea]]
    (is (= [["h" "Focus left"] ["j" "Focus down"] ["k" "Focus up"] ["l" "Focus right"]]
           (take 4 (keyleader/menu grammar context "w")))
        (str context))))

(deftest root-menu-starts-with-the-palette
  (is (= ["SPC" "Command palette"] (first (keyleader/menu grammar :herdr nil))))
  (is (= ["SPC" "Command palette"] (first (keyleader/menu grammar :desktop nil))))
  (is (not-any? #(= "o" (first %)) (keyleader/menu grammar :herdr nil))
      "Herdr has no application entries, so its menu must not offer the o group"))

(deftest dot-picks-and-d-closes-in-one-key
  (is (some #{["d" "Close window or pane"]} (keyleader/menu grammar :desktop nil)))
  (is (not-any? #(= "d" (first %)) (keyleader/menu grammar :doom nil))
      "Doom's SPC d stays its debugger group")
  (let [lines (set (map str/trim (str/split-lines (keyleader/hypr-config grammar))))]
    (is (lines "hl.bind(mods .. \"D\", keys.leave(hl.dsp.window.close(), \"Cmd+Space d\", \"Close window or pane\"))")))
  (let [ran (atom [])]
    (with-redefs [keylog/log! (fn [& _] nil)
                  keyleader/choose-workspace! (fn [] (swap! ran conj :choose-workspace))
                  keyleader/run! (fn [argv] (swap! ran conj argv))]
      (keyleader/run-herdr! grammar ["."] {:pane "w1:p2" :tab "w1:t1" :workspace "w1"})
      (keyleader/run-herdr! grammar ["d"] {:pane "w1:p2" :tab "w1:t1" :workspace "w1"}))
    (is (= [:choose-workspace ["herdr" "pane" "close" "w1:p2"]] @ran))))

(deftest herdr-runs-single-keys-and-plugin-actions
  (is (= [["SPC" "Command palette"] ["," "Switch buffer, tab or window"]
          ["." "Find file, launch app or switch workspace"] ["d" "Close window or pane"]
          ["e" "Edit scrollback"] ["w" "Windows and panes ..."] ["b" "Buffers, tabs and windows ..."] ["s" "Search ..."]
          ["p" "Projects ..."] ["TAB" "Workspaces ..."]]
         (keyleader/menu grammar :herdr nil)))
  (is (= [["." "Switch workspace"] ["`" "Last workspace"]] (keyleader/menu grammar :herdr "TAB")))
  (is (= [["l" "Copy visible text by hint"]] (keyleader/menu grammar :herdr "s"))
      "Doom's SPC s l jumps to a visible link")
  (let [calls (atom [])]
    (with-redefs [keyleader/herdr-pane (constantly "w1:p2")
                  keylog/log! (fn [& _] nil)
                  keyleader/herdr-api! (fn [method params] (swap! calls conj [method params]))]
      (keyleader/run-herdr! grammar ["s" "l"])
      (keyleader/run-herdr! grammar ["e"])
      (keyleader/run-herdr! grammar ["TAB" "`"]))
    (is (= [["plugin.action.invoke" {:plugin_id "hotchpotch.herdr-tiny-fingers" :action_id "open"
                                     :context {:focused_pane_id "w1:p2"}}]
            ["pane.edit_scrollback" {:pane_id "w1:p2"}]
            ["plugin.action.invoke" {:plugin_id "fullerzz.sesh" :action_id "last"
                                     :context {:focused_pane_id "w1:p2"}}]]
           @calls))))

(deftest frequent-actions-take-one-key-on-the-desktop
  (let [lines (set (map str/trim (str/split-lines (keyleader/hypr-config grammar))))]
    (is (lines "hl.bind(mods .. \"period\", keys.leave(hl.dsp.exec_cmd(\"~/.local/bin/keyleader run '.'\")))")
        "Cmd+Space . launches an application")
    (is (lines "hl.bind(mods .. \"grave\", keys.leave(hl.dsp.focus({ last = true }), \"Cmd+Space `\", \"Last buffer or window\"))"))
    (is (lines "hl.bind(mods .. \"grave\", keys.leave(hl.dsp.focus({ workspace = \"previous\" }), \"Cmd+Space TAB `\", \"Last workspace\"))")))
  (let [ran (atom [])]
    (with-redefs [keylog/log! (fn [& _] nil)
                  keyleader/spawn! (fn [argv] (swap! ran conj argv))]
      (keyleader/run-desktop! grammar ["."]))
    (is (= [["~/.local/bin/custom-launcher"]] @ran))))

(deftest hyprland-leader-runs-dispatchers-or-the-script
  (let [lines (set (map str/trim (str/split-lines (keyleader/hypr-config grammar))))]
    (is (lines "hl.bind(\"SUPER + SPACE\", keys.enter(\"leader\", \"Cmd+Space\"))")
        "Entering the leader logs a leader row")
    (is (lines "hl.bind(mods .. \"H\", keys.leave(hl.dsp.focus({ direction = \"left\" }), \"Cmd+Space w h\", \"Focus left\"))"))
    (is (lines "hl.bind(mods .. \"D\", keys.leave(hl.dsp.window.close(), \"Cmd+Space w d\", \"Close window or pane\"))")
        "Closing runs in Hyprland without asking")
    (is (lines "hl.bind(mods .. \"B\", keys.leave(hl.dsp.exec_cmd(\"~/.local/bin/keyleader run b b\")))")
        "Menus go through the script")
    (is (lines "hl.bind(\"catchall\", keys.cancel(\"leader-w\", \"Cmd+Space w\"))"))
    (is (lines "hl.bind(\"escape\", keys.cancel(\"leader\", \"Cmd+Space\", \"Escape\"))")
        "Cancels log the keys pressed before them")))

(defn herdr-leader-with
  "The keylog rows and herdr calls when the Herdr popup reads KEYS."
  [& keys]
  (let [rows (atom []) calls (atom []) pending (atom keys)]
    (with-redefs [keyleader/herdr-pane (constantly "w1:p2")
                  keyleader/show! (fn [& _] nil)
                  keyleader/read-key (fn [] (let [k (first @pending)] (swap! pending rest) k))
                  keylog/log! (fn [& row] (when-not keylog/*silent* (swap! rows conj (vec row))))
                  keyleader/herdr-api! (fn [method _] (swap! calls conj method))
                  dusk.palette/pick! (fn [_ entries] (some #(when (= "Edit scrollback" (:label %)) (:value %)) entries))
                  dusk.palette/by-history (fn [_ entries] entries)
                  dusk.palette/remember! (fn [& _] nil)]
      (apply keyleader/herdr-leader! [grammar]))
    {:rows @rows :calls @calls}))

(deftest herdr-leader-logs-opening-acting-and-cancelling
  (is (= [["leader" "herdr" "Ctrl+b Space" "Herdr leader menu"]
          ["action" "herdr" "Ctrl+b Space e" "Edit scrollback"]]
         (:rows (herdr-leader-with "e"))))
  (is (= [["leader" "herdr" "Ctrl+b Space" "Herdr leader menu"]
          ["cancel" "herdr" "Ctrl+b Space" "Cancelled with Escape"]]
         (:rows (herdr-leader-with :escape))))
  (is (= [["leader" "herdr" "Ctrl+b Space" "Herdr leader menu"]
          ["cancel" "herdr" "Ctrl+b Space w" "Cancelled by unbound z"]]
         (:rows (herdr-leader-with "w" "z"))))
  (let [{:keys [rows calls]} (herdr-leader-with "SPC")]
    (is (= ["pane.edit_scrollback"] calls) "the palette runs its choice")
    (is (= [["leader" "herdr" "Ctrl+b Space" "Herdr leader menu"]
            ["action" "herdr" "Ctrl+b Space Space" "Edit scrollback"]]
           rows)
        "a palette choice is logged once, under the keys that chose it")))

(deftest kept-keys-are-logged-as-forwarded-by-herdr
  (let [rows (atom [])]
    (with-redefs [keyleader/herdr-pane (constantly "w1:p2")
                  keyleader/foreground-programs (constantly [".emacs-31.1-rea"])
                  keylog/log! (fn [& row] (swap! rows conj (vec row)))
                  keyleader/run! (fn [& _] "")]
      (keyleader/term-nav! grammar "left"))
    (is (= [["forward" "herdr" "Ctrl+h" "Forwarded to emacs"]] @rows))))

(deftest grammar-rejects-ambiguity
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate leader sequence"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"} {:keys ["w" "h"] :label "Right" :nav "right"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"known group"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]] :bindings [{:keys ["x" "h"] :label "Left" :nav "left"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doom-match must be one of"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]]
                              :bindings [{:keys [","] :label "Switch" :doom "x" :doom-match :same}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one key that is not a group"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"}
                                         {:keys ["w"] :label "Edit" :herdr :edit-scrollback}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differ only by case"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["c" "Code"]]
                              :bindings [{:keys ["c" "r"] :label "Run" :desktop-command ["a"]}
                                         {:keys ["c" "R"] :label "Restart" :desktop-command ["b"]}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"KEYS from hjkl"
        (keyleader/validate! {:terminal {:keeps [["^fish$" "x"]]} :groups [["w" "W"]]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no context"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]] :bindings [{:keys ["w" "h"] :label "x"}]}))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
