;;; clojure.el --- Clojure feature config -*- lexical-binding: t; -*-

;; Register Babashka files before clojure-mode loads. Keeping this inside
;; `after!' creates a cycle: opening a .bb file cannot load clojure-mode until
;; the association exists, but the association would not exist until the mode
;; had already loaded.
(add-to-list 'auto-mode-alist '("\\.bb\\'" . clojure-mode))

;; Corretto 25's Nix package currently ships jspawnhelper without its execute
;; bit. clojure-lsp runs `bb print-deps', whose Clojure subprocess may need to
;; spawn Git, so use Java's legacy fork launcher until the package is fixed.
(let ((fork-option "-Djdk.lang.Process.launchMechanism=FORK"))
  (unless (member fork-option (split-string (or (getenv "JAVA_TOOL_OPTIONS") "")))
    (setenv "JAVA_TOOL_OPTIONS"
            (string-join (delq nil (list (getenv "JAVA_TOOL_OPTIONS") fork-option)) " "))))

(after! clojure-mode
  (setq cider-babashka-command "bb"))

(after! quickrun
  (quickrun-add-command
   "clojure"
   '((:command . "clojure")
     (:exec . ("%c %s"))
     (:description . "Run Clojure with the clojure CLI"))
   :mode '(clojure-mode clojure-ts-mode)))

(defun eng/clojure-project-root ()
  "Return the current Clojure project root or `default-directory`."
  (let ((dir (or (and buffer-file-name (file-name-directory buffer-file-name))
                 default-directory)))
    (or (locate-dominating-file dir "deps.edn")
        (locate-dominating-file dir "bb.edn")
        (and (fboundp 'projectile-project-root)
             (ignore-errors (projectile-project-root)))
        dir)))

(defun eng/clojure-start-repl ()
  "Start a Clojure REPL from the current project root."
  (interactive)
  (let ((default-directory (file-name-as-directory (eng/clojure-project-root))))
    (cond
     ((and buffer-file-name (string-match-p "\\.bb\\'" buffer-file-name)
           (fboundp 'cider-jack-in-clj))
      (let ((cider-preferred-build-tool 'babashka))
        (call-interactively #'cider-jack-in-clj)))
     ((fboundp 'cider-jack-in-clj)
      (call-interactively #'cider-jack-in-clj))
     ((fboundp 'cider-jack-in)
      (call-interactively #'cider-jack-in))
     (t
      (user-error "CIDER is not available")))))
