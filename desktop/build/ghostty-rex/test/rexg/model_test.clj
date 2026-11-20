(ns rexg.model-test
  "rexmux's model.rs tests, ported: the ghostty-rex model keeps the same rules."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rexg.keys :as keys]
            [rexg.model :as m]))

(defn- with-startup []
  (m/new-model [{:label "t" :address nil}]
               {:startup {:label "main" :windows [{:label "emacs" :command ["emacs-project-start"]}
                                                  {:label "bbh" :command ["emacs-bbh"]}
                                                  {:label "shell"}]}}))

(defn- with-view [model session]
  (assoc-in model [:servers 0 :view] {:session session :label "" :revision 0 :active nil :windows []}))

(deftest a-server-with-no-sessions-gets-the-startup-session
  (let [[_ [[_ _ method payload]]] (m/rex-response (with-startup) [:sessions 0] {:ok {:sessions []}})
        ws (:initial_windows payload)]
    (is (= "session.create" method))
    (is (= "main" (:label payload)))
    (is (= 3 (count ws)))
    (is (= "bbh" (:window_label (ws 1))))
    (is (= ["emacs-bbh"] (get-in ws [1 :layout :block :options :command])))
    (is (nil? (get-in ws [2 :layout :block :options])) "no command is a login shell")))

