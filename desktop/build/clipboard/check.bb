#!/usr/bin/env bb
;; Checks that Emacs and Neovim share the Wayland clipboard both ways, also when
;; started without WAYLAND_DISPLAY, as the shepherd-run Emacs daemons are.
;; Each case yanks a fresh token in the editor and compares it with wl-paste,
;; then copies a token with wl-copy and reads it back in the editor.
;; Usage: bb desktop/build/clipboard/check.bb [--daemon NAME]...
;; The user's clipboard text is restored at the end.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))))
(def runtime-dir (or (System/getenv "XDG_RUNTIME_DIR") (str "/run/user/" (str/trim (:out (p/sh "id" "-u"))))))
(def display
  (or (System/getenv "WAYLAND_DISPLAY")
      (some->> (fs/list-dir runtime-dir) (map fs/file-name)
               (filter #(re-matches #"wayland-\d+" %)) sort first)
      (do (println "no Wayland socket in" runtime-dir) (System/exit 2))))

(def wl-env (assoc (into {} (System/getenv)) "WAYLAND_DISPLAY" display))
(def bare-env (dissoc (into {} (System/getenv)) "WAYLAND_DISPLAY"))

(defn wl-paste [] (:out (p/sh {:env wl-env} "wl-paste" "--no-newline")))
;; wl-copy forks a server that keeps the clipboard; detach it from our pipes.
(defn wl-copy [text]
  @(p/process {:env wl-env :in text :out :discard :err :discard} "wl-copy" "--type" "text/plain"))
(defn token [what] (str "clip-" what "-" (System/nanoTime)))

(def saved (wl-paste))
(def results (atom []))
(defn settle
  "Editors hand text to wl-copy without waiting, so poll a little."
  [expected actual]
  (if (fn? actual)
    (loop [n 0]
      (let [v (actual)]
        (if (or (= v expected) (>= n 30)) v (do (Thread/sleep 100) (recur (inc n))))))
    actual))

(defn check! [name expected actual]
  (let [actual (settle expected actual)
        ok (= expected actual)]
    (swap! results conj ok)
    (println (if ok "PASS" "FAIL") name (if ok "" (pr-str {:expected expected :actual actual})))))

(defn elisp-str [s] (pr-str s))

;; Emacs --batch with the Doom clipboard settings, as a daemon without
;; WAYLAND_DISPLAY and as a session frame with it.
(defn emacs-batch [env form]
  (-> (p/sh {:env env} "emacs" "--batch" "-Q" "-l" (str root "/desktop/configs/doom/clipboard.el")
            "--eval" (str "(princ (format \"%s\" " form "))"))
      :out))

(doseq [[label env] [["no WAYLAND_DISPLAY" bare-env] ["WAYLAND_DISPLAY set" wl-env]]]
  (let [t (token "emacs")]
    (emacs-batch env (str "(progn (kill-new " (elisp-str t) ") t)"))
    (check! (str "emacs --batch yank -> wl-paste (" label ")") t wl-paste))
  (let [t (token "wl")]
    (wl-copy t)
    (check! (str "wl-copy -> emacs --batch paste (" label ")") t
            (emacs-batch env "(current-kill 0)"))))

;; Running Emacs daemons, e.g. bbh-super, through emacsclient.
(defn daemon-names []
  (let [args (vec *command-line-args*)
        named (keep-indexed (fn [i a] (when (= a "--daemon") (get args (inc i)))) args)]
    (if (seq named)
      named
      (->> (fs/list-dir (str runtime-dir "/emacs")) (map fs/file-name)))))

(doseq [d (daemon-names)]
  (let [ec (fn [form] (str/trim (:out (p/sh {:env bare-env} "emacsclient" "-s" d "--eval" form))))
        t (token "daemon")]
    (ec (str "(progn (kill-new " (elisp-str t) ") t)"))
    (check! (str "emacsclient -s " d " yank -> wl-paste") t wl-paste)
    (let [t (token "wl")]
      (wl-copy t)
      (check! (str "wl-copy -> emacsclient -s " d " paste") (pr-str t) (ec "(current-kill 0)")))))

;; Neovim reads this checkout's config, not the stowed one.
(def nvim-config-home
  (let [d (str (fs/create-temp-dir))]
    (fs/create-sym-link (str d "/nvim") (str root "/desktop/configs/nvim"))
    (fs/delete-on-exit d)
    d))

;; Neovim with the user config in a pseudo-terminal, so LazyVim's deferred
;; clipboard option is in effect. `yy` must reach wl-paste and `p` must read it.
(defn nvim-tty [env lua]
  (let [script (str (fs/create-temp-file {:suffix ".lua"}))
        out (str (fs/create-temp-file {:suffix ".out"}))]
    (spit script (str "vim.defer_fn(function() local ok, r = pcall(function() " lua " end) "
                      "vim.fn.writefile({tostring(r)}, " (pr-str out) ") vim.cmd('qa!') end, 1500)"))
    (p/sh {:env (assoc env "TERM" "xterm-256color" "XDG_CONFIG_HOME" nvim-config-home)}
          "timeout" "20" "script" "-q" "-c" (str "nvim -i NONE -S " script) "/dev/null")
    (let [r (str/trim-newline (slurp out))] (fs/delete script) (fs/delete out) r)))

(doseq [[label env] [["no WAYLAND_DISPLAY" bare-env] ["WAYLAND_DISPLAY set" wl-env]]]
  (let [t (token "nvim")]
    (nvim-tty env (str "vim.cmd('tabnew') vim.fn.setline(1, " (pr-str t) ") vim.cmd('normal! 0y$') return vim.o.clipboard"))
    (check! (str "nvim yank -> wl-paste (" label ")") t wl-paste))
  (let [t (token "wl")]
    (wl-copy t)
    (check! (str "wl-copy -> nvim paste (" label ")") t
            (nvim-tty env "vim.cmd('tabnew') vim.cmd('normal! p') return vim.fn.getline(1)"))))

(when (seq saved) (wl-copy saved))
(let [rs @results]
  (println (count (filter true? rs)) "of" (count rs) "passed")
  (System/exit (if (every? true? rs) 0 1)))
