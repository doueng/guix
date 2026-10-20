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

(deftest window-keys-mean-the-same-in-every-context
  (doseq [context [:desktop :herdr :nvim :doom :idea]]
    (is (= [["h" "Focus left"] ["j" "Focus down"] ["k" "Focus up"] ["l" "Focus right"]]
           (take 4 (keyleader/menu grammar context "w")))
        (str context))))

(deftest root-menu-starts-with-the-palette
  (is (= ["SPC" "Command palette"] (first (keyleader/menu grammar :herdr nil))))
  (is (= ["SPC" "Command palette"] (first (keyleader/menu grammar :desktop nil))))
  (is (not-any? #(= "a" (first %)) (keyleader/menu grammar :herdr nil))
      "Herdr has no application entries, so its menu must not offer the a group"))

(deftest hyprland-leader-runs-dispatchers-or-the-script
  (let [lines (set (map str/trim (str/split-lines (keyleader/hypr-config grammar))))]
    (is (lines "hl.bind(\"ALT + SPACE\", hl.dsp.submap(\"leader\"))"))
    (is (lines "hl.bind(mods .. \"H\", keys.leave(hl.dsp.focus({ direction = \"left\" }), \"Alt+Space w h\", \"Focus left\"))"))
    (is (lines "hl.bind(mods .. \"D\", keys.leave(hl.dsp.exec_cmd(\"~/.local/bin/keyleader run w d\")))")
        "Confirmed actions go through the script")
    (is (lines "hl.bind(\"catchall\", keys.cancel(\"leader-w\"))"))))

(deftest grammar-rejects-ambiguity
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate leader sequence"
        (keyleader/validate! {:terminal {:passthrough "^nvim$"} :groups [["w" "W"]]
                              :bindings [{:keys ["w" "h"] :label "Left" :nav "left"} {:keys ["w" "h"] :label "Right" :nav "right"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"known group"
        (keyleader/validate! {:terminal {:passthrough "^nvim$"} :groups [["w" "W"]] :bindings [{:keys ["x" "h"] :label "Left" :nav "left"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no context"
        (keyleader/validate! {:terminal {:passthrough "^nvim$"} :groups [["w" "W"]] :bindings [{:keys ["w" "h"] :label "x"}]}))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
