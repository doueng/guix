(ns bbh.sessions-test
  "Reaping finished Workers' sessions (T108 item 1), the machine-wide
  heavy-job slot (item 3) and the health pass's machine log (item 7)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.heavy :as heavy]
            [bbh.hermetic :as hermetic]
            [bbh.super :as super]
            [bbh.super.client :as client]
            [bbh.super.process :as process]
            [bbh.super.sessions :as sessions]
            [bbh.super.store :as store]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(use-fixtures :once hermetic/fixture)

(defn- wait-for [pred ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) end) false
                   :else (do (Thread/sleep 20) (recur))))))

(deftest a-session-is-found-by-its-acp-argv
  (is (= "super-W7" (sessions/conversation-of ["bb" "--config" "x/bb.edn" "-m" "bbh.main" "acp" "--id" "super-W7"])))
  (is (= "router" (sessions/conversation-of ["bb" "-m" "bbh.main" "acp" "--id" "router"])))
  (is (nil? (sessions/conversation-of ["bb" "-m" "bbh.mcp"])))
  (is (nil? (sessions/conversation-of ["bb" "-m" "bbh.main" "super" "serve" "--id" "x"]))))

(def ^:private state
  {:workers {"W1" {:id "W1" :state :working :conversation "super-W1"}
             "W2" {:id "W2" :state :done :conversation "super-W2"}
             "W3" {:id "W3" :state :failed :conversation "super-W3"}
             "W4" {:id "W4" :state :cancelled :conversation "super-W4"}}})

(defn- s [conversation pid] {:pid pid :start "1" :conversation conversation})

(deftest only-an-ended-workers-own-idle-session-is-reaped
  (let [live [(s "super-W1" 1) (s "super-W2" 2) (s "super-W3" 3) (s "super-W4" 4) (s "router" 5) (s "super-W9" 9)]
        agents {"super-W1" {:pid 1} "super-W2" {:pid 2} "super-W3" {:pid 3 :busy true} "super-W4" {:pid 40} "router" {:pid 5}}
        {:keys [kill stale since]} (sessions/plan state live agents {} 1000 600000)]
    (is (= ["super-W2"] (map :conversation kill)) "W1 runs, W3 is in a turn, W4's agent.json names another process")
    (is (= ["super-W2" "super-W3"] (map :conversation stale)) "the Router and an unknown conversation are never stale")
    (is (= {"super-W2" 1000 "super-W3" 1000} since))
    (testing "a turn gets the grace from when it was first seen stale"
      (is (= ["super-W2"] (map :conversation (:kill (sessions/plan state live agents since 600999 600000)))))
      (is (= ["super-W2" "super-W3"] (map :conversation (:kill (sessions/plan state live agents since 601000 600000))))))
    (is (= {:sessions 6 :worker-sessions 3 :active-workers 1 :stale ["super-W2"] :reaped ["super-W3"]}
           (sessions/report state live agents [(s "super-W2" 2)] ["super-W3"]))
        "W4's process is not its announced session, so it is not counted as a Worker's")))

(def ^:private grace 50)

