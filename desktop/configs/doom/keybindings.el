;;; keybindings.el --- Doom keybindings -*- lexical-binding: t; -*-


(load! "key-coach")
(eng/key-coach-mode 1)
(after! which-key
  (setq which-key-idle-delay 0.35))
(map! :leader
      :desc "Toggle motion coaching" "t h" #'eng/key-coach-mode
      :desc "Motion coaching report" "t H" #'eng/key-coach-report)

(defun eng/lsp-rename ()
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (call-interactively #'lsp-rename)
    (user-error "No LSP rename backend available")))

(defun eng/scheme-guix-record-definition ()
  "Jump to Guix's generated PACKAGE or ORIGIN record constructor."
  (let* ((symbol (thing-at-point 'symbol t))
         (source (locate-file "guix/packages.scm"
                              (split-string (or (getenv "GUILE_LOAD_PATH") "")
                                            path-separator t)))
         (pattern (cdr (assoc symbol
                              '(("package" . "^[[:space:]]+package make-package")
                                ("origin" . "^[[:space:]]+%origin make-origin"))))))
    (when (and source pattern)
      (find-file source)
      (goto-char (point-min))
      (when (re-search-forward pattern nil t)
        (beginning-of-line)
        t))))

(defun eng/scheme-find-definition ()
  "Find the Scheme definition at point using Guix source or Geiser."
  (interactive)
  (unless (eng/scheme-guix-record-definition)
    (require 'geiser)
    (require 'geiser-guile)
    (setq geiser-default-implementation 'guile
          geiser-mode-start-repl-p t)
    (unless (bound-and-true-p geiser-mode)
      (geiser-mode 1))
    (call-interactively #'geiser-edit-symbol-at-point)))

(defun eng/lsp-find-definition ()
  (interactive)
  (cond
   ((bound-and-true-p lsp-mode)
    (call-interactively #'lsp-find-definition))
   ((derived-mode-p 'scheme-mode)
    (call-interactively #'eng/scheme-find-definition))
   (t
    (call-interactively #'xref-find-definitions))))

(with-eval-after-load 'scheme
  (define-key scheme-mode-map (kbd "M-.") #'eng/scheme-find-definition)
  (map! :map scheme-mode-map :n "gd" #'eng/scheme-find-definition))

(defun eng/lsp-find-references ()
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (call-interactively #'lsp-find-references)
    (call-interactively #'xref-find-references)))

(defun eng/lsp-code-actions ()
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (call-interactively #'lsp-execute-code-action)
    (user-error "No LSP code-action backend available")))

(defun eng/lsp-diagnostics ()
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (call-interactively #'flycheck-list-errors)
    (call-interactively #'flymake-show-buffer-diagnostics)))

(load! "key-navigate")
(load! "palette")
(map! :map general-override-mode-map
      :desc "Focus left"  "C-h" #'eng/focus-left
      :desc "Focus down"  "C-j" #'eng/focus-down
      :desc "Focus up"    "C-k" #'eng/focus-up
      :desc "Focus right" "C-l" #'eng/focus-right)

(map! :leader
      :desc "Find file"             "f f" #'find-file
      :desc "Recent files"          "f r" #'recentf-open-files
      :desc "Save file"             "f s" #'save-buffer
      :desc "Save all"              "f S" #'evil-write-all)

(map! :leader
      :desc "Search project"        "s p" #'+default/search-project
      :desc "Search buffer"         "s b" #'consult-line
      :desc "Search files"          "s f" #'consult-fd
      :desc "Search all"            "s a" #'+default/search-project-for-symbol-at-point)

(map! :leader
      :desc "List buffers"          "b b" #'switch-to-buffer
      :desc "Kill buffer"           "b d" #'kill-current-buffer
      :desc "Next buffer"           "b n" #'next-buffer
      :desc "Previous buffer"       "b p" #'previous-buffer
      :desc "Kill all buffers"      "b D" #'doom/kill-all-buffers)

(map! :leader
      (:prefix ("G" . "GitHub")
       :desc "GitHub menu"         "g" #'consult-gh-transient
       :desc "Dashboard"           "d" #'consult-gh-dashboard
       :desc "Notifications"       "n" #'consult-gh-notifications
       :desc "Search repos"        "r" #'consult-gh-search-repos
       :desc "Search issues"       "i" #'consult-gh-search-issues
       :desc "Search PRs"          "p" #'consult-gh-search-prs
       :desc "Search code"         "c" #'consult-gh-search-code
       :desc "List PRs"            "l" #'consult-gh-pr-list))

(map! :leader
      :desc "Format buffer"         "c f" #'+format/buffer
      :desc "LSP rename"            "c r" #'eng/lsp-rename
      :desc "LSP find definition"   "c d" #'eng/lsp-find-definition
      :desc "LSP code actions"      "c a" #'eng/lsp-code-actions
      :desc "LSP diagnostics"       "c x" #'eng/lsp-diagnostics
      :desc "Comment line"          "c /" #'comment-line)

;; Match idea-doom-emacs where Doom's defaults differ. Git hunks stay on
;; SPC g [ and SPC g ] in these buffers.
(map! :after lsp-mode
      :map lsp-mode-map
      :n "gi"    #'lsp-find-implementation
      :n "[d"    #'flycheck-previous-error
      :n "]d"    #'flycheck-next-error
      :n "C-w d" #'flycheck-display-error-at-point
      :n "C-w C-d" #'flycheck-display-error-at-point)

(after! go-mode
  (map! :map go-mode-map
        :localleader
        :desc "Find definition"       "d" #'eng/lsp-find-definition
        :desc "Find references"       "r" #'eng/lsp-find-references
        :desc "Hover"                 "h" #'lsp-describe-thing-at-point
        :desc "Rename"                "R" #'eng/lsp-rename
        :desc "Code action"           "a" #'eng/lsp-code-actions
        :desc "Format (LSP)"          "=" #'+format/buffer
        :desc "Find implementation"   "i" #'lsp-find-implementation
        :desc "Diagnostics"           "x" #'eng/lsp-diagnostics)

  (when (boundp 'go-ts-mode-map)
    (map! :map go-ts-mode-map
          :localleader
          :desc "Find definition"       "d" #'eng/lsp-find-definition
          :desc "Find references"       "r" #'eng/lsp-find-references
          :desc "Hover"                 "h" #'lsp-describe-thing-at-point
          :desc "Rename"                "R" #'eng/lsp-rename
          :desc "Code action"           "a" #'eng/lsp-code-actions
          :desc "Format (LSP)"          "=" #'+format/buffer
          :desc "Find implementation"   "i" #'lsp-find-implementation
          :desc "Diagnostics"           "x" #'eng/lsp-diagnostics)))

(map! :leader
      :desc "Open project"          "p p" #'projectile-switch-project
      :desc "Search in project"     "p s" #'projectile-grep
      :desc "Recent project files"  "p r" #'projectile-recentf)

(map! :leader
      :desc "Toggle treemacs"       "e" #'+treemacs/toggle
      :desc "Toggle line numbers"   "t l" #'doom/toggle-line-numbers
      :desc "Toggle word wrap"      "t w" #'visual-line-mode)

(map! :leader
      :desc "Split window below"    "w s" #'split-window-below
      :desc "Split window right"    "w v" #'split-window-right
      :desc "Delete window"         "w d" #'delete-window
      :desc "Delete other windows"  "w o" #'delete-other-windows
      :desc "Balance windows"       "w =" #'balance-windows)

(map! :leader
      :desc "M-x"                   "SPC" #'execute-extended-command
      :desc "Reload config"         "h r r" #'eng/reload-config
      :desc "Eval expression"       ";"   #'eval-expression)

;; Insert state keeps C-h and C-l for editing, but C-j and C-k still move
;; focus. Evil's own insert C-k (digraph) would shadow the override map.
(map! :i "C-h" #'backward-delete-char
      :i "C-l" #'delete-char
      :i "C-j" #'eng/focus-down
      :i "C-k" #'eng/focus-up
      :i "C-a" #'beginning-of-line
      :i "C-e" #'end-of-line)

(map! :v ">" #'evil-shift-right
      :v "<" #'evil-shift-left)

(map! :n "u"   #'undo-fu-only-undo
      :n "C-r" #'undo-fu-only-redo)

(map! :nvo "s" #'evil-avy-goto-char-2-below
      :nvo "S" #'evil-avy-goto-char-2-above)

(map! "C-s" #'save-buffer)

(map! :v "C-c" #'clipboard-kill-ring-save
      :ign "C-v" #'clipboard-yank)

(map! :leader
      :desc "Quit Emacs"            "q q" #'save-buffers-kill-terminal
      :desc "Quit without saving"   "q Q" #'evil-quit-all-with-error-code)

(after! treemacs
  (map! :map treemacs-mode-map
        :m "x" #'treemacs-delete-file
        :m "d" nil))

(after! clojure-mode
  (map! :map clojure-mode-map
        :localleader
        :desc "Start REPL" "r J" #'eng/clojure-start-repl)

  (when (boundp 'clojure-ts-mode-map)
    (map! :map clojure-ts-mode-map
          :localleader
          :desc "Start REPL" "r J" #'eng/clojure-start-repl)))

(load! "shared-keys")
