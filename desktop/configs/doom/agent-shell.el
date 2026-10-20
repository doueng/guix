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
  "Send the draft and keep the bottom composer ready for another prompt.
/new starts a fresh conversation instead, as it does in Pi."
  (interactive)
  (if (equal (string-trim (buffer-string)) "/new")
      (progn (erase-buffer) (eng/bbh-new))
    (agent-shell-viewport-compose-send t)))

(defun eng/bbh--shell ()
  "The bbh shell behind the current transcript or composer."
  (or (bound-and-true-p eng/bbh-transcript-shell)
      (agent-shell--shell-buffer :no-create t)))

(defun eng/bbh-new ()
  "Replace this layout's session with a new one and clear its transcript."
  (interactive)
  (let* ((old (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
         (transcript (buffer-local-value 'eng/bbh-transcript old))
         (old-composer (agent-shell-viewport--buffer :shell-buffer old :existing-only t))
         (input (and old-composer (get-buffer-window old-composer)))
         (shell (agent-shell--start :config (eng/bbh-agent-config) :new-session t :no-focus t)))
    (when input
      (set-window-buffer input (agent-shell-viewport--buffer :shell-buffer shell)))
    (prog1 (eng/bbh-layout shell transcript)
      (let ((kill-buffer-query-functions nil))
        (when (buffer-live-p old-composer) (kill-buffer old-composer))
        (kill-buffer old)))))

;;; Org transcript

(defvar-local eng/bbh-transcript nil
  "The org buffer that renders this shell's conversation.")

(defvar-local eng/bbh-transcript-shell nil)

(defvar-local eng/bbh-transcript--reply nil
  "(START . MARKDOWN) for the reply still streaming at the end, or nil.")

(defvar-local eng/bbh-transcript--tools nil
  "Tool call ids to markers at the start of their blocks.")

(defvar-local eng/bbh-transcript-tools-shown nil
  "Whether tool calls are expanded.  New tool calls follow this state.")

(defun eng/bbh--md-emphasis (text)
  (replace-regexp-in-string
   "\\[\\([^]\n]+\\)\\](\\([^) \n]+\\))" "[[\\2][\\1]]"
   (replace-regexp-in-string "\\*\\*\\([^*\n]+\\)\\*\\*" "*\\1*" text t)
   t))

(defun eng/bbh--md-inline (line)
  "Convert the code spans, bold and links in LINE from Markdown to org."
  (let ((pos 0) (out ""))
    (while (string-match "`\\([^`\n]+\\)`" line pos)
      (let ((before (substring line pos (match-beginning 0)))
            (code (match-string 1 line)))
        (setq pos (match-end 0)
              out (concat out (eng/bbh--md-emphasis before)
                          (format (if (string-search "~" code) "=%s=" "~%s~") code)))))
    (concat out (eng/bbh--md-emphasis (substring line pos)))))

(defun eng/bbh--md->org (md)
  "Convert the Markdown models write into org, closing an unfinished fence."
  (let (fence lines)
    (dolist (line (split-string md "\n"))
      (push (cond
             ((and fence (string-match-p "\\`[ \t]*```[ \t]*\\'" line))
              (prog1 (if (eq fence 'src) "#+end_src" "#+end_example") (setq fence nil)))
             (fence (org-escape-code-in-string line))
             ((string-match "\\`[ \t]*```[ \t]*\\([^ \t`]*\\)" line)
              (let ((lang (match-string 1 line)))
                (setq fence (if (string-empty-p lang) 'example 'src))
                (if (eq fence 'src) (concat "#+begin_src " lang) "#+begin_example")))
             ((string-match "\\`#+[ \t]+\\(.*?\\)[ \t]*\\'" line)
              (format "*%s*" (match-string 1 line)))
             ((string-match "\\`\\([ \t]*\\)[*+][ \t]+" line)
              (concat (match-string 1 line) "- " (eng/bbh--md-inline (substring line (match-end 0)))))
             ((string-match-p "\\`[ \t]*|[-:| ]+|[ \t]*\\'" line)
              (subst-char-in-string ?: ?- line))
             (t (let ((org (eng/bbh--md-inline line)))
                  (if (string-match-p "\\`\\(\\*+ \\|#\\+\\)" org) (concat "," org) org))))
            lines))
    (when fence (push (if (eq fence 'src) "#+end_src" "#+end_example") lines))
    (string-join (nreverse lines) "\n")))

(defun eng/bbh--tool-block (tool)
  "An org block for TOOL, agent-shell's record of one tool call."
  (format "#+begin_tool %s %s\n%s\n#+end_tool\n"
          (pcase (map-elt tool :status) ("completed" "done") ("failed" "failed") (_ "running"))
          (or (map-elt tool :title) "")
          (eng/bbh--md->org
           (string-trim (mapconcat (lambda (item) (or (map-nested-elt item '(content text)) ""))
                                   (map-elt tool :content) "\n\n")))))

(defun eng/bbh-transcript--edit (change)
  "Call CHANGE at the end of the transcript, keeping followers at the end."
  (let ((inhibit-read-only t)
        (followers (seq-filter (lambda (w) (= (window-point w) (point-max)))
                               (get-buffer-window-list nil nil t)))
        (at-end (= (point) (point-max))))
    (save-excursion
      (goto-char (point-max))
      (funcall change))
    (when at-end (goto-char (point-max)))
    (dolist (w followers) (set-window-point w (point-max)))))

(defun eng/bbh-transcript--separator ()
  (if (bobp) "" "\n"))

(defun eng/bbh-transcript--fold-tool (marker fold)
  (save-excursion
    (goto-char marker)
    (org-fold-hide-block-toggle (if fold t 'off) t)))

(defun eng/bbh-transcript--prompt (prompt)
  (setq eng/bbh-transcript--reply nil)
  (let* ((prompt (string-trim prompt))
         (title (truncate-string-to-width (car (split-string prompt "\n")) 72 nil nil "…")))
    (eng/bbh-transcript--edit
     (lambda ()
       (insert (eng/bbh-transcript--separator) "* " title "\n"
               (if (equal title prompt) ""
                 (concat "#+begin_quote\n" (eng/bbh--md->org prompt) "\n#+end_quote\n")))))))

(defun eng/bbh-transcript--reply-chunk (text)
  (eng/bbh-transcript--edit
   (lambda ()
     (let* ((reply (or eng/bbh-transcript--reply
                       (setq eng/bbh-transcript--reply (cons (point-marker) ""))))
            (md (setcdr reply (concat (cdr reply) text))))
       (delete-region (car reply) (point-max))
       (unless (string-blank-p md)
         (insert (eng/bbh-transcript--separator) (eng/bbh--md->org (string-trim md)) "\n"))))))

(defun eng/bbh-transcript--tool (id tool)
  (setq eng/bbh-transcript--reply nil)
  (eng/bbh-transcript--edit
   (lambda ()
     (if-let* ((start (gethash id eng/bbh-transcript--tools)))
         (let ((folded (invisible-p (save-excursion (goto-char start) (forward-line 1) (point)))))
           (goto-char start)
           (delete-region start (if (re-search-forward "^#\\+end_tool\n" nil t) (point) (point-max)))
           (insert (eng/bbh--tool-block tool))
           (eng/bbh-transcript--fold-tool start folded))
       (insert (eng/bbh-transcript--separator))
       (let ((start (point-marker)))
         (insert (eng/bbh--tool-block tool))
         (puthash id start eng/bbh-transcript--tools)
         (eng/bbh-transcript--fold-tool start (not eng/bbh-transcript-tools-shown)))))))

(defun eng/bbh-transcript--on-event (transcript event)
  (when (buffer-live-p transcript)
    (with-current-buffer transcript
      (let ((data (map-elt event :data)))
        (pcase (map-elt event :event)
          ('input-submitted (eng/bbh-transcript--prompt (map-elt data :prompt)))
          ('agent-message-chunk (eng/bbh-transcript--reply-chunk (map-elt data :text-chunk)))
          ('tool-call-update (eng/bbh-transcript--tool (map-elt data :tool-call-id)
                                                       (map-elt data :tool-call)))
          ((or 'turn-complete 'error) (setq eng/bbh-transcript--reply nil)))))))

(defun eng/bbh--tool-label (status title)
  (concat (propertize (pcase status ("done" "✓") ("failed" "✗") (_ "…"))
                      'face (pcase status ("done" 'success) ("failed" 'error) (_ 'warning)))
          " " (propertize title 'face 'font-lock-function-name-face)))

(defun eng/bbh--fontify-nested-blocks (limit)
  "Fontify src and example blocks up to LIMIT as code.
Org fontifies a tool block as one special block and skips the blocks inside it."
  (while (re-search-forward "^#\\+begin_\\(src\\|example\\)\\(?: +\\(\\S-+\\)\\)?.*\n" limit t)
    (let ((beg (match-end 0))
          (type (match-string 1))
          (lang (match-string 2)))
      (when (re-search-forward (format "^#\\+end_%s$" type) nil t)
        (let ((end (match-beginning 0)))
          (when (and lang (< beg end))
            (org-src-font-lock-fontify-block lang beg end))
          (add-face-text-property beg end 'org-block t)))))
  nil)

(defconst eng/bbh-transcript--markup
  '((eng/bbh--fontify-nested-blocks)
    ("^#\\+begin_\\(?:src\\|example\\|quote\\).*\n\\|\n#\\+end_\\(?:src\\|example\\|quote\\|tool\\)$"
     (0 (progn (put-text-property (match-beginning 0) (match-end 0) 'display "") nil)))
    ("^#\\+begin_tool \\([a-z]+\\) \\(.*\\)$"
     (0 (progn (put-text-property (match-beginning 0) (match-end 0) 'display
                                  (eng/bbh--tool-label (match-string 1) (match-string 2)))
               nil))))
  "Format nested blocks as code and hide the org markup lines around blocks.")

(define-derived-mode eng/bbh-transcript-mode org-mode "bbh"
  "A read-only org rendering of a bbh conversation."
  (setq buffer-read-only t)
  (setq-local font-lock-extra-managed-props
              (cl-adjoin 'display font-lock-extra-managed-props))
  (font-lock-add-keywords nil eng/bbh-transcript--markup 'append)
  (visual-line-mode 1))

(defun eng/bbh--transcript (shell &optional buffer)
  "Return SHELL's org transcript, clearing BUFFER to serve as it if SHELL has none."
  (let ((existing (buffer-local-value 'eng/bbh-transcript shell)))
    (if (buffer-live-p existing)
        existing
      (let ((transcript (or buffer (generate-new-buffer "bbh transcript"))))
        (with-current-buffer transcript
          (unless (derived-mode-p 'eng/bbh-transcript-mode) (eng/bbh-transcript-mode))
          (let ((inhibit-read-only t)) (erase-buffer))
          (setq eng/bbh-transcript-shell shell
                eng/bbh-transcript--reply nil
                eng/bbh-transcript--tools (make-hash-table :test #'equal)))
        (agent-shell-subscribe-to :shell-buffer shell
                                  :on-event (lambda (event) (eng/bbh-transcript--on-event transcript event)))
        (with-current-buffer shell (setq eng/bbh-transcript transcript))))))

(defun eng/bbh-toggle-tools ()
  "Expand every tool call in the transcript, or fold them all."
  (interactive)
  (with-current-buffer (eng/bbh--transcript (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
    (setq eng/bbh-transcript-tools-shown (not eng/bbh-transcript-tools-shown))
    (maphash (lambda (_ start) (eng/bbh-transcript--fold-tool start (not eng/bbh-transcript-tools-shown)))
             eng/bbh-transcript--tools)
    (message (if eng/bbh-transcript-tools-shown "Tool calls expanded" "Tool calls folded"))))

(define-key eng/bbh-transcript-mode-map (kbd "C-c C-t") #'eng/bbh-toggle-tools)

(defvar-local eng/bbh-composer nil)
(put 'eng/bbh-composer 'permanent-local t)

(defun eng/bbh-composer-header (&rest _)
  "Keep the send hint visible when agent-shell refreshes its header."
  (when (and eng/bbh-composer (derived-mode-p 'agent-shell-viewport-edit-mode))
    (setq-local header-line-format
                '(:eval (concat " " (if (agent-shell-viewport--busy-p) "Working · C-n queue" "Ready · C-n send")
                                "   RET newline   M-p history   M-v/C-v scroll   C-c C-t tools   C-c C-k stop")))))

(defun eng/bbh-scroll-output (backward)
  "Scroll this composer's transcript without moving input focus."
  (let ((window (get-buffer-window (eng/bbh--transcript (agent-shell-viewport--shell-buffer)))))
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
                       ("C-c C-t" . eng/bbh-toggle-tools)
                       ("M-v" . eng/bbh-output-page-up)
                       ("C-v" . eng/bbh-output-page-down)))
      (local-set-key (kbd (car binding)) (cdr binding))
      (when (fboundp 'evil-local-set-key)
        (dolist (state '(normal insert))
          (evil-local-set-key state (kbd (car binding)) (cdr binding)))))
    (eng/bbh-composer-header)))

(defun eng/bbh-layout (&optional shell transcript)
  "Show SHELL's org transcript above its persistent composer.
TRANSCRIPT is an earlier session's transcript to clear and reuse.  When
called interactively, restore this layout for the current session."
  (interactive)
  (require 'agent-shell-viewport)
  (let* ((shell (or shell (eng/bbh--shell)
                    (user-error "No agent session in this buffer")))
         (transcript (eng/bbh--transcript shell transcript))
         (composer (agent-shell-viewport--buffer :shell-buffer shell))
         (output (or (get-buffer-window transcript) (selected-window)))
         (input (get-buffer-window composer)))
    (when (eq output input)
      (setq output (split-window input nil 'above)))
    (set-window-buffer output transcript)
    (unless input
      (setq input (split-window output (- (min 8 (max 4 (/ (window-total-height output) 3)))) 'below)))
    (set-window-buffer input composer)
    (with-current-buffer composer
      (setq-local eng/bbh-composer t)
      (eng/bbh-setup-composer))
    (select-window input)
    (when (fboundp 'evil-insert-state) (evil-insert-state))
    composer))

(defun eng/bbh-cider-connect ()
  "Connect CIDER to the REPL of the bbh session in the current buffer."
  (interactive)
  (let* ((shell (or (eng/bbh--shell)
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
