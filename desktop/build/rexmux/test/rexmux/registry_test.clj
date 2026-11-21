(ns rexmux.registry-test
  "The registry against a real help document (Rex preview.21, API revision
  18, saved in test/fixtures), so a new Rex revision that breaks command
  generation fails here first."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [rexmux.commands :as commands]
            [rexmux.props :refer [trials]]
            [rexmux.registry :as reg]
            [rexmux.schema :as schema]))

(def help (json/parse-string (slurp "test/fixtures/rex-api-rev18.json") true))
(def api (reg/api-commands help))
(def registry (reg/merge-sources api commands/all))
(def reg-repo "../../..")
(def ctx {:session-id "session:1" :window-id "window:1" :block-id "block:1" :client-id "client:1"})

(deftest every-method-and-block-method-is-a-command
  (is (= (+ (count (:methods help)) (count (mapcat :methods (:block_types help)))) (count api)))
  (is (apply distinct? (map :id api)))
  (is (contains? registry :rex/session.new_split))
  (is (contains? registry :rex/com.superlogical.terminal.write)))

(defn gen-value
  "A generator of values valid for SCHEMA (the subset Rex uses)."
  [{:keys [type enum minimum maximum]}]
  (let [t (if (sequential? type) (first (remove #{"null"} type)) type)]
    (cond enum (gen/elements enum)
          (= t "integer") (gen/choose (or minimum 0) (or maximum 1000))
          (= t "number") (gen/fmap double (gen/choose (or minimum 0) (or maximum 1000)))
          (= t "boolean") gen/boolean
          (= t "string") (gen/fmap #(str "x" %) gen/nat)
          (= t "array") (gen/return [])
          :else (gen/return {}))))

(defn gen-args
  "Generator of valid argument maps for CMD: every required non-context
  argument, and some of the optional ones."
  [cmd]
  (let [props (get-in cmd [:schema :properties])
        req (set (map keyword (get-in cmd [:schema :required])))
        own (vec (remove #(contains? reg/ctx-args (key %)) props))]
    (gen/let [chosen (gen/vector gen/boolean (count own))
              vals (apply gen/tuple (gen/return nil) (map (comp gen-value val) own))]
      (into {} (keep (fn [[[k] v pick]] (when (or (req k) pick) [k v]))
                     (map vector own (rest vals) chosen))))))

(def simple-api
  "API commands whose arguments the generator can fill: no object-typed
  arguments, whose shape the schema leaves open."
  (filter (fn [c] (every? (fn [[_ s]] (not (some #{"object"} (flatten [(:type s)])))) (get-in c [:schema :properties]))) api))

(defn- call-ok? [cmd [kind method call-args]]
  (and (= :rex/call kind)
       (if (:block-method cmd)
         (and (= "block.method" method) (empty? (schema/errors (:schema cmd) (:args call-args))))
         (and (= (name (:id cmd)) method) (empty? (schema/errors (:schema cmd) call-args))))))

(deftest valid-arguments-become-exactly-one-checked-rex-call
  (is (:pass? (tc/quick-check
               (trials 400)
               (prop/for-all [[cmd args] (gen/bind (gen/elements simple-api) (fn [c] (gen/tuple (gen/return c) (gen-args c))))]
                 (let [{:keys [effects prompt error]} (reg/invoke registry ctx (:id cmd) args)]
                   (and (nil? prompt) (nil? error) (= 1 (count effects)) (call-ok? cmd (first effects)))))))))

(deftest missing-required-arguments-prompt-instead-of-calling
  (is (:pass? (tc/quick-check
               (trials 200)
               (prop/for-all [cmd (gen/elements (filter #(seq (remove (comp (some-fn reg/ctx-args reg/arg-defaults) :name) (filter :required (:args %)))) simple-api))]
                 (let [{:keys [effects prompt]} (reg/invoke registry ctx (:id cmd) {})]
                   (and (nil? effects) (seq prompt) (every? :required prompt))))))))

(deftest wrong-values-are-refused-before-rex-sees-them
  (let [r (reg/invoke registry ctx :rex/session.focus_direction {:direction "sideways"})]
    (is (nil? (:effects r)))
    (is (= [[:direction]] (map :path (:invalid r)))))
  (is (re-find #"needs session-id" (:error (reg/invoke registry {} :rex/session.view {})))))

(deftest context-fills-ids
  (is (= [[:rex/call "session.view" {:session_id "session:1"}]]
         (:effects (reg/invoke registry ctx :rex/session.view {})))))

(deftest keymap-layer-declares-every-bound-action-and-has-no-conflicts
  (let [{:keys [layer actions]} (reg/keymap-layer registry)
        declared (set (map :name actions))]
    (is (= "mx" (:name layer)))
    (is (seq (:bindings layer)))
    (is (every? #(declared (:action %)) (:bindings layer)))
    (is (every? #(re-matches #"mx\.[a-z0-9_.]+" %) declared))
    (is (= [] (reg/key-conflicts registry)))))

(deftest conflicts-are-found
  (let [r (reg/merge-sources [{:id :a/x :keys [["ctrl+b" "n"]]} {:id :a/y :keys [["ctrl+b" "n"]]} {:id :a/z :keys [["ctrl+b"]]}])]
    (is (= #{:duplicate :prefix} (set (map :problem (reg/key-conflicts r)))))))

(deftest leader-entries-with-rex-become-commands
  (let [leader {:bindings [{:keys ["w" "v"] :label "Split right" :rex ["session.new_split" {:direction "horizontal" :side "after"}]}
                           {:keys ["a"] :label "Agents" :rex :agent/pick}
                           {:keys ["f" "s"] :label "Save" :doom "save-buffer"}]}
        all (reg/leader-commands leader ["alt+space"])
        cmds (remove #(= :leader/palette (:id %)) all)
        r (reg/merge-sources api commands/all all)]
    (is (= 2 (count cmds)))
    (testing "the prefix and Space open M-x"
      (is (= [[:ui/mx {}]] (:effects (reg/invoke r ctx :leader/palette {}))))
      (is (= [["alt+space" "space"]] (:keys (get r :leader/palette)))))
    (is (= [["alt+space" "w" "v"]] (:keys (first cmds))))
    (testing "a direct call gets context, defaults and the schema check"
      (is (= [[:rex/call "session.new_split" {:session_id "session:1" :window_id "window:1" :direction "horizontal" :side "after"
                                               :layout (:layout reg/arg-defaults) :focus true}]]
             (:effects (reg/invoke r ctx (:id (first cmds)) {})))))
    (testing "an alias runs the command it names"
      (is (= [[:ui/message "No agents."]]
             (:effects (reg/invoke r (assoc ctx :world {:agents {:blocks {}}}) (:id (second cmds)) {})))))))

(deftest the-real-leader-maps-onto-rexmux-commands
  (let [leader (clojure.edn/read-string (slurp (str reg-repo "/desktop/configs/keybindings/leader.edn")))
        cmds (reg/leader-commands leader ["alt+space"])
        r (reg/merge-sources api commands/all commands/term-nav-commands cmds)
        by-keys (into {} (for [c cmds k (:keys c)] [k c]))]
    (testing "every mapped binding runs a command that exists, with no key conflicts"
      (is (every? #(or (vector? %) (contains? r %)) (keep (fn [b] (or (:rex b) (reg/herdr->rex b))) (:bindings leader))))
      (is (= [] (reg/key-conflicts r)))
      (is (= (count cmds) (count (distinct (map :id cmds)))) "each leader key has its own action, , . and ? too"))
    (testing "no command binds Ctrl+b, so it reaches the pane"
      (is (= [] (for [c (vals r) ks (:keys c) :when (= "ctrl+b" (first ks))] ks))))
    (testing "Herdr's leader keys reach the same actions"
      (is (= :pane/split-right (:alias (by-keys ["alt+space" "w" "v"]))))
      (is (= :pane/focus-left (:alias (by-keys ["alt+space" "w" "h"]))))
      (is (= :agent/pick (:alias (by-keys ["alt+space" "a"]))))
      (is (= :tab/pick (:alias (by-keys ["alt+space" "b" "b"]))))
      (is (= :tab/pick (:alias (by-keys ["alt+space" ","]))))
      (is (= :session/switch (:alias (by-keys ["alt+space" "."]))))
      (is (= :session/switch (:alias (by-keys ["alt+space" "tab" "."])))))
    (testing "what Rex's Ctrl+b keys did has an Alt+Space sequence"
      (doseq [[ks id] [[["u"] :agent/next-blocked] [["shift+u"] :agent/next-attention] [["i"] :bbh/inbox]
                       [["?"] :mx/describe-command] [["e"] :pane/scrollback] [["s" "b"] :pane/search]
                       [["s" "l"] :hints/open] [["w" "w"] :pane/next] [["w" "shift+l"] :pane/resize-right]
                       [["o" "g"] :layer/jjui] [["p" "p"] :session/project] [["tab" "`"] :session/last]
                       [["tab" "r"] :session/rename] [["t" "r"] :tab/rename] [["t" "n"] :tab/new]]]
        (is (= id (:alias (by-keys (into ["alt+space"] ks)))) (str/join " " ks))))))

(deftest alt-space-reaches-the-tab-commands
  (let [leader (clojure.edn/read-string (slurp (str reg-repo "/desktop/configs/keybindings/leader.edn")))
        cmds (reg/leader-commands leader ["alt+space"])
        r (reg/merge-sources api commands/all cmds)
        by-keys (into {} (for [c cmds k (:keys c)] [k c]))
        run (fn [ks c] (:effects (reg/invoke r c (:id (by-keys ks)) {})))
        four (assoc ctx :window-ids ["window:0" "window:1" "window:2" "window:3"])]
    (is (= [] (reg/key-conflicts r)))
    (testing "Alt+Space t n opens a tab in the focused pane's directory"
      (is (= :tab/new (:alias (by-keys ["alt+space" "t" "n"]))))
      (is (= "session.new_window" (second (first (run ["alt+space" "t" "n"] ctx))))))
    (testing "next, previous, close, go to N and rename"
      (is (= [[:rex/call "session.focus_next_window" {:session_id "session:1"}]] (run ["alt+space" "t" "l"] ctx)))
      (is (= [[:rex/call "session.focus_previous_window" {:session_id "session:1"}]] (run ["alt+space" "t" "h"] ctx)))
      (is (= "session.close_window" (second (first (run ["alt+space" "t" "d"] ctx)))))
      (is (= [[:rex/call "session.focus_window" {:session_id "session:1" :window_id "window:2"}]] (run ["alt+space" "t" "3"] four)))
      (is (= ["label"] (map (comp name :name) (:prompt (reg/invoke r ctx (:id (by-keys ["alt+space" "t" "r"])) {})))))
      (is (= [[:rex/call "session.set_window_label" {:session_id "session:1" :window_id "window:1" :label "logs"}]]
             (:effects (reg/invoke r ctx (:id (by-keys ["alt+space" "t" "r"])) {:label "logs"})))
          "the label the prompt gathered reaches the aliased command"))
    (testing "t H and t L move the tab, and are not t h and t l"
      (is (not= (reg/action-name (:id (by-keys ["alt+space" "t" "shift+h"])))
                (reg/action-name (:id (by-keys ["alt+space" "t" "h"])))))
      (is (= [[:rex/call "session.move_window" {:session_id "session:1" :window_id "window:1" :before_window_id "window:0"}]]
             (run ["alt+space" "t" "shift+h"] four)))
      (is (= [[:rex/call "session.move_window" {:session_id "session:1" :window_id "window:1" :before_window_id "window:3"}]]
             (run ["alt+space" "t" "shift+l"] four)))
      (is (= [[:rex/call "session.move_window" {:session_id "session:1" :window_id "window:2"}]]
             (run ["alt+space" "t" "shift+l"] (assoc four :window-id "window:2")))
          "the last but one moves to the end")
      (is (= [[:ui/message "No room to move the tab"]] (run ["alt+space" "t" "shift+h"] (assoc four :window-id "window:0")))))))

(deftest catalogue-is-plain-data-with-enabled-flags
  (let [c (reg/catalogue registry {})]
    (is (every? #(not (fn? (:run %))) c))
    (is (false? (:enabled (first (filter #(= :rex/session.view (:id %)) c)))))
    (is (true? (:enabled (first (filter #(= :rex/server.status (:id %)) c)))))))

(deftest herdr-parity-commands-make-their-effects
  (testing "Alt+Space s l opens tiny fingers in the window"
    (is (= [[:hints/open {}]] (:effects (reg/invoke registry ctx :hints/open {})))))
  (testing "copy mode and search float the scrollback helper over the pane"
    (let [[[kind {:keys [argv close-on-exit]}]] (:effects (reg/invoke registry ctx :pane/search {:text "error"}))]
      (is (= :layer/float kind))
      (is close-on-exit)
      (is (re-find #"desktop/bin/rexmux-scrollback$" (first argv)))
      (is (= ["session:1" "block:1" "error"] (rest argv)))))
  (testing "renaming a tab names the focused window"
    (is (= [[:rex/call "session.set_window_label" {:session_id "session:1" :window_id "window:1" :label "logs"}]]
           (:effects (reg/invoke registry ctx :tab/rename {:label "logs"}))))))
