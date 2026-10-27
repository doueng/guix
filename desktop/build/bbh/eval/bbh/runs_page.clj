(ns bbh.runs-page
  "Render the newest bbh conversations, with their system prompts, tool calls
  and workflow runs, as one self-contained HTML page for a person to read."
  (:require [babashka.fs :as fs]
            [bbh.transcript :as transcript]
            [bbh.workflow :as workflow]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h]))

;; Loading

(defn conversations-root []
  (str (fs/path (or (System/getenv "XDG_DATA_HOME") (fs/expand-home "~/.local/share")) "bbh" "conversations")))

(defn- read-lines-of [f parse]
  (if (fs/exists? f)
    (->> (fs/read-all-lines (str f)) (remove str/blank?) (mapv parse))
    []))

(defn- slurp-if [f] (when (and f (fs/regular-file? f)) (slurp (str f))))

(def ^:private run-id-re #"(?m)^run (\d{8}-\d{6}-\d{3}-[a-z-]+)$")

(defn load-run
  "A workflow run directory as {:id :events :evidence :reviews}. Reviews are
  the reviewer conversations, keyed by directory name."
  [dir]
  (let [dir (fs/path dir)]
    {:id (str (fs/file-name dir))
     :events (read-lines-of (fs/path dir "journal.edn") edn/read-string)
     :evidence (into (sorted-map)
                     (for [f (some-> (fs/path dir "evidence") (#(when (fs/exists? %) (fs/list-dir %))))
                           :when (fs/regular-file? f)]
                       [(str (fs/file-name f)) (slurp (str f))]))
     :reviews (into {} (for [d (some-> (fs/path dir "reviews") (#(when (fs/exists? %) (fs/list-dir %))))
                             :when (fs/directory? d)]
                         [(str (fs/file-name d)) d]))}))

(defn load-conversation
  "A conversation directory with everything the page shows. Workflow runs are
  the ones a tool result names in the `run ID` line wf prints, looked up under
  RUNS-ROOT; nil RUNS-ROOT skips them, as for reviewer conversations."
  [dir runs-root]
  (let [dir (fs/path dir)
        messages (transcript/load-messages dir)
        run-ids (->> messages (filter #(= "tool" (:role %)))
                     (mapcat #(map second (re-seq run-id-re (str (:content %))))) distinct)]
    {:id (str (fs/file-name dir))
     :dir (str dir)
     :messages messages
     :prompts (transcript/load-prompts dir)
     :compactions (read-lines-of (fs/path dir "compactions.jsonl") #(json/parse-string % true))
     :claude? (fs/exists? (fs/path dir "claude-session"))
     :claude-log (slurp-if (fs/path dir "claude.log"))
     :updated (str (fs/last-modified-time (fs/path dir "messages.jsonl")))
     :jobs (for [cmd (sort (fs/glob dir "jobs/*.cmd"))
                 :let [base (str/replace (str cmd) #"\.cmd$" "")]]
             {:cmd (slurp (str cmd)) :exit (some-> (slurp-if (str base ".exit")) str/trim)
              :log (slurp-if (str base ".log"))})
     :runs (when runs-root
             (->> run-ids (map #(fs/path runs-root %)) (filter fs/exists?) (mapv load-run)))}))

(defn newest-conversations
  "The N newest conversation directories under ROOT that hold messages."
  [root n]
  (->> (fs/list-dir root)
       (filter #(let [f (fs/path % "messages.jsonl")] (and (fs/exists? f) (pos? (fs/size f)))))
       (sort-by #(str (fs/file-name %)) #(compare %2 %1))
       (take n)))

;; Text

(def ^:private wrapper-re #"(?s)<(skill|system-reminder)\b([^>]*)>(.*?)</\1>")

(defn prompt-text
  "What the user typed, without the skill and reminder blocks the env adds."
  [content]
  (str/trim (str/replace (str content) wrapper-re "")))

(defn- inline [line]
  (for [[_ code bold text] (re-seq #"`([^`]+)`|\*\*([^*]+)\*\*|([^`*]+|[`*])" line)]
    (cond code [:code code] bold [:strong bold] :else text)))

(defn markdown
  "A small Markdown subset as hiccup: fences, headings, lists, inline code and bold."
  [text]
  (for [[i part] (map-indexed vector (str/split (str text) #"(?m)^```.*$"))]
    (if (odd? i)
      [:pre.code (str/replace part #"^\n|\n$" "")]
      (for [para (str/split part #"\n\s*\n") :when (not (str/blank? para))
            :let [lines (str/split-lines (str/trim para))]]
        (cond
          (every? #(re-find #"^\s*([-*]|\d+\.)\s" %) lines)
          [:ul (for [l lines] [:li (inline (str/replace l #"^\s*([-*]|\d+\.)\s+" ""))])]
          (re-find #"^#{1,6}\s" (first lines))
          (list [:h4.md (inline (str/replace (first lines) #"^#+\s+" ""))]
                (when (next lines) [:p (interpose [:br] (map inline (rest lines)))]))
          (re-find #"^\|" (first lines))
          [:pre.table para]
          :else [:p (interpose [:br] (map inline lines))])))))

(defn- line-count [s] (count (str/split-lines (str s))))

(defn- fold
  "A <details> block that starts open when BODY-TEXT is short."
  [cls summary body-text body]
  [:details {:class cls :open (< (line-count body-text) 25)} [:summary summary] body])

(defn- user-content [content]
  (let [wrappers (re-seq wrapper-re (str content))]
    (list (for [[_ tag attrs body] wrappers]
            [:details.wrapper [:summary "<" (str/trim (str tag " " (str/trim attrs))) "> " [:span.dim (line-count body) " lines"]]
             [:pre.plain (str/trim body)]])
          (markdown (prompt-text content)))))

;; Conversations

(def ^:private spill-re #"^\[bbh\] Full output \((\d+) chars\) saved to (\S+?)\. ")

(defn- result-flags [text]
  (cond-> ()
    (re-find #"^(Timed out|The REPL process exited)" text) (conj [:span.badge.bad "REPL restarted"])
    (re-find #"^The user interrupted" text) (conj [:span.badge.bad "interrupted"])
    (re-find #"(?m)^err:$" text) (conj [:span.badge.warn "stderr"])
    (re-find #"(?m)^\[auto-repair\]" text) (conj [:span.badge.warn "auto-repair"])))

(defn- tool-call [n {:keys [id function]} result]
  (let [code (or (:code (try (json/parse-string (:arguments function) true) (catch Exception _ nil)))
                 (:arguments function))
        text (str result)
        [_ chars path] (re-find spill-re text)
        full (slurp-if path)
        wf? (re-find #"\bwf (start|attest|review|status|set|abandon)\b" (str code))]
    [:div.call {:id id}
     [:div.call-head [:span.label (str "tool call " n)]
      (when wf? [:span.badge.wf "workflow"])
      (result-flags text)]
     [:pre.code code]
     (fold "result" (list "result " [:span.dim (line-count text) " lines"]) text [:pre.plain text])
     (when full
       [:details.result [:summary "full output " [:span.dim chars " chars, " path]] [:pre.plain full]])]))

(defn- prompt-block [{:keys [time model effort system tool-description]} first?]
  [:div.prompt
   [:div.call-head [:span.label (if first? "system prompt" "system prompt changed")]
    [:span.dim " " model " · effort " (or effort "?") " · " time]]
   [:details [:summary "system prompt " [:span.dim (line-count system) " lines"]] [:pre.plain system]]
   (when tool-description
     [:details [:summary "tool description"] [:pre.plain tool-description]])])

(defn- timeline [{:keys [messages prompts compactions]}]
  (let [results (into {} (for [m messages :when (= "tool" (:role m))] [(:tool_call_id m) (:content m)]))
        prompts-at (group-by :at prompts)
        compactions-at (group-by :first-kept compactions)
        calls (atom 0)
        turn (atom 0)]
    (for [[i m] (map-indexed vector messages)]
      (list
       (for [p (get prompts-at i)] (prompt-block p (= p (first prompts))))
       (for [c (get compactions-at i)]
         [:div.compaction [:details [:summary "compaction: messages before this point were summarized"]
                           (markdown (:summary c))]])
       (case (:role m)
         "user" [:div.user {:id (str "turn-" (swap! turn inc))}
                 [:div.call-head [:span.label (str "user · turn " @turn)]]
                 (user-content (:content m))]
         "assistant" (list (when-not (str/blank? (:content m))
                             [:div.assistant [:div.call-head [:span.label "assistant"]] (markdown (:content m))])
                           (for [c (:tool_calls m)] (tool-call (swap! calls inc) c (get results (:id c)))))
         "tool" nil
         [:pre.plain (pr-str m)])))))

(defn summary
  "Counts and the opening prompt of a loaded conversation."
  [{:keys [messages prompts claude? runs]}]
  {:title (or (some #(when (= "user" (:role %)) (prompt-text (:content %))) messages) "")
   :model (or (:model (peek prompts)) (when claude? "claude (not recorded)") "not recorded")
   :turns (count (filter #(= "user" (:role %)) messages))
   :calls (reduce + (map (comp count :tool_calls) messages))
   :runs (count runs)})

;; Workflow runs

(defn- run-status [events]
  (or (some #(case (:type %) :done "done" :abandon "abandoned" :block "blocked" nil) (rseq (vec events)))
      "active"))

(defn- phase-order [{:keys [start phases]}]
  (loop [p start seen []]
    (if (or (nil? p) (some #{p} seen)) (into seen (remove (set seen) (keys phases))) (recur (get-in phases [p :next]) (conj seen p)))))

(defn- phase-title [{:keys [brief principles]}]
  (cond-> (str brief)
    (seq principles) (str "\nprinciples: " (str/join ", " principles))))

(defn- phase-strip [definition events]
  (let [attests (filter #(= :attest (:type %)) events)
        passed (set (map :phase (filter #(= :pass (:outcome %)) attests)))
        fails (frequencies (map :phase (filter #(= :fail (:outcome %)) attests)))
        current (when (= "active" (run-status events))
                  (:phase (last (filter #(#{:enter :start} (:type %)) events))))]
    [:div.phases
     (interpose [:span.arrow "→"]
                (for [p (phase-order definition)]
                  [:span {:class (str "phase " (cond (passed p) "passed" (= p current) "current" :else "pending"))
                          :title (phase-title (get-in definition [:phases p]))}
                   (name p) (when-let [n (fails p)] [:span.fails (str " ✗" n)])]))]))

(defn- clock [at] (some-> at (subs 11 19)))

(defn- gate [{:keys [kind cmd path criteria pass? pending? exit evidence timed-out? reviewer]}]
  [:li {:class (cond pending? "pending" pass? "pass" :else "fail")}
   [:span.badge {:class (cond pending? "warn" pass? "ok" :else "bad")}
    (cond pending? "waiting" pass? "pass" :else "fail")]
   " " (name kind) " "
   (case kind
     :command (list [:code cmd] [:span.dim " exit " exit (when timed-out? ", timed out")]
                    (when-let [t (slurp-if evidence)]
                      [:details [:summary "command output"] [:pre.plain t]]))
     :artifact [:code (str (fs/file-name path))]
     :verdict (list [:span criteria] (when reviewer [:span.dim " · " reviewer]))
     [:code (pr-str (or cmd path criteria))])])

(declare conversation-section)

(defn- run-event [definition reviews {:keys [type phase visit at] :as e}]
  [:li {:class (str "ev " (name type))}
   [:span.time (clock at)] " " [:span.evtype (name type)]
   (when phase (list " " [:span.phase-name (name phase)] (when visit [:span.dim " visit " visit])))
   (case type
     :start [:div [:div "task: " (:task e)]
             (when-let [ps (seq (get-in definition [:phases phase :principles]))]
               [:div.dim "principles: " (str/join ", " ps)])
             (for [k [:check :repro :bench] :when (get e k)] [:div (name k) ": " [:code (get e k)]])]
     :enter (let [{:keys [brief principles]} (get-in definition [:phases phase])]
              [:details [:summary.dim "brief" (when (seq principles) (str " · principles: " (str/join ", " principles)))]
               (markdown brief)])
     :attest [:div [:div "claim: " [:em (:claim e)] " "
                    [:span.badge {:class (if (= :pass (:outcome e)) "ok" "bad")} (some-> (:outcome e) name)]]
              (when (seq (:gates e)) [:ul.gates (map gate (:gates e))])]
     :verdict [:div [:span.badge {:class (if (= :pass (:verdict e)) "ok" "bad")} (some-> (:verdict e) name)]
               [:span.dim " " (:reviewer e)]
               [:details [:summary "reviewer's note"] (markdown (:note e))]
               (when-let [d (some-> (:reviewer e) (str/replace #"^bbh-review/" "") reviews)]
                 [:details.review [:summary "reviewer conversation"]
                  (conversation-section (load-conversation d nil) true)])]
     (:abandon :block) [:div (:reason e)]
     nil)])

(defn- run-section [{:keys [id events evidence reviews]}]
  (let [start (first (filter #(= :start (:type %)) events))
        definition (:definition start)
        status (run-status events)]
    [:div.run
     [:div.run-head [:span.label "workflow " (some-> (:workflow start) name)] " "
      [:span {:class (str "badge status-" status)} status] [:span.dim " " id]]
     [:div.task (:task start)]
     (phase-strip definition events)
     [:details {:open true} [:summary "journal " [:span.dim (count events) " events"]]
      [:ol.events (map (partial run-event definition reviews) events)]]
     (when (seq evidence)
       [:details [:summary "evidence files " [:span.dim (str/join ", " (keys evidence))]]
        (for [[n t] evidence] [:details.evidence [:summary n] [:pre.plain t]])])]))

;; Page

(defn conversation-section [{:keys [id dir prompts runs jobs claude-log updated] :as c} nested?]
  (let [{:keys [title model turns calls]} (summary c)]
    [:section.conversation {:id (when-not nested? id)}
     [(if nested? :h3 :h2) id]
     [:div.meta model " · " turns " turns · " calls " tool calls · last write " (clock updated) " · " [:code dir]]
     (when-not nested? [:div.title title])
     (when (empty? prompts)
       [:div.note "System prompt not recorded. This conversation predates prompts.jsonl."])
     (when (seq runs)
       [:div.runs [:h3 "Workflow runs"] (map run-section runs)])
     [:h3 "Transcript"]
     (timeline c)
     (when (seq jobs)
       [:details [:summary "background jobs " [:span.dim (count jobs)]]
        (for [{:keys [cmd exit log]} jobs]
          [:details [:summary [:code (str/trim cmd)] [:span.dim " exit " (or exit "running")]] [:pre.plain log]])])
     (when-not (str/blank? claude-log)
       [:details [:summary "claude.log"] [:pre.plain claude-log]])]))

(def ^:private css "
body{font:15px/1.5 system-ui,sans-serif;margin:0;display:flex;background:#f6f6f3;color:#222}
nav{position:sticky;top:0;height:100vh;overflow:auto;width:260px;flex:none;background:#2b2d31;color:#ddd;padding:12px;box-sizing:border-box;font-size:13px}
nav a{color:#ddd;text-decoration:none;display:block;padding:6px 4px;border-bottom:1px solid #3a3c42}
nav a:hover{background:#3a3c42} nav .t{color:#aaa;display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
main{flex:1;max-width:1100px;padding:16px 28px;min-width:0}
section.conversation{background:#fff;border:1px solid #ddd;border-radius:8px;padding:12px 20px;margin-bottom:32px}
.meta,.dim,.time{color:#888;font-size:13px} .title{font-weight:600;margin:6px 0 12px;white-space:pre-wrap}
pre{white-space:pre-wrap;word-break:break-word;margin:4px 0;padding:8px;border-radius:4px;font-size:12.5px;max-height:600px;overflow:auto}
pre.code{background:#272822;color:#f8f8f2} pre.plain{background:#f3f3f0;border:1px solid #e3e3df} pre.table{background:#fafafa}
code{background:#eee;padding:0 3px;border-radius:3px;font-size:90%}
.user{border-left:4px solid #3b82f6;background:#eef4ff;padding:6px 12px;margin:18px 0 8px;border-radius:4px}
.assistant{border-left:4px solid #10b981;padding:4px 12px;margin:8px 0}
.call{border-left:4px solid #9ca3af;padding:2px 12px;margin:6px 0 6px 16px}
.prompt{border-left:4px solid #a855f7;background:#f8f0ff;padding:6px 12px;margin:8px 0;border-radius:4px}
.compaction{border:1px dashed #f59e0b;padding:4px 12px;margin:8px 0}
.label{font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:#555;margin-right:6px}
.badge{display:inline-block;font-size:11px;padding:0 6px;border-radius:9px;margin-left:4px;background:#ddd}
.ok,.status-done{background:#d1fae5;color:#065f46}.bad,.status-abandoned,.status-blocked{background:#fee2e2;color:#991b1b}
.warn,.status-active{background:#fef3c7;color:#92400e}.wf{background:#ede9fe;color:#5b21b6}
.run{border:1px solid #c4b5fd;background:#faf8ff;border-radius:6px;padding:8px 12px;margin:8px 0}
.task{margin:4px 0 8px} .phases{margin:6px 0 10px}
.phase{display:inline-block;padding:2px 10px;border-radius:12px;font-size:13px;border:1px solid #ccc}
.phase.passed{background:#d1fae5;border-color:#10b981}.phase.current{background:#fef3c7;border-color:#f59e0b;font-weight:600}.phase.pending{color:#999}
.fails{color:#b91c1c} .arrow{color:#aaa;margin:0 4px}
ol.events{padding-left:18px} li.ev{margin:6px 0} .evtype{font-weight:600} .phase-name{background:#eee;padding:0 6px;border-radius:4px}
ul.gates{margin:2px 0;padding-left:18px;list-style:none}
details>summary{cursor:pointer;color:#444} details{margin:2px 0} .note{color:#92400e;font-size:13px}
table.overview{border-collapse:collapse;width:100%;font-size:13px;background:#fff} table.overview td,table.overview th{border:1px solid #ddd;padding:4px 8px;text-align:left;vertical-align:top}
h4.md{margin:8px 0 2px} button{margin:4px 0 12px}
")

(def ^:private js "
function setAll(open){document.querySelectorAll('details').forEach(d=>d.open=open)}")

(defn render-page
  "The HTML page for loaded conversations, newest first."
  [conversations generated-at]
  (str "<!DOCTYPE html>\n"
       (h/html
        [:html
         [:head [:meta {:charset "utf-8"}] [:title "bbh runs"] [:style (h/raw css)] [:script (h/raw js)]]
         [:body
          [:nav [:b "bbh runs"] [:div.dim "generated " generated-at]
           (for [c conversations :let [{:keys [title]} (summary c)]]
             [:a {:href (str "#" (:id c))} (:id c) [:span.t title]])]
          [:main
           [:h1 "Last " (count conversations) " bbh runs"]
           [:p.dim "Purple blocks are system prompts, blue the user, green the assistant, grey tool calls. Workflow runs sit above each transcript; hover a phase for its brief."]
           [:button {:onclick "setAll(true)"} "expand all"] " " [:button {:onclick "setAll(false)"} "collapse all"]
           [:table.overview
            [:tr [:th "conversation"] [:th "first prompt"] [:th "model"] [:th "turns"] [:th "calls"] [:th "workflows"]]
            (for [c conversations :let [{:keys [title model turns calls]} (summary c)]]
              [:tr [:td [:a {:href (str "#" (:id c))} (:id c)]]
               [:td (let [t (first (str/split-lines title))] (if (> (count t) 120) (str (subs t 0 120) "…") t))]
               [:td model] [:td turns] [:td calls]
               [:td (for [r (:runs c) :let [s (first (:events r))]]
                      [:div (some-> (:workflow s) name) " " [:span {:class (str "badge status-" (run-status (:events r)))}
                                                           (run-status (:events r))]])]])]
           (for [c conversations] (conversation-section c false))]]])))

(defn -main
  "Args: [N] [OUT]. Writes the newest N conversations (default 10) to OUT
  (default ~/.cache/bbh/runs.html) and prints the path."
  [& [n out]]
  (let [n (if n (parse-long n) 10)
        out (or out (str (fs/path (or (System/getenv "XDG_CACHE_HOME") (fs/expand-home "~/.cache")) "bbh" "runs.html")))
        convs (mapv #(load-conversation % (workflow/state-root))
                    (newest-conversations (conversations-root) n))]
    (fs/create-dirs (fs/parent out))
    (spit out (render-page convs (str (java.time.Instant/now))))
    (println (str "file://" (fs/absolutize out)))))
