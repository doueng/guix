;; Settings: Vimium's option names and defaults (lib/settings.js, MIT), the
;; import of a Vimium options export, and the search-engine list.
;; Settings stay a map with Vimium's string keys, so an export round-trips.
(ns vimium-cljs.settings
  (:require [clojure.string :as str]
            [vimium-cljs.keys :as keys]
            [vimium-cljs.commands :as commands]))

(def defaults
  {"scrollStepSize" 60
   "smoothScroll" true
   "keyMappings" "# Insert your preferred key mappings here."
   "linkHintCharacters" "sadfjklewcmpgh"
   "hideHud" false
   "userDefinedLinkHintCss" ""
   "exclusionRules" [{"passKeys" "" "pattern" "https?://mail.google.com/*"}]
   "previousPatterns" "prev,previous,back,older,<,\u2039,\u2190,\u00ab,\u226a,<<"
   "nextPatterns" "next,more,newer,>,\u203a,\u2192,\u00bb,\u226b,>>"
   "searchUrl" "https://www.google.com/search?q="
   "searchEngines" "w: https://www.wikipedia.org/w/index.php?title=Special:Search&search=%s Wikipedia"
   "newTabDestination" "browserNewTabPage"
   "newTabCustomUrl" ""
   "grabBackFocus" false
   "regexFindMode" false
   "ignoreKeyboardLayout" false})

(def ^:private kinds
  {"scrollStepSize" number? "smoothScroll" boolean? "keyMappings" string?
   "linkHintCharacters" string? "hideHud" boolean? "userDefinedLinkHintCss" string?
   "exclusionRules" vector? "previousPatterns" string? "nextPatterns" string?
   "searchUrl" string? "searchEngines" string? "newTabDestination" string?
   "newTabCustomUrl" string? "grabBackFocus" boolean? "regexFindMode" boolean?
   "ignoreKeyboardLayout" boolean?})

;; Vimium options this port reads but does not act on, and why.
(def unsupported
  {"filterLinkHints" "filtered (text-typing) link hints are not ported; hints use the characters"
   "linkHintNumbers" "only used by filtered link hints"
   "waitForEnterForFilteredHints" "only used by filtered link hints"
   "openVomnibarOnNewTabPage" "the Vimium new-tab page is not ported"
   "hideUpdateNotifications" "there are no update notifications"
   "helpDialog_showAdvancedCommands" "the help dialog always lists every command"})

(def ^:private ignored #{"settingsVersion"})

(defn merged [stored] (merge defaults stored))

(defn- clean-rules [rules]
  (->> rules
       (filter map?)
       (map (fn [r] {"pattern" (str/trim (str (get r "pattern" ""))) "passKeys" (str (get r "passKeys" ""))}))
       (remove #(str/blank? (get % "pattern")))
       distinct
       vec))

(defn import-vimium
  "Reads the text of a Vimium options export. Only parses it: nothing in it
  runs. Returns {:settings {...} :notes [...] :errors [...]} where :settings
  holds the recognised values that differ from nothing (stored as given)."
  [text]
  (let [data (try (js->clj (js/JSON.parse text)) (catch :default e {::error (.-message e)}))]
    (cond
      (::error data) {:settings {} :notes [] :errors [(str "Not JSON: " (::error data))]}
      (not (map? data)) {:settings {} :notes [] :errors ["Expected a JSON object of Vimium options."]}
      :else
      (reduce-kv
       (fn [acc k v]
         (cond
           (ignored k) acc
           (unsupported k) (update acc :notes conj (str k ": kept but not used, " (unsupported k)))
           (not (kinds k)) (update acc :notes conj (str k ": unknown option, ignored"))
           (not ((kinds k) v)) (update acc :errors conj (str k ": expected a different type, ignored"))
           (= k "exclusionRules")
           (let [rules (clean-rules v)]
             (cond-> (assoc-in acc [:settings k] rules)
               (< (count rules) (count v))
               (update :notes conj (str "exclusionRules: dropped " (- (count v) (count rules)) " duplicate or empty rule(s)"))))
           (and (= k "linkHintCharacters") (< (count (distinct (str/lower-case v))) 2))
           (update acc :errors conj "linkHintCharacters: needs at least 2 distinct characters, ignored")
           :else (assoc-in acc [:settings k] v)))
       {:settings {} :notes [] :errors []}
       data))))

(defn search-engines
  "keyword -> {:url :name} from lines like `w: https://...%s Wikipedia`."
  [text]
  (into {}
        (keep (fn [line]
                (let [line (str/trim line)]
                  (when-not (or (str/blank? line) (str/starts-with? line "#") (str/starts-with? line "\""))
                    (when-let [[_ kw url name] (re-find #"^([^:\s]+):\s*(\S+)\s*(.*)$" line)]
                      [kw {:url url :name (if (str/blank? name) kw name)}])))))
        (str/split-lines (or text ""))))

(defn summary
  "What a settings map means, for the options page and the scope review."
  [stored]
  (let [s (merged stored)
        user (keys/parse-mappings (get s "keyMappings"))
        lines (->> (str/split-lines (get s "keyMappings"))
                   (map str/trim)
                   (remove #(or (str/blank? %) (str/starts-with? % "#") (str/starts-with? % "\""))))
        effective (keys/effective-mappings (get s "keyMappings"))]
    {:mapped (vec (for [k (:order user) :let [e ((:entries user) k)]]
                    {:key k :command (:command e) :desc (:desc (commands/by-name (:command e)))}))
     :unmapped (vec (keep #(second (re-find #"(?i)^unmap\s+(\S+)$" %)) lines))
     :errors (:errors user)
     :lines lines
     :effective-count (count (:order effective))
     :exclusions (get s "exclusionRules")
     :hint-chars (get s "linkHintCharacters")
     :engines (search-engines (get s "searchEngines"))
     :grab-back-focus (get s "grabBackFocus")}))
