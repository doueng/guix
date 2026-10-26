#!/usr/bin/env bb
;; Usage: bb desktop/build/keybindings/usage.clj [--log PATH] [--leader PATH]
;;          [--since YYYY-MM-DD] [--until YYYY-MM-DD] [--top N]
;; `keylog report` runs this. It weights each logged binding's physical cost by
;; how often it ran, and compares the use of leader.edn's bindings with the
;; grammar's rule that the most frequent actions take one key after the leader.
(ns keybindings-usage
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def repo (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/real-path *file*)))))))
(load-file (str repo "/desktop/bin/keyleader"))
(require '[keylog])

;; Keystroke-Level Model operators (Card, Moran and Newell): K for one key
;; press by an average non-secretarial typist, M for mentally preparing one
;; command. A binding costs one M and one K per key, modifiers included.
(def k-seconds 0.28)
(def m-seconds 1.35)

(defn presses
  "Physical key presses in KEYS, such as 3 for \"Alt+Space w\"."
  [keys]
  (reduce + 0 (for [step (str/split (str/trim keys) #" +") :when (seq step)]
                (count (str/split step #"\+")))))

(defn klm-seconds [n] (+ m-seconds (* k-seconds n)))

;; Each leader and its leader.edn context. Herdr's and the desktop's leaders
;; are logged by whichever layer finally acts, such as Hyprland when Ctrl+b
;; Space w h leaves the terminal. Editors share Space, so their program decides.
(def leaders [["Ctrl+b Space" (constantly :herdr)]
              ["Alt+Space" (constantly :desktop)]
              ["Space" {"emacs" :doom "neovim" :nvim}]])

(defn leader-binding
  "[context keys] when ROW ran a leader binding by its keys, such as
  [:doom [\"w\" \"h\"]] for Emacs's Space w h."
  [row]
  (some (fn [[prefix context-of]]
          (when (str/starts-with? (:keys row) (str prefix " "))
            (let [ks (mapv #(get {"Space" "SPC"} % %) (str/split (subs (:keys row) (inc (count prefix))) #" "))
                  context (context-of (:program row))]
              (when (and context (not= ["SPC"] ks)) [context ks]))))
        leaders))

(defn read-rows
  "Parsed keylog rows in PATH from SINCE until UNTIL, dates as YYYY-MM-DD,
  and the count of lines that are neither schema."
  [path since until]
  (let [lines (if (fs/exists? path) (fs/read-all-lines path) [])
        rows (map keylog/parse-line lines)
        in? #(and (or (nil? since) (>= (compare (:time %) since) 0))
                  (or (nil? until) (neg? (compare (subs (:time %) 0 10) until))))]
    {:rows (filter in? (remove nil? rows)) :unreadable (count (filter nil? rows))}))

(defn- mean [xs] (when (seq xs) (/ (reduce + xs) (double (count xs)))))

(defn- cost [actions]
  (let [ps (map (comp presses :keys) actions)]
    {:actions (count actions) :presses-per-action (mean ps) :klm-seconds (some-> (mean ps) klm-seconds)}))

(defn binding-uses
  "How often each leader.edn binding ran, by keys, per context. A binding
  chosen by name in Herdr's palette counts for Herdr."
  [rows grammar]
  (let [herdr-labels (into {} (for [b (keyleader/in-context grammar :herdr)] [(:label b) (:keys b)]))]
    (frequencies
     (keep (fn [row]
             (when (= "action" (:kind row))
               (or (leader-binding row)
                   (when (and (= "herdr" (:program row)) (= "Ctrl+b Space Space" (:keys row)))
                     (some->> (herdr-labels (:effect row)) (vector :herdr))))))
           rows))))

(defn report
  "The usage report for ROWS against GRAMMAR, as data. TOP limits lists."
  [rows grammar {:keys [top] :or {top 15}}]
  (let [actions (filter #(= "action" (:kind %)) rows)
        uses (binding-uses rows grammar)
        bindings (:bindings grammar)
        total #(reduce + 0 (for [[[_ ks] n] uses :when (= ks (:keys %))] n))
        logged-contexts (set (map first (keys uses)))
        singles (sort-by second (for [b bindings :when (= 1 (count (:keys b)))] [b (total b)]))
        [least least-uses] (first singles)
        v2 (filter #(= keylog/version (:version %)) rows)]
    {:rows (count rows)
     :from (some-> (first rows) :time) :to (some-> (last rows) :time)
     :schemas (frequencies (map :version rows))
     :kinds (frequencies (map :kind rows))
     :cost (cost actions)
     :by-program (sort-by (comp - :actions)
                          (for [[p as] (group-by :program actions)] (assoc (cost as) :program p)))
     :costliest (take top (sort-by (comp - :total)
                                   (for [[[p k e] as] (group-by (juxt :program :keys :effect) actions)
                                         :let [n (count as)]]
                                     {:program p :keys k :effect e :count n :presses (presses k)
                                      :total (* n (presses k))})))
     :single-keys (for [[b n] singles] {:keys (:keys b) :label (:label b) :uses n})
     :promote (when least
                (take top (sort-by (comp - :uses)
                                   (for [b bindings :let [n (total b)]
                                         :when (and (< 1 (count (:keys b))) (> n least-uses))]
                                     {:keys (:keys b) :label (:label b) :uses n :nav (:nav b)}))))
     :least-single (when least {:keys (:keys least) :uses least-uses})
     :unused (into (sorted-map)
                   (for [ctx [:doom :nvim :herdr :desktop] :when (logged-contexts ctx)]
                     [ctx (vec (for [b (keyleader/in-context grammar ctx)
                                     :when (zero? (get uses [ctx (:keys b)] 0))]
                                 (:keys b)))]))
     :leaders (for [[program prefix] [["hyprland" "Alt+Space"] ["herdr" "Ctrl+b Space"]]
                    :let [mine #(and (= program (:program %)) (= prefix (:keys %)))
                          under #(str/starts-with? (:keys %) prefix)
                          opens (count (filter #(and (= "leader" (:kind %)) (mine %)) v2))
                          cancels (count (filter #(and (= "cancel" (:kind %)) (under %)) v2))]]
                {:program program :keys prefix :opens opens
                 :actions (count (filter #(and (= "action" (:kind %)) (under %) (str/starts-with? (:keys %) (str prefix " "))) v2))
                 :cancels cancels
                 :cancel-rate (when (pos? opens) (/ cancels (double opens)))})
     :forwards (sort-by (comp - val) (frequencies (for [r rows :when (= "forward" (:kind r))]
                                                    [(:program r) (str/replace (:effect r) #"^Forwarded to " "")])))}))

(defn- fmt [x] (if (number? x) (format "%.2f" (double x)) "-"))
(defn- ks [keys] (str/join " " keys))

(defn render [{:keys [cost] :as r} unreadable]
  (str/join
   "\n"
   (concat
    [(str "Keybinding usage, " (:rows r) " rows from " (:from r) " to " (:to r))
     (str "Schemas " (pr-str (:schemas r)) "  kinds " (pr-str (:kinds r))
          (when (pos? unreadable) (str "  unreadable lines " unreadable)))
     ""
     (str "Weighted cost: " (fmt (:presses-per-action cost)) " presses per action, KLM estimate "
          (fmt (:klm-seconds cost)) " s, over " (:actions cost) " actions. Lower is better.")
     ""
     "By program            actions  presses  KLM s"]
    (for [p (:by-program r)]
      (format "  %-18s %8d %8s %6s" (:program p) (:actions p) (fmt (:presses-per-action p)) (fmt (:klm-seconds p))))
    ["" "Costliest bindings (uses x presses)"]
    (for [c (:costliest r)]
      (format "  %5d  %3d x %d  %-10s %-22s %s" (:total c) (:count c) (:presses c) (:program c) (:keys c) (:effect c)))
    ["" "One-key leader bindings, least used first"]
    (for [s (:single-keys r)] (format "  %4d  %-6s %s" (:uses s) (ks (:keys s)) (:label s)))
    ["" (str "Promotion candidates: longer leader bindings used more than the least used one-key binding"
              (when-let [l (:least-single r)] (str " (" (pr-str (ks (:keys l))) ", " (:uses l) " uses)")))]
    (if (seq (:promote r))
      (for [p (:promote r)]
        (format "  %4d  %-6s %s%s" (:uses p) (ks (:keys p)) (:label p)
                (if-let [d (:nav p)] (str ", also Ctrl+" ({"left" "h" "down" "j" "up" "k" "right" "l"} d)) "")))
      ["  none"])
    ["" "Leader bindings never used, per context with logged use"]
    (for [[ctx unused] (:unused r)] (str "  " (name ctx) ": " (if (seq unused) (str/join ", " (map ks unused)) "none")))
    ["" "Leader outcomes (schema 2 rows)"]
    (for [l (:leaders r)]
      (format "  %-9s %-13s opened %d, acted %d, cancelled %d, cancel rate %s"
              (:program l) (:keys l) (:opens l) (:actions l) (:cancels l) (fmt (:cancel-rate l))))
    ["" (str "Keys forwarded: "
              (if (seq (:forwards r)) (str/join ", " (for [[[from to] n] (:forwards r)] (str from " to " to " " n))) "none"))
     ""])))

(defn main [args]
  (let [opts (into {} (map vec (partition 2 args)))
        path (get opts "--log" (keylog/path))
        grammar (keyleader/validate! (edn/read-string (slurp (get opts "--leader" (str repo "/desktop/configs/keybindings/leader.edn")))))
        {:keys [rows unreadable]} (read-rows path (opts "--since") (opts "--until"))]
    (print (render (report rows grammar {:top (parse-long (get opts "--top" "15"))}) unreadable))
    (flush)))

(when (= *file* (System/getProperty "babashka.file"))
  (main *command-line-args*))
