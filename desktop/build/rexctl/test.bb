#!/usr/bin/env bb
;; rexctl's rules on made-up Rex replies, then (with --live) the CLI end to end
;; against a throwaway Rex server on a private socket, as an agent would use it.
;; The live part never touches your Rex: REX_SERVER names the temp socket, and
;; rexctl passes --autostart=false.
(require '[babashka.classpath :as classpath] '[babashka.fs :as fs] '[babashka.process :as p]
         '[cheshire.core :as json] '[clojure.string :as str] '[clojure.test :as t :refer [deftest is testing]])
(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/real-path *file*)))))))
(classpath/add-classpath (str root "/desktop/lib"))
(classpath/add-classpath (str root "/desktop/build/rexmux/test"))
(require '[rexctl :as r])

(def inspect
  {:session_id "session:s" :label "main" :active_window_id "window:w2"
   :windows [{:window_id "window:w1" :label "shell" :focused_block_id "block:a"
              :layers [{:kind "tiled" :blocks [{:block_id "block:a" :label "sh" :creator_name "com.superlogical.terminal"}]}]}
             {:window_id "window:w2" :label "pi" :focused_block_id "block:c"
              :layers [{:kind "tiled" :blocks [{:block_id "block:b" :label "pi" :creator_name "com.superlogical.terminal"}
                                               {:block_id "block:c" :label "logs" :creator_name "com.superlogical.terminal"}
                                               {:block_id "block:v" :label "diff" :creator_name "com.superlogical.viewer"}]}]}]
   :clients [{:info {:kind "cli"} :connection_state "connected"}]})

(def viewed (update inspect :clients conj {:info {:kind "app" :name "ghostty-rex"} :connection_state "connected"}))

(deftest panes
  (let [ps (r/session-panes inspect)]
    (testing "only terminals, in tab order, with one-based tab numbers"
      (is (= ["block:a" "block:b" "block:c"] (map :pane ps)))
      (is (= [1 2 2] (map :tab_index ps))))
    (testing "the focused pane is the active tab's, not every tab's"
      (is (= ["block:c"] (map :pane (filter :focused ps)))))
    (testing "a session is viewed only while an app is attached"
      (is (not-any? :viewed ps))
      (is (every? :viewed (r/session-panes viewed))))))

(deftest guard
  (let [by-id (fn [ins id] (some #(when (= id (:pane %)) %) (r/session-panes ins)))]
    (is (some? (r/guard (by-id inspect "block:a") "block:a")) "your own pane")
    (is (some? (r/guard (by-id viewed "block:c") nil)) "the focused pane of a session an app shows")
    (is (nil? (r/guard (by-id inspect "block:c") nil)) "focused, but no app shows the session")
    (is (nil? (r/guard (by-id viewed "block:b") nil)) "a pane beside the focused one")))

(deftest status
  (is (nil? (r/status-of [])))
  (is (= "blocked" (:state (r/status-of [{:state "working"} {:state "blocked" :app "pi"} {:state "done"}]))))
  (is (= "pi" (:app (r/status-of [{:state "idle" :effective_app "pi"}]))))
  (is (= "idle" (:state (r/status-of [{:state "dancing"}]))) "an unknown state counts as idle"))

(deftest prompt
  (is (r/at-prompt? {:child {:pid 1 :name "fish"} :foreground {:pid 1 :name "fish"}}))
  (is (not (r/at-prompt? {:child {:pid 1 :name "fish"} :foreground {:pid 2 :name "make"}})))
  (is (not (r/at-prompt? {:child {:pid 1 :name "pi"} :foreground {:pid 1 :name "pi"}})) "a pane started on a program")
  (is (not (r/at-prompt? {:last_exit {:exit_code 0}})) "an exited pane has no prompt"))

(deftest text
  (is (= "c\nd" (r/tail-lines "a\nb\nc\nd" 2)))
  (is (= ["x DONE" "DONE"] (r/hits (r/matcher {:match "DONE"}) "x DONE\ny\nDONE")))
  (is (= ["DONE"] (r/hits (r/matcher {:regex "^DONE$"}) "$ echo DONE\nDONE")))
  (is (= "$ make\nok\n$" (r/since-command "$ ls\nx\n$ make\nok\n$" "make" 40)))
  (is (= "right" (r/split-direction {:columns 200 :rows 50 :cell_width_px 8 :cell_height_px 16})))
  (is (= "below" (r/split-direction {:columns 80 :rows 60 :cell_width_px 8 :cell_height_px 16}))))

;; Live

(def ^:dynamic *env* nil)

(defn rexctl [& args]
  (let [{:keys [exit out err]} (apply p/sh {:env *env* :continue true} (str root "/desktop/bin/rexctl") args)]
    {:exit exit :out out :err err
     :json (try (json/parse-string out true) (catch Exception _ nil))}))

(deftest live
  (require '[rexmux.testserver :as ts])
  (let [start! (resolve 'rexmux.testserver/start!) stop! (resolve 'rexmux.testserver/stop!)
        rex! (resolve 'rexmux.testserver/rex!) env-of (resolve 'rexmux.testserver/env)
        s (start! {:bin (r/bin)})]
    (try
      (let [created (json/parse-string (:out (rex! s "new" "work" "--json")) true)
            home-pane (first (get-in created [:initial_windows 0 :block_ids]))]
        (binding [*env* (-> (into {} (System/getenv)) (merge (env-of s)) (dissoc "REX_BLOCK" "REX_SESSION" "REX_CLIENT"))]
          (testing "ls lists the one pane, at its prompt"
            (let [[p :as ps] (:json (rexctl "ls"))]
              (is (= 1 (count ps)))
              (is (= home-pane (:pane p)))
              (is (:at_prompt p))))
          (let [pane (get-in (rexctl "split" home-pane "--dir" "below" "--label" "agent-work") [:json :pane])]
            (testing "split makes a new pane and leaves focus where it was"
              (is (str/starts-with? (str pane) "block:"))
              (is (= [home-pane] (map :pane (filter :focused (:json (rexctl "ls")))))))
            (testing "run --wait returns the command's own output"
              (let [{:keys [ok output]} (:json (rexctl "run" "agent-work" "echo $((6*7))" "--wait" "--timeout" "20000"))]
                (is ok)
                (is (str/includes? output "42"))
                (is (str/includes? (:out (rexctl "read" pane)) "42"))))
            (testing "wait --new skips the echo of the command and sees its output"
              (rexctl "run" pane "sleep 1; echo FIN-$((1+1))")
              (let [{:keys [ok line]} (:json (rexctl "wait" pane "--match" "FIN-2" "--new" "--timeout" "10000"))]
                (is ok)
                (is (= "FIN-2" (str/trim (str line))))))
            (testing "run refuses a pane whose shell is busy"
              (rexctl "run" pane "sleep 30")
              (Thread/sleep 500)
              (let [{:keys [exit err]} (rexctl "run" pane "echo no")]
                (is (= 1 exit))
                (is (str/includes? err "not at a shell prompt")))
              (rexctl "keys" pane "ctrl+c")
              (is (:ok (:json (rexctl "wait" pane "--prompt" "--timeout" "10000")))))
            (testing "an agent's OSC 7501 report shows in status and ends a wait"
              (rexctl "run" pane "printf '\\033]7501;state=blocked:kind=question:app=pi\\033\\\\'" "--wait")
              (is (= {:state "blocked" :app "pi" :kind "question"}
                     (select-keys (get-in (rexctl "status" pane) [:json :status]) [:state :app :kind])))
              (is (:ok (:json (rexctl "wait" pane "--status" "blocked" "--timeout" "5000")))))
            (testing "exec runs a command in a new pane and reports its exit code"
              (let [{:keys [exit output]} (:json (rexctl "exec" "--anchor" pane "--" "sh" "-c" "echo built; exit 3"))]
                (is (= 3 exit))
                (is (= "built" output))))
            (testing "typing into your own pane is refused without --force"
              (binding [*env* (assoc *env* "REX_BLOCK" pane)]
                (let [{:keys [exit err]} (rexctl "send" pane "x")]
                  (is (= 1 exit))
                  (is (str/includes? err "your own pane")))))
            (testing "snapshot writes each pane's text"
              (let [{:keys [panes]} (:json (rexctl "snapshot"))]
                (is (= 3 (count panes)))
                (is (some #(str/includes? (slurp (:file %)) "FIN-2") panes))))
            (testing "close removes the pane"
              (rexctl "close" pane)
              (is (not-any? #(= pane (:pane %)) (:json (rexctl "ls"))))))))
      (finally (stop! s)))))

(let [vars (if (some #{"--live"} *command-line-args*) [#'live] [#'panes #'guard #'status #'prompt #'text])
      {:keys [test pass fail error]} (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                                       (t/test-vars vars)
                                       @t/*report-counters*)]
  (println (str "rexctl: " pass " assertions, " fail " failures, " error " errors"))
  (System/exit (if (zero? (+ fail error)) 0 1)))
