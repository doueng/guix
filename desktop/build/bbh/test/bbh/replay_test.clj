(ns bbh.replay-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bbh.hermetic :as hermetic]
            [bbh.replay :as replay]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once hermetic/fixture)

(defn- conversation! [root name cwd]
  (let [dir (str (fs/create-dirs (fs/path root name)))]
    (spit (str dir "/messages.jsonl") (str (json/generate-string {:role "user" :content (str "brief for " name)}) "\n"))
    (spit (str dir "/agent.json") (json/generate-string {:project cwd}))
    dir))

(defn- finish [_ _ data _] [:finished {:result {:end :end-turn} :prompt-seen (:prompt data)}])

(deftest a-batch-runs-every-item-and-an-interrupted-one-resumes-to-the-same-end
  (let [root (str (fs/create-temp-dir {:prefix "bbh-replay-test"}))
        cwd (str (fs/create-dirs (fs/path root "ws")))
        dirs [(conversation! root "W1" cwd) (conversation! root "W2" cwd)]
        arms {"a" "/init-a.clj" "b" "/init-b.clj"}
        final #(into {} (for [[k v] (replay/states %)] [k (:state v)]))
        whole (str root "/whole")
        cut (str root "/cut")
        calls (atom 0)]
    (replay/run-batch! whole dirs arms {:run-item finish :grade (fn [_ _] {:graded true})})
    (is (= {"W1/a" :graded "W1/b" :graded "W2/a" :graded "W2/b" :graded} (final whole)))
    (is (= "brief for W2" (get-in (replay/states whole) ["W2/b" :data :prompt-seen])))
    (is (thrown? Exception
                 (replay/run-batch! cut dirs arms {:run-item (fn [& args] (if (= 2 (swap! calls inc)) (throw (ex-info "killed" {})) (apply finish args))) :grade (fn [_ _] {})}))
        "the process dies during the second item")
    (is (= :running (get-in (replay/states cut) ["W1/b" :state])))
    (replay/run-batch! cut dirs arms {:run-item finish :grade (fn [_ _] {:graded true})})
    (is (= {:state :failed :reason :lost} (-> (replay/states cut) (get "W1/b") (#(hash-map :state (:state %) :reason (:reason (:data %)))))))
    (replay/run-batch! cut dirs arms {:run-item finish :grade (fn [_ _] {:graded true}) :retry true})
    (is (= (final whole) (final cut)) "after a retry the interrupted batch ends where the whole one did")
    (is (= [:selected :prepared :running :failed :prepared :running :finished :graded]
           (mapv :to (filter #(= "W1/b" (:item %)) (replay/entries cut)))))
    (is (thrown-with-msg? Exception #"cannot go from nil to :finished" (replay/transition! (str root "/x") "W9/a" :finished {})))))

(deftest a-second-batch-on-the-same-out-is-refused-while-the-first-runs
  (let [root (str (fs/create-temp-dir {:prefix "bbh-replay-lock"}))
        cwd (str (fs/create-dirs (fs/path root "ws")))
        dirs [(conversation! root "W1" cwd)]
        out (str root "/out")
        inner (atom nil)]
    (replay/run-batch! out dirs {"a" "/i.clj"}
                       {:grade (fn [_ _] {})
                        :run-item (fn [& args]
                                    (reset! inner (try (replay/run-batch! out dirs {"a" "/i.clj"} {:run-item finish :grade (fn [_ _] {})})
                                                       (catch Exception e (ex-message e))))
                                    (apply finish args))})
    (is (re-find #"another batch holds" (str @inner)))
    (is (= [:selected :prepared :running :finished :graded] (mapv :to (replay/entries out))))))

(deftest the-base-revision-and-recorded-run-come-from-the-op-log-and-the-brief
  (let [root (str (fs/create-temp-dir {:prefix "bbh-replay-base"}))
        repo (str root "/repo")
        sh #(apply p/shell {:dir repo :out :string :err :string :extra-env {"JJ_USER" "t" "JJ_EMAIL" "t@t"}} %&)]
    (fs/create-dirs repo)
    (sh "jj" "git" "init")
    (sh "jj" "commit" "-m" "base")
    (let [base (str/trim (:out (sh "jj" "log" "--no-graph" "-r" "@-" "-T" "commit_id")))]
      (sh "jj" "workspace" "add" "--name" "T1-1" "-r" "@-" (str root "/T1-1"))
      (sh "jj" "commit" "-m" "later")
      (sh "jj" "workspace" "forget" "T1-1")
      (fs/delete-tree (str root "/T1-1"))
      (is (= base (replay/base-revision repo "T1-1"))))
    (let [state (str root "/state")
          run "20261106-153218-802-change"]
      (fs/create-dirs (fs/path state "bbh" "workflows" run))
      (spit (str state "/bbh/workflows/" run "/journal.edn")
            (str (pr-str {:workflow :change :task "Remove the panel" :check "bb test" :repro nil :bench nil}) "\n{:phase :shape}\n"))
      (is (= {:run run :wf {:workflow "change" :task "Remove the panel" :check "bb test" :repro nil :bench nil}}
             (replay/recorded-run (str "Your wf run is " run " (change workflow; WF_RUN is set)") state))))))
