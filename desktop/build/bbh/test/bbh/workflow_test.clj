(ns bbh.workflow-test
  (:require [babashka.fs :as fs]
            [bbh.workflow :as wf]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(def ^:private repro "! grep -q bug file.txt")

(defn- with-temp-state [f]
  (let [root (str (fs/create-temp-dir {:prefix "wf-state"}))]
    (with-redefs [wf/state-root (fn [] root)
                  wf/session-run-file (constantly nil)]
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
      (is (str/starts-with? (:reviewer verdict-gate) "bbh-review/"))
      (is (some #(str/includes? (slurp (str %)) "<principle name=\\\"attack-the-premise\\\">")
                (fs/glob (fs/parent (:evidence (wf/status id))) "reviews/*/messages.jsonl"))
          "the reviewer is briefed with the phase's principles"))))

(deftest a-phase-brief-carries-its-principles-in-full
  (let [id (wf/start! :bug-fix {:cwd (buggy-repo) :repro repro})
        _ (wf/attest! id "bug reproduces")
        out (with-out-str (#'wf/print-status (wf/status id)))]
    (is (= ["fix-root-causes" "attack-the-premise"] (:principles (wf/status id))))
    (is (str/includes? out "principles: fix-root-causes, attack-the-premise."))
    (is (str/includes? out "<principle name=\"fix-root-causes\">"))
    (is (str/includes? out "- **Write the premise down.** The premise is the one sentence that every failed fix assumed."))
    (is (not (str/includes? out "disable-model-invocation")) "the YAML header is stripped")
    (is (not (str/includes? out "](../")) "links resolve from any directory")))

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

(deftest the-default-run-belongs-to-this-directory
  (let [mine (buggy-repo)
        theirs (buggy-repo)
        sub (str (fs/create-dirs (fs/path mine "sub")))
        id (wf/start! :bug-fix {:cwd mine :repro repro})
        _ (Thread/sleep 5)
        other (wf/start! :bug-fix {:cwd theirs :repro repro})]
    (is (= other (wf/latest-id)))
    (is (= id (wf/latest-id mine)))
    (is (= id (wf/latest-id sub)))
    (is (= other (wf/latest-id theirs)))
    (is (nil? (wf/latest-id (str (fs/create-temp-dir {:prefix "wf-elsewhere"})))))))

(deftest status-json-lists-phases-in-run-order
  (let [j (wf/status-json {:id "r" :workflow :change :status :active :phase :implement
                           :visits {:shape 1 :implement 1}})]
    (is (= ["shape" "implement" "verify" "review" "finish"] (map :name (:phases j))))
    (is (= "implement" (:phase j)))
    (is (= 1 (:visit j)))
    (is (= [1 1 0 0 0] (map :visits (:phases j))))
    (is (= ["artifact shape.md"] (:gates (first (:phases j)))))
    (is (= ["command `bb test`"] (:gates (second (:phases (wf/status-json {:id "r" :workflow :change :status :active :phase :implement :visits {} :check "bb test"}))))))))

(def ^:private shipped
  [:bug-fix :change :investigation :performance :prototype :handoff :large-project])

(deftest shipped-workflows-are-valid
  (doseq [w shipped]
    (is (= [] (wf/workflow-problems (wf/load-workflow w))) (name w))))

(deftest invalid-workflows-are-rejected-with-every-problem
  (let [good (wf/load-workflow :bug-fix)
        problems (fn [f] (wf/workflow-problems (f good)))]
    (is (= [":implement :next names missing phase :verfy"]
           (problems #(assoc-in % [:phases :implement :next] :verfy))))
    (is (= [":verify :on-fail names missing phase :nope"]
           (problems #(assoc-in % [:phases :verify :on-fail] :nope))))
    (is (= [":start names missing phase :begin"] (problems #(assoc % :start :begin))))
    (is (= [":reproduce gate 0 has unknown :kind :comand"]
           (problems #(assoc-in % [:phases :reproduce :gates 0 :kind] :comand))))
    (is (re-find #"has :cmd :tset" (first (problems #(assoc-in % [:phases :reproduce :gates 0 :cmd] :tset)))))
    (is (= [":diagnose gate 0 has no string :path"]
           (problems #(update-in % [:phases :diagnose :gates 0] dissoc :path))))
    (is (= [":requires names unknown flag :repo"] (problems #(assoc % :requires [:repo]))))
    (is (= [":diagnose names missing principle \"fix-root-cause\""]
           (problems #(assoc-in % [:phases :diagnose :principles] ["fix-root-cause"]))))
    (is (= [":diagnose :principles is not a vector of strings"]
           (problems #(assoc-in % [:phases :diagnose :principles] "fix-root-causes"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid workflow bug-fix: :implement :next"
                          (wf/validate-workflow (assoc-in good [:phases :implement :next] :verfy))))))

(deftest an-unknown-phase-blocks-instead-of-finishing
  (let [wf {:phases {:a {:next :gone}}}]
    (is (= [{:type :block :phase :gone :reason "unknown phase :gone"}]
           (wf/decide wf {:phase :a :visits {:a 1}} [{:pass? true}])))))

(deftest a-run-keeps-the-definition-it-started-with
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo :repro repro})
        original (wf/load-workflow :bug-fix)
        edited (assoc-in original [:phases :reproduce :brief] "edited")]
    (with-redefs [wf/load-workflow (constantly edited)]
      (is (= (get-in original [:phases :reproduce :brief]) (:brief (wf/status id))))
      (is (= :pass (:outcome (wf/attest! id "repro fails"))))
      (is (= [:diagnose 1] (at id))))))

(defn- simulate
  "Drives decide and fold-event from the start phase with one gate outcome per attest.
  Returns the events in order, stopping when the run is done or blocked."
  [wf outcomes]
  (loop [st (wf/fold-event {} {:type :start :workflow (:workflow wf) :phase (:start wf)})
         [o & more] outcomes
         events []]
    (if (or (nil? o) (not= :active (:status st)))
      events
      (let [es (wf/decide wf st [o])]
        (recur (reduce wf/fold-event st es) more (into events (map #(assoc % :from (:phase st) :result o)) es))))))

(def ^:private gate-outcome (gen/elements [{:pass? true} {:pass? false} {:pending? true}]))

(deftest property-runs-only-enter-real-phases-and-finish-from-a-terminal-pass
  (let [result
        (tc/quick-check
         300
         (prop/for-all [w (gen/elements shipped)
                        outcomes (gen/vector gate-outcome 0 40)]
           (let [wf (wf/load-workflow w)
                 events (simulate wf outcomes)]
             (every? (fn [{:keys [type phase visit from result]}]
                       (case type
                         :enter (and (contains? (:phases wf) phase)
                                     (<= visit (get-in wf [:phases phase :max-visits] wf/default-max-visits)))
                         :done (and (:pass? result) (nil? (get-in wf [:phases from :next])))
                         :block (contains? (:phases wf) phase)))
                     events))))]
    (is (:pass? result) (pr-str (:shrunk result)))))

(deftest set-gives-a-run-the-repro-it-started-without
  (let [repo (buggy-repo)
        id (wf/start! :bug-fix {:cwd repo})]
    (is (= :fail (:outcome (wf/attest! id "no repro yet"))))
    (is (= [:reproduce 2] (at id)))
    (wf/set-commands! id {:repro repro})
    (is (= repro (:repro (wf/status id))))
    (is (= :pass (:outcome (wf/attest! id "repro fails"))))
    (is (= [:diagnose 1] (at id)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs --repro" (wf/set-commands! id {})))))

(deftest an-abandoned-run-stops-and-is-no-longer-the-default
  (let [repo (buggy-repo)
        older (wf/start! :bug-fix {:cwd repo :repro repro})
        _ (Thread/sleep 5)
        newer (wf/start! :bug-fix {:cwd repo :repro repro})]
    (is (= newer (wf/latest-id repo)))
    (wf/abandon! newer "started without the right repro")
    (is (= :abandoned (:status (wf/status newer))))
    (is (= "started without the right repro" (:abandon-reason (wf/status newer))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run is abandoned" (wf/attest! newer "late")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run is abandoned" (wf/set-commands! newer {:repro "true"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing to abandon" (wf/abandon! newer nil)))
    (is (= older (wf/latest-id repo)))
    (with-redefs [wf/session-run-id (constantly newer) fs/cwd (constantly repo)]
      (is (= older (wf/default-id)) "an abandoned session run falls back to the newest live run"))
    (with-redefs [wf/session-run-id (constantly "20000101-000000-000-bug-fix") fs/cwd (constantly repo)]
      (is (= older (wf/default-id)) "a session run from another state root falls back too"))
    (is (= "abandoned" (:status (wf/status-json (wf/status newer)))))))
