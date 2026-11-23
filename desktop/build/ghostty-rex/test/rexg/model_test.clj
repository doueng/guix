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

(deftest a-restored-bare-shell-is-replaced-by-its-program-in-the-configured-shell
  (let [model (-> (with-startup) (assoc :shell "fish")
                  (assoc-in [:startup :windows 1 :restore] ["emacs-bbh" "--resume" "it's"])
                  (with-view "session:a"))
        bare {:child {:pid 5 :name "bash" :cwd "/home/u/guix"} :foreground {:pid 5 :name "bash"}}
        out (fn [block window proc] (some-> (m/revive model 0 block window proc) second first))
        opts (fn [block window proc] (some-> (out block window proc) (nth 3) :layout :block :options))
        [_ _ method payload tag] (out "block:b" "bbh" bare)]
    (is (= "session.new_split" method) "nothing is typed into the old shell")
    (is (= {:shell "fish" :command ["emacs-bbh" "--resume" "it's"] :cwd "/home/u/guix"} (get-in payload [:layout :block :options])))
    (is (= "bbh" (get-in payload [:layout :block :label])))
    (is (= ["block:b" "horizontal" "after"] ((juxt :anchor_id :direction :side) payload)))
    (is (= :replaced (first tag)))
    (testing "the new block up, the old one closes"
      (let [[m2 outs] (m/rex-response model tag {:ok {:block_ids ["block:n"]}})]
        (is (= ["block.close" "block:b"] ((juxt #(nth % 2) #(:block_id (nth % 3))) (first outs))))
        (is (nil? (:echo (first (m/rex-response (dissoc m2 :echo) [:ignore] {:error "block not found: block:b (not_found)"}))))
            "a late request for the replaced block shows no error")))
    (testing "a running program, or a fish shell tab, is left alone; a bash shell tab becomes fish"
      (is (nil? (out "block:b" "bbh" {:foreground {:name "emacs"}})))
      (is (nil? (out "block:s" "shell" {:foreground {:name "fish"}})))
      (is (= {:shell "fish" :cwd "/home/u/guix"} (opts "block:s" "shell" bare))))
    (testing "a split's bare bash gets fish, a bash started inside fish does not"
      (is (= {:shell "fish" :cwd "/home/u/guix"} (opts "block:t" "" bare)))
      (let [nested {:child {:pid 1 :name "fish"} :foreground {:pid 2 :name "bash"}}]
        (is (nil? (out "block:t" "" nested)))
        (is (nil? (out "block:b" "bbh" nested)))))))

(deftest a-lost-connection-retries-and-reattaches-the-session-it-showed
  (let [model (-> (with-startup) (with-view "session:p") (assoc :streams {"block:a" :open}))
        model (assoc-in model [:servers 0 :sessions] [["session:p" "project"]])
        [m outs] (m/disconnected model 0 "server closed the connection" 250)]
    (is (= :down (get-in m [:servers 0 :conn])))
    (is (nil? (get-in m [:servers 0 :view])))
    (is (= [[:close-stream "block:a"]] outs) "the panes' streams close")
    (is (str/includes? (:echo m) "retrying in 0.3 s"))
    (testing "the status line says so"
      (is (= [250 500 1000 2000 4000 4000] (map m/retry-ms [0 1 2 3 4 9]))))
    (testing "connected again, it attaches the session it showed, not the startup one"
      (let [[m [[_ _ gen]]] (m/connect m 0)
            [m [adopt & outs]] (m/connected m 0 gen "client:new")
            [_ outs2] (m/rex-response m [:sessions 0] {:ok {:sessions [{:session_id "session:main" :label "main"}
                                                                       {:session_id "session:p" :label "project"}]}})]
        (is (= :up (get-in m [:servers 0 :conn])))
        (is (= [:conn/adopt 0 gen] adopt))
        (is (= "t: connected again" (:echo m)))
        (is (= "session.list" (nth (first outs) 2)))
        (is (= ["session.attach" "session:p"] ((juxt #(nth % 2) #(:session_id (nth % 3))) (first outs2))))))))

(deftest typing-into-a-scrolled-pane-follows-the-output-and-drops-the-selection
  (let [model (-> (with-startup)
                  (assoc-in [:servers 0 :view] {:session "session:p" :label "" :revision 0 :active "w1"
                                                :windows [{:id "w1" :focused "block:a" :layout {:block {:id "block:a"}}}]})
                  (assoc :selection ["block:a" [0 0] [3 0]]))
        scrolled (first (m/renderer-mode model "block:a" 2))
        [m1 outs] (m/key scrolled (keys/parse "x"))]
    (is (= {:mouse false :scrolled true} (get-in scrolled [:panes "block:a"])))
    (is (= [:scroll-bottom "block:a"] (first outs)) "rexg-render scrolls it back first")
    (is (= "client.key" (nth (second outs) 2)))
    (is (nil? (:selection m1)))
    (is (not-any? #(= :scroll-bottom (first %)) (second (m/key model (keys/parse "x")))) "a pane at the bottom stays")
    (is (not-any? #(= :scroll-bottom (first %)) (second (m/key (assoc scrolled :minibuffer {:mode {:kind :mx} :input "" :selected 0}) (keys/parse "x"))))
        "keys for the minibuffer leave the pane alone")
    (is (= 3 (get-in (first (m/child-exited scrolled "block:a" 3)) [:panes "block:a" :exited])))))

(deftest a-perform-request-is-answered-under-its-call-id
  (let [[_ outs] (m/rex-event (m/new-model [{:label "t"}]) 0 {:type "client_perform_requested" :payload {:call_id "7" :action "mx.nothing"}})
        [_ _ method payload] (last outs)]
    (is (= "client.reply" method))
    (is (= "7" (:call_id payload)))))

(deftest a-reload-asked-through-rex-answers-before-it-reloads
  (let [model (assoc (m/new-model [{:label "t"}]) :pending {1 [0 "7"]} :invoked {1 "client/reload"})
        [_ outs] (m/sidecar model {:op :reply :id 1 :effects [[:client/reload {}]]})
        [_ _ method payload tag] (last outs)]
    (is (not-any? #{[:reload]} outs) "an exec now would drop the answer")
    (is (= ["client.reply" "7" [:reload-after]] [method (:call_id payload) tag]))
    (is (= [[:reload]] (second (m/rex-response model tag {:ok {}}))) "Rex has the answer: reload")
    (is (= [[:reload]] (second (m/rex-response model tag {:error "gone"}))) "or refused it: reload all the same"))
  (let [[_ outs] (m/sidecar (m/new-model [{:label "t"}]) {:op :effects :effects [[:client/reload {}]]})]
    (is (= [[:reload]] outs) "M-x Client: reload has no caller to answer")))

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
         :bindings [[["alt+space" "t" "n"] "mx.tab.new"] [["alt+space" "w" "v"] "mx.pane.split_right"]]))

(deftest a-prefix-key-waits-then-runs-its-command
  (let [model (with-bindings (with-view (m/new-model [{:label "t"}]) "session:a"))
        [w outs] (m/key model (keys/parse "alt+space"))]
    (is (empty? outs))
    (is (= ["alt+space"] (:prefix w)))
    (is (= [["t n" "Tabs: new"] ["w v" "Panes: split right"]] (m/continuations w)) "which-key lists what may follow")
    (let [[done [[kind msg]]] (reduce (fn [[mm _] k] (m/key mm (keys/parse k))) [w nil] ["w" "v"])]
      (is (= :sidecar kind))
      (is (= [:commands/invoke "pane/split-right"] ((juxt :op :command) msg)))
      (is (empty? (:prefix done))))
    (let [[u outs] (m/key w (keys/parse "q"))]
      (is (empty? outs))
      (is (= "alt+space q is undefined" (:echo u))))))

(deftest an-unbound-key-goes-to-the-focused-pane-through-rex
  (let [model (-> (m/new-model [{:label "t"}])
                  (assoc-in [:servers 0 :view] {:session "session:a" :active "w" :revision 1
                                                 :windows [{:id "w" :focused "block:a" :tiles []}]}))
        [_ [[_ _ method payload]]] (m/key model (keys/parse "x"))]
    (is (= "client.key" method))
    (is (= "block:a" (:block_id payload)))
    (is (= {:key "key_x" :text "x" :mods ""} (select-keys (:key payload) [:key :text :mods])))
    (testing "Ctrl+b binds nothing, so the shell or Emacs gets it"
      (let [[w [[_ _ method payload]]] (m/key (with-bindings model) (keys/parse "ctrl+b"))]
        (is (empty? (:prefix w)))
        (is (= "client.key" method))
        (is (= {:key "key_b" :mods "ctrl"} (select-keys (:key payload) [:key :mods])))))))

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

(deftest the-mouse-selects-copies-scrolls-and-passes-reports-on
  (let [model (-> (with-startup)
                  (assoc-in [:servers 0 :view] {:session "session:p" :label "" :revision 0 :active "w1"
                                                :windows [{:id "w1" :focused "block:a" :layout {:block {:id "block:a"}}}]}))
        press {:button 0 :kind :press} drag {:button 32 :kind :press} release {:button 0 :kind :release}]
    (testing "a press on another pane focuses it and starts a selection"
      (let [[m outs] (m/mouse model ["block:b" 2 1] press)]
        (is (= [[:rex 0 "session.focus_block" {:session_id "session:p" :block_id "block:b"} [:effect]]] outs))
        (is (= ["block:b" [2 1] [2 1]] (:selection m)))))
    (testing "a drag extends it, and a release asks for the text to copy"
      (let [[m] (m/mouse model ["block:a" 2 1] press)
            [m] (m/mouse m ["block:a" 5 2] drag)
            [m outs] (m/mouse m ["block:a" 5 2] release)]
        (is (= [[:read-text [:select "block:a" [2 1] [5 2]] "block:a"]] outs))
        (let [[m2 outs] (m/text-read m [:select "block:a" [2 1] [5 2]] ["zero" "one line  " "two lines"] 80)]
          (is (= [[:clipboard "e line\ntwo li"]] outs))
          (is (= "Copied 13 characters" (:echo m2))))))
    (testing "a click without a drag selects nothing"
      (let [[m] (m/mouse model ["block:a" 2 1] press)
            [m outs] (m/mouse m ["block:a" 2 1] release)]
        (is (= [] outs))
        (is (nil? (:selection m)))))
    (testing "the wheel scrolls the pane, unless its program wants mouse reports"
      (is (= [[:scroll "block:a" -3]] (second (m/mouse model ["block:a" 1 1] {:button 64 :kind :press}))))
      (let [vim (assoc-in model [:panes "block:a" :mouse] true)
            [_ [[_ _ method payload]]] (m/mouse vim ["block:a" 1 1] {:button 65 :kind :press})]
        (is (= "block.event" method))
        (is (= {:type "wheel" :payload {:x 1 :y 1 :delta_y 1 :delta_mode "line"}} (:event payload)))))
    (testing "a middle press pastes; a release off the panes drops the selection"
      (is (= [[:paste 0 "session:p" "block:a" nil]] (second (m/mouse model ["block:a" 1 1] {:button 1 :kind :press}))))
      (is (nil? (:selection (first (m/mouse (assoc model :selection ["block:a" [0 0] [1 1]]) nil release))))))
    (testing "no session, no effect"
      (is (= [(with-startup) []] (m/mouse (with-startup) ["block:a" 1 1] press))))))
