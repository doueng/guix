(ns keybindings-reference
  "Builds keybindings/reference.html from the binding sources and Doom's leader map."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h]))

;; Elisp leader maps

(defn- elisp-tokens
  "Strings, parens and atoms of elisp TEXT, without comments or character literals."
  [text]
  (for [[_ comment chr string paren atom] (re-seq #"(;[^\n]*)|(\?\\?.)|(\"(?:[^\"\\]|\\.)*\")|([()])|([^\s()\";]+)" text)
        :when (not (or comment chr))]
    (cond string {:string (edn/read-string string)} paren {:paren paren} :else {:atom atom})))

(defn leader-descs
  "Map of leader sequence, such as \"c d\", to the :desc labels that the
  (map! :leader ...) forms in elisp TEXT give it."
  [text]
  (loop [[t & ts] (elisp-tokens text) stack [] out {}]
    (cond
      (nil? t) out
      (= "(" (:paren t)) (recur ts (conj stack {:items []}) out)
      (= ")" (:paren t))
      (let [{:keys [items]} (peek stack)
            stack (pop stack)
            [k dot label] items
            parent (peek stack)]
        (if (and parent (= "." (:atom dot)) (:string k) (:string label)
                 (#{":prefix" ":prefix-map"} (:atom (last (:items parent)))))
          (let [stack (conj (pop stack) (assoc parent :prefix (:string k)))
                path (str/join " " (keep :prefix stack))]
            (recur ts stack (update out path #(vec (distinct (conj (or % []) (:string label)))))))
          (recur ts stack out)))
      :else
      (let [frame (update (peek stack) :items conj t)
            stack (conj (pop stack) frame)
            [d label k] (take-last 3 (:items frame))
            leader? (some #(= ["map!" ":leader"] (map :atom (take 2 (:items %)))) stack)]
        (if (and leader? (= ":desc" (:atom d)) (:string label) (:string k))
          (let [path (str/join " " (concat (keep :prefix stack) [(:string k)]))]
            (recur ts stack (update out path #(vec (distinct (conj (or % []) (:string label)))))))
          (recur ts stack out))))))

(defn- command-label [sym]
  (-> sym (str/replace #"^#?'" "") (str/replace #"^(\+evil/|evil-|doom/)" "") (str/replace "-" " ")))

(defn window-map
  "Map of key to label for SPC w, which is evil-window-map. EVIL is evil-maps.el
  and DOOM is Doom's evil module config, which adds keys such as d and m."
  [evil doom]
  (merge (into {} (for [[_ k sym] (re-seq #"\(define-key evil-window-map \"(\w|=)\" '([\w-]+)\)" evil)]
                    [k (command-label sym)]))
         (when-let [block (some-> (second (re-find #"(?s)\(:map evil-window-map(.*?)tear-off-window\)" doom))
                                  (str/replace #"(?s)\(:prefix \(\"m\" \. \"maximize\"\).*?\)\s*\n" ""))]
           (into {"m" "maximize"}
                 (for [[_ k sym] (re-seq #"(?m)^\s+\"(\w|=)\"\s+(#'[^\s)]+)" block)
                       :when (not (re-find #"^\s*\(:prefix" k))]
                   [k (command-label sym)])))))

;; Other sources

(defn kanata-layers
  "[layer [[key action comment] ...]] for each deflayermap in kanata TEXT."
  [text]
  (let [balance #(- (count (re-seq #"\(" %)) (count (re-seq #"\)" %)))]
    (loop [[line & lines] (str/split-lines text) layer nil comment nil out []]
      (cond
        (nil? line) out
        (re-find #"^\(deflayermap \(" line)
        (recur lines (second (re-find #"\(deflayermap \(([^)]+)\)" line)) nil (conj out [(second (re-find #"\(deflayermap \(([^)]+)\)" line)) []]))
        (nil? layer) (recur lines nil nil out)
        (re-find #"^\s*;;" line) (recur lines layer (str/trim (str comment " " (str/replace line #"^\s*;;\s?" ""))) out)
        (re-find #"^\s+\S" line)
        (let [[_ k action] (re-find #"^\s+(\S+)\s+(.+)$" line)
              closes (- (balance (str k " " action)))
              action (subs action 0 (- (count action) (max 0 closes)))]
          (recur (if (pos? closes) (cons "" lines) lines) (when-not (pos? closes) layer) nil
                 (update-in out [(dec (count out)) 1] conj [k action comment])))
        :else (recur lines nil nil out)))))

(defn hypr-binds [text]
  (for [line (str/split-lines text)
        :let [[_ chord action] (re-find #"^hl\.bind\(\"([^\"]+)\",\s*(.+?)(?:,\s*\{[^}]*\})?\)$" line)]
        :when chord]
    [chord action]))

(defn herdr-keys
  "[chord action] pairs from Herdr's [keys] table and [[keys.command]] entries."
  [text]
  (let [lines (str/split-lines text)
        simple (->> lines
                    (drop-while #(not= "[keys]" %))
                    rest
                    (take-while #(not (str/starts-with? % "[")))
                    (keep #(re-find #"^(\w+) = (.+)$" %))
                    (map (fn [[_ action chord]] [(if (= "[]" chord) "unbound" (str/replace chord "\"" "")) action])))
        commands (for [block (rest (str/split text #"\[\[keys\.command\]\]"))
                       :let [field #(second (re-find (re-pattern (str "(?m)^" % " = \"([^\"]*)\"")) block))]]
                   [(field "key") (or (field "description") (field "command"))])]
    (concat simple commands)))

(defn ghostty-keys [text]
  (for [[_ chord action] (re-seq #"(?m)^keybind = (.+?)=(.+)$" text)] [chord action]))

;; Doom alignment

(defn alignment
  "How binding B compares with Doom's DOOM-LABELS at the same keys, as leader.edn
  records it in :doom-match. Labels alone cannot tell copying text from jumping
  to a link, so a sequence Doom binds without a recorded match is unverified."
  [b doom-labels]
  (cond (empty? doom-labels) :doom-free
        (:doom-match b) (:doom-match b)
        :else :unverified))

(def contexts [:desktop :herdr :nvim :doom :idea])

(defn context-action [b context]
  (or (:nav b)
      (case context
        :desktop (or (:desktop-command b) (:desktop b))
        (get b context))))

(defn leader-rows [grammar upstream mine windows]
  (for [b (:bindings grammar)
        :let [seq-keys (str/join " " (:keys b))
              doom (or (get upstream seq-keys)
                       (when (= "w" (first (:keys b)))
                         (some-> (get windows (second (:keys b))) (#(vector (str % " (C-w " (second (:keys b)) ")"))))))]]
    {:keys seq-keys :label (:label b) :contexts (into {} (for [c contexts :let [a (context-action b c)] :when a] [c a]))
     :doom doom :mine (get mine seq-keys) :alignment (alignment b doom)}))

;; HTML

(defn- show [v] (if (string? v) v (pr-str v)))

(defn- table [headers rows]
  [:table [:thead [:tr (for [x headers] [:th x])]] [:tbody (for [r rows] [:tr (for [c r] [:td c])])]])

(def status-label {:exact "matches Doom" :analogous "like Doom, another object" :differs "differs from Doom"
                   :unverified "unverified" :doom-free "Doom binds nothing here"
                   :match "matches" :partial "partly" :deviates "deviates" :open "open" :fixed "fixed" :decided "decided"})

(defn- badge [k] [:span {:class (str "badge " (name k))} (status-label k (name k))])

(def css "
body{font:14px/1.5 system-ui,sans-serif;max-width:1200px;margin:2rem auto;padding:0 1rem;color:#222;background:#fdfdfc}
h1,h2,h3{line-height:1.2} h2{margin-top:2.5rem;border-bottom:1px solid #ddd;padding-bottom:.3rem}
table{border-collapse:collapse;width:100%;margin:.5rem 0 1.5rem;font-size:13px}
th,td{border:1px solid #e3e3e3;padding:.3rem .5rem;text-align:left;vertical-align:top}
th{background:#f3f3f1;position:sticky;top:0} code{font:12px ui-monospace,monospace;background:#f3f3f1;padding:0 .2rem;border-radius:3px}
.badge{font-size:11px;padding:.05rem .4rem;border-radius:8px;white-space:nowrap;background:#eee}
.exact,.match,.fixed,.decided{background:#dff3e0}.differs,.unverified,.deviates,.open{background:#fde2dd}.analogous,.doom-free,.partial{background:#fff3cc}
.flow{display:flex;flex-wrap:wrap;gap:.4rem;align-items:stretch;margin:1rem 0}
.flow div{border:1px solid #ccc;border-radius:6px;padding:.5rem .7rem;background:#fff;flex:1 1 150px}
.flow div b{display:block}.flow span.arrow{align-self:center;font-size:18px;color:#888}
#filter{position:sticky;top:0;width:100%;padding:.4rem;font-size:14px;margin:.5rem 0;z-index:2}
.note{color:#555}
")

(def js "
document.getElementById('filter').addEventListener('input', e => {
  const q = e.target.value.toLowerCase();
  document.querySelectorAll('tbody tr').forEach(r => { r.style.display = r.textContent.toLowerCase().includes(q) ? '' : 'none'; });
});")

(defn html [{:keys [grammar guide kanata hypr herdr ghostty upstream mine windows notes doom-source]}]
  (let [rows (leader-rows grammar upstream mine windows)
        counts (frequencies (map :alignment rows))]
    (str
     "<!doctype html>\n"
     (h/html
      [:html {:lang "en"}
       [:head [:meta {:charset "utf-8"}] [:title "Keybinding reference"] [:style (h/raw css)]]
       [:body
        [:h1 "Keybinding reference"]
        [:p.note "Generated by desktop/build/keybindings/generate.bb from leader.edn, bindings.edn, kanata.kbd, hyprland.lua, herdr/config.toml, the Ghostty config and "
         [:code doom-source] ". Edit those sources or " [:code "desktop/build/keybindings/reference-notes.edn"] ", then regenerate."]
        [:input#filter {:placeholder "Filter every table, for example: Ctrl+b, workspace, Doom"}]

        [:h2 "How a key travels"]
        [:p "Each layer can consume a chord before the next one sees it. A chord belongs to the first layer that binds it."]
        [:div.flow
         (interpose [:span.arrow "→"]
                    (for [[title body] (:layers notes)] [:div [:b title] body]))]

        [:h2 "Philosophy, after Doom Emacs"]
        [:p (:philosophy-intro notes)]
        (table ["Doom principle" "In Doom" "Here" "Status"]
               (for [{:keys [title doom here status]} (:principles notes)] [title doom here (badge status)]))

        [:h2 "Inconsistencies and proposals"]
        (table ["#" "Finding" "Evidence" "Proposal" "Status"]
               (for [[i {:keys [title evidence proposal status]}] (map-indexed vector (:inconsistencies notes))]
                 [(inc i) title evidence proposal (badge status)]))

        [:h2 "Shared leader against Doom"]
        [:p "Editors enter the leader with Space, Herdr with Ctrl+b Space or Cmd+Space, the desktop with Alt+Space. "
         (str (get counts :exact 0) " sequences share Doom's meaning, " (get counts :analogous 0) " apply it to another object, "
              (get counts :differs 0) " differ, " (get counts :unverified 0) " are unverified and " (get counts :doom-free 0)
              " use keys that Doom leaves free. leader.edn's :doom-match records each comparison by hand, and the Alignment column shows it.")]
        (table ["Keys" "Label" "Desktop" "Herdr" "Neovim" "Doom" "IdeaVim" "Upstream Doom SPC" "Your Doom config" "Alignment"]
               (for [{:keys [keys label doom mine alignment] actions :contexts} rows]
                 (concat [[:code keys] label]
                         (for [c contexts] (some-> (get actions c) show (#(vector :code %))))
                         [(str/join " / " doom) (str/join " / " mine) (badge alignment)])))
        [:h3 "Leader groups"]
        (table ["Key" "Group" "Upstream Doom"]
               (for [[k label] (:groups grammar)] [[:code k] label (str/join " / " (get upstream k))]))

        [:h2 "Desktop chords and the Cmd+/ guide"]
        (table ["Group" "Entry" "Chord" "Action or tip"]
               (for [g (:groups guide)
                     e (if (:workspace-digits g)
                         [{:label "Workspaces 1 to 10" :chord "CTRL ALT 1..0, add SHIFT to move a window" :tip "Focus or move to the workspace"}]
                         (:entries g))]
                 [(:label g) (:label e)
                  [:code (or (:chord e) (when-let [[mods k] (:hypr e)] (str mods " " k)) (when (:workspace-digits g) "CTRL ALT digits"))]
                  (or (:tip e) (some-> (or (:command e) (:dispatch e) (nth (:hypr e) 2 nil)) show))]))
        [:h3 "hyprland.lua"]
        (table ["Chord" "Action"] (for [[c a] hypr] [[:code c] [:code a]]))

        [:h2 "kanata layers"]
        [:p "hyprkan switches the base layer by window class from kanata/apps.edn. Cmd and Alt layers apply while those keys are held."]
        (for [[layer entries] kanata]
          (list [:h3 layer] (table ["Key" "Action" "Note"] (for [[k a c] entries] [[:code k] [:code a] c]))))

        [:h2 "Herdr"]
        (table ["Chord" "Action"] (for [[c a] herdr] [[:code c] a]))

        [:h2 "Ghostty"]
        [:p "Ghostty consumes its own bindings and defaults before Herdr or the program. " [:code "ghostty +list-keybinds"] " lists the defaults."]
        (table ["Chord" "Action"] (for [[c a] ghostty] [[:code c] [:code a]]))
        [:script (h/raw js)]]]))))

(defn sources
  "Read every input from the repository root ROOT and the installed Doom."
  [root grammar]
  (let [emacs (str (fs/home) "/.config/emacs")
        doom (str emacs "/sources/doom+/modules/config/default/+evil-bindings.el")
        evil (str emacs "/.local/straight/repos/evil/evil-maps.el")
        doom-evil (str emacs "/sources/doom+/modules/editor/evil/config.el")
        slurp-root #(slurp (str root "/" %))]
    {:grammar grammar
     :guide (edn/read-string (slurp-root "desktop/configs/keybindings/bindings.edn"))
     :kanata (kanata-layers (slurp-root "desktop/kanata.kbd"))
     :hypr (hypr-binds (slurp-root "desktop/configs/hypr/hyprland.lua"))
     :herdr (herdr-keys (slurp-root "desktop/configs/herdr/config.toml"))
     :ghostty (ghostty-keys (slurp-root "desktop/configs/ghostty/config"))
     :upstream (if (fs/exists? doom) (leader-descs (slurp doom)) {})
     :mine (leader-descs (slurp-root "desktop/configs/doom/keybindings.el"))
     :windows (if (and (fs/exists? evil) (fs/exists? doom-evil)) (window-map (slurp evil) (slurp doom-evil)) {})
     :notes (edn/read-string (slurp-root "desktop/build/keybindings/reference-notes.edn"))
     :doom-source (if (fs/exists? doom) "~/.config/emacs/sources/doom+/modules/config/default/+evil-bindings.el" "no installed Doom")}))
