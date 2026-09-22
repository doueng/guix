;;; keybindings.el --- Doom keybindings -*- lexical-binding: t; -*-

;; Global leader bindings
(map! :leader
      :desc "Toggle Treemacs" "e" #'treemacs)

(defun eng/lsp-rename ()
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (call-interactively #'lsp-rename)
    (user-error "No LSP rename backend available")))

(defun eng/lsp-find-definition ()
  (interactive)
  (if (bound-and-true-p lsp-mode)
      (call-interactively #'lsp-find-definition)
    (call-interactively #'xref-find-definitions)))

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

;; Better window navigation (similar to Neovim's Ctrl+hjkl)
(map! :map general-override-mode-map
      :desc "Window left"  "C-h" #'evil-window-left
      :desc "Window down"  "C-j" #'evil-window-down
      :desc "Window up"    "C-k" #'evil-window-up
      :desc "Window right" "C-l" #'evil-window-right)

;; File operations
(map! :leader
      :desc "Find file"             "f f" #'find-file
      :desc "Recent files"          "f r" #'recentf-open-files
      :desc "Save file"             "f s" #'save-buffer
      :desc "Save all"              "f S" #'evil-write-all)

;; Search operations (similar to Telescope in Neovim)
(map! :leader
      :desc "Search project"        "s p" #'+default/search-project
      :desc "Search buffer"         "s b" #'consult-line
      :desc "Search files"          "s f" #'consult-fd
      :desc "Search all"            "s a" #'+default/search-project-for-symbol-at-point)

;; Buffer operations
(map! :leader
      :desc "List buffers"          "b b" #'switch-to-buffer
      :desc "Kill buffer"           "b d" #'kill-current-buffer
      :desc "Next buffer"           "b n" #'next-buffer
      :desc "Previous buffer"       "b p" #'previous-buffer
      :desc "Kill all buffers"      "b D" #'doom/kill-all-buffers)

;; GitHub
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

;; Code operations
(map! :leader
      :desc "Format buffer"         "c f" #'+format/buffer
      :desc "LSP rename"            "c r" #'eng/lsp-rename
      :desc "LSP find definition"   "c d" #'eng/lsp-find-definition
      :desc "LSP find references"   "c R" #'eng/lsp-find-references
      :desc "LSP code actions"      "c a" #'eng/lsp-code-actions
      :desc "LSP diagnostics"       "c x" #'eng/lsp-diagnostics
      :desc "Comment line"          "c /" #'comment-line)

(map! :leader
      :prefix ("c u" . "uLSP")
      :desc "Show query scope"      "q" #'eng/go-code-show-query-scope)

;; Go (lsp-mode)
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

;; Project operations
(map! :leader
      :desc "Open project"          "p p" #'projectile-switch-project
      :desc "Find file in project"  "." #'eng/project-find-file
      :desc "Search in project"     "p s" #'projectile-grep
      :desc "Recent project files"  "p r" #'projectile-recentf)

;; Toggle operations
(map! :leader
      :desc "Toggle treemacs"       "e" #'treemacs
      :desc "Toggle line numbers"   "t l" #'doom/toggle-line-numbers
      :desc "Toggle word wrap"      "t w" #'visual-line-mode
      :desc "Toggle terminal"       "t T" #'+vterm/toggle)

;; Window operations
(map! :leader
      :desc "Split window below"    "w s" #'split-window-below
      :desc "Split window right"    "w v" #'split-window-right
      :desc "Delete window"         "w d" #'delete-window
      :desc "Delete other windows"  "w o" #'delete-other-windows
      :desc "Balance windows"       "w =" #'balance-windows)

;; Quick access
(map! :leader
      :desc "M-x"                   "SPC" #'execute-extended-command
      :desc "Reload config"         "h r r" #'eng/reload-config
      :desc "Eval expression"       ";"   #'eval-expression)

;; Better movement in insert mode (similar to Neovim)
(map! :i "C-h" #'backward-delete-char
      :i "C-l" #'delete-char
      :i "C-a" #'beginning-of-line
      :i "C-e" #'end-of-line)

;; Better text object selection
(map! :v ">" #'evil-shift-right
      :v "<" #'evil-shift-left)

;; Better undo/redo
(map! :n "u"   #'undo-fu-only-undo
      :n "C-r" #'undo-fu-only-redo)

;; Jump (avy)
(map! :nvo "s" #'evil-avy-goto-char-2-below
      :nvo "S" #'evil-avy-goto-char-2-above)

;; Save file shortcuts
(map! "C-s" #'save-buffer)

;; Clipboard operations (ensure system clipboard integration)
(map! :v "C-c" #'clipboard-kill-ring-save
      :ign "C-v" #'clipboard-yank)

;; Quick quit
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
        :desc "Start REPL" "r J" #'eng/clojure-start-repl
        (:prefix ("b" . "Babashka/Nix")
         :desc "Generate Nix" "g" #'eng/bb-generate-nix
         :desc "Check generated Nix" "c" #'eng/bb-check-generated
         :desc "Validate DSL" "v" #'eng/bb-validate
         :desc "Run DSL tests" "t" #'eng/bb-test))

  (when (boundp 'clojure-ts-mode-map)
    (map! :map clojure-ts-mode-map
          :localleader
          :desc "Start REPL" "r J" #'eng/clojure-start-repl
          (:prefix ("b" . "Babashka/Nix")
           :desc "Generate Nix" "g" #'eng/bb-generate-nix
           :desc "Check generated Nix" "c" #'eng/bb-check-generated
           :desc "Validate DSL" "v" #'eng/bb-validate
           :desc "Run DSL tests" "t" #'eng/bb-test))))
