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
/new starts a fresh conversation instead, as it does in Pi, and resuming a
saved conversation clears the transcript the same way."
  (interactive)
  (let ((draft (string-trim (buffer-string))))
    (if (equal draft "/new")
        (progn (erase-buffer) (eng/bbh-new))
      (when (eng/bbh--resumes-p draft)
        (eng/bbh-clear-transcript))
      (agent-shell-viewport-compose-send t))))

(defun eng/bbh--conversation-file (id file)
  (expand-file-name (format "bbh/conversations/%s/%s" id file)
                    (or (getenv "XDG_DATA_HOME") "~/.local/share")))

(defun eng/bbh--resumes-p (draft)
  "Whether DRAFT names a conversation /resume can switch to."
  (and (string-match "\\`/resume[ \t]+\\(\\S-+\\)\\'" draft)
       (let ((id (match-string 1 draft)))
         (or (equal id "last")
             (file-exists-p (eng/bbh--conversation-file id "messages.jsonl"))))))

(defun eng/bbh-clear-transcript ()
  "Empty the transcript of the bbh session in the current buffer."
  (with-current-buffer (eng/bbh--transcript (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
    (let ((inhibit-read-only t)) (erase-buffer))
    (setq eng/bbh-transcript--reply nil)
    (clrhash eng/bbh-transcript--tools)))

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
  "Whether tool results are expanded.  New results follow this state.")

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
  "Org blocks for TOOL, agent-shell's record of one tool call: its code,
then a result block.  bbh's adapter sends the content as the code's fence
followed by the result's fence."
  (let* ((code (or (map-nested-elt tool '(:raw-input code)) ""))
         (text (string-trim (mapconcat (lambda (item) (or (map-nested-elt item '(content text)) ""))
                                       (map-elt tool :content) "\n\n")))
         (result (string-trim (string-remove-prefix (concat "```clojure\n" code "\n```") text)))
         (result (string-remove-suffix "\n```" (string-remove-prefix "```\n" result))))
    (format "#+begin_src clojure\n%s\n#+end_src\n#+begin_result %s %d\n%s#+end_result\n"
            (org-escape-code-in-string code)
            (pcase (map-elt tool :status) ("completed" "done") ("failed" "failed") (_ "running"))
            (if (string-empty-p result) 0 (length (split-string result "\n")))
            (if (string-empty-p result) "" (concat (org-escape-code-in-string result) "\n")))))

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

(defun eng/bbh-transcript--result-start (marker)
  (save-excursion
    (goto-char marker)
    (re-search-forward "^#\\+begin_result ")
    (line-beginning-position)))

(defun eng/bbh-transcript--fold-tool (marker fold)
  "Fold or show the result of the tool call that starts at MARKER."
  (save-excursion
    (goto-char (eng/bbh-transcript--result-start marker))
    (org-fold-hide-block-toggle (if fold t 'off) t)))

(defun eng/bbh-transcript--tool-folded-p (marker)
  "Whether the result at MARKER is folded, or nil when it is still empty."
  (save-excursion
    (goto-char (eng/bbh-transcript--result-start marker))
    (forward-line 1)
    (and (not (looking-at-p "#\\+end_result")) (invisible-p (point)))))

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
         (let ((folded (or (eng/bbh-transcript--tool-folded-p start)
                           (not eng/bbh-transcript-tools-shown))))
           (goto-char start)
           (delete-region start (if (re-search-forward "^#\\+end_result\n" nil t) (point) (point-max)))
           (insert (eng/bbh--tool-block tool))
           (eng/bbh-transcript--fold-tool start folded))
       (insert (eng/bbh-transcript--separator))
       (let ((start (point-marker)))
         (insert (eng/bbh--tool-block tool))
         (puthash id start eng/bbh-transcript--tools)
         (eng/bbh-transcript--fold-tool start (not eng/bbh-transcript-tools-shown)))))))

(defun eng/bbh-transcript--error (message)
  (setq eng/bbh-transcript--reply nil)
  (eng/bbh-transcript--edit
   (lambda ()
     (insert (eng/bbh-transcript--separator)
             (propertize (concat "Error: " message) 'font-lock-face 'error) "\n"))))

;;; Usage

(defvar-local eng/bbh-usage-requests nil
  "This turn's model requests as bbh reported them, newest first.
Each is bbh's request record: input, cached, written, output, n, ratio
and cache, which is \"hit\", \"miss\" or \"cold\".")

(defvar-local eng/bbh-usage-last nil
  "The latest model request bbh reported.")

(defvar-local eng/bbh-usage-turn-cost nil
  "The session's cost when the current turn started.")

(defun eng/bbh--record-usage (&rest args)
  "Keep the request bbh attaches to a usage_update, for the header and transcript."
  (when-let* ((shell (map-elt (plist-get args :state) :buffer))
              ((buffer-live-p shell))
              (request (map-nested-elt (plist-get args :acp-update) '(_meta bbh request))))
    (with-current-buffer shell
      (setq eng/bbh-usage-last request)
      (push request eng/bbh-usage-requests))
    (force-mode-line-update t)))

(defun eng/bbh--tokens (n)
  "N tokens compactly: 950, 1.2k, 45k or 1m."
  (cond ((< n 1000) (format "%d" n))
        ((< n 10000) (format "%.1fk" (/ n 1000.0)))
        ((< n 1000000) (format "%.0fk" (/ n 1000.0)))
        (t (replace-regexp-in-string "\\.0m\\'" "m" (format "%.1fm" (/ n 1000000.0))))))

(defun eng/bbh--percent (ratio)
  (round (* 100 (or ratio 0))))

(defun eng/bbh--cache-label (request)
  "How much of REQUEST's input came from the prompt cache."
  (pcase (map-elt request 'cache)
    ("miss" (propertize (format "cache miss %d%%" (eng/bbh--percent (map-elt request 'ratio))) 'face 'error))
    ("cold" (propertize "cache cold" 'face 'warning))
    (_ (propertize (format "cache %d%%" (eng/bbh--percent (map-elt request 'ratio))) 'face 'success))))

(defun eng/bbh-usage-header (shell)
  "Context, cache and cost for SHELL's composer header, or nil before a request."
  (when (buffer-live-p shell)
    (with-current-buffer shell
      (when eng/bbh-usage-last
        (let* ((usage (map-elt agent-shell--state :usage))
               (used (map-elt usage :context-used))
               (size (map-elt usage :context-size)))
          (string-join
           (delq nil (list (when used
                             (format "ctx %s%s" (eng/bbh--tokens used)
                                     (if size (concat "/" (eng/bbh--tokens size)) "")))
                           (eng/bbh--cache-label eng/bbh-usage-last)
                           (format "$%.2f" (or (map-elt usage :cost-amount) 0))))
           " · "))))))

(defun eng/bbh--turn-usage (requests cost)
  "One line about a turn's REQUESTS, oldest first, which cost COST dollars."
  (let* ((sum (lambda (key) (apply #'+ (mapcar (lambda (r) (or (map-elt r key) 0)) requests))))
         (input (funcall sum 'input))
         (cached (funcall sum 'cached))
         (misses (seq-filter (lambda (r) (equal (map-elt r 'cache) "miss")) requests))
         (n (length requests)))
    (string-join
     (list (format "Usage: %d request%s" n (if (= n 1) "" "s"))
           (format "%s in, %d%% cached" (eng/bbh--tokens input)
                   (if (> input 0) (round (* 100.0 (/ (float cached) input))) 0))
           (if misses
               (mapconcat (lambda (r) (format "miss on request %d (%d%% of %s)" (map-elt r 'n)
                                              (eng/bbh--percent (map-elt r 'ratio))
                                              (eng/bbh--tokens (map-elt r 'input))))
                          misses ", ")
             "no cache misses")
           (format "%s out" (eng/bbh--tokens (funcall sum 'output)))
           (format "$%.4f" (or cost 0)))
     " · ")))

(defun eng/bbh-transcript--turn-start ()
  (when-let* ((shell eng/bbh-transcript-shell) ((buffer-live-p shell)))
    (with-current-buffer shell
      (setq eng/bbh-usage-requests nil
            eng/bbh-usage-turn-cost (or (map-nested-elt agent-shell--state '(:usage :cost-amount)) 0)))))

(defun eng/bbh-transcript--usage ()
  "Note the finished turn's usage at the end of the transcript."
  (when-let* ((shell eng/bbh-transcript-shell)
              ((buffer-live-p shell))
              (requests (buffer-local-value 'eng/bbh-usage-requests shell)))
    (let ((cost (with-current-buffer shell
                  (- (or (map-nested-elt agent-shell--state '(:usage :cost-amount)) 0)
                     (or eng/bbh-usage-turn-cost 0)))))
      (with-current-buffer shell (setq eng/bbh-usage-requests nil))
      (eng/bbh-transcript--edit
       (lambda ()
         (insert (eng/bbh-transcript--separator)
                 (propertize (eng/bbh--turn-usage (reverse requests) cost) 'font-lock-face 'shadow) "\n"))))))

(defun eng/bbh-transcript--on-event (transcript event)
  (when (buffer-live-p transcript)
    (with-current-buffer transcript
      (let ((data (map-elt event :data)))
        (pcase (map-elt event :event)
          ('input-submitted (eng/bbh-transcript--turn-start)
                            (eng/bbh-transcript--prompt (map-elt data :prompt)))
          ('agent-message-chunk (eng/bbh-transcript--reply-chunk (map-elt data :text-chunk)))
          ('tool-call-update (eng/bbh-transcript--tool (map-elt data :tool-call-id)
                                                       (map-elt data :tool-call)))
          ('error (eng/bbh-transcript--error (or (map-elt data :message) "the request failed")))
          ('turn-complete (setq eng/bbh-transcript--reply nil)
                          (eng/bbh-transcript--usage)))))))

(defun eng/bbh--result-label (status lines)
  (concat (propertize (pcase status ("done" "✓") ("failed" "✗") (_ "…"))
                      'face (pcase status ("done" 'success) ("failed" 'error) (_ 'warning)))
          " "
          (propertize (pcase lines
                        ("0" (if (equal status "running") "running" "no output"))
                        ("1" "output (1 line)")
                        (_ (format "output (%s lines)" lines)))
                      'face 'shadow)))

(defun eng/bbh--fontify-nested-blocks (limit)
  "Fontify src and example blocks up to LIMIT as code.
Org leaves result blocks, a special block, without the code background."
  (while (re-search-forward "^#\\+begin_\\(src\\|example\\|result\\)\\(?: +\\(\\S-+\\)\\)?.*\n" limit t)
    (let ((beg (match-end 0))
          (type (match-string 1))
          (lang (and (equal (match-string 1) "src") (match-string 2))))
      (when (re-search-forward (format "^#\\+end_%s$" type) nil t)
        (let ((end (match-beginning 0)))
          (when (and lang (< beg end))
            (org-src-font-lock-fontify-block lang beg end))
          (add-face-text-property beg end 'org-block t)))))
  nil)

(defconst eng/bbh-transcript--markup
  '((eng/bbh--fontify-nested-blocks)
    ("^#\\+begin_\\(?:src\\|example\\|quote\\).*\n\\|\n#\\+end_\\(?:src\\|example\\|quote\\|result\\)$"
     (0 (progn (put-text-property (match-beginning 0) (match-end 0) 'display "") nil)))
    ("^#\\+begin_result \\([a-z]+\\) \\([0-9]+\\)$"
     (0 (progn (put-text-property (match-beginning 0) (match-end 0) 'display
                                  (eng/bbh--result-label (match-string 1) (match-string 2)))
               nil))))
  "Format nested blocks as code and hide the org markup lines around blocks.")

(define-derived-mode eng/bbh-transcript-mode org-mode "bbh"
  "A read-only org rendering of a bbh conversation."
  (setq buffer-read-only t)
  (setq-local font-lock-extra-managed-props
              (cl-adjoin 'display font-lock-extra-managed-props))
  (font-lock-add-keywords nil eng/bbh-transcript--markup 'append)
  ;; Doom-one paints org-block with an opaque background. In a terminal that
  ;; band hides the transparent default, so drop it in this buffer only.
  (unless (display-graphic-p)
    (face-remap-add-relative 'org-block '(:background "unspecified-bg")))
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
  "Expand every tool result in the transcript, or fold them all."
  (interactive)
  (with-current-buffer (eng/bbh--transcript (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
    (setq eng/bbh-transcript-tools-shown (not eng/bbh-transcript-tools-shown))
    (maphash (lambda (_ start) (eng/bbh-transcript--fold-tool start (not eng/bbh-transcript-tools-shown)))
             eng/bbh-transcript--tools)
    (message (if eng/bbh-transcript-tools-shown "Tool results expanded" "Tool results folded"))))

(define-key eng/bbh-transcript-mode-map (kbd "C-c C-t") #'eng/bbh-toggle-tools)

;; Magit's section keys in evil normal state: n and p move between headings,
;; M-n and M-p between prompts, ^ up, S-TAB cycles all tool results, q buries.
(with-eval-after-load 'evil
  (evil-define-key 'normal eng/bbh-transcript-mode-map
    (kbd "n") #'org-next-visible-heading
    (kbd "p") #'org-previous-visible-heading
    (kbd "M-n") #'org-forward-heading-same-level
    (kbd "M-p") #'org-backward-heading-same-level
    (kbd "^") #'outline-up-heading
    (kbd "<backtab>") #'eng/bbh-toggle-tools
    (kbd "q") #'bury-buffer))

(defvar-local eng/bbh-composer nil)
(put 'eng/bbh-composer 'permanent-local t)

(defun eng/bbh-composer-header (&rest _)
  "Keep the send hint visible when agent-shell refreshes its header."
  (when (and eng/bbh-composer (derived-mode-p 'agent-shell-viewport-edit-mode))
    (setq-local header-line-format
                '(:eval (concat " " (if (agent-shell-viewport--busy-p) "Working · C-n queue" "Ready · C-n send")
                                (if-let* ((usage (eng/bbh-usage-header (agent-shell-viewport--shell-buffer))))
                                    (concat "   " usage)
                                  "")
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

(defvar-local eng/bbh-composer-padding nil
  "Overlay that shows one blank row above the composer's draft.")

(defun eng/bbh-composer-padding ()
  "Leave one blank row between the composer header and the draft.
The overlay only displays a newline, so the draft and what is sent are unchanged.
Its start stays at the top of the buffer, so text typed or sent there does not move it."
  (unless (overlayp eng/bbh-composer-padding)
    (setq eng/bbh-composer-padding (make-overlay (point-min) (point-min) nil nil t))
    (overlay-put eng/bbh-composer-padding 'before-string "\n")))

(defun eng/bbh-setup-composer ()
  "Restore bbh bindings after returning from agent-shell's history view."
  (when eng/bbh-composer
    (eng/bbh-composer-padding)
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
         (port-file (eng/bbh--conversation-file id "repl.port")))
    (cider-connect-clj (list :host "127.0.0.1"
                             :port (string-to-number (with-temp-buffer
                                                       (insert-file-contents port-file)
                                                       (buffer-string)))))))

(after! agent-shell-viewport
  (add-hook 'agent-shell-viewport-edit-mode-hook #'eng/bbh-setup-composer)
  (advice-add 'agent-shell-viewport--update-header :after #'eng/bbh-composer-header))

(after! agent-shell
  (advice-add 'agent-shell--update-usage-from-notification :after #'eng/bbh--record-usage)
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
