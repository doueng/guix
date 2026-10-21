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
                  keyleader/log! (fn [& _] nil)
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
                  keyleader/log! (fn [& _] nil)
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

(deftest herdr-runs-single-keys-and-plugin-actions
  (is (= [["SPC" "Command palette"] ["," "Switch buffer, tab or window"] ["e" "Edit scrollback"]
          ["w" "Windows and panes ..."] ["b" "Buffers, tabs and windows ..."] ["s" "Search ..."]
          ["p" "Projects ..."] ["TAB" "Workspaces ..."]]
         (keyleader/menu grammar :herdr nil)))
  (is (= [["." "Switch workspace"] ["`" "Last workspace"]] (keyleader/menu grammar :herdr "TAB")))
  (is (= [["l" "Copy visible text by hint"]] (keyleader/menu grammar :herdr "s"))
      "Doom's SPC s l jumps to a visible link")
  (let [calls (atom [])]
    (with-redefs [keyleader/herdr-pane (constantly "w1:p2")
                  keyleader/log! (fn [& _] nil)
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
        "Alt+Space . launches an application")
    (is (lines "hl.bind(mods .. \"grave\", keys.leave(hl.dsp.focus({ last = true }), \"Alt+Space `\", \"Last buffer or window\"))"))
    (is (lines "hl.bind(mods .. \"grave\", keys.leave(hl.dsp.focus({ workspace = \"previous\" }), \"Alt+Space TAB `\", \"Last workspace\"))")))
  (let [ran (atom [])]
    (with-redefs [keyleader/log! (fn [& _] nil)
                  keyleader/spawn! (fn [argv] (swap! ran conj argv))]
      (keyleader/run-desktop! grammar ["."]))
    (is (= [["~/.local/bin/custom-launcher"]] @ran))))

(deftest hyprland-leader-runs-dispatchers-or-the-script
  (let [lines (set (map str/trim (str/split-lines (keyleader/hypr-config grammar))))]
    (is (lines "hl.bind(\"ALT + SPACE\", hl.dsp.submap(\"leader\"))"))
    (is (lines "hl.bind(mods .. \"H\", keys.leave(hl.dsp.focus({ direction = \"left\" }), \"Alt+Space w h\", \"Focus left\"))"))
    (is (lines "hl.bind(mods .. \"D\", keys.leave(hl.dsp.window.close(), \"Alt+Space w d\", \"Close window or pane\"))")
        "Closing runs in Hyprland without asking")
    (is (lines "hl.bind(mods .. \"B\", keys.leave(hl.dsp.exec_cmd(\"~/.local/bin/keyleader run b b\")))")
        "Menus go through the script")
    (is (lines "hl.bind(\"catchall\", keys.cancel(\"leader-w\"))"))))

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
