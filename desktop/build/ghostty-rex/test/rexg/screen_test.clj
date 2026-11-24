(ns rexg.screen-test
  "The layout rexmux's screen.rs drew, which the Bombadil specs read."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rexg.main :as main]
            [rexg.model :as m]
            [rexg.screen :as s]))

;; Not a layout test, but this namespace already loads rexg.main.
(deftest a-new-server-keeps-the-session-env
  (is (= ["server" "start" "--env" "GIT_SSL_CAINFO" "--env" "HYPRLAND_INSTANCE_SIGNATURE"
          "--env" "SSL_CERT_FILE"]
         (main/start-args {"HYPRLAND_INSTANCE_SIGNATURE" "abc_1_2" "SSL_CERT_FILE" "/c.crt"
                           "GIT_SSL_CAINFO" "/c.crt" "TERM" "xterm-ghostty" "PWD" "/"
                           "REX_SERVER" "unix:///s" "REXG_LOG" "/l" "GHOSTTY_RESOURCES_DIR" "/g"})))
  (is (= ["server" "start"] (main/start-args {}))))

(deftest side-by-side-tiles-share-the-width-with-one-divider
  (let [[pw ph] [101 30]
        a (s/tile-cells [0 0 0.5 1] pw ph)
        b (s/tile-cells [0.5 0 0.5 1] pw ph)]
    (is (= 0 (a 0)))
    (is (= (b 0) (+ (a 0) (a 2) 1)) "one column between them for the divider")
    (is (= pw (+ (b 0) (b 2))) "together they fill the width")
    (is (= [1 ph] [(a 1) (a 3)]))))

(def view {:session "session:s" :label "main" :active "w1" :revision 1
           :windows [{:id "w1" :label "shell" :focused "block:aaaaaa"
                      :tiles [{:block "block:aaaaaa" :rect [0 0 0.5 1]} {:block "block:bbbbbb" :rect [0.5 0 0.5 1]}]}
                     {:id "w2" :label "pi" :tiles []}]})

(defn- row-text [spans row w]
  (let [cells (char-array (repeat w \space))]
    (doseq [{r :row c :col t :text f :flags} spans :when (and (= r row) (not= 128 f))
            [i ch] (map-indexed vector t) :when (< (+ c i) w)]
      (aset cells (+ c i) ch))
    (str/trimr (String. cells))))

(deftest the-tab-bar-titles-and-status-line-are-where-the-specs-look
  (let [model (-> (m/new-model [{:label "local"}]) (assoc-in [:servers 0 :view] view) (assoc :sidecar-up true))
        {:keys [spans layout cursor]} (s/render model 100 30 {})]
    (is (str/includes? (row-text spans 0 100) "[1 shell]"))
    (is (str/includes? (row-text spans 0 100) "2 pi"))
    (is (str/starts-with? (row-text spans 1 100) "▶ ") "the focused pane's title")
    (is (str/includes? (row-text spans 28 100) "│ agents 0 │ blocked 0") "the status line")
    (is (= [:focus "block:aaaaaa"] cursor))
    (is (= [{:block "block:aaaaaa" :x 0 :y 2 :w 50 :h 26} {:block "block:bbbbbb" :x 51 :y 2 :w 49 :h 26}] layout))))

(deftest a-floating-pane-is-framed-and-nothing-crosses-it
  (let [fl {:block "block:ffffff" :rect [0.25 0.2 0.5 0.6] :floating true}
        model (-> (m/new-model [{:label "local"}])
                  (assoc-in [:servers 0 :view] (update-in view [:windows 0 :tiles] conj fl))
                  (assoc :sidecar-up true))
        {:keys [spans layout]} (s/render model 100 30 {})
        at (fn [row col] (get (row-text spans row 100) col \space))]
    ;; The float covers columns 25-74 and rows 6-21: title row 6, rule row 21.
    (is (= {:block "block:ffffff" :x 26 :y 7 :w 48 :h 14} (last layout)) "its pane sits inside the frame")
    (is (= \│ (at 3 50)) "the split's divider above the float")
    (is (= \space (at 10 50)) "but not through it")
    (is (= [\│ \│] [(at 10 25) (at 10 74)]) "left and right borders")
    (is (= \─ (at 21 40)) "a bottom rule")
    (is (= [\┌ \┐ \└ \┘] [(at 6 25) (at 6 74) (at 21 25) (at 21 74)]) "joined corners")
    (is (str/starts-with? (subs (row-text spans 6 100) 26) "─ ") "its title row")))

(deftest which-key-shows-under-a-prefix
  (let [model (-> (m/new-model [{:label "local"}]) (assoc-in [:servers 0 :view] view)
                  (assoc :prefix ["alt+space"] :catalogue [{:id "tab/new" :title "Tabs: new" :action "mx.tab.new"}]
                         :bindings [[["alt+space" "c"] "mx.tab.new"]]))
        {:keys [spans cursor]} (s/render model 100 30 {})]
    (is (= "alt+space-" (row-text spans 29 100)))
    (is (str/includes? (row-text spans 27 100) "c  Tabs: new"))
    (is (= [:hide] cursor))))

(deftest overlay-text-never-carries-a-tab-or-newline
  (is (= "1 2 - - 0\ta b" (main/spans-text [{:row 1 :col 2 :flags 0 :text "a\tb"}]))))
