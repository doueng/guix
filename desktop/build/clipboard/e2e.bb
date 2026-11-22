;; Copy and paste right after a focus switch, in Chrome, terminal Emacs and Neovim,
;; on the hidden test display. Keys are what kanata sends: Cmd+C is Ctrl+Insert,
;; Cmd+V Shift+Insert (desktop/kanata.kbd). Run it as make verify ONLY=clipboard-e2e, which
;; wraps it in test-display; see docs/clipboard.html.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(when-not (System/getenv "TEST_DISPLAY")
  (println "run inside test-display: make verify ONLY=clipboard-e2e")
  (System/exit 2))

(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))))
(def tmp (str (fs/create-temp-dir {:prefix "clipboard-e2e"})))
(def results (atom []))
(def procs (atom []))

(defn check! [name ok & [detail]]
  (swap! results conj (boolean ok))
  (println (if ok "PASS" "FAIL") name (if (and detail (not ok)) (pr-str detail) "")))

(defn wait-for [ms f]
  (loop [t 0]
    (or (try (f) (catch Exception _ nil))
        (when (< t ms) (Thread/sleep 100) (recur (+ t 100))))))

(defn spawn! [& cmd]
  (let [pr (p/process (vec cmd) {:out :discard :err (str tmp "/" (fs/file-name (first cmd)) ".log")})]
    (swap! procs conj pr)
    pr))

