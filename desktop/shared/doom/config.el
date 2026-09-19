;;; config.el --- Doom Emacs configuration -*- lexical-binding: t; -*-

;; Personal Information
(setq user-full-name "Douglas Engstrand"
      user-mail-address "doueng09@gmail.com"
      ;; Emacs subprocess helpers require a POSIX shell even though the
      ;; interactive terminal shell remains Fish.
      shell-file-name (or (executable-find "bash") "/bin/sh"))

(let ((interactive-shell (or (executable-find "fish") shell-file-name)))
  (setq-default explicit-shell-file-name interactive-shell
                vterm-shell interactive-shell))

;; Doom 2.2.3's Nix doctor checks this before loading nix-format.el.
;; Remove once upstream requires nix-format before checking the executable.
(defvar nix-nixfmt-bin "nixfmt")

(defvar eng/doom-config-dir
  (file-name-directory
   (or (and (boundp 'load-file-name) load-file-name)
       (expand-file-name "config.el" (or (getenv "DOOMDIR") "~/.config/doom"))))
  "Mutable Doom config directory in the Guix checkout.")

(defun eng/reload-config ()
  "Reload the mutable config from `eng/doom-config-dir`.
This is a lightweight way to apply config changes without restarting Emacs."
  (interactive)
  (when (fboundp 'general-auto-unbind-keys)
    (condition-case err
        (general-auto-unbind-keys)
      (error (message "general-auto-unbind-keys failed: %s" (error-message-string err)))))
  (load (expand-file-name "config.el" eng/doom-config-dir) t 'nomessage)
  (when (fboundp 'general-auto-unbind-keys)
    (condition-case err
        (general-auto-unbind-keys t)
      (error (message "general-auto-unbind-keys (post) failed: %s" (error-message-string err)))))
  (message "Reloaded %s" (abbreviate-file-name eng/doom-config-dir)))

(setq frame-title-format "MAIN EMACS"
      ;; Doom's TTY module otherwise recomputes the terminal title after every
      ;; command. Keep the stable title above without paying that hot-path cost.
      xterm-set-window-title nil)

;; Keep Emacs frames free of menu, tool, and scroll bars.
(dolist (mode '(menu-bar-mode tool-bar-mode scroll-bar-mode))
  (when (fboundp mode)
    (funcall mode -1)))
(dolist (parameter '((menu-bar-lines . 0) (tool-bar-lines . 0)))
  (dolist (alist '(default-frame-alist initial-frame-alist))
    (set alist (cons parameter
                     (assq-delete-all (car parameter) (symbol-value alist))))))

;; Ensure terminal mouse support remains enabled.
(defun eng/enable-tty-mouse ()
  (unless (display-graphic-p)
    (xterm-mouse-mode 1)))
(dolist (hook '(tty-setup-hook window-setup-hook))
  (add-hook hook #'eng/enable-tty-mouse))

;; `evil-terminal-cursor-changer' normally emits a terminal escape sequence in
;; both pre- and post-command hooks. Preserve the useful Evil state cursor
;; shapes, but only write to Ghostty when the shape, blink mode, or color changes.
(defvar eng/etcc--cursor-state (make-hash-table :test #'eq :weakness 'key))

(defun eng/etcc--set-cursor-when-changed (fn &rest args)
  "Call FN only when the selected frame's terminal cursor state changed."
  (let* ((frame (selected-frame))
         (blink (and etcc-use-blink (bound-and-true-p blink-cursor-mode)))
         (color (and etcc-use-color (frame-parameter frame 'cursor-color)))
         (cached (gethash frame eng/etcc--cursor-state)))
    (unless (and cached
                 (equal cursor-type (nth 0 cached))
                 (eq blink (nth 1 cached))
                 (equal color (nth 2 cached)))
      (puthash frame (list (copy-tree cursor-type) blink color)
               eng/etcc--cursor-state)
      (apply fn args))))

(after! evil-terminal-cursor-changer
  (advice-remove #'etcc--set-cursor #'eng/etcc--set-cursor-when-changed)
  (clrhash eng/etcc--cursor-state)
  (advice-add #'etcc--set-cursor :around #'eng/etcc--set-cursor-when-changed)
  (etcc--set-cursor))

;; Theme (matching Neovim doom-one theme)
(setq doom-theme 'doom-one)

;; Line numbers (disabled like in Neovim config)
(setq display-line-numbers-type nil)

(after! doom-modeline
  (setq doom-modeline-highlight-modified-buffer-name nil
        doom-modeline-buffer-modification-icon nil
        doom-modeline-buffer-state-icon nil)
  (when (facep 'doom-modeline-buffer-modified)
    (set-face-attribute 'doom-modeline-buffer-modified nil :inherit 'doom-modeline-buffer-file))
  (force-mode-line-update t))

;; Autosave configuration - save actual files to disk
(setq auto-save-default nil ; Disable default autosave to #file#
      make-backup-files nil ; Disable backup files
      file-precious-flag t) ; Avoid in-place truncation (safer for concurrent readers)

;; Auto-save the *real* file on idle using Emacs' built-in timer.
(auto-save-visited-mode 1)

;; Auto-revert buffers when files change on disk
(global-auto-revert-mode 1)

;; Disable pairing/matching helpers globally.
(electric-pair-mode -1)
(show-paren-mode -1)
(setq blink-matching-paren nil)

(defun eng/disable-delimiter-helpers ()
  "Disable delimiter pairing helpers in the current buffer."
  (when (bound-and-true-p electric-pair-local-mode)
    (electric-pair-local-mode -1))
  (when (bound-and-true-p smartparens-mode)
    (smartparens-mode -1))
  (when (bound-and-true-p smartparens-strict-mode)
    (smartparens-strict-mode -1)))

(add-hook 'after-change-major-mode-hook #'eng/disable-delimiter-helpers)

(with-eval-after-load 'smartparens
  (when (fboundp 'smartparens-global-mode)
    (smartparens-global-mode -1))
  (remove-hook 'doom-first-buffer-hook #'smartparens-global-mode)
  (remove-hook 'prog-mode-hook #'smartparens-mode)
  (remove-hook 'text-mode-hook #'smartparens-mode)
  (remove-hook 'minibuffer-setup-hook #'turn-on-smartparens-strict-mode))

(dolist (buf (buffer-list))
  (with-current-buffer buf
    (eng/disable-delimiter-helpers)))

;; Save place in files (remember cursor position)
(save-place-mode 1)
(add-hook 'save-place-after-find-file-hook
          (lambda ()
            (when buffer-file-name
              (ignore-errors (recenter)))))

(let ((custom-path (expand-file-name "custom.el" (or (bound-and-true-p doom-cache-dir) user-emacs-directory))))
  (setq custom-file custom-path)
  (load custom-file t 'nomessage))

(defun copy-file-path ()
  "Copy the current buffer's file path to clipboard."
  (interactive)
  (when buffer-file-name
    (kill-new buffer-file-name)
    (message "Copied: %s" buffer-file-name)))

;; Editor behavior (similar to options.lua)
(setq-default
 bidi-display-reordering 'left-to-right           ; Faster redisplay when not editing RTL text
 bidi-paragraph-direction 'left-to-right
 cursor-in-non-selected-windows nil
 delete-by-moving-to-trash t                      ; Delete files to trash
 window-combination-resize t                      ; take new window space from all other windows (not just current)
 x-stretch-cursor t)                              ; Stretch cursor to the glyph width

(setq bidi-inhibit-bpa t
      highlight-nonselected-windows nil
      undo-limit 80000000                         ; Raise undo-limit to 80Mb
      evil-want-fine-undo t                       ; By default while in insert all changes are one big blob. Be more granular
      truncate-string-ellipsis "…"                ; Unicode ellispis are nicer than "...", and also save /precious/ space
      password-cache-expiry nil                   ; I can trust my computers ... can't I?
      scroll-preserve-screen-position 'always     ; Don't have `point' jump around
      scroll-margin 2                             ; It's nice to maintain a little margin
      display-time-default-load-average nil       ; I don't think I've ever found this useful
      ;; Keep newly displayed text fully fontified while scrolling. This trades
      ;; some peak scroll throughput for immediate, stable syntax colors.
      redisplay-skip-fontification-on-input nil
      ;; Avoid a terminal clear-and-redraw when recentering with C-l.
      recenter-redisplay nil
      reb-re-syntax 'string
      ffap-machine-p-known 'reject
      set-mark-command-repeat-pop t
      help-window-select t)

;; Doom binds `s`/`S` to `evil-snipe` by default. We want `s`/`S` for avy-style
;; jumping instead, so disable evil-snipe auto-enablement (per Doom docs).
(remove-hook 'doom-first-input-hook #'evil-snipe-mode)

;; Clipboard integration (like Neovim unnamedplus)
(setq select-enable-clipboard t
      select-enable-primary t
      save-interprogram-paste-before-kill t
      kill-do-not-save-duplicates t
      yank-pop-change-selection t)

(setq savehist-additional-variables '(search-ring regexp-search-ring kill-ring))
(savehist-mode 1)
(add-hook 'savehist-save-hook
          (lambda ()
            (setq kill-ring
                  (mapcar #'substring-no-properties
                          (cl-remove-if-not #'stringp kill-ring)))))

;; Shared clipboard integration with Neovim.
(defun eng/system-clipboard-copy (text &optional _push)
  "Copy TEXT to the Wayland clipboard."
  (when (and (stringp text) (not (string-empty-p text)))
    (with-temp-buffer
      (insert text)
      (call-process-region (point-min) (point-max) "wl-copy" nil 0 nil
                           "--type" "text/plain"))))

(defun eng/system-clipboard-paste ()
  "Read text from the Wayland clipboard."
  (with-temp-buffer
    (when (zerop (call-process "wl-paste" nil t nil "--no-newline"))
      (buffer-string))))

(setq interprogram-cut-function #'eng/system-clipboard-copy
      interprogram-paste-function #'eng/system-clipboard-paste)

;; Search behavior (case insensitive like Neovim)
(setq-default search-upper-case t)  ; If there's upper case, be case sensitive

;; Indentation (matching Neovim config: 2 spaces, expandtab)
(setq-default
 tab-width 2
 indent-tabs-mode nil)  ; Use spaces instead of tabs

;; Enable evil mode globally (Vim keybindings)
(setq evil-want-C-u-scroll t
      evil-want-C-d-scroll t
      evil-respect-visual-line-mode t
      evil-undo-system 'undo-redo)

;; Performance optimizations
(setq read-process-output-max (* 4 1024 1024) ; 4 MiB for language servers
      ;; The local Ghostty PTY reports 9600 baud, which makes Emacs favor
      ;; insert/delete-line terminal operations. Those expose Ghostty's darker
      ;; default background during rapid scrolling. A realistic local-terminal
      ;; rate makes Emacs repaint rows instead; this also emitted fewer bytes in
      ;; the C-u/C-d trace.
      baud-rate 1000000
      ;; Never show temporarily unfontified text while scrolling.
      fast-but-imprecise-scrolling nil
      jit-lock-defer-time nil)

(after! flycheck
  ;; Don't run syntax checks from save; explicit/manual or idle checks are enough.
  (setq flycheck-check-syntax-automatically '(mode-enabled idle-change)))

;; Disable line highlighting completely to prevent rendering artifacts. Doom can
;; enable the globalized mode late during startup, so enforce this after startup
;; as well as after each major-mode change.
(defun eng/disable-hl-line-locally ()
  "Disable `hl-line-mode' in the current buffer."
  (when (bound-and-true-p hl-line-mode)
    (hl-line-mode -1)))

(defun eng/disable-hl-line ()
  "Disable global and buffer-local current-line highlighting."
  (when (bound-and-true-p global-hl-line-mode)
    (global-hl-line-mode -1))
  (dolist (buffer (buffer-list))
    (with-current-buffer buffer
      (eng/disable-hl-line-locally))))

(remove-hook 'doom-first-buffer-hook #'global-hl-line-mode)
(remove-hook 'prog-mode-hook #'hl-line-mode)
(remove-hook 'text-mode-hook #'hl-line-mode)
(add-hook 'after-change-major-mode-hook #'eng/disable-hl-line-locally 100)
(dolist (hook '(after-init-hook doom-after-init-hook doom-first-buffer-hook
                                window-setup-hook))
  (add-hook hook #'eng/disable-hl-line 100))
(eng/disable-hl-line)

(use-package! fish-mode
  :mode ("\\.fish\\'" . fish-mode))

;; Evil jumplist navigation (normal state).
(with-eval-after-load 'evil
  (define-key evil-normal-state-map (kbd "C-o") #'evil-jump-backward)
  (define-key evil-normal-state-map (kbd "C-i") #'evil-jump-forward))

(defun eng--set-process-environment-var (name value)
  (setq-local process-environment
              (cons (concat name "=" value)
                    (seq-remove
                     (lambda (entry)
                       (string-prefix-p (concat name "=") entry))
                     process-environment))))

;; Doom LSP using lsp-mode with nixd, fish-lsp, gopls.
(after! lsp-mode
  (setq lsp-use-plists t
        lsp-idle-delay 0.3
        lsp-enable-file-watchers nil
        lsp-format-buffer-on-save nil
        lsp-fix-all-on-save nil
        lsp-disabled-clients '(deno-ls golangci-lint nix-nil rnix-lsp semgrep-ls)
        lsp-headerline-breadcrumb-enable nil
        lsp-lens-enable nil
        lsp-modeline-code-actions-enable nil
        lsp-modeline-diagnostics-enable nil
        lsp-enable-symbol-highlighting nil
        lsp-enable-folding nil
        lsp-enable-text-document-color nil
        lsp-javascript-typescript-server "typescript-language-server"
        lsp-nix-nixd-server-path "nixd"
        lsp-go-gopls-server-path "gopls"
        lsp-go-gopls-server-args '("-remote=auto")
        lsp-go-use-gofumpt t
        lsp-go-use-placeholders t
        lsp-go-complete-unimported t
        lsp-go-staticcheck t
        lsp-go-directory-filters ["-.git"
                                  "-.direnv"
                                  "-.scip"
                                  "-bazel-bin"
                                  "-bazel-out"
                                  "-bazel-testlogs"
                                  "-node_modules"
                                  "-vendor"])

  ;; lsp-mode computes recursive per-directory diagnostic totals for its
  ;; headerline and Dired integrations on every publishDiagnostics message.
  ;; The headerline is disabled and lsp-dired is unused here. Keep Flycheck
  ;; buffer diagnostics while skipping this otherwise unused aggregation work.
  (defun eng/lsp--skip-unused-diagnostic-aggregates (&rest _args)
    nil)
  (advice-remove #'lsp--on-diagnostics-update-stats
                 #'eng/lsp--skip-unused-diagnostic-aggregates)
  (advice-add #'lsp--on-diagnostics-update-stats :override
              #'eng/lsp--skip-unused-diagnostic-aggregates)

  (unless (gethash 'fish-lsp lsp-clients)
    (lsp-register-client
     (make-lsp-client :new-connection (lsp-stdio-connection '("fish-lsp" "start"))
                      :major-modes '(fish-mode)
                      :server-id 'fish-lsp)))

  (add-hook 'nix-mode-hook #'lsp-deferred)
  (add-hook 'fish-mode-hook #'lsp-deferred))

(after! lsp-ui
  (setq lsp-ui-doc-enable nil
        lsp-ui-sideline-enable nil))

(after! projectile
  (setq projectile-indexing-method 'alien
        projectile-enable-caching t
        projectile-sort-order 'recentf
        projectile-project-search-path '(("~/Uber/" . 1)
                                         ("~/src/" . 2)
                                         ("~/code/" . 2)
                                         ("~/exercism/" . 2)))

  (dolist (file '("deps.edn" "bb.edn" "project.clj" ".projectile"))
    (add-to-list 'projectile-project-root-files-bottom-up file))

  (add-to-list 'projectile-project-root-files-bottom-up ".ijwb/.bazelproject")

  ;; Avoid traversing huge generated trees.
  (dolist (dir '(".git" ".direnv" ".cache" ".ccls-cache" ".venv"
                 "bazel-bin" "bazel-out" "bazel-testlogs"
                 ".scip" "node_modules" "vendor"))
    (add-to-list 'projectile-globally-ignored-directories dir))

  ;; Use fd if available (much faster than find).
  (when-let ((fd (executable-find "fd")))
    (setq projectile-generic-command
          (string-join
           (list fd
                 "--hidden"
                 "--type" "f"
                 "--color=never"
                 "--exclude" ".git"
                 "--exclude" ".direnv"
                 "--exclude" "bazel-*"
                 "--exclude" "bazel-out"
                 "--exclude" "node_modules"
                 "--exclude" ".scip"
                 ".")
           " "))))

(defun eng/open-project-startup-layout (&optional file)
  "Open FILE and show the project tree.
This is intended for project-specific launcher commands, not as a global
Emacs startup hook.  FILE may be omitted when Emacs
was already started with a file argument."
  (interactive)
  (when (require 'projectile nil t)
    (projectile-mode +1))
  (when-let ((startup-file (and file (expand-file-name file))))
    (when (file-readable-p startup-file)
      (find-file startup-file)))
  (when (require 'treemacs nil t)
    (let ((file-window (selected-window)))
      (ignore-errors
        (treemacs))
      (when (window-live-p file-window)
        (select-window file-window)))))

(defun eng/project-find-file ()
  "Find a file in the current project, with sane fallbacks.

Prefer Projectile when a project is detected, fall back to project.el, and
ultimately to plain `find-file' when the current directory is not a project."
  (interactive)
  (cond
   ((ignore-errors (projectile-project-p))
    (call-interactively #'projectile-find-file))
   ((project-current nil)
    (call-interactively #'project-find-file))
   (t
    (call-interactively #'find-file))))

;; No automatic Go formatting on save. Use the explicit format bindings instead.

;; Load our custom keybindings
(load (expand-file-name "keybindings.el" eng/doom-config-dir) t 'nomessage)

;; Load feature-specific Emacs config supplied by enabled Home Manager modules.
(dolist (file (and (boundp 'eng/emacs-extra-config-files) eng/emacs-extra-config-files))
  (load file t 'nomessage))

(let ((clojure-config (expand-file-name "clojure.el" eng/doom-config-dir)))
  (when (file-readable-p clojure-config)
    (load clojure-config t 'nomessage)))
