;;; agent-shell.el --- bbh in agent-shell -*- lexical-binding: t; -*-

(defun eng/bbh-agent-config ()
  "agent-shell configuration for bbh, the babashka agent harness."
  (agent-shell-make-agent-config
   :identifier 'bbh
   :mode-line-name "bbh"
   :buffer-name "bbh"
   :shell-prompt "bbh> "
   :shell-prompt-regexp "bbh> "
   :client-maker (lambda (buffer)
                   (agent-shell--make-acp-client
                    :command (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))
                    :command-params '("acp")
                    :context-buffer buffer))))

(defun eng/bbh ()
  "Start a bbh session in agent-shell."
  (interactive)
  (require 'agent-shell)
  (eng/bbh-layout (agent-shell--start :config (eng/bbh-agent-config)
                                       :new-session t :no-focus t)))

(defun eng/bbh-send ()
  "Send the draft and keep the bottom composer ready for another prompt."
  (interactive)
  (agent-shell-viewport-compose-send t))

(defvar-local eng/bbh-composer nil)
(put 'eng/bbh-composer 'permanent-local t)

(defun eng/bbh-composer-header (&rest _)
  "Keep the send hint visible when agent-shell refreshes its header."
  (when (and eng/bbh-composer (derived-mode-p 'agent-shell-viewport-edit-mode))
    (setq-local header-line-format
                '(:eval (concat " " (if (agent-shell-viewport--busy-p) "Working · C-n queue" "Ready · C-n send")
                                "   RET newline   M-p history   M-v/C-v scroll   C-c C-k stop")))))

(defun eng/bbh-scroll-output (backward)
  "Scroll this composer's transcript without moving input focus."
  (let ((window (get-buffer-window (agent-shell-viewport--shell-buffer))))
    (unless window (user-error "Restore the transcript with M-x eng/bbh-layout"))
    (with-selected-window window
      (if backward (scroll-down-command) (scroll-up-command)))))

(defun eng/bbh-output-page-up ()
  "Read earlier output while keeping the composer selected."
  (interactive)
  (eng/bbh-scroll-output t))

(defun eng/bbh-output-page-down ()
  "Read later output while keeping the composer selected."
  (interactive)
  (eng/bbh-scroll-output nil))

(defun eng/bbh-setup-composer ()
  "Restore bbh bindings after returning from agent-shell's history view."
  (when eng/bbh-composer
    (use-local-map (copy-keymap (current-local-map)))
    (dolist (binding '(("C-n" . eng/bbh-send)
                       ("C-c C-c" . eng/bbh-send)
                       ("M-RET" . eng/bbh-send)
                       ("C-c C-k" . agent-shell-viewport-interrupt)
                       ("M-v" . eng/bbh-output-page-up)
                       ("C-v" . eng/bbh-output-page-down)))
      (local-set-key (kbd (car binding)) (cdr binding))
      (when (fboundp 'evil-local-set-key)
        (dolist (state '(normal insert))
          (evil-local-set-key state (kbd (car binding)) (cdr binding)))))
    (eng/bbh-composer-header)))

(defvar-local eng/bbh-split-input nil)
(defvar agent-shell-prompt-bar-mode nil)

(defun eng/bbh-hide-transcript-prompt (render &rest args)
  "Render split sessions without a second live input prompt."
  ;; Chat rendering already supports an external input area. Scope that switch
  ;; to this render, rather than pretending the absent prompt-bar mode is on.
  (let ((agent-shell-prompt-bar-mode
         (or (bound-and-true-p agent-shell-prompt-bar-mode)
             (and eng/bbh-split-input
                  (get-buffer-window
                   (agent-shell-viewport--buffer :existing-only t))))))
    (apply render args)))

(defun eng/bbh-layout (&optional shell)
  "Show SHELL above its persistent composer in the selected window.
When called interactively, restore this layout for the current session."
  (interactive)
  (require 'agent-shell-viewport)
  (let* ((shell (or shell (agent-shell--shell-buffer :no-create t)
                    (user-error "No agent session in this buffer")))
         (composer (agent-shell-viewport--buffer :shell-buffer shell))
         (output (or (get-buffer-window shell) (selected-window)))
         (input (get-buffer-window composer)))
    (when (eq output input)
      (setq output (split-window input nil 'above)))
    (set-window-buffer output shell)
    (unless input
      (setq input (split-window output (- (min 8 (max 4 (/ (window-total-height output) 3)))) 'below)))
    (set-window-buffer input composer)
    (with-current-buffer shell
      (setq-local eng/bbh-split-input t)
      (when (fboundp 'agent-shell-chat--relabel)
        (agent-shell-chat--relabel)))
    (with-current-buffer composer
      (setq-local eng/bbh-composer t)
      (eng/bbh-setup-composer))
    (select-window input)
    (when (fboundp 'evil-insert-state) (evil-insert-state))
    composer))

(defun eng/bbh-cider-connect ()
  "Connect CIDER to the REPL of the bbh session in the current buffer."
  (interactive)
  (let* ((shell (or (agent-shell--shell-buffer :no-create t)
                    (user-error "No bbh session in this buffer")))
         (id (or (with-current-buffer shell
                   (map-nested-elt (agent-shell--state) '(:session :id)))
                 (user-error "The bbh session is still starting")))
         (port-file (expand-file-name (format "bbh/conversations/%s/repl.port" id)
                                      (or (getenv "XDG_DATA_HOME") "~/.local/share"))))
    (cider-connect-clj (list :host "127.0.0.1"
                             :port (string-to-number (with-temp-buffer
                                                       (insert-file-contents port-file)
                                                       (buffer-string)))))))

(after! agent-shell-viewport
  (add-hook 'agent-shell-viewport-edit-mode-hook #'eng/bbh-setup-composer)
  (advice-add 'agent-shell-viewport--update-header :after #'eng/bbh-composer-header))

(after! agent-shell-chat-mode
  (advice-add 'agent-shell-chat--label-prompts :around #'eng/bbh-hide-transcript-prompt))

(after! agent-shell
  ;; Loading system-sleep warns even when this Emacs has no usable backend.
  (unless (or (featurep 'dbusbind) (featurep 'ns) (featurep 'w32))
    (setq agent-shell-inhibit-system-sleep nil))
  ;; Keep screenshots and transcripts out of project checkouts; bbh already
  ;; writes its own transcript next to the REPL log.
  (setq agent-shell-dot-subdir-function
        (lambda (subdir)
          (expand-file-name subdir (expand-file-name "agent-shell" (or (getenv "XDG_DATA_HOME") "~/.local/share"))))
        agent-shell-preferred-agent-config (eng/bbh-agent-config))
  (add-to-list 'agent-shell-agent-configs #'eng/bbh-agent-config))
