(ns review-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [babashka.process :as p]
            [clojure.string :as str]))
(load-file "desktop/bin/keyguide")
(load-file "desktop/bin/keyleader")
(load-file "desktop/build/keybindings/reference.clj")
(require '[hypr.dispatch :as hypr])

(def leader-grammar (keyleader/load-grammar))

(defn palette-run [answers]
  (let [answers (atom answers) ran (atom [])]
    (with-redefs [keylog/log! (fn [& _] nil)
                  keyleader/spawn! (fn [argv] (swap! ran conj argv))
                  keyleader/run! (fn [argv & _] (swap! ran conj (vec argv)) "")
                  hypr/dispatch! (fn [d] (swap! ran conj d))
                  keyguide/execute! (fn [argv] (swap! ran conj argv))]
      (doseq [label @answers]
        (keyleader/run-desktop-entry! leader-grammar
                                      (:value (some #(when (= label (:label %)) %) (keyleader/desktop-entries leader-grammar))))))
    @ran))

(deftest palette-closes-a-window-without-asking
  (is (= [["window.close"]] (palette-run ["Close window"]))))

(deftest palette-runs-leader-entries-and-skips-focus-moves
  (is (= [["~/.local/bin/custom-launcher"]] (palette-run ["Find file or launch app"])))
  (is (not-any? #(= "leader:w h" (:value %)) (keyleader/desktop-entries leader-grammar))
      "the leader's focus moves are left out; the guide's Ctrl+Alt chords teach them"))

(deftest hyprland-drift-reports-missing-and-unexpected-binds
  (let [expected (keyleader/expected-binds leader-grammar)
        bind (fn [[submap modmask key]] {:submap submap :modmask modmask :key (str/upper-case key)
                                         :catch_all (= "catchall" key)})
        loaded (map bind expected)
        unrelated {:submap "" :modmask 64 :key "Q"}]
    (is (contains? expected ["leader-w" 8 "h"]) "keys also bind with Alt still held")
    (is (nil? (keyleader/hypr-drift expected (keyleader/live-binds (cons unrelated loaded)))))
    (is (= {:missing [["leader-w" 0 "h"]] :unexpected [["leader-w" 0 "z"]]}
           (keyleader/hypr-drift expected
                                 (keyleader/live-binds (cons (bind ["leader-w" 0 "z"])
                                                             (remove #(= (bind ["leader-w" 0 "h"]) %) loaded))))))))

(defn nav-calls [pane herdr]
  (let [calls (atom [])]
    (with-redefs [keyleader/herdr-pane (constantly pane)
                  keylog/log! (fn [& _] nil)
                  keyleader/herdr-json herdr
                  hypr/dispatch! (fn [d] (swap! calls conj d))
                  keyleader/run! (fn [argv & _] (swap! calls conj (vec argv)) "")]
      (try (keyleader/nav! "left") (catch clojure.lang.ExceptionInfo e (swap! calls conj (ex-message e)))))
    @calls))

(deftest herdr-failure-does-not-move-desktop-focus
  (is (= ["Herdr did not report pane edges"]
         (nav-calls "w1:p9" (fn [& _] (throw (ex-info "socket gone" {}))))))
  (is (= ["Herdr did not report pane edges"]
         (nav-calls "w1:p9" (fn [& _] {:result {:edges {:right true}}}))))
  (is (= [["focus" {:direction "left"}]]
         (nav-calls "w1:p9" (fn [& _] {:result {:edges {:left true}}})))))

(deftest grammar-rejects-invalid-actions
  (doseq [[why b] [["direction" {:keys ["w" "h"] :label "x" :nav "sideways"}]
                   ["Herdr action" {:keys ["w" "h"] :label "x" :herdr :bogus}]
                   ["desktop action" {:keys ["w" "h"] :label "x" :desktop :bogus}]
                   ["label" {:keys ["w" "h"] :label " " :nav "left"}]
                   ["unknown field" {:keys ["w" "h"] :label "x" :nvm "<cmd>x<cr>"}]
                   ["argv shape" {:keys ["w" "h"] :label "x" :desktop-command "launcher"}]
                   ["nav with another action" {:keys ["w" "h"] :label "x" :nav "left" :nvim "<C-w>h"}]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (keyleader/validate! {:terminal {:keeps [["^nvim$" "hjkl"]]} :groups [["w" "W"]] :bindings [b]}))
        why)))

(deftest tab-cycling-reports-missing-tabs
  (doseq [[tabs message] [[[] "Herdr reported no tabs"]
                          [[{:tab_id "w1:t1" :focused false}] "Herdr reported no focused tab"]]]
    (with-redefs [keyleader/tabs (constantly tabs)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern message) (keyleader/step-tab! "w1" 1))))))

(deftest guide-path-is-independent-of-the-leader-path
  (with-redefs [keyleader/config-path (constantly "/tmp/custom/my-leader.edn")]
    (is (= (keyguide/config-path) (keyleader/guide-path)))))

(deftest inventory-includes-the-leader-grammar
  (let [rows (->> (:out (p/shell {:out :string} "bb" "desktop/pi/agent/skills/keybindings/scripts/inventory.bb" "."))
                  str/split-lines
                  (map #(str/split % #"\t")))
        leader-rows (filter #(str/starts-with? (first %) "leader ") rows)]
    (doseq [context ["desktop" "herdr" "nvim" "doom" "idea"]]
      (is (some #(and (= (str "leader " context) (first %)) (= "SPC w h" (second %))) leader-rows) context))
    (is (some #(str/starts-with? (nth % 3) "keybindings/leader.edn:") leader-rows))))

(deftest every-sequence-doom-binds-records-its-match
  (let [{:keys [upstream mine windows]} (keybindings-reference/sources "." leader-grammar)]
    (is (seq upstream) "the installed Doom's leader map was read")
    (is (= [] (for [row (keybindings-reference/leader-rows leader-grammar upstream mine windows)
                    :when (= :unverified (:alignment row))]
                (:keys row))))))

(deftest ghostty-leaves-application-chords-alone
  (let [binds (set (re-seq #"(?m)^keybind = ([^=]+)=" (:out (p/shell {:out :string} "ghostty" "+list-keybinds"))))]
    (doseq [chord ["ctrl+enter"]]
      (is (not (contains? (set (map second binds)) chord)) (str "Ghostty consumes " chord)))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
