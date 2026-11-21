(ns rexmux.sidecar-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [rexmux.props :refer [trials]]
            [rexmux.proto :as proto]
            [rexmux.sidecar :as sc]))

(def help (json/parse-string (slurp "test/fixtures/rex-api-rev18.json") true))
(def events (map #(json/parse-string % true) (remove str/blank? (str/split-lines (slurp "test/fixtures/events-blocked.ndjson")))))
(def io {:load-help (constantly help)
         :rex {:sessions (constantly [{:session_id "session:1" :label "work"}])
               :capture (constantly "line")}})
(def ctx {:session-id "session:1" :block-id "block:9"})

(defn run
  "Feed MSGS (already numbered) to a fresh sidecar; [state outgoing]."
  [msgs]
  (reduce (fn [[s out] m] (let [[s o] (if (= :rex/event (:op m)) (sc/deliver s io m) (sc/handle s io m))] [s (into out o)]))
          [(sc/initial-state sc/default-config) []] msgs))

(defn numbered [msgs] (map-indexed (fn [i m] (assoc m :seq (inc i))) msgs))

(deftest hello-sends-the-catalogue-and-keymap
  (let [[_ out] (run (numbered [{:op :hello :client-id "client:1" :ctx ctx}]))
        cat (first (filter #(= :commands/changed (:op %)) out))]
    (is (< 90 (count (:commands cat))))
    (is (= "mx" (get-in cat [:keymap :name])))
    (is (= [] (:conflicts cat)))
    (is (every? #(proto/op-ok? :to-rust (:op %)) out))))

(deftest invoke-replies-once-with-its-id
  (let [[_ out] (run (numbered [{:op :hello :ctx ctx}
                                {:op :commands/invoke :id 7 :command :rex/session.focus_direction :args {:direction "left"}}
                                {:op :commands/invoke :id 8 :command :session/switch :args {}}]))
        replies (filter #(= :reply (:op %)) out)]
    (is (= [7 8] (map :id replies)))
    (is (= [[:rex/call "session.focus_direction" {:session_id "session:1" :direction "left"}]]
           (:effects (first replies))))
    (is (= :ui/pick (ffirst (:effects (second replies)))))))

(deftest a-blocked-agent-event-reaches-the-window-and-next-blocked
  (let [[_ out] (run (concat (numbered [{:op :hello :ctx {:session-id "s"}}])
                             (for [e events] {:op :rex/event :event e :server "local"})
                             [{:op :commands/invoke :seq 2 :id 3 :command :agent/next-blocked}]))
        agents (last (filter #(= :agents (:op %)) out))
        reply (first (filter #(= :reply (:op %)) out))]
    (is (= ["blocked"] (map :state (:rows agents))))
    (is (= :focus/block (ffirst (:effects reply))))
    (is (= "local" (:server (second (first (:effects reply))))))))

(deftest hello-reads-the-agents-rex-already-has
  ;; A sidecar that restarts must agree with Rex before it reports (TLA SidecarAgrees).
  (let [io (assoc io :resync (constantly [{:session "s" :block "b7" :server "local" :record {:state "blocked" :app "pi"}}]))
        [_ out] (reduce (fn [[s o] m] (let [[s o2] (sc/handle s io m)] [s (into o o2)]))
                        [(sc/initial-state sc/default-config) []] (numbered [{:op :hello :ctx ctx}]))
        agents (first (filter #(= :agents (:op %)) out))]
    (is (= ["b7"] (:attention agents)))))

(deftest repeats-are-dropped-and-gaps-reported
  (let [[_ out] (run [{:op :hello :seq 1 :ctx ctx} {:op :ping :seq 2 :id 1} {:op :ping :seq 2 :id 1} {:op :ping :seq 5 :id 2}])]
    (is (= [1 2] (map :id (filter #(= :pong (:op %)) out))))
    (is (some #(re-find #"missed messages \[3 4\]" (str (:text %))) out))))

(defn- window-msgs [ops]
  (cons {:op :hello :ctx ctx}
        (map-indexed (fn [i op]
                       (case op
                         :ping {:op :ping :id i}
                         :context {:op :context :ctx (assoc ctx :block-id (str "block:" (mod i 3)))}
                         :invoke {:op :commands/invoke :id i :command :agent/next-blocked}
                         :say {:op :commands/invoke :id i :command :bbh/say :args {:text "hi"}}
                         :event {:op :rex/event :event (nth events (mod i (count events))) :server "local"}))
                     ops)))

(defn- number-window [msgs]
  (second (reduce (fn [[n acc] m] (if (= :rex/event (:op m)) [n (conj acc m)] [(inc n) (conj acc (assoc m :seq n))])) [1 []] msgs)))

(deftest outgoing-numbering-has-no-holes-or-repeats
  (is (:pass? (tc/quick-check
               (trials 100)
               (prop/for-all [ops (gen/vector (gen/elements [:ping :context :invoke :say :event]) 0 25)]
                 (let [msgs (number-window (window-msgs ops))
                       [_ out] (run msgs)
                       ;; spawns and notifications stay in the sidecar's process
                       out (remove #(sc/local-ops (:op %)) out)
                       invokes (keep #(when (= :commands/invoke (:op %)) (:id %)) msgs)]
                   (and (= (map :seq out) (range 1 (inc (count out))))
                        (= invokes (keep #(when (= :reply (:op %)) (:id %)) out)))))))))

(deftest the-shipped-config-loads-with-the-real-leader-and-no-key-conflicts
  (let [config (sc/read-config "../../configs/rexmux/config.edn")
        {:keys [registry errors]} (with-redefs [babashka.fs/home (constantly (babashka.fs/path "../.."))]
                                    (sc/build-registry help (sc/read-config "../../configs/rexmux/config.edn")))]
    (is (str/ends-with? (:leader-file config) "/.config/keybindings/leader.edn") "~ is expanded")
    (is (:term-nav config))
    ;; The Herdr guix workspace's tabs, in order, so Super+Return E D G match.
    (is (= ["shell" "bbh" "pi" "jjui"] (map :label (get-in config [:startup :windows]))))
    (is (= "fish" (:shell config)) "blocks run fish, as Herdr's default_shell")
    (doseq [id [:hints/open :pane/scrollback :pane/search :tab/rename :session/rename :session/last
                :session/project :clipboard/paste :pane/resize-left]]
      (is (contains? registry id) (str id " is in the registry")))
    (is (= [] errors))
    (is (= [] (rexmux.registry/key-conflicts registry)))
    (is (contains? registry :pane/term-nav-left))))

(deftest spawned-helpers-are-found-on-hyprlands-path
  ;; A window Hyprland opens has no ~/.local/bin on PATH, so a bare keyleader
  ;; (bare Ctrl+h/j/k/l, Ctrl+L in a shell) was not found (T207).
  (let [hypr "/gnu/profile/bin:/run/current-system/profile/bin"
        env (sc/spawn-env {"TERM_PROGRAM" "rex"} "unix:///tmp/r.sock" hypr)]
    (is (= (str hypr ":" rexmux.commands/bin-dir) (get env "PATH")))
    (is (= "unix:///tmp/r.sock" (get env "REX_SERVER")))
    (is (not (contains? (sc/spawn-env {} nil hypr) "REX_SERVER")))
    (doseq [helper ["keyleader" "bbh"]]
      (is (= [(str (babashka.fs/path rexmux.commands/bin-dir helper)) "x"] (sc/resolve-argv [helper "x"] env))
          (str helper " resolves to desktop/bin")))
    (is (= ["/bin/sh" "-c"] (sc/resolve-argv ["/bin/sh" "-c"] env)) "a path stays as it is")
    (is (= ["no-such-program-w260"] (sc/resolve-argv ["no-such-program-w260"] env)))
    (is (= ["/run/current-system/profile/bin/ls"]
           (sc/resolve-argv ["ls"] (sc/spawn-env {} nil "/run/current-system/profile/bin")))
        "a program on the inherited PATH wins over desktop/bin")))

(deftest a-newly-blocked-agent-elsewhere-notifies-once
  (let [blocked (fn [a block] (rexmux.agents/report a {:session "s" :block block :id "1" :record {:state "blocked" :app "pi" :msg "approve?"}}))
        a1 (blocked rexmux.agents/empty-state "block:x")
        [note :as notes] (sc/attention-notes {:block-id "block:here"} rexmux.agents/empty-state a1)]
    (is (= 1 (count notes)))
    (is (= {:op :notify :summary "pi needs you"} (select-keys note [:op :summary])))
    (is (str/starts-with? (:body note) "approve?\nAlt+Space u goes there: "))
    (is (= [] (sc/attention-notes {:block-id "block:here"} a1 a1)) "still blocked is no news")
    (is (= [] (sc/attention-notes {:block-id "block:y"} a1 (blocked a1 "block:y"))) "not for the pane being looked at")
    (testing "a Rex event's notification is performed, not sent to the window"
      (let [sent (atom [])
            io (assoc io :notify (fn [s b] (swap! sent conj s)))
            [_ out] (run (concat (numbered [{:op :hello :ctx {:session-id "s"}}])
                                 (for [e events] {:op :rex/event :event e :server "local"})))
            window (sc/perform! io out)]
        (is (seq (filter #(= :notify (:op %)) out)))
        (is (= (count (filter #(= :notify (:op %)) out)) (count @sent)))
        (is (not-any? #(sc/local-ops (:op %)) window))
        (is (every? #(proto/op-ok? :to-rust (:op %)) window))))))

(defn- invoke-spawns
  "The spawn messages, and the window's reply, of invoking COMMAND with ARGS
  in CTX on a sidecar whose config turns term-nav on."
  [command args ctx]
  (let [s (first (sc/handle (sc/initial-state (assoc sc/default-config :term-nav true)) io {:op :hello :seq 1 :ctx ctx}))
        [_ out] (sc/handle s io {:op :commands/invoke :seq 2 :id 5 :command command :args args :ctx ctx})]
    [(filterv #(= :spawn (:op %)) out) (first (filter #(= :reply (:op %)) out))]))

(deftest spawning-commands-return-their-argv-and-env-as-data
  (let [ctx {:session-id "session:1" :block-id "block:9" :server "far"}
        config {:servers [{:label "local" :address nil} {:label "far" :address "unix:///tmp/far.sock"}]}
        stubs (str (babashka.fs/create-temp-dir {:prefix "rexmux-path"}))
        _ (doseq [p ["keyleader" "bbh"]]
            (spit (str stubs "/" p) "#!/bin/sh\n") (babashka.fs/set-posix-file-permissions (str stubs "/" p) "rwxr-xr-x"))
        hypr (str stubs ":/run/current-system/profile/bin")]
    (doseq [[command args argv env] [[:pane/term-nav-left {} ["keyleader" "term-nav" "left"]
                                      {"REX_BLOCK" "block:9" "REX_SESSION" "session:1" "TERM_PROGRAM" "rex"}]
                                     [:pane/term-nav-down {} ["keyleader" "term-nav" "down"]
                                      {"REX_BLOCK" "block:9" "REX_SESSION" "session:1" "TERM_PROGRAM" "rex"}]
                                     [:bbh/say {:text "T12 go on"} ["bbh" "say" "--from" "rex" "T12 go on"] {}]]]
      (let [[spawns reply] (invoke-spawns command args ctx)
            spec (sc/spawn-spec config (first spawns) hypr)]
        (is (= [{:op :spawn :server "far" :argv argv :env env :for 5}] spawns) (str command))
        (is (nil? (:seq (first spawns))) "a local op takes no number")
        (is (not-any? #(= :spawn (first %)) (:effects reply)))
        (is (= (into [(str stubs "/" (first argv))] (rest argv)) (:argv spec)) "found on the child's PATH, a test's stubs first")
        (is (= (merge env {"PATH" (str hypr ":" rexmux.commands/bin-dir) "REX_SERVER" "unix:///tmp/far.sock"}) (:env spec)))))
    (testing "without stubs, the helpers resolve to desktop/bin, which Hyprland's PATH lacks"
      (let [[[spawn]] (invoke-spawns :bbh/say {:text "hi"} (dissoc ctx :server))
            spec (sc/spawn-spec config spawn "/run/current-system/profile/bin")]
        (is (= (str (babashka.fs/path rexmux.commands/bin-dir "bbh")) (first (:argv spec))))
        (is (not (contains? (:env spec) "REX_SERVER")) "the first server, local, has no address")))
    (testing "perform! starts each spawn and passes the rest on"
      (let [started (atom [])
            [spawns reply] (invoke-spawns :pane/term-nav-left {} ctx)]
        (is (= [reply] (sc/perform! {:spawn! #(swap! started conj (:argv %))} (conj spawns reply))))
        (is (= [["keyleader" "term-nav" "left"]] @started))))))

(deftest a-spawn-that-throws-answers-its-invoke-with-an-error-and-loses-nothing
  (let [logged (atom [])
        io (assoc io :log #(swap! logged conj %)
                  :spawn! (fn [_] (throw (java.io.IOException. "Cannot run program \"bbh\"")))
                  :notify (fn [_ _] (throw (java.io.IOException. "Cannot run program \"notify-send\""))))
        steps (numbered [{:op :hello :ctx ctx}
                         {:op :commands/invoke :id 5 :command :bbh/say :args {:text "T12 go on"} :ctx ctx}
                         {:op :ping :id 6}])
        [state window] (reduce (fn [[s acc] m] (let [[s out] (sc/handle s io m)] [s (into acc (sc/perform! io out))]))
                               [(sc/initial-state sc/default-config) []] steps)
        [_ more] (sc/deliver state io {:op :rex/event :event (first events) :server "local"})
        window (into window (sc/perform! io more))
        reply (first (filter #(= 5 (:id %)) window))]
    (is (= {:op :reply :id 5} (select-keys reply [:op :id])) "the invoke is answered")
    (is (str/includes? (:error reply) "Cannot run program") "with the error, not Sent:")
    (is (not-any? #(= [:ui/message "Sent: T12 go on"] %) (:effects reply)))
    (is (some #(= :pong (:op %)) window) "a later message still arrives")
    (is (= (range 1 (inc (count window))) (map :seq window)) "numbered from 1 without a gap")
    (is (not-any? #(sc/local-ops (:op %)) window))
    (is (some #(str/includes? % "bbh") @logged) "the failure is logged")))
