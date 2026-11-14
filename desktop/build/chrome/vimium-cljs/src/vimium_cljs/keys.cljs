;; Key names, key-mapping text and the normal-mode key state machine.
;; Ported from Vimium (lib/keyboard_utils.js, background_scripts/commands.js,
;; content_scripts/mode_key_handler.js; MIT, Copyright (c) Phil Crosby and
;; Ilya Sukhar). Everything here is pure, so cljs.test covers it on node.
(ns vimium-cljs.keys
  (:require [clojure.string :as str]
            [vimium-cljs.commands :as commands]))

(def ^:private key-names
  {"ArrowLeft" "left" "ArrowUp" "up" "ArrowRight" "right" "ArrowDown" "down"
   " " "space" "\n" "enter"})

(def ^:private modifier-keys #{"Control" "Shift" "Alt" "OS" "AltGraph" "Meta"})

(def ^:private en-us
  {"Backquote" ["`" "~"] "Minus" ["-" "_"] "Equal" ["=" "+"] "Backslash" ["\\" "|"]
   "IntlBackslash" ["\\" "|"] "BracketLeft" ["[" "{"] "BracketRight" ["]" "}"]
   "Semicolon" [";" ":"] "Quote" ["'" "\""] "Comma" ["," "<"] "Period" ["." ">"]
   "Slash" ["/" "?"] "Space" [" " " "] "Digit1" ["1" "!"] "Digit2" ["2" "@"]
   "Digit3" ["3" "#"] "Digit4" ["4" "$"] "Digit5" ["5" "%"] "Digit6" ["6" "^"]
   "Digit7" ["7" "&"] "Digit8" ["8" "*"] "Digit9" ["9" "("] "Digit0" ["0" ")"]})

(defn key-char
  "Vimium's name for the key in EV, a map of :key :code :shift :ctrl :alt :meta,
  without modifiers. With IGNORE-LAYOUT it reads the physical key (:code)."
  [{:keys [key code shift] :as ev} ignore-layout]
  (let [k (cond (not ignore-layout) key
                (not code) (or key "")
                (str/starts-with? code "Numpad") key
                :else (let [c (if (str/starts-with? code "Key") (subs code 3) code)]
                        (if-let [[lo hi] (en-us c)]
                          (if shift hi lo)
                          (if (and (= 1 (count c)) (not shift)) (str/lower-case c) c))))]
    (cond (str/blank? k) (if (= k " ") "space" "")
          (key-names k) (key-names k)
          (modifier-keys key) ""
          (= 1 (count k)) k
          :else (str/lower-case k))))

(defn key-string
  "The key as Vimium writes it in mappings: a, A, <c-a>, <a-f>, <s-left>, <space>.
  MAPKEYS (from mapkey lines) translates the result. Nil for a bare modifier."
  ([ev] (key-string ev {} false))
  ([{:keys [shift ctrl alt meta] :as ev} mapkeys ignore-layout]
   (let [c (key-char ev ignore-layout)]
     (when (seq c)
       (let [c (if (and shift (= 1 (count c))) (str/upper-case c) c)
             mods (cond-> []
                    alt (conj "a")
                    ctrl (conj "c")
                    meta (conj "m")
                    (and shift (> (count c) 1)) (conj "s"))
             s (str/join "-" (conj mods c))
             s (if (> (count s) 1) (str "<" s ">") s)]
         (get mapkeys s s))))))

(defn escape?
  "Escape, or <c-[> unless the user mapped it. IME escapes (keyCode 229) are not."
  [ev ks vim-escape?]
  (or (and (= "Escape" (:key ev)) (not= 229 (:key-code ev)))
      (and vim-escape? (= "<c-[>" ks))))

;; ---- mapping text ---------------------------------------------------------

(def ^:private named-key "(?:[a-z][a-z0-9]+)")
(def ^:private modified-key (str "(?:(?:[a-zA-Z]-)+(?:.|" named-key "))"))
(def ^:private special-key-re (re-pattern (str "(?i)^<(" named-key "|" modified-key ")>(.*)")))

(defn parse-key-sequence
  "\"<c-A>gb\" -> [\"<c-A>\" \"g\" \"b\"]: named keys lower-cased, modifiers sorted."
  [s]
  (loop [s s acc []]
    (if (empty? s)
      acc
      (if-let [[_ inner rest] (re-find special-key-re s)]
        (let [parts (str/split inner #"-" -1)
              k (last parts)
              k (if (= 1 (count k)) k (str/lower-case k))
              mods (sort (map str/lower-case (butlast parts)))]
          (recur rest (conj acc (str "<" (str/join "-" (concat mods [k])) ">"))))
        (recur (subs s 1) (conj acc (subs s 0 1)))))))

(defn parse-options
  "key=value, key=\"a b\" and bare flags (or a URL) after a command name."
  [s]
  (loop [s (str/trim s) acc {}]
    (if (empty? s)
      (if-let [c (get acc "count")]
        (let [n (js/parseInt c 10)]
          (if (js/isNaN n) (dissoc acc "count") (assoc acc "count" n)))
        acc)
      (if-let [[m k v] (or (re-find #"^([a-zA-Z]+)=\"([^\"]+)\"(?:\s+|$)" s)
                           (re-find #"^([a-zA-Z]+)=(\S+)(?:\s+|$)" s)
                           (when-let [[m k] (re-find #"^([^\s\"]+)(?:\s+|$)" s)] [m k true]))]
        (recur (subs s (count m)) (assoc acc k v))
        (assoc acc s true)))))

(defn- config-lines [text]
  (->> (str/split (str/replace text #"\\\n" "") #"\n")
       (map str/trim)
       (remove #(or (empty? %) (#{\# \"} (first %))))))

(defn- bad-modifier [k]
  (when-let [[_ m] (re-find #"^<([^>-]+)-." k)]
    (when-not (#{"a" "c" "m" "s"} m)
      (str k " has an invalid modifier; valid modifiers are a, c, m, s"))))

(defn parse-mappings
  "Parses Vimium key-mapping TEXT (map, unmap, unmapAll, mapkey). Returns
  {:order [key ...] :entries {key {:keys :command :options}} :mapkeys {} :errors []}.
  :order keeps first-definition order, as Vimium's object keys do."
  [text]
  (reduce
   (fn [{:keys [entries] :as acc} line]
     (let [tokens (str/split line #"\s+")
           action (str/lower-case (first tokens))]
       (case action
         "map"
         (let [[_ k cmd] tokens
               info (commands/by-name cmd)
               opt-str (if-let [m (re-find #"^\S+\s+\S+\s+\S+\s+" line)] (subs line (count m)) "")
               ks (when k (parse-key-sequence k))
               errs (keep bad-modifier ks)
               opts (parse-options opt-str)
               allowed (cond-> (or (:options info) #{}) (not (:once info)) (conj "count"))
               unknown (remove allowed (keys opts))]
           (cond (< (count tokens) 3) (update acc :errors conj (str "\"map\" requires at least 2 arguments in the line: " line))
                 (not info) (update acc :errors conj (str "\"" cmd "\" is not a valid command in the line: " line))
                 (seq errs) (update acc :errors into errs)
                 (seq unknown) (update acc :errors conj (str "Command " cmd " does not support option " (first unknown)))
                 :else (cond-> (assoc-in acc [:entries k] {:keys ks :command cmd :options opts})
                         (not (contains? entries k)) (update :order conj k))))
         "unmap"
         (if (not= 2 (count tokens))
           (update acc :errors conj (str "Incorrect usage for unmap in the line: " line))
           (let [k (second tokens)]
             (-> acc
                 (update :entries dissoc k)
                 (update :mapkeys dissoc k)
                 (update :order (fn [o] (filterv #(not= k %) o))))))
         "unmapall" (assoc acc :entries {} :mapkeys {} :order [])
         "mapkey"
         (let [from (parse-key-sequence (or (second tokens) ""))
               to (parse-key-sequence (or (nth tokens 2 nil) ""))]
           (if (and (= 3 (count tokens)) (= 1 (count from) (count to)))
             (assoc-in acc [:mapkeys (first from)] (first to))
             (update acc :errors conj (str "mapkey only supports mapping keys which are single characters. Line: " line))))
         (update acc :errors conj (str "\"" action "\" is not a valid config command in line: " line)))))
   {:order [] :entries {} :mapkeys {} :errors []}
   (config-lines text)))

(defn mappings-text
  "Vimium mapping text for config/key-mappings entries: [key command {option value}]
  maps, [key nil] unmaps. An option whose value is true is a bare flag."
  [entries]
  (str/join "\n"
            (for [[k cmd opts] entries]
              (if cmd
                (str/join " " (concat ["map" k cmd]
                                      (for [[o v] opts] (if (true? v) (name o) (str (name o) "=" v)))))
                (str "unmap " k)))))

(defn effective-mappings
  "Vimium's defaults followed by the user's mapping text."
  [user-text]
  (parse-mappings (str commands/default-mappings-text "\n" (or user-text ""))))

(defn trie
  "Nested key -> node maps whose leaves carry :command. An existing command
  binding wins over a longer sequence through it, as in Vimium."
  [{:keys [order entries]}]
  (reduce
   (fn [t k]
     (let [{ks :keys :as e} (entries k)]
       (loop [path [] [x & more] ks]
         (let [p (conj path x)
               node (get-in t p)]
           (cond (:command node) t
                 (seq more) (recur p more)
                 :else (assoc-in t p (assoc (dissoc e :keys) :key k)))))))
   {}
   order))

(defn command-keys
  "command -> [key ...] for the help dialog, in mapping order."
  [{:keys [order entries]}]
  (reduce (fn [m k] (update m (:command (entries k)) (fnil conj []) k)) {} order))

;; ---- the key state machine --------------------------------------------------

(def initial {:states nil :count 0})

(defn- states [trie {:keys [states]}] (or states [trie]))

(defn reset-state? [{:keys [states count]}] (and (nil? states) (zero? count)))

(defn- pass-key? [trie st pass-keys k]
  (and (seq pass-keys)
       (zero? (:count st))
       (not-any? #(and (not (identical? % trie)) (contains? % k)) (states trie st))
       (str/includes? pass-keys k)))

(defn step
  "Feeds key string K (and whether it was an escape) to state ST. Returns
  {:state :result} with :result :command (and :command, :count), :pending
  (swallow the key, wait for more), :reset (swallow, back to the start) or
  :pass (let the page have it)."
  [trie st k escape? pass-keys]
  (let [ss (states trie st)]
    (cond
      (and escape? (not (reset-state? st))) {:state initial :result :reset}
      escape? {:state initial :result :pass}
      (and k (some #(contains? % k) ss) (not (pass-key? trie st pass-keys k)))
      (let [cnt (if (contains? (first ss) k) (:count st) 0)
            nxt (conj (mapv #(get % k) (filter #(contains? % k) ss)) trie)
            head (first nxt)]
        (if (:command head)
          {:state initial :result :command :command head :count (when (pos? cnt) cnt)}
          {:state {:states nxt :count cnt} :result :pending}))
      (and k (re-matches (if (pos? (:count st)) #"[0-9]" #"[1-9]") k)
           (not (pass-key? trie st pass-keys k)))
      (let [d (js/parseInt k 10)]
        {:state {:states nil :count (if (nil? (:states st)) (+ (* 10 (:count st)) d) d)} :result :pending})
      :else {:state initial :result :pass})))