(defn- step
  "World W after random choice [K N]: Workers start, end, resume and turn;
  time passes; a health pass runs the controller's plan and kills what it says."
  [{:keys [workers sessions busy since now] :as w} [k n]]
  (let [ids (vec (sort (keys workers)))
        id (when (seq ids) (nth ids (mod n (count ids))))
        conv #(str "super-" %)]
    (case k
      :start (let [id (str "W" (inc (count workers)))]
               (-> w (assoc-in [:workers id] {:id id :state :working :conversation (conv id)})
                   (assoc-in [:sessions (conv id)] {:pid (+ 100 (count workers)) :start "1" :conversation (conv id)})))
      :end (if (and id (= :working (get-in workers [id :state])))
             (-> w (assoc-in [:workers id :state] ([:done :failed :cancelled] (mod n 3)))
                 (update :ended assoc (conv id) now))
             w)
      :resume (if (and id (not= :working (get-in workers [id :state])))
                (-> w (assoc-in [:workers id :state] :working)
                    (update :ended dissoc (conv id))
                    (assoc-in [:sessions (conv id)] {:pid (+ 1000 now) :start "1" :conversation (conv id)}))
                w)
      :turn (if (and id (get sessions (conv id))) (update w :busy (if (busy (conv id)) disj conj) (conv id)) w)
      :tick (update w :now + (inc (mod n 30)))
      :pass (let [st {:workers workers}
                  agents (into {} (map (fn [[c sess]] [c (assoc (select-keys sess [:pid]) :busy (contains? busy c))])) sessions)
                  {:keys [kill] since' :since} (sessions/plan st (vals sessions) agents since now grace)
                  killed (set (map :conversation kill))]
              (when-let [bad (seq (filter #(sessions/holds-session? (get workers (str/replace % "super-" ""))) killed))]
                (throw (ex-info "killed an active Worker's session" {:bad bad})))
              (assoc w :sessions (apply dissoc sessions killed) :since (apply dissoc since' killed))))))

(def ^:private choice
  (gen/tuple (gen/frequency [[2 (gen/return :start)] [2 (gen/return :end)] [1 (gen/return :resume)]
                             [3 (gen/return :turn)] [3 (gen/return :tick)] [3 (gen/return :pass)]])
             gen/nat))

(deftest no-session-outlives-its-worker
  (let [r (tc/quick-check
           150
           (prop/for-all [choices (gen/vector choice 0 60)]
             (let [w (reduce step {:workers {} :sessions {} :busy #{} :since {} :ended {} :now 0} choices)
                   ;; A pass sees what is stale; once the grace has passed, the next reaps it even in a turn.
                   w (-> w (step [:pass 0]) (update :now + grace) (step [:pass 0]))]
               (every? (fn [[c _]] (sessions/holds-session? (get-in w [:workers (str/replace c "super-" "")])))
                       (:sessions w)))))]
    (is (:pass? r) (pr-str (:shrunk r)))))

(defn- session-group!
  "A process group standing in for a session, its leader and a child each a
  sleep, and a sleep in a group of its own inside its session, as a browser a
  test starts, as {:pid :start :other {:pid :start}}."
  []
  (let [pid-file (str (fs/create-temp-file))
        other-file (str (fs/create-temp-file))
        _ (p/shell "setsid" "-f" "bash" "-c" (str "echo $$ > " pid-file "; sleep 60 & set -m; sleep 60 & echo $! > " other-file
                                                "; set +m; exec sleep 60"))
        _ (wait-for #(seq (str/trim (slurp pid-file))) 3000)
        pid (parse-long (str/trim (slurp pid-file)))]
    (wait-for #(process/identity-of pid) 3000)
    (wait-for #(seq (str/trim (slurp other-file))) 3000)
    (assoc (process/identity-of pid) :other (process/identity-of (parse-long (str/trim (slurp other-file)))))))

(deftest a-workers-session-is-killed-when-it-ends-and-its-buffer-is-ended-first
  (let [dir (str (fs/create-temp-dir {:prefix "bbh-super"}))
        conversations (str (fs/create-temp-dir {:prefix "bbh-super-conv"}))
        ws (str (fs/create-temp-dir {:prefix "bbh-super-ws"}))
        group (atom nil)
        ended (atom [])
        swept (atom [])
        c (super/start! {:dir dir :conversations conversations :scan-ms 10
                         :launch {:workspace! (fn [_ w] (str (fs/create-dirs (fs/path ws (str (:thread w) "-" (:generation w))))))
                                  :open! (fn [{:keys [conversation]}]
                                           (let [g (session-group!)]
                                             (reset! group g)
                                             (spit (str (fs/path conversations conversation "agent.json"))
                                                   (json/generate-string (assoc g :busy false))))
                                           "buf")
                                  :land! (fn [_] {:landed nil}) :retire! (constantly nil) :release! (constantly nil)}
                         :sessions {:live #(if-let [g @group] [(assoc g :conversation "super-W1")] [])
                                    :end! #(swap! ended conj (:conversation %))
                                    :sweep! (fn [conversations] (swap! swept conj (vec conversations))
                                              {:shells-ended 0 :timers-swept 2 :mail-timers 1})
                                    :machine (constantly {:mem-available-mb 4000 :swap-used-mb 100})}})]
    (try
      (client/call! dir {:command :thread! :args {:title "reap me"}})
      (client/call! dir {:command :start-worker! :args {:thread "T1" :workflow :investigation :brief "look" :repo (str (fs/home))}})
      (is (wait-for #(= :working (get-in @(:state c) [:workers "W1" :state])) 5000))
      (let [g @group]
        (is (process/matches? g))
        (super/observe-machine! c)
        (is (process/matches? g) "a running Worker's session stays")
        (is (= {:sessions 1 :worker-sessions 1 :active-workers 1 :stale [] :mem-available-mb 4000 :swap-used-mb 100}
               (select-keys @(:machine c) [:sessions :worker-sessions :active-workers :stale :mem-available-mb :swap-used-mb])))
        (client/call! dir {:command :finish-worker! :args {:worker "W1" :state-after :failed :reason "test"}})
        (is (wait-for #(not (process/matches? g)) 10000) "the session dies once its Worker ended")
        (is (= ["super-W1"] @ended) "Emacs was told first, so the buffer stays and does not respawn")
        (is (= "" (str/trim (:out (p/shell {:out :string :continue true} "pgrep" "-g" (str (:pid g))))))
            "nothing is left in its process group")
        (is (some? (:other g)))
        (is (wait-for #(not (process/matches? (:other g))) 3000) "nor in another group of its session (T184)")
        (super/observe-machine! c)
        (is (= 0 (:active-workers @(:machine c))))
        (is (= [] (first @swept)) "no Worker had ended at the first pass")
        (is (= ["super-W1"] (last @swept)) "Emacs is told which Workers ended, to end their shells")
        (is (= {:mail-timers 1 :timers-swept 2} (select-keys @(:machine c) [:mail-timers :timers-swept]))
            "the health event counts Emacs's mailbox timers")
        (is (some #(= :machine-observed (:kind %)) (store/events (:db c))) "the health pass logs the machine")
        (is (= (:machine (:ok (client/call! dir {:command :sections}))) @(:machine c)) "bbh super list shows it"))
      (finally
        (super/stop! c)
        (some-> @group :pid (#(p/shell {:continue true :err :discard} "kill" "-KILL" "--" (str "-" %))))
        (some-> @group :other :pid (#(p/shell {:continue true :err :discard} "kill" "-KILL" "--" (str "-" %))))))))

(deftest a-pid-that-does-not-lead-its-group-is-never-signalled
  (let [proc (p/process "sleep" "30")
        id (process/identity-of (.pid (:proc proc)))]
    (try
      (is (false? (sessions/kill! id :grace-ms 100)))
      (is (process/matches? id))
      (finally (p/destroy proc)))))

(deftest the-list-line-counts-emacs-mailbox-timers
  (is (= "4000 MB available, 1200 MB swap used; 5 sessions live, 3 of them Workers' for 2 active Workers; 3 Emacs mailbox timers (69 leftover cancelled)"
         (client/machine-line {:mem-available-mb 4000 :swap-used-mb 1200 :sessions 5 :worker-sessions 3 :active-workers 2 :mail-timers 3 :timers-swept 69}))))

(deftest the-list-line-names-stale-sessions
  (is (= "4000 MB available, 1200 MB swap used; 5 sessions live, 3 of them Workers' for 2 active Workers; stale: super-W9"
         (client/machine-line {:mem-available-mb 4000 :swap-used-mb 1200 :sessions 5 :worker-sessions 3 :active-workers 2 :stale ["super-W9"]}))))

(deftest the-list-line-names-the-last-earlyoom-kill
  (is (= "4000 MB available, 1200 MB swap used; 5 sessions live, 3 of them Workers' for 2 active Workers; last earlyoom kill: 2026-11-12T21:00:00+0000 pid=7 uid=1000 name=bb"
         (client/machine-line {:mem-available-mb 4000 :swap-used-mb 1200 :sessions 5 :worker-sessions 3 :active-workers 2
                               :earlyoom-last "2026-11-12T21:00:00+0000 pid=7 uid=1000 name=bb"}))))

(deftest the-last-earlyoom-kill-is-the-log's-last-line
  (let [f (fs/create-temp-file)]
    (try
      (is (nil? (sessions/last-earlyoom-kill (str f))))
      (spit (str f) "t1 pid=1 uid=0 name=a\nt2 pid=2 uid=1000 name=bb\n")
      (is (= "t2 pid=2 uid=1000 name=bb" (sessions/last-earlyoom-kill (str f))))
      (is (nil? (sessions/last-earlyoom-kill "/nonexistent/earlyoom-kills.log")))
      (finally (fs/delete-if-exists f)))))

(deftest heavy-commands-are-recognized
  (doseq [cmd ["bb test" "cd desktop/build/bbh && bb test:emacs" "make verify SLOW=1" "make doom-check" "guix build -f x.scm"
               "guix system reconfigure c.scm" "bb verify:tla" "bb eval:replay run1" "flatpak run com.google.Chrome --headless" "tlc Super.tla"
               "emacs-throwaway --batch -- -Q -l t.el" "desktop/bin/emacs-throwaway --daemon bench"]]
    (is (heavy/heavy? cmd) cmd))
  (doseq [cmd ["make lint" "jj log" "bb lint" "guix describe" "bb testing" "make affected" "wf status"]]
    (is (not (heavy/heavy? cmd)) cmd)))

(deftest one-heavy-command-runs-at-a-time-and-a-waiter-says-who-holds-the-slot
  (let [lock (str (fs/path (fs/create-temp-dir) "heavy.lock"))
        run #(p/process {:out :string :err :string} "bash" "-c" (heavy/wrap %1 :who %2 :lock lock :timeout-s %3))
        holder (run "sleep 1.5; echo held" "W1" 10)]
    (is (wait-for #(fs/exists? (heavy/holder-file lock)) 3000))
    (let [timed-out @(run "echo never" "W2" 0)
          waiter @(run "echo $BBH_HEAVY_SLOT" "W3" 10)]
      (is (= 75 (:exit timed-out)))
      (is (str/includes? (:err timed-out) "gave up after 0 s waiting for the heavy-job slot held by W1: sleep 1.5; echo held"))
      (is (= "" (:out timed-out)))
      (is (str/includes? (:err waiter) "waiting for the heavy-job slot held by W1"))
      (is (= {:exit 0 :out "1\n"} (select-keys waiter [:exit :out])) "it runs after, with the slot marked held")
      (is (= "held\n" (:out @holder))))))

(deftest a-command-already-in-the-slot-does-not-wait-for-its-parent
  (with-redefs [heavy/held? (constantly false)]
    (is (str/includes? (heavy/command "bb test") "flock"))
    (is (= "make lint" (heavy/command "make lint"))))
  (with-redefs [heavy/held? (constantly true)]
    (is (= "bb test" (heavy/command "bb test")))))
