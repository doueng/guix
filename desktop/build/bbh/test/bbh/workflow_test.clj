(ns bbh.workflow-test
  (:require [babashka.fs :as fs]
            [bbh.workflow :as wf]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(def ^:private repro "! grep -q bug file.txt")

(defn- with-temp-state [f]
  (let [root (str (fs/create-temp-dir {:prefix "wf-state"}))]
    (with-redefs [wf/state-root (fn [] root)]
      (f))))

(use-fixtures :each with-temp-state)

(defn- buggy-repo []
  (let [dir (str (fs/create-temp-dir {:prefix "wf-repo"}))]
    (spit (str dir "/file.txt") "bug\n")
    dir))

(defn- fix! [dir] (spit (str dir "/file.txt") "fixed\n"))

(defn- at [id]
  (let [st (wf/status id)]
    [(:phase st) (get-in st [:visits (:phase st)])]))

(defn- evidence! [id file text]
  (spit (str (fs/path (:evidence (wf/status id)) file)) text))

(deftest bug-fix-advances-only-on-runner-evidence
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :task "fix file" :repro repro})]
    (is (= [:reproduce 1] (at id)))
    (is (= :pass (:outcome (wf/attest! id "repro fails"))))
    (is (= [:diagnose 1] (at id)))
    (is (= :fail (:outcome (wf/attest! id "mechanism"))))
    (is (= [:diagnose 2] (at id)))
    (evidence! id "mechanism.md" "grep finds bug in file.txt")
    (is (= :pending (:outcome (wf/attest! id "mechanism"))))
    (is (= [:diagnose 2] (at id)))
    (wf/verdict! id "fresh-session" :pass "evidence supports the mechanism")
    (is (= :pass (:outcome (wf/attest! id "mechanism"))))
    (is (= [:implement 1] (at id)))
    (is (= :pass (:outcome (wf/attest! id "no change yet"))))
    (is (= [:verify 1] (at id)))
    (is (= :fail (:outcome (wf/attest! id "claims fixed"))))
    (is (= [:implement 2] (at id)))
    (fix! repo)
    (wf/attest! id "fix applied")
    (is (= [:verify 2] (at id)))
    (is (= :pass (:outcome (wf/attest! id "repro passes"))))
    (is (= [:finish 1] (at id)))
    (wf/attest! id "reported")
    (is (= :done (:status (wf/status id))))))

(deftest premature-fixed-claim-is-rejected
  (let [repo (buggy-repo)]
    (fix! repo)
    (let [id (wf/start! :bug-fix {:cwd repo :repro repro})]
      (is (= :fail (:outcome (wf/attest! id "already fixed"))))
      (is (= [:reproduce 2] (at id))))))

(deftest repeated-verify-failures-block-the-run
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro repro})]
    (wf/attest! id "bug reproduces")
    (wf/attest! id "mechanism")
    (evidence! id "mechanism.md" "grep finds bug")
    (wf/verdict! id "fresh-session" :pass "supported")
    (wf/attest! id "mechanism")
    (doseq [_ (range 3)]
      (wf/attest! id "fix")
      (wf/attest! id "verify"))
    (let [st (wf/status id)]
      (is (= :blocked (:status st)))
      (is (= "phase implement exceeded 3 visits" (:block-reason st))))))

(deftest hanging-gate-fails-even-when-nonzero-was-expected
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro "sleep 30"})
        st (wf/status id)
        gate {:kind :command :cmd :repro :expect :nonzero :timeout 1}
        result (#'wf/run-command id st :reproduce 1 0 gate)]
    (is (:timed-out? result))
    (is (false? (:pass? result)))))

(deftest unknown-run-is-a-clean-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no run named nope"
                        (wf/status "nope"))))

(deftest change-requires-a-check-command
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs --check"
                        (wf/start! :change {:cwd "."}))))

(deftest decide-advances-retries-and-caps
  (let [wf {:phases {:a {:next :b :max-visits 1}
                     :b {:next nil}}}]
    (is (= [{:type :enter :phase :b :visit 1}]
           (wf/decide wf {:phase :a :visits {:a 1}} [{:pass? true}])))
    (is (= [{:type :done}]
           (wf/decide wf {:phase :b :visits {:b 1}} [{:pass? true}])))
    (is (= [] (wf/decide wf {:phase :a :visits {:a 1}} [{:pending? true}])))
    (is (= [{:type :block :phase :a :reason "phase a exceeded 1 visits"}]
           (wf/decide wf {:phase :a :visits {:a 1}} [{:pass? false}])))))

