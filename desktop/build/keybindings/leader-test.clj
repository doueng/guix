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
  (is (not-any? #(= "d" (first %)) (keyleader/menu grammar :desktop nil))
      "Alt+W alone closes a desktop window")
  (is (not-any? #(= "d" (first %)) (keyleader/menu grammar :doom nil))
      "Doom's SPC d stays its debugger group")
  (is (not-any? #(str/includes? % "window.close") (str/split-lines (keyleader/hypr-config grammar)))
      "The desktop leader does not duplicate Alt+W")
  (let [ran (atom [])]
    (with-redefs [keylog/log! (fn [& _] nil)
                  keyleader/choose-workspace! (fn [] (swap! ran conj :choose-workspace))
                  keyleader/run! (fn [argv] (swap! ran conj argv))
                  keyleader/spawn! (fn [argv] (swap! ran conj argv) (delay nil))]
      (keyleader/run-herdr! grammar ["."] {:pane "w1:p2" :tab "w1:t1" :workspace "w1"})
      (keyleader/run-herdr! grammar ["d"] {:pane "w1:p2" :tab "w1:t1" :workspace "w1"})
      (keyleader/run-herdr! grammar ["a"] {:pane "w1:p2" :tab "w1:t1" :workspace "w1"}))
    (is (= [:choose-workspace ["herdr" "pane" "close" "w1:p2"] ["~/.local/bin/herdr-agent-pick"]] @ran))))

(deftest herdr-runs-single-keys-and-plugin-actions
  (is (= [["SPC" "Command palette"] ["," "Switch buffer, tab or window"]
          ["." "Find file, launch app or switch workspace"] ["d" "Close pane"]
          ["a" "Agents"] ["e" "Edit scrollback"] ["w" "Windows and panes ..."] ["b" "Buffers, tabs and windows ..."] ["s" "Search ..."]
          ["p" "Projects ..."] ["TAB" "Workspaces ..."] ["t" "Tabs ..."]]
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

(deftest old-cmd-space-sequences-are-launcher-shortcuts
  (let [es (keyleader/desktop-entries grammar)
        by-shortcut (into {} (keep (fn [e] (when (:shortcut e) [(:shortcut e) e])) es))]
    (is (= "Choose buffer, tab or window" (:label (by-shortcut "bb"))) "Cmd+Space b b is typed bb")
    (is (= "Cmd+Space b b" (:keys (by-shortcut "bb"))) "the row still shows the old keys")
    (is (= "Last workspace" (:label (by-shortcut "tab`"))) "TAB is typed tab")
    (is (= "Find file, launch app or switch workspace" (:label (by-shortcut ".")))))
  (let [ran (atom [])]
    (with-redefs [keylog/log! (fn [& _] nil)
                  keyleader/spawn! (fn [argv] (swap! ran conj argv))]
      (keyleader/run-desktop! grammar ["."]))
    (is (= [["~/.local/bin/custom-launcher"]] @ran) "choosing the row runs what Cmd+Space . ran")))

(deftest cmd-space-opens-the-launcher-and-alt-space-routes-by-class
  (let [config (keyleader/hypr-config grammar)
        lines (set (map str/trim (str/split-lines config)))]
    (is (lines "hl.bind(\"SUPER + SPACE\", keys.launcher)"))
    (is (not (str/includes? config "define_submap(\"leader")) "no desktop leader submap is left")
    (is (str/includes? config "hl.bind(\"ALT + SPACE\", keys.alt_space({"))
    (is (lines "[\"dev.engstrand.Rex\"] = \"pass\",") "the Rex client binds Alt+Space itself")
    (is (lines "[\"com.mitchellh.ghostty.herdr\"] = { { mods = \"CTRL\", key = \"b\" }, { mods = \"\", key = \"space\" } },")
        "Herdr gets Ctrl+b Space, its leader popup")
    (is (lines "[\"google-chrome\"] = \"local-chrome\","))
    (is (lines "hl.bind(mods .. \"T\", hl.dsp.submap(\"local-chrome-t\"))"))
    (is (lines "hl.bind(mods .. \"N\", keys.leave_send({ mods = \"CTRL SHIFT\", key = \"y\" }, \"Alt+Space t n\", \"New tab\"))")
        "Alt+Space t n in Chrome opens the Vomnibar in new-tab mode, as Cmd+T")
    (is (lines "hl.bind(mods .. \"SHIFT + H\", keys.leave_send({ mods = \"CTRL SHIFT\", key = \"Page_Up\" }, \"Alt+Space t H\", \"Move tab left\"))"))
    (is (lines "hl.bind(mods .. \"B\", keys.leave_send({ mods = \"CTRL SHIFT\", key = \"k\" }, \"Alt+Space b b\", \"Choose buffer, tab or window\"))")
        "Alt+Space b b searches Chrome's tabs")
    (is (lines "hl.bind(\"escape\", keys.cancel(\"local-chrome-t\", \"Alt+Space t\", \"Escape\"))"))
    (is (not-any? #(str/includes? % "window.close") lines) "Alt+W alone closes a window"))
  (let [expected (keyleader/expected-binds grammar)]
    (is (every? expected [["" 64 "space"] ["" 8 "space"] ["local-chrome-t" 0 "n"] ["local-chrome-t" 8 "n"]
                          ["local-chrome-t" 1 "h"] ["local-chrome-t" 0 "h"] ["local-chrome" 0 "catchall"]])
        "verify-hypr expects Super+Space, Alt+Space and each Chrome key, with and without Alt held")
    (is (not-any? #(str/starts-with? (first %) "leader") expected)))
  (is (= {:title "Alt+Space t" :rows (keyleader/menu grammar :chrome "t")} (keyleader/submap-panel grammar "local-chrome-t")))
  (is (some #{["n" "New tab"]} (:rows (keyleader/submap-panel grammar "local-chrome-t"))))
  (is (nil? (keyleader/submap-panel grammar "reset"))))

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

(defn desktop-run-with [keys]
  (let [calls (atom [])]
    (with-redefs [keyleader/run! (fn [argv & _] (swap! calls conj (vec argv)) "")
                  keyleader/choose-window! (fn [] (swap! calls conj :choose-window))
                  keylog/log! (fn [& _] nil)]
      (keyleader/run-desktop! grammar keys))
    @calls))

(deftest b-b-picks-a-window-from-the-launcher-and-searches-tabs-in-chrome
  (is (= [:choose-window] (desktop-run-with ["b" "b"])))
  (is (= "ctrl+shift+k" (:chrome (some #(when (= ["b" "b"] (:keys %)) %) (keyleader/in-context grammar :chrome)))))
  (is (re-find #"chrome must be a chord" (keyleader/binding-problem {:keys ["b" "b"] :label "x" :desktop :choose-window :chrome "ctrl+f13"})))
  (is (nil? (keyleader/binding-problem {:keys ["e"] :label "x" :chrome "ctrl+shift+e"})))
  (is (not-any? #(= "e" (first %)) (keyleader/menu grammar :desktop nil)) "Alt+E, not a leader key, toggles Chrome's tab list")
  (is (some #{["e" "Edit scrollback"]} (keyleader/menu grammar :herdr nil))))

(defn herdr-tab-run
  "The herdr commands that the Herdr leader runs for KEYS, reading LINE when it asks."
  [keys & [line]]
  (let [calls (atom [])]
    (with-redefs [keylog/log! (fn [& _] nil)
                  keyleader/read-line! (fn [_] line)
                  keyleader/run! (fn [argv & _]
                                   (swap! calls conj (vec argv))
                                   (cond (= ["herdr" "tab" "list"] (take 3 argv))
                                         "{\"result\":{\"tabs\":[{\"tab_id\":\"w1:t1\"},{\"tab_id\":\"w1:t2\",\"focused\":true},{\"tab_id\":\"w1:t3\"}]}}"
                                         (= ["herdr" "pane" "list"] (take 3 argv))
                                         "{\"result\":{\"panes\":[{\"pane_id\":\"w1:p2\",\"cwd\":\"/a\",\"foreground_cwd\":\"/a/b\"}]}}"
                                         :else ""))]
      (keyleader/run-herdr! grammar keys {:pane "w1:p2" :tab "w1:t2" :workspace "w1"}))
    (remove #(#{["herdr" "tab" "list"] ["herdr" "pane" "list"]} (vec (take 3 %))) @calls)))

(deftest alt-space-t-runs-herdr-tab-commands
  (is (= [["herdr" "tab" "create" "--workspace" "w1" "--focus" "--cwd" "/a/b"]] (herdr-tab-run ["t" "n"]))
      "a new tab opens in the focused pane's directory, as Herdr's new_cwd = follow")
  (is (= [["herdr" "tab" "focus" "w1:t3"]] (herdr-tab-run ["t" "l"])))
  (is (= [["herdr" "tab" "focus" "w1:t1"]] (herdr-tab-run ["t" "h"])))
  (is (= [["herdr" "tab" "focus" "w1:t3"]] (herdr-tab-run ["t" "3"])))
  (is (= [["herdr" "tab" "close" "w1:t2"]] (herdr-tab-run ["t" "d"])))
  (is (= [["herdr" "tab" "rename" "w1:t2" "logs"]] (herdr-tab-run ["t" "r"] "logs")))
  (is (= [] (herdr-tab-run ["t" "r"] nil)) "an empty name renames nothing")
  (is (not-any? #(= ["t" "H"] (:keys %)) (keyleader/in-context grammar :herdr)) "Herdr cannot move a tab")
  (let [es (keyleader/herdr-entries grammar)]
    (is (some #(= {:label "New tab" :keys "Alt+Space t n"} (select-keys % [:label :keys])) es))
    (is (not-any? #(= "Rename tab" (:label %)) es) "the palette cannot ask for a name")))

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
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"alt-space must be"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]]
                              :apps [{:class "kitty" :alt-space :launcher}]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate app class"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]]
                              :apps [{:class "kitty" :alt-space :pass} {:class "kitty" :alt-space ["ctrl+b"]}]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"}]})))
  (is (re-find #"either :herdr" (keyleader/binding-problem {:keys ["t" "n"] :label "x" :herdr :new-tab :rex :tab/new})))
  (is (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["t" "Tabs"]]
                            :bindings [{:keys ["t" "h"] :label "Previous" :herdr :previous-tab}
                                       {:keys ["t" "H"] :label "Move left" :rex :tab/move-left :chrome "ctrl+shift+pageup"}]})
      "Alt+Space submaps bind Shift, so t h and t H are different keys")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"KEYS from hjkl"
        (keyleader/validate! {:terminal {:keeps [["^fish$" "x"]]} :groups [["w" "W"]]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no context"
        (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]] :bindings [{:keys ["w" "h"] :label "x"}]}))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
