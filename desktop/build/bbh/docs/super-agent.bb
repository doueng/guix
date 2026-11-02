#!/usr/bin/env bb
;; Render docs/super-agent.md as one HTML page for review, with mockups of the
;; three surfaces and the journeys as step cards tagged by surface.
;; Run from desktop/build/bbh: bb docs/super-agent.bb
(require '[babashka.fs :as fs]
         '[bbh.web.markdown :as md]
         '[clojure.string :as str]
         '[hiccup2.core :as h])

(def here (str (fs/parent *file*)))
(def source-md (str here "/super-agent.md"))
(def target (str here "/super-agent.html"))

(defn slug [s] (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn sections
  "The Markdown as [{:level :title :body}] split at headings."
  [text]
  (loop [lines (str/split-lines text) cur nil out []]
    (if-let [line (first lines)]
      (if-let [[_ hashes title] (re-matches #"(#{1,6})\s+(.*)" line)]
        (recur (rest lines) {:level (count hashes) :title title :body []} (cond-> out cur (conj cur)))
        (recur (rest lines) (update cur :body conj line) out))
      (cond-> out cur (conj cur)))))

(defn heading [{:keys [level title]}]
  [(keyword (str "h" level)) {:id (slug title)} title])

(def surfaces ["Voice" "Herdr" "iPhone" "System"])

(defn step
  "A journey step line's [surface text], from its bold prefix such as **Herdr.**"
  [line]
  (when-let [[_ tag text] (re-matches #"\d+\.\s+\*\*(\w+)\.\*\*\s+(.*)" line)]
    [tag text]))

(defn journey [{:keys [title body]}]
  (let [steps (keep step body)
        [_ id name] (re-matches #"(J\d+)\.\s+(.*)" title)]
    [:section.journey {:id (slug title)}
     [:h3 [:span.jid id] " " name]
     (into [:ol.steps]
           (for [[tag text] steps]
             [:li [:span {:class (str "tag " (slug tag))} tag]
              (into [:span.text] (md/inline text))]))]))

(def herdr-mockup
  [:figure.mock
   [:div.term
    [:div.tabs [:span.tab.active "1 Inbox"] [:span.tab "2 guix"] [:span.tab "3 research_lab"] [:span.tab "4 reader"]
     [:span.right "Herdr"]]
    [:div.panes
     [:div.pane.inbox
      [:div.ptitle "bbh inbox · 2 need you"]
      [:div.sec "Stopped on you"]
      [:div.row.sel "● T14  Herdr key for the thread picker               3m"]
      [:div.sec "Waiting on you"]
      [:div.row "◆ T9   review: cost per session, 4 files, gates ok  1h"]
      [:div.sec "Sent"]
      [:div.row "→ T12  Alt+W everywhere · delivered, not yet read   2m"]
      [:div.sec "Working"]
      [:div.row "… T15  web cost · change: implement                12m"]
      [:div.row "… T16  Asahi build fix · queued (guix-build)        40m"]
      [:div.sec "Quiet"]
      [:div.row.dim "  T11  phone layout                                 2d"]
      [:div.preview
       [:div.q "T14 asks: Which key should open the inbox pane?"]
       [:div.opt "[1] Alt+Space t  (recommended)"]
       [:div.opt "[2] Alt+Space a"]
       [:div.dim "stopped · W9 · wf change: implement"]]
      [:div.keys "j/k  1-4 pick  r note  s send  ⏎ pane  d diff  t takeover  m read  a archive"]]
     [:div.pane.router
      [:div.ptitle "bbh · Router"]
      [:div.you "> make the bbh web page show cost per session"]
      [:div.bot "Started T15 (change). I'll ask if it needs you."]
      [:div.you "> T11 also handle the phone layout"]
      [:div.bot "Steered T11."]
      [:div.you "> what's open?"]
      [:div.bot "T14 stopped on a key choice. T9 waits for your review. T15 and T16 are working, T16 waits for guix-build."]
      [:div.cursor "> "]]]]
   [:figcaption "Herdr. Workspace 1 is the Inbox: the inbox pane on the left with the selected ask in its preview, the Router conversation on the right. Workers live in panes in their project workspaces."]])

(def phone-mockup
  [:figure.mock.phones
   [:div.phone
    [:div.bar "9:41"]
    [:div.masthead "bbh"]
    [:div.ptabs [:span.ptab.current "Inbox " [:span.badge "2"]] [:span.ptab "guix"] [:span.ptab "research_lab"]]
    [:div.label.claret "Stopped on you"]
    [:div.prow [:div.ph "T14 · Herdr key for the thread picker"] [:div.pd "Which key should open the inbox pane?"] [:div.pm "W9 · wf change · 3m"]]
    [:div.label "Waiting on you"]
    [:div.prow [:div.ph "T9 · Cost per session"] [:div.pd "Review 7c1e2a0, 4 files, gates ok"] [:div.pm "1h"]]
    [:div.label "Working"]
    [:div.prow.dim [:div.ph "T15 · web cost"] [:div.pm "change: implement · 12m"]]
    [:div.prow.dim [:div.ph "T16 · Asahi build fix"] [:div.pm "queued, waits for guix-build · 40m"]]
    [:div.compose [:span "Tell the Router"] [:span.mic "mic"]]]
   [:div.phone
    [:div.bar "9:41"]
    [:div.masthead "bbh"]
    [:div.crumb "‹ Inbox"]
    [:div.thead [:div.kicker.claret "Stopped on you"] [:div.ttitle "T14 · Herdr key for the thread picker"] [:div.pm "W9 · wf change: implement · 14m"]]
    [:div.ev [:div.pm "W9"] "Alt+Space a already opens the agent picker. The inbox pane needs its own key."]
    [:div.ask
     [:div.kicker "Question"]
     [:div.q "Which key should open the inbox pane?"]
     [:div.choice.rec "Alt+Space t" [:span.rectag "Recommended"]]
     [:div.choice "Alt+Space a"]
     [:div.pm "The Worker waits for this."]]
    [:div.actions [:span "Mark read"] [:span "Archive"] [:span.send "Send"]]]
   [:figcaption "iPhone, in the page's existing idiom: paper, serif masthead, small uppercase labels, hairline rules, claret for what needs you and teal for actions. The inbox with sections and badge, and a thread with an ask. A tap saves the pick, Send delivers one message to the Worker. The composer reaches the Router, with iOS dictation from the keyboard."]])

(def voice-mockup
  [:figure.mock
   [:div.flow
    [:span.box "hold Cmd+Shift+M"] [:span.arrow "→"] [:span.box "Hex transcribes"] [:span.arrow "→"]
    [:span.box "bbh say"] [:span.arrow "→"] [:span.box.ctl "controller"] [:span.arrow "→"]
    [:span.stack [:span.box "spoken handle: that thread"] [:span.box "selected in inbox pane: that thread"] [:span.box "otherwise: Router"]]]
   [:figcaption "Voice. Nothing is pasted; the transcript becomes a voice event. Plain Cmd+M still pastes at the cursor."]])

(def mockups {"Herdr: the main desk" herdr-mockup
              "iPhone: triage and answers away from the desk" phone-mockup
              "Voice: talking to the agent" voice-mockup})

(defn render-section [{:keys [title body] :as s}]
  (cond
    (re-matches #"J\d+\..*" title) (journey s)
    :else (into [:div (heading s)] (concat (when-let [m (mockups title)] [m])
                                            (md/render (str/join "\n" body))))))

(defn toc [secs]
  (into [:nav] (for [{:keys [level title]} secs :when (= 2 level)]
                 [:a {:href (str "#" (slug title))} (str/replace title #"^\d+\.\s+" "")])))

(def css "
body{font:16px/1.55 system-ui,sans-serif;max-width:960px;margin:2rem auto;padding:0 1rem;color:#1d1f21}
h1{font-size:1.9rem;margin-bottom:.2rem}h2{margin-top:2.6rem;border-bottom:1px solid #ddd;padding-bottom:.25rem}
h3{margin-top:1.8rem}nav{display:flex;flex-wrap:wrap;gap:.3rem .9rem;margin:1rem 0 2rem;font-size:.92rem}
code,pre{font-family:ui-monospace,Menlo,monospace;font-size:.88em}code{background:#f2f2f1;padding:.05rem .3rem;border-radius:4px}
pre{background:#f5f5f4;padding:.8rem 1rem;overflow-x:auto;border-radius:6px;line-height:1.4}pre code{background:none;padding:0}
table{border-collapse:collapse;width:100%;margin:.8rem 0}th,td{border:1px solid #ddd;padding:.45rem .6rem;text-align:left;vertical-align:top}th{background:#f5f5f4}
.status{color:#8a6d00;background:#fff8e1;border-left:4px solid #e0a800;padding:.5rem .9rem;border-radius:4px}
figure.mock{margin:1.2rem 0 1.6rem;}figcaption{font-size:.9rem;color:#555;margin-top:.5rem}
.term{background:#1b1e24;color:#d7dae0;border-radius:8px;font:12.5px/1.45 ui-monospace,Menlo,monospace;overflow:hidden}
.tabs{background:#12141a;padding:.35rem .6rem;display:flex;gap:1rem;color:#8b919d}.tab.active{color:#fff;border-bottom:2px solid #7aa2f7}.tabs .right{margin-left:auto}
.panes{display:grid;grid-template-columns:3fr 2fr;min-height:360px}.pane{padding:.5rem .7rem;border-right:1px solid #2c313c}.pane:last-child{border-right:0}
.ptitle{color:#8b919d;margin-bottom:.4rem}.sec{color:#7aa2f7;margin-top:.45rem}.row{white-space:pre}.row.sel{background:#2c313c;color:#fff}.dim{color:#6b7280}
.preview{border-top:1px solid #2c313c;margin-top:.6rem;padding-top:.5rem}.q{color:#e0af68}.opt{padding-left:1rem}
.keys{color:#6b7280;margin-top:.6rem;border-top:1px solid #2c313c;padding-top:.3rem}
.you{color:#9ece6a;margin-top:.4rem}.bot{color:#d7dae0;padding-left:.6rem}.cursor{color:#9ece6a;margin-top:.5rem}
.phones{display:flex;gap:1.2rem;flex-wrap:wrap}
.phone{--paper:#fff1e5;--ink:#33302e;--muted:#66605c;--rule:#ccc1b7;--claret:#990f3d;--teal:#0d7680;width:280px;border:10px solid #111;border-radius:30px;background:var(--paper);color:var(--ink);font:13px/1.4 'Helvetica Neue',Arial,system-ui,sans-serif;overflow:hidden;min-height:470px;position:relative}
.phone .bar{text-align:center;color:var(--muted);padding:.25rem;font-size:11px}
.phone .masthead{margin:0 12px;padding:4px 0 6px;text-align:center;border-bottom:4px solid #000;font:400 24px/1 Georgia,'Times New Roman',serif;color:#000}
.ptabs{display:flex;gap:14px;margin:0 12px;border-bottom:1px solid var(--rule);font:600 11px/1 'Helvetica Neue',Arial,sans-serif;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
.ptab{padding:9px 0 7px;border-bottom:3px solid transparent}.ptab.current{color:#000;border-bottom-color:var(--claret)}
.phone .badge{background:var(--claret);color:#fff;padding:1px 5px;font-size:10px;margin-left:4px;border-radius:0}
.phone .label{margin:12px 12px 2px;font:600 11px/1 'Helvetica Neue',Arial,sans-serif;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
.phone .label.claret,.kicker.claret{color:var(--claret)}
.prow{margin:0 12px;padding:8px 0;border-bottom:1px solid var(--rule)}.prow.dim .ph{color:var(--muted)}
.ph{font:400 15px/1.3 Georgia,'Times New Roman',serif;color:#000}.pd{margin-top:2px}.pm{color:var(--muted);font-size:11px;margin-top:2px}
.compose{position:absolute;bottom:0;left:0;right:0;display:flex;justify-content:space-between;align-items:center;margin:0 12px;padding:10px 0;border-top:1px solid var(--rule);color:var(--muted)}.compose span:first-child{flex:1;background:#f2dfce;padding:6px 8px;margin-right:8px}.compose .mic{color:var(--teal);font:600 11px/1.4 'Helvetica Neue',Arial,sans-serif;letter-spacing:.08em;text-transform:uppercase}
.crumb{margin:8px 12px 0;color:var(--teal);font-size:12px}
.thead{margin:6px 12px 0;padding-bottom:8px;border-bottom:1px solid var(--rule)}
.kicker{font:600 11px/1 'Helvetica Neue',Arial,sans-serif;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
.ttitle{font:400 19px/1.2 Georgia,'Times New Roman',serif;color:#000;margin:4px 0 2px}
.ev{margin:10px 12px;padding-bottom:8px;border-bottom:1px solid var(--rule)}
.ask{margin:10px 12px;padding:10px 0 6px;border-top:4px solid #000}
.ask .q{font:400 16px/1.3 Georgia,'Times New Roman',serif;color:#000;margin:6px 0 10px}
.choice{display:flex;justify-content:space-between;align-items:center;border:1px solid var(--rule);padding:9px 10px;margin:6px 0;background:#f2dfce;color:#000}
.choice.rec{border-color:var(--teal);box-shadow:inset 3px 0 0 var(--teal)}.rectag{font:600 10px/1 'Helvetica Neue',Arial,sans-serif;letter-spacing:.08em;text-transform:uppercase;color:var(--teal)}
.actions{position:absolute;bottom:0;left:0;right:0;display:flex;align-items:center;gap:14px;margin:0 12px;padding:10px 0;border-top:1px solid var(--rule);color:var(--teal);font:600 11px/1.4 'Helvetica Neue',Arial,sans-serif;letter-spacing:.08em;text-transform:uppercase}
.actions .send{margin-left:auto;background:var(--teal);color:#fff;padding:4px 12px}
.flow{display:flex;align-items:center;gap:.5rem;flex-wrap:wrap;font-size:.9rem}.box{border:1px solid #bbb;border-radius:6px;padding:.3rem .6rem;background:#fafafa}.box.ctl{background:#e8f0fe;border-color:#7aa2f7}.arrow{color:#888}.stack{display:flex;flex-direction:column;gap:.3rem}
.journey{margin:1.4rem 0;padding:.2rem 1rem .6rem;border:1px solid #e3e3e3;border-radius:8px;background:#fcfcfb}.jid{color:#1a73e8}
.steps{padding-left:1.4rem}.steps li{margin:.35rem 0;display:flex;align-items:baseline;gap:.5rem}.steps .tag{flex:none;margin-right:0}.tag{display:inline-block;font-size:.72rem;font-weight:600;letter-spacing:.03em;padding:.05rem .45rem;border-radius:999px;margin-right:.5rem;vertical-align:middle;color:#fff;background:#777}
.tag.herdr{background:#2b7a78}.tag.iphone{background:#1a73e8}.tag.voice{background:#c2185b}.tag.system{background:#8d99ae}
.legend{display:flex;gap:.5rem;flex-wrap:wrap;margin:.4rem 0 1rem}
")

(defn page [text]
  (let [secs (sections text)
        [title & rest-secs] secs
        status (first (remove str/blank? (:body title)))
        intro (rest (drop-while #(not= % status) (:body title)))]
    (str "<!doctype html>"
         (h/html
          [:html {:lang "en"}
           [:head [:meta {:charset "utf-8"}] [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
            [:title (:title title)] [:style (h/raw css)]]
           (into [:body [:h1 (:title title)] [:p.status status]]
                 (concat (md/render (str/join "\n" intro))
                         [(toc rest-secs)]
                         (mapcat (fn [s]
                                   (if (= "8. User journeys" (:title s))
                                     [(heading s)
                                      (into [:div.legend] (for [n surfaces] [:span {:class (str "tag " (slug n))} n]))
                                      (into [:div] (md/render (str/join "\n" (:body s))))]
                                     [(render-section s)]))
                                 rest-secs)))]))))

(spit target (page (slurp source-md)))
(println "wrote" target)