(deftest a-torn-last-journal-line-is-dropped
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro repro})
        jf (#'wf/journal-file id)]
    (spit jf "{:type :attest :phase :reprod" :append true)
    (is (= [:reproduce 1] (at id)))
    (is (= 1 (count (str/split-lines (slurp jf)))))
    (is (= :pass (:outcome (wf/attest! id "repro fails"))))))

(deftest a-corrupt-line-before_the-end-stops-reads
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro repro})
        jf (#'wf/journal-file id)]
    (spit jf (str "not-edn{\n" (slurp jf)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"journal corrupt at line 1"
                          (wf/status id)))))

(deftest reviewer-runs-in-a-fresh-session-and-its-verdict-is-recorded
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro repro})
        script (str (fs/path (fs/create-temp-dir {:prefix "wf-script"}) "script.edn"))]
    (spit script (pr-str ["VERDICT: pass\nThe mechanism explains the grep result."]))
    (wf/attest! id "bug reproduces")
    (wf/attest! id "mechanism")
    (evidence! id "mechanism.md" "grep finds bug")
    (let [review (wf/review! id :model (str "script/" script))
          attested (wf/attest! id "mechanism")
          verdict-gate (second (:results attested))]
      (is (= :pass (:verdict review)))
      (is (str/starts-with? (:reviewer review) "bbh-review/"))
      (is (= :pass (:outcome attested)))
      (is (str/starts-with? (:reviewer verdict-gate) "bbh-review/")))))

(deftest reviewer-without-a-verdict-line-is-an-error
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro repro})
        script (str (fs/path (fs/create-temp-dir {:prefix "wf-script"}) "script.edn"))]
    (spit script (pr-str ["Looks fine to me."]))
    (wf/attest! id "bug reproduces")
    (wf/attest! id "mechanism")
    (evidence! id "mechanism.md" "grep finds bug")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no VERDICT line"
                          (wf/review! id :model (str "script/" script))))))

(deftest performance-needs-a-bench-command
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs --bench"
                        (wf/start! :performance {:cwd "."}))))

(deftest session-dir-honors-an-explicit-directory
  (is (= "/tmp/explicit-sessions" (wf/session-dir "/tmp/workspace" "/tmp/explicit-sessions"))))

(deftest session-dir-uses-the-pi-workspace-slug
  (is (str/ends-with? (wf/session-dir "/home/engstrand/guix") "/sessions/--home-engstrand-guix--")))

(deftest routes-match-the-jev-classifiers
  (let [ts (slurp "../../pi/agent/extensions/jev-pstack.ts")
        ts-block (second (re-find #"(?s)ROUTES = \{(.*?)\n\};?" ts))
        ts-routes (set (map second (re-seq #"(?m)^\s+(\w+):" ts-block)))
        init (slurp "../../configs/bbh/init.clj")
        init-block (second (re-find #"(?s)\(def routes(.*?)\(defn jev-classify-task" init))
        init-routes (set (map second (re-seq #":(\w+) \"" init-block)))
        covered (set (mapcat #(:routes (wf/load-workflow %))
                              [:bug-fix :change :investigation :performance :prototype :handoff :large-project]))
        manual #{"pr_workflow" "skill_work" "other"}
        expected (set/union covered manual)]
    (is (= expected ts-routes))
    (is (= expected init-routes))
    (is (empty? (set/intersection covered manual)))
    ;; The route table in the poteto-mode skill is the one a human reads. It must agree too.
    (let [skill (slurp "../../pi/agent/skills/pstack/poteto-mode/SKILL.md")
          rows (for [[_ routes workflow] (re-seq #"(?m)^\| ([\w, ]+?) \| ([\w-]+) \|" skill)
                     r (str/split routes #", ")
                     :when (not= "Route" r)]
                 [r workflow])
          skill-routes (set (map first rows))
          skill-covered (set (for [[r w] rows :when (not= "manual" w)] r))]
      (is (= expected skill-routes))
      (is (= covered skill-covered))
      (is (every? (fn [[r w]]
                    (or (= "manual" w)
                        (some #{r} (:routes (wf/load-workflow w)))))
                  rows)))))