(defn windows []
  (->> (json/parse-string (:out (p/sh "swaymsg" "-t" "get_tree")) true)
       (tree-seq map? #(concat (:nodes %) (:floating_nodes %)))
       (filter :app_id)))

(defn window [app] (some #(when (= app (:app_id %)) %) (windows)))
(defn focus! [app] (p/sh "swaymsg" (str "[app_id=\"" app "\"] focus")))
;; The pause lets wtype's fresh virtual keyboard load its keymap before the first key.
(defn keys! [& args] (apply p/sh "wtype" "-s" "150" args))
(def cmd-c ["-M" "ctrl" "-k" "Insert" "-m" "ctrl"])
(def cmd-v ["-M" "shift" "-k" "Insert" "-m" "shift"])
(def cmd-x ["-M" "shift" "-k" "Delete" "-m" "shift"])
(def caps-c ["-M" "ctrl" "c" "-m" "ctrl"])

(defn clipboard [] (:out (p/sh "wl-paste" "--no-newline")))
(defn wl-copy! [text] @(p/process ["wl-copy" "--type" "text/plain"] {:in text :out :discard :err :discard}))
(defn token [what] (str what "-" (System/nanoTime)))
(defn clipboard-is [t] (wait-for 3000 #(= t (clipboard))))

;; Each step first focuses another window, as a switch from it would.
(defn switch-to! [app] (focus! "clip.other") (Thread/sleep 150) (focus! app))

;; Chrome: a textarea whose text the title mirrors.
(def page
  (str "data:text/html,"
       (str/replace (java.net.URLEncoder/encode
                     (str "<title>empty</title><textarea id=t autofocus></textarea><script>"
                          "t.oninput=()=>document.title='v:'+t.value;"
                          "window.set=s=>{t.value=s;document.title='v:'+s}</script>")
                     "UTF-8")
                    "+" "%20")))

(defn chrome-argv [profile]
  (let [flags ["--ozone-platform=wayland" (str "--user-data-dir=" profile) "--no-first-run"
               "--no-default-browser-check" "--disable-sync" "--class=clip.chrome" page]]
    (if-let [c (some fs/which (remove nil? [(System/getenv "CHROME") "chromium"]))]
      (into [(str c)] flags)
      (into ["flatpak" "run" "--no-documents-portal" "--socket=wayland" "--nosocket=x11"
             (str "--filesystem=" profile) "com.google.Chrome"] flags))))

(defn chrome-text [] (some-> (some #(when (str/includes? (str (:app_id %)) "chrome") %) (windows)) :name
                             (str/replace #" - Google Chrome$| - Chromium$" "")
                             (as-> s (when (str/starts-with? s "v:") (subs s 2)))))
(defn chrome-app [] (some #(when (str/includes? (str (:app_id %)) "chrome") (:app_id %)) (windows)))

;; Ghostty with the repo's configs. :rex adds the ghostty-rex window's config,
;; whose keybind = clear drops Ghostty's defaults.
(defn ghostty! [class rex? & cmd]
  (apply spawn! (concat ["ghostty" "--gtk-single-instance=false" "--config-default-files=false"
                         (str "--config-file=" root "/desktop/configs/ghostty/config")]
                        (when rex? [(str "--config-file=" root "/desktop/configs/ghostty-rex/ghostty")])
                        [(str "--class=" class) "-e"] cmd)))

(defn emacs! [class rex?]
  (let [server (str tmp "/" class)]
    (spit (str server ".el")
          (str "(load " (pr-str (str root "/desktop/configs/doom/clipboard.el")) ")\n"
               "(setq server-name " (pr-str server) ")\n(server-start)\n(transient-mark-mode 1)\n"))
    (ghostty! class rex? "emacs" "-nw" "-Q" "-l" (str server ".el"))
    server))

(defn elisp [server form]
  (str/trim (:out (p/sh "emacsclient" "-s" server "--eval"
                        (str "(with-selected-window (frame-selected-window (car (filtered-frame-list #'frame-live-p))) " form ")")))))

(def nvim-config
  (let [d (str tmp "/config")]
    (fs/create-dirs d)
    (fs/create-sym-link (str d "/nvim") (str root "/desktop/configs/nvim"))
    d))
(def nvim-sock (str tmp "/nvim.sock"))
(defn nvim-expr [e] (str/trim (:out (p/sh "nvim" "--server" nvim-sock "--remote-expr" e))))
(defn nvim-lines!
  "Normal mode, with TEXT as the buffer's only line."
  [text]
  (p/sh "nvim" "--server" nvim-sock "--remote-send" "<C-\\><C-n>")
  (nvim-expr (str "setline(1, " (pr-str text) ") + deletebufline('', 2, '$') + cursor(1, 1)")))

(def interrupted (str tmp "/interrupted"))

(try
  (let [profile (str tmp "/chrome")]
    (fs/create-dirs profile)
    (ghostty! "clip.other" false "sh" "-c" "echo GHOSTTY-SELECTION; sleep 600")
    (apply spawn! (chrome-argv profile))
    (def emacs (emacs! "clip.emacs" false))
    (def rex-emacs (emacs! "clip.rex" true))
    (swap! procs conj (p/process ["ghostty" "--gtk-single-instance=false" "--config-default-files=false"
                                  (str "--config-file=" root "/desktop/configs/ghostty/config") "--class=clip.nvim"
                                  "-e" "nvim" "-i" "NONE" "--listen" nvim-sock (str tmp "/nvim.txt")]
                       {:extra-env {"XDG_CONFIG_HOME" nvim-config} :out :discard :err :discard}))
    (ghostty! "clip.sigint" false "sh" "-c" (str "trap 'echo INT > " interrupted "; exit' INT; while :; do sleep 0.1; done"))
    (check! "windows opened (Chrome and five Ghostty)"
            (wait-for 60000 #(every? (set (map :app_id (windows))) ["clip.chrome" "clip.other" "clip.emacs" "clip.rex" "clip.nvim" "clip.sigint"]))
            (map :app_id (windows)))
    (check! "Emacs servers answer" (wait-for 15000 #(and (= "t" (elisp emacs "t")) (= "t" (elisp rex-emacs "t")))))
    (check! "Neovim answers" (wait-for 15000 #(= "1" (nvim-expr "1"))))
    (Thread/sleep 1500))

  (let [chrome (chrome-app) t (token "chrome")]
    (switch-to! chrome)
    (keys! t)
    (check! "Chrome takes typing" (wait-for 3000 #(= t (chrome-text))) (chrome-text))
    (switch-to! chrome)
    (apply keys! (concat ["-M" "ctrl" "a" "-m" "ctrl"] cmd-c))
    (check! "Cmd+C in Chrome copies, right after a focus switch" (clipboard-is t) (clipboard))

    (elisp emacs "(progn (erase-buffer) t)")
    (switch-to! "clip.emacs")
    (apply keys! cmd-v)
    (check! "Cmd+V in terminal Emacs pastes Chrome's text" (wait-for 3000 #(= (pr-str t) (elisp emacs "(buffer-substring-no-properties (point-min) (point-max))")))
            (elisp emacs "(buffer-string)")))

  (let [t (token "emacs")]
    (elisp emacs (str "(progn (erase-buffer) (insert " (pr-str t) ") (push-mark (point-min) t t) t)"))
    (switch-to! "clip.emacs")
    (apply keys! cmd-c)
    (check! "Cmd+C in terminal Emacs copies the region" (clipboard-is t) (clipboard))

    (switch-to! (chrome-app))
    (apply keys! (concat ["-M" "ctrl" "a" "-m" "ctrl"] cmd-v))
    (check! "Cmd+V in Chrome pastes Emacs's text" (wait-for 3000 #(= t (chrome-text))) (chrome-text)))

  (let [t (token "cut")]
    (switch-to! (chrome-app))
    (apply keys! (concat ["-M" "ctrl" "a" "-m" "ctrl" t "-M" "ctrl" "a" "-m" "ctrl"] cmd-x))
    (check! "Cmd+X in Chrome cuts" (and (clipboard-is t) (wait-for 3000 #(= "" (chrome-text)))) [(clipboard) (chrome-text)]))

  (let [t (token "rex")]
    (elisp rex-emacs (str "(progn (erase-buffer) (insert " (pr-str t) ") (push-mark (point-min) t t) t)"))
    (switch-to! "clip.rex")
    (apply keys! cmd-c)
    (check! "Cmd+C reaches terminal Emacs through the ghostty-rex window's config" (clipboard-is t) (clipboard)))

  (let [t (token "nvim")]
    (nvim-lines! t)
    (switch-to! "clip.nvim")
    (keys! "V")
    (apply keys! cmd-c)
    (check! "Cmd+C in Neovim copies the visual selection" (clipboard-is (str t "\n")) (clipboard))
    (let [t2 (token "wl")]
      (wl-copy! t2)
      (nvim-lines! "")
      (switch-to! "clip.nvim")
      (apply keys! cmd-v)
      (check! "Cmd+V in Neovim pastes" (wait-for 3000 #(str/includes? (nvim-expr "join(getline(1,'$'))") t2))
              (nvim-expr "join(getline(1,'$'))"))))

  (switch-to! "clip.emacs")
  (focus! "clip.other")
  (apply keys! (concat ["-M" "ctrl" "-M" "shift" "a" "-m" "shift" "-m" "ctrl"] cmd-c))
  (check! "Cmd+C in Ghostty copies Ghostty's own selection"
          (wait-for 3000 #(str/includes? (str (clipboard)) "GHOSTTY-SELECTION")) (clipboard))

  (switch-to! "clip.sigint")
  (apply keys! caps-c)
  (check! "Ctrl+C in a terminal still interrupts the program" (wait-for 3000 #(fs/exists? interrupted)))
  (finally
    (run! #(p/destroy-tree %) @procs)
    (fs/delete-tree tmp {:force true})))

(let [rs @results]
  (println (count (filter true? rs)) "of" (count rs) "passed")
  (shutdown-agents)
  (System/exit (if (every? true? rs) 0 1)))
