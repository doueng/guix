;; Usage: bb scripts/inventory.bb <repo-root>
;; Lists every keybinding in the desktop configs as layer, chord, action, source.
(ns inventory (:require [babashka.fs :as fs] [clojure.string :as str]))
(def root (or (first *command-line-args*) "."))
(defn path [& parts] (str/join "/" (cons root parts)))
(defn
 numbered
 [file]
 (map-indexed
  (fn [i line] [(inc i) line])
  (str/split-lines (slurp file))))
(defn
 emit!
 [layer chord action source line]
 (println (str/join "\t" [layer chord action (str source ":" line)])))
(def lua-bind
  #"\s*(?:hl|keys)\.bind\(\"([^\"]+)\",\s*(hl\.dsp\.[\w.]+\((?:[^()]|\([^()]*\))*\)|[\w.]+(?:\([^()]*\))?).*")
(defn hypr! []
  (doseq [file ["desktop/configs/hypr/hyprland.lua" "desktop/configs/hypr/keybindings.lua"]
          [ln l] (numbered (path file))
          :let [[_ chord action] (re-matches lua-bind l)]
          :when chord]
    (emit! "hypr" chord action file ln)))
(defn- balanced
  "S without the closing parentheses that end the enclosing form."
  [s]
  (if (> (count (filter #{\)} s)) (count (filter #{\(} s)))
    (recur (subs s 0 (dec (count s))))
    s))
(defn kanata! []
  (let [layer (atom nil)]
    (doseq [[ln l] (numbered (path "desktop/kanata.kbd"))]
      (if-let [[_ name] (re-find #"^\(deflayermap \(([^)]+)\)" l)]
        (reset! layer name)
        (if-let [[_ k action] (and @layer (re-matches #"\s+(\S+)\s+(.+)" l))]
          (emit! (str "kanata " @layer) k (balanced action) "kanata.kbd" ln)
          (reset! layer nil))))))
(defn
 ghostty!
 []
 (doseq
  [[ln l]
   (numbered (path "desktop/configs/ghostty/config"))
   :let
   [m (re-matches #"\s*keybind\s*=\s*([^=]+)=(.+)" l)]
   :when
   m]
  (emit!
   "ghostty"
   (str/trim (nth m 1))
   (str/trim (nth m 2))
   "ghostty/config"
   ln)))
(defn
 herdr!
 []
 (doseq
  [[ln l]
   (numbered (path "desktop/configs/herdr/config.toml"))
   :let
   [m (re-matches #"\s*([a-z_]+)\s*=\s*\"(.+)\"\s*" l)]
   :when
   m]
  (when (re-matches #"prefix.*|ctrl\+.*|[a-z]" (nth m 2))
            (emit! "herdr" (nth m 2) (nth m 1) "herdr/config.toml" ln))))
(defn
 nvim!
 []
 (doseq
  [[ln l]
   (numbered (path "desktop/configs/nvim/fnl/config/keymaps.fnl"))
   :let
   [m
    (re-find
     #"\(vim\.keymap\.set\s+(\[[^\]]*\]|\S+)\s+(\"[^\"]*\"|\S+)\s*(.*)"
     l)]
   :when
   m]
  (emit!
   "nvim"
   (nth m 2)
   (str "modes " (nth m 1) " " (str/trim (nth m 3)))
   "keymaps.fnl"
   ln)))
(defn
 fish!
 []
 (doseq
  [file
   (cons
    (path "desktop/configs/fish/config.fish")
    (map
     str
     (fs/glob (path "desktop/configs/fish/functions") "*.fish")))
   [ln l]
   (numbered file)
   :let
   [m (re-matches #"\s*bind\s+(?:-M\s+(\S+)\s+)?(\S+)\s+(.+)" l)]
   :when
   m]
  (emit!
   (str "fish" (when (second m) (str " " (second m))))
   (nth m 2)
   (nth m 3)
   (fs/file-name file)
   ln)))
(defn
 doom!
 []
 (doseq
  [file
   ["desktop/configs/doom/keybindings.el"
    "desktop/configs/doom/config.el"]
   [ln l]
   (numbered (path file))
   :when
   (re-find #"\(map!" l)]
  (emit! "doom" "" (str/trim l) file ln)))
(println (str/join "\t" ["layer" "chord" "action" "source"]))
(defn
 bbh!
 []
 (doseq
  [[ln l] (numbered (path "desktop/configs/doom/agent-shell.el"))]
  (when-let
   [[_ chord action] (re-find #"\(\"([^\"]+)\" \. ([^)]+)\)" l)]
   (emit! "bbh composer" chord action "agent-shell.el" ln))
  (when-let
   [[_ chord action]
    (re-find
     #"\(kbd \"([^\"]+)\"\) #'([^)\s]+)"
     l)]
   (emit! "bbh transcript" chord action "agent-shell.el" ln))))
(defn
 bbh-terminal!
 []
 (doseq
  [[ln l] (numbered (path "desktop/build/bbh/src/bbh/main.clj"))]
  (when
   (re-find #"Signal\. \"INT\"" l)
   (emit!
    "bbh terminal"
    "C-c"
    "interrupt the running turn"
    "main.clj"
    ln))
  (when
   (re-find #"catch UserInterruptException" l)
   (emit!
    "bbh terminal"
    "C-c at prompt"
    "clear the line"
    "main.clj"
    ln))
  (when
   (re-find #"catch EndOfFileException" l)
   (emit! "bbh terminal" "C-d" "quit" "main.clj" ln))
  (when
   (re-find #"\"/quit\"" l)
   (emit! "bbh terminal" "/quit" "quit" "main.clj" ln))))
(def leader-contexts
  {:desktop ["Alt+Space" "generated hypr/leader.lua"]
   :herdr ["Ctrl+b Space" "bin/keyleader reads leader.edn at run time"]
   :nvim ["Space" "generated nvim/fnl/config/shared-keys.fnl"]
   :doom ["SPC" "generated doom/shared-keys.el"]
   :idea ["Space" "generated ideavim/.config/ideavim/shared-keys.vim"]})

(defn leader-action [context b]
  (or (when-let [d (:nav b)] (str "focus " d ", falls through to the next layer"))
      (case context
        :desktop (or (some->> (:desktop-command b) (str/join " "))
                     (if (keyword? (:desktop b)) (name (:desktop b)) ((resolve 'hypr.dispatch/lua) (:desktop b))))
        :herdr (if (keyword? (:herdr b)) (name (:herdr b)) (str "herdr " (str/join " " (:herdr b))))
        (get b context))))

(defn leader! []
  (load-file (path "desktop/bin/keyleader"))
  (let [file (path "desktop/configs/keybindings/leader.edn")
        grammar ((resolve 'keyleader/validate!) (clojure.edn/read-string (slurp file)))
        in-context (resolve 'keyleader/in-context)
        line-of (fn [b] (some (fn [[ln l]] (when (str/includes? l (str ":keys " (pr-str (:keys b)))) ln))
                              (numbered file)))]
    (doseq [[context [entry owner]] leader-contexts
            b (in-context grammar context)]
      (emit! (str "leader " (name context))
             (str "SPC " (str/join " " (:keys b)))
             (str "after " entry ": " (leader-action context b) "; applied by " owner)
             "keybindings/leader.edn"
             (line-of b)))))

(defn vimium! []
  (doseq [[ln line] (numbered (path "desktop/configs/vimium/keybindings.txt"))]
    (when-let [[_ chord action] (re-matches #"map\s+(\S+)\s+(.+)" line)]
      (emit! "vimium" chord action "vimium/keybindings.txt" ln))))

(doseq [f [hypr! kanata! ghostty! herdr! nvim! fish! doom! bbh! bbh-terminal! vimium! leader!]] (f))