(deftest a-server-with-only-other-sessions-still-gets-the-startup-session
  (let [[_ [[_ _ method payload]]] (m/rex-response (assoc (with-startup) :shell "fish") [:sessions 0]
                                                   {:ok {:sessions [{:session_id "session:x" :label "spike"}
                                                                    {:session_id "session:y" :label "w242"}]}})]
    (is (= "session.create" method))
    (is (every? #(= "fish" (get-in % [:layout :block :options :shell])) (:initial_windows payload)))))

(deftest the-startup-session-is-shown-first-when-it-exists
  (let [[_ [[_ _ method payload]]] (m/rex-response (with-startup) [:sessions 0]
                                                   {:ok {:sessions [{:session_id "session:a" :label "main"}
                                                                    {:session_id "session:b" :label "other"}]}})]
    (is (= ["session.attach" "session:a"] [method (:session_id payload)]))))

(deftest new-shell-blocks-get-the-configured-shell
  (let [v (m/with-options {:layout {:split {:before {:block {:flavor "com.superlogical.terminal.shell"}}
                                            :after {:block {:flavor "com.superlogical.terminal.shell" :options {:shell "none"}}}}}}
                          {:shell "fish"})]
    (is (= "fish" (get-in v [:layout :split :before :block :options :shell])))
    (is (= "none" (get-in v [:layout :split :after :block :options :shell])) "an explicit shell stays")))

(deftest a-restored-bare-shell-gets-its-program-back-in-the-configured-shell
  (let [model (-> (with-startup) (assoc :shell "fish")
                  (assoc-in [:startup :windows 1 :restore] ["emacs-bbh" "--resume" "it's"])
                  (with-view "session:a"))
        bare {:foreground {:name "bash"}}
        typed (fn [block window proc] (some-> (m/revive model 0 block window proc) second first (nth 3) :args :data))
        line (typed "block:b" "bbh" bare)
        quoted (-> line (str/replace #"^exec fish -l -c " "") (str/replace #"\r$" ""))]
    (is (str/starts-with? line "exec fish -l -c "))
    (is (= "emacs-bbh --resume 'it'\"'\"'s'" (:out (p/sh "sh" "-c" (str "printf %s " quoted)))))
    (testing "a running program, or a fish shell tab, is left alone; a bash shell tab becomes fish"
      (is (nil? (typed "block:b" "bbh" {:foreground {:name "emacs"}})))
      (is (nil? (typed "block:s" "shell" {:foreground {:name "fish"}})))
      (is (= "exec fish -l\r" (typed "block:s" "shell" bare))))
    (testing "a split's bare bash gets fish, a bash started inside fish does not"
      (is (= "exec fish -l\r" (typed "block:t" "" bare)))
      (let [nested {:child {:pid 1 :name "fish"} :foreground {:pid 2 :name "bash"}}]
        (is (nil? (typed "block:t" "" nested)))
        (is (nil? (typed "block:b" "bbh" nested)))))))

(deftest a-perform-request-is-answered-under-its-call-id
  (let [[_ outs] (m/rex-event (m/new-model [{:label "t"}]) 0 {:type "client_perform_requested" :payload {:call_id "7" :action "mx.nothing"}})
        [_ _ method payload] (last outs)]
    (is (= "client.reply" method))
    (is (= "7" (:call_id payload)))))

(deftest resizing-moves-the-nearest-border-of-the-right-kind
  (let [layout {:split {:direction "horizontal" :ratio 0.5
                        :before {:block_id "block:a"}
                        :after {:split {:direction "vertical" :ratio 0.5
                                        :before {:block_id "block:b"} :after {:block_id "block:c"}}}}}
        r (m/resize-request layout "block:c" "left" 0.05)]
    (is (= ["horizontal" "block:a" "block:b"] ((juxt :direction :before_block_id :after_block_id) r)))
    (is (< (Math/abs (- 0.45 (:ratio r))) 1e-9))
    (is (= ["block:b" "block:c"] ((juxt :before_block_id :after_block_id) (m/resize-request layout "block:b" "down" 0.05))))
    (is (nil? (m/resize-request layout "block:a" "up" 0.05)) "no horizontal border above a")))

(deftest tiny-fingers-copy-open-or-paste-the-picked-text
  (let [model (with-view (m/new-model [{:label "t"}]) "session:a")
        lines ["see https://example.com/x and /tmp/f"]
        h (m/open-hints model "block:a" lines 80)]
    (is (= 2 (count (get-in h [:hints :targets]))))
    (let [[h2 outs] (m/key h (keys/parse "a"))]
      (is (= [[:clipboard "https://example.com/x"] [:open "https://example.com/x"]] outs))
      (is (nil? (:hints h2))))
    (let [[_ outs] (m/key h (keys/parse "shift+s"))]
      (is (= [[:paste 0 "session:a" "block:a" "/tmp/f"]] outs)))
    (let [[h3 outs] (m/key h (keys/parse "escape"))]
      (is (empty? outs))
      (is (nil? (:hints h3)) "Escape leaves tiny fingers"))))

(defn- with-bindings [model]
  (assoc model :sidecar-up true
         :catalogue [{:id "tab/new" :title "Tabs: new" :action "mx.tab.new" :enabled true}
                     {:id "pane/split-right" :title "Panes: split right" :action "mx.pane.split_right" :enabled true}]
         :bindings [[["ctrl+b" "c"] "mx.tab.new"] [["ctrl+b" "%"] "mx.pane.split_right"]]))

(deftest a-prefix-key-waits-then-runs-its-command
  (let [model (with-bindings (with-view (m/new-model [{:label "t"}]) "session:a"))
        [w outs] (m/key model (keys/parse "ctrl+b"))]
    (is (empty? outs))
    (is (= ["ctrl+b"] (:prefix w)))
    (is (= [["%" "Panes: split right"] ["c" "Tabs: new"]] (m/continuations w)) "which-key lists what may follow")
    (let [[done [[kind msg]]] (m/key w (keys/parse "%"))]
      (is (= :sidecar kind))
      (is (= [:commands/invoke "pane/split-right"] ((juxt :op :command) msg)))
      (is (empty? (:prefix done))))
    (let [[u outs] (m/key w (keys/parse "q"))]
      (is (empty? outs))
      (is (= "ctrl+b q is undefined" (:echo u))))))

(deftest an-unbound-key-goes-to-the-focused-pane-through-rex
  (let [model (-> (m/new-model [{:label "t"}])
                  (assoc-in [:servers 0 :view] {:session "session:a" :active "w" :revision 1
                                                 :windows [{:id "w" :focused "block:a" :tiles []}]}))
        [_ [[_ _ method payload]]] (m/key model (keys/parse "x"))]
    (is (= "client.key" method))
    (is (= "block:a" (:block_id payload)))
    (is (= {:key "key_x" :text "x" :mods ""} (select-keys (:key payload) [:key :text :mods])))))

(deftest m-x-runs-the-fuzzy-best-command
  (let [model (with-bindings (with-view (m/new-model [{:label "t"}]) "session:a"))
        [mx] (m/key model (keys/parse "alt+x"))
        mx (reduce (fn [mm k] (first (m/key mm (keys/parse k)))) mx ["s" "p" "l"])
        [_ [[_ msg]]] (m/key mx (keys/parse "enter"))]
    (is (= "pane/split-right" (:command msg)))))

(deftest a-pane-never-has-two-streams
  (let [view {:session "s" :active "w" :revision 1 :windows [{:id "w" :focused "b1" :tiles [{:block "b1" :rect [0 0 1 1]}]}]}
        [model outs] (m/set-view (m/new-model [{:label "t"}]) 0 view)
        [model2 outs2] (m/set-view model 0 (assoc view :revision 2))]
    (is (= 1 (count (filter #(= "data.connect" (nth % 2 nil)) outs))))
    (is (empty? (filter #(= "data.connect" (nth % 2 nil)) outs2)) "the same view asks for no second stream")
    (let [[_ outs3] (m/set-view model2 0 (assoc view :revision 3 :windows [{:id "w" :tiles [{:block "b2" :rect [0 0 1 1]}]}]))]
      (is (some #(= [:close-stream "b1"] %) outs3) "a pane no longer shown closes its stream"))))
