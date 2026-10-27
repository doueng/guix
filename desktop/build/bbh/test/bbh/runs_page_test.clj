(ns bbh.runs-page-test
  (:require [babashka.fs :as fs]
            [bbh.runs-page :as page]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- jsonl! [f rows]
  (fs/create-dirs (fs/parent f))
  (spit (str f) (str/join (map #(str (json/generate-string %) "\n") rows))))

(defn- edn-lines! [f rows]
  (fs/create-dirs (fs/parent f))
  (spit (str f) (str/join (map #(str (pr-str %) "\n") rows))))

(defn- fixture
  "A conversation that started a change run whose review passed."
  []
  (let [root (fs/create-temp-dir {:prefix "bbh-runs-page"})
        conv (fs/path root "conversations" "20261027-100000-000")
        run (fs/path root "workflows" "20261027-100001-000-change")
        spilled (fs/path conv "outputs" "call-2.txt")]
    (jsonl! (fs/path conv "messages.jsonl")
            [{:role "user" :content "<skill name=\"poteto-mode\">Run a workflow.</skill>\n\nFix <b>the</b> page"}
             {:role "assistant" :content "" :tool_calls [{:id "call-1" :type "function"
                                                         :function {:name "clojure" :arguments (json/generate-string {:code "(sh \"wf start change\")"})}}]}
             {:role "tool" :tool_call_id "call-1" :content "out:\nrun 20261027-100001-000-change\nworkflow change"}
             {:role "assistant" :content "" :tool_calls [{:id "call-2" :type "function"
                                                         :function {:name "clojure" :arguments (json/generate-string {:code "(slurp big)"})}}]}
             {:role "tool" :tool_call_id "call-2" :content (str "[bbh] Full output (9 chars) saved to " spilled ". Read parts with read-lines.\nFULL...")}
             {:role "assistant" :content "Done. See `page.clj`."}])
    (fs/create-dirs (fs/parent spilled))
    (spit (str spilled) "FULL TAIL")
    (jsonl! (fs/path conv "prompts.jsonl")
            [{:at 0 :time "2026-10-27T10:00:00Z" :model "claude/opus" :effort "medium" :system "You are bbh." :tool-description "Evaluate."}])
    (edn-lines! (fs/path run "journal.edn")
                [{:type :start :workflow :change :task "Fix the page" :check "make lint" :phase :shape
                  :definition {:start :shape :phases {:shape {:brief "Name the shape." :next :review :principles ["model-the-domain"]}
                                                      :review {:brief "Get a verdict." :next nil}}}
                  :at "2026-10-27T10:00:01Z"}
                 {:type :attest :phase :shape :visit 1 :claim "shape written" :outcome :pass
                  :gates [{:index 0 :kind :artifact :path (str run "/evidence/shape.md") :pass? true}] :at "2026-10-27T10:00:02Z"}
                 {:type :enter :phase :review :visit 1 :at "2026-10-27T10:00:03Z"}
                 {:type :verdict :phase :review :visit 1 :reviewer "bbh-review/review-1-5" :verdict :pass
                  :note "VERDICT: pass" :at "2026-10-27T10:00:04Z"}
                 {:type :done :at "2026-10-27T10:00:05Z"}])
    (fs/create-dirs (fs/path run "evidence"))
    (spit (str (fs/path run "evidence" "shape.md")) "# Shape\nA map.")
    (jsonl! (fs/path run "reviews" "review-1-5" "messages.jsonl")
            [{:role "user" :content "You are an independent reviewer."}
             {:role "assistant" :content "VERDICT: pass, reviewer checked it"}])
    {:conv conv :runs (fs/path root "workflows")}))

(deftest the-page-shows-prompt-calls-workflow-and-review
  (let [{:keys [conv runs]} (fixture)
        c (page/load-conversation conv runs)
        html (page/render-page [c] "now")]
    (is (= {:title "Fix <b>the</b> page" :model "claude/opus" :turns 1 :calls 2 :runs 1} (page/summary c)))
    (is (str/includes? html "You are bbh."))
    (is (str/includes? html "Fix &lt;b&gt;the&lt;/b&gt; page"))
    (is (not (str/includes? html "<b>the</b>")))
    (is (str/includes? html "(sh &quot;wf start change&quot;)"))
    (is (str/includes? html "FULL TAIL"))
    (is (str/includes? html "<code>page.clj</code>"))
    (is (str/includes? html "<span class=\"phase passed\" title=\"Name the shape.\nprinciples: model-the-domain\">shape</span>"))
    (is (str/includes? html "<span class=\"phase pending\" title=\"Get a verdict.\">review</span>"))
    (is (str/includes? html "<summary>&lt;skill name=&quot;poteto-mode&quot;&gt; "))
    (is (str/includes? html "principles: model-the-domain"))
    (is (str/includes? html "<span class=\"badge status-done\">done</span>"))
    (is (str/includes? html "VERDICT: pass, reviewer checked it"))
    (is (str/includes? html "# Shape\nA map."))))

(deftest a-conversation-without-recorded-prompts-says-so
  (let [{:keys [conv runs]} (fixture)
        _ (fs/delete (fs/path conv "prompts.jsonl"))
        c (page/load-conversation conv runs)]
    (is (= "not recorded" (:model (page/summary c))))
    (is (str/includes? (page/render-page [c] "now") "System prompt not recorded."))))
