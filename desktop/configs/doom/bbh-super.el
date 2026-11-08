;;; bbh-super.el --- the bbh super-agent: Router, Workers and Inbox -*- lexical-binding: t; -*-

;; agent-shell.el loads this file after the bbh session code it builds on.

;;; Super-agent

;; `bbh super' keeps its Workers, its Router and its Inbox in one terminal
;; Emacs daemon, `emacs --daemon=bbh-super'.  The controller calls
;; `eng/bbh-super-open' through emacsclient to give a conversation a buffer,
;; and you attach with `bbh inbox', which opens a frame on *bbh inbox*.

(defun eng/bbh-super--refuse-prompt (prompt-fn &rest args)
  "Signal an error instead of calling PROMPT-FN when no command is running.
A prompt from a server eval, timer or process filter would wait in some frame
and stop the daemon serving anyone, the controller included."
  (if this-command
      (apply prompt-fn args)
    (error "bbh-super: refused to prompt %S outside a command; nobody would answer it"
           (car args))))

(when (equal (daemonp) "bbh-super")
  (dolist (f '(y-or-n-p yes-or-no-p read-from-minibuffer))
    (advice-add f :around #'eng/bbh-super--refuse-prompt)))

(defvar-local eng/bbh-super-finished nil
  "Non-nil in a Worker's shell once `eng/bbh-super-release' moved it out of its workspace.
Its Worker is finished, so the shell runs no more prompts.")

(defun eng/bbh-super--to-router-p (shell)
  "Whether what you type in SHELL goes to the Router: SHELL is the Router or a finished Worker."
  (with-current-buffer shell
    (or (equal "router" eng/bbh-super-conversation) eng/bbh-super-finished)))

(defun eng/bbh-super--say-from (shell text on-done)
  "Give TEXT, typed in SHELL, to the Router, then call ON-DONE.
From a finished Worker it names the thread, so the Router can reopen it."
  (if (not (buffer-local-value 'eng/bbh-super-finished shell))
      (eng/bbh-super-say text on-done)
    (let ((thread (car (split-string (buffer-name shell)))))
      (eng/bbh-super-say (format "About %s, whose Worker is finished: %s" thread text)
                         (lambda ()
                           (funcall on-done)
                           (message "%s's Worker is finished, so this went to the Router, which can reopen %s"
                                    thread thread))))))

(defun eng/bbh-super--router-submit (submit &rest args)
  "Send what you typed at the Router's or a finished Worker's prompt through the controller.
Mailbox prompts, which arrive outside a command, still SUBMIT directly."
  (if (and this-command (not args) (eng/bbh-super--to-router-p (current-buffer)))
      (let* ((shell (current-buffer))
             (start (process-mark (get-buffer-process shell)))
             (text (string-trim (buffer-substring-no-properties start (point-max)))))
        (unless (string-empty-p text)
          (eng/bbh-super--say-from shell text
                                   (lambda ()
                                     (when (buffer-live-p shell)
                                       (with-current-buffer shell
                                         (delete-region (process-mark (get-buffer-process shell))
                                                        (point-max))))))))
    (apply submit args)))

(advice-add 'shell-maker-submit :around #'eng/bbh-super--router-submit)

(defun eng/bbh-super--on-screen-p (shell)
  "Whether SHELL, its transcript or its composer shows in some window."
  (seq-some (lambda (b) (and (buffer-live-p b) (get-buffer-window b t)))
            (list shell
                  (buffer-local-value 'eng/bbh-transcript shell)
                  (agent-shell-viewport--buffer :shell-buffer shell :existing-only t))))

(defun eng/bbh-super--quiet-echo (echo &rest args)
  "Run ECHO with ARGS unless it comes from a super session you are not looking at.
Workers queue prompts in the background, and the echo area is shared."
  (unless (and (bound-and-true-p eng/bbh-super-conversation)
               (not (eng/bbh-super--on-screen-p (current-buffer))))
    (apply echo args)))

(advice-add 'agent-shell--echo :around #'eng/bbh-super--quiet-echo)

(defvar-local eng/bbh-super-conversation nil
  "The conversation this shell runs for `bbh super', or nil for an ordinary session.")

(defvar eng/bbh-super-mode-map (make-sparse-keymap)
  "Keys of every super-agent buffer.  Under Doom, SPC m r and SPC m i.")

(define-minor-mode eng/bbh-super-mode
  "A buffer of the super-agent: a Router or Worker session, or the Inbox.
SPC m r shows the Router and SPC m i the Inbox.  Ordinary bbh sessions never
get it, since an Inbox opened in another Emacs would take the controller's
reload port from the bbh-super daemon."
  :keymap eng/bbh-super-mode-map
  ;; Turned on outside a command, by the controller's emacsclient, evil would
  ;; not see the map until its next state change.
  (when (fboundp 'evil-normalize-keymaps) (evil-normalize-keymaps)))
;; The composer's major mode restarts when it returns from the history view.
(put 'eng/bbh-super-mode 'permanent-local t)

(define-key eng/bbh-super-mode-map (kbd "C-c r") #'eng/bbh-inbox-router)
(define-key eng/bbh-super-mode-map (kbd "C-c i") #'eng/bbh-inbox)
(when (fboundp 'map!)
  (map! :map eng/bbh-super-mode-map
        :localleader
        :desc "Router" "r" #'eng/bbh-inbox-router
        :desc "Inbox"  "i" #'eng/bbh-inbox))

(defun eng/bbh-super--clear-frame ()
  "Leave the selected frame one window, so a view starts from nothing.
Side windows survive
`delete-other-windows', so they go first."
  (dolist (window (window-list nil 'nomini))
    (when (and (window-live-p window) (window-parameter window 'window-side))
      (delete-window window)))
  (delete-other-windows))

(defun eng/bbh-super-config (conversation env)
  "agent-shell configuration for CONVERSATION with ENV, an alist of variables."
  (agent-shell-make-agent-config
   :identifier 'bbh
   :mode-line-name "bbh"
   :buffer-name "bbh"
   :shell-prompt "bbh> "
   :shell-prompt-regexp "bbh> "
   :client-maker (lambda (buffer)
                   (agent-shell--make-acp-client
                    :command (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))
                    :command-params (list "acp" "--id" conversation)
                    :environment-variables
                    (apply #'agent-shell-make-environment-variables
                           (append (mapcan (lambda (pair) (list (car pair) (cdr pair))) env)
                                   '(:inherit-env t)))
                    :context-buffer buffer))))

(defun eng/bbh-super--shell (conversation)
  "The live shell running CONVERSATION for `bbh super', or nil."
  (seq-find (lambda (buffer)
              (equal conversation (buffer-local-value 'eng/bbh-super-conversation buffer)))
            (buffer-list)))

(defun eng/bbh-super--kill (shell)
  "Kill SHELL with its transcript and composer, without asking."
  (let ((kill-buffer-query-functions nil)
        (transcript (buffer-local-value 'eng/bbh-transcript shell))
        (composer (agent-shell-viewport--buffer :shell-buffer shell :existing-only t)))
    ;; The shell first: its clean-up shuts the client down, so killing the
    ;; composer after it does not ask whether to kill the shell too.
    (dolist (buffer (list shell composer transcript))
      (when (buffer-live-p buffer) (kill-buffer buffer)))))

(defun eng/bbh-super-open (conversation dir title env)
  "Run CONVERSATION in DIR as buffer TITLE with ENV, and return the buffer's name.
A session already running CONVERSATION is replaced: the controller opens one
to launch a Worker, to restart one whose session died, and to give the Router
a new token.  Nothing is shown; `eng/bbh-super-visit' shows it."
  (require 'agent-shell)
  (require 'agent-shell-viewport)
  (unless (file-directory-p dir) (error "No directory %s for %s" dir conversation))
  (when-let* ((old (eng/bbh-super--shell conversation)))
    (eng/bbh-super--kill old))
  (let ((shell (agent-shell--start :config (eng/bbh-super-config conversation env)
                                   :new-session t :no-focus t :cwd (file-name-as-directory dir))))
    (with-current-buffer shell
      (setq eng/bbh-super-conversation conversation)
      (eng/bbh-super-mode 1))
    ;; shell-maker finds its buffer by this name; a plain rename-buffer would lose it.
    (shell-maker-set-buffer-name shell title)
    (with-current-buffer (eng/bbh--transcript shell)
      (rename-buffer (concat title " transcript") t)
      (eng/bbh-super-mode 1))
    (buffer-name shell)))

(defun eng/bbh-super-release (conversation dir)
  "Move CONVERSATION's live session out of its workspace into DIR.
The controller calls this before it deletes a finished Worker's workspace.
Each submit starts processes in the shell's directory, and shell-maker
marks the shell busy before the first of them.  In a deleted directory
that process fails, so busy is never cleared and every later prompt and
mailbox wake waits for a turn end that never comes.  The shell is marked
finished too: its bbh session still has the deleted workspace as its cwd,
so it runs no more prompts, and what you type there goes to the Router.
Returns the shell's name, or nil when no session runs CONVERSATION."
  (when-let* ((shell (eng/bbh-super--shell conversation)))
    (with-current-buffer shell (setq eng/bbh-super-finished t))
    (let ((dir (file-name-as-directory (expand-file-name dir))))
      (dolist (buffer (list shell
                            (buffer-local-value 'eng/bbh-transcript shell)
                            (and (featurep 'agent-shell-viewport)
                                 (agent-shell-viewport--buffer :shell-buffer shell :existing-only t))))
        (when (buffer-live-p buffer)
          (with-current-buffer buffer (setq default-directory dir)))))
    (buffer-name shell)))

(defun eng/bbh-super-visit (name)
  "Show the session in buffer NAME as its transcript above its composer, alone in the frame."
  (let ((shell (or (get-buffer name) (user-error "No buffer %s; is its Worker running?" name))))
    (eng/bbh-super--clear-frame)
    (let ((composer (eng/bbh-layout shell)))
      (with-current-buffer composer (eng/bbh-super-mode 1))
      composer)))

;;; Super-agent Inbox

;; The Inbox is an org outline from `bbh inbox org': a heading per thread,
;; starting with its section and id, then per open ask, starting with its id,
;; with the options in its body, a folded Details heading and an Answer
;; heading.  Everything is read-only but the Answer bodies, where you type an
;; answer in place.  Commands find the thread and ask from the headings above
;; the cursor, so they act on what you are looking at.

(defvar eng/bbh-inbox-bbh nil
  "The bbh command the Inbox runs, as a list, or nil for bbh on the PATH.")

(defvar eng/bbh-inbox--listener nil
  "The local server the controller connects to when the Inbox should reload.")

(defvar-local eng/bbh-inbox--thread nil
  "The thread and ask under the cursor when the Inbox last looked, as (THREAD . ASK).")

(defvar-local eng/bbh-inbox--timer nil
  "The pending selection update.")

(defvar-local eng/bbh-inbox--text nil
  "The outline the Inbox shows, so a reload that changed nothing redraws nothing.")

(defvar-local eng/bbh-inbox--rendered nil
  "Each ask's answer as the controller's draft has it, ask id to text, so a
reload can tell what you typed and have not saved, and keep it.")

(defvar-local eng/bbh-inbox--saved nil
  "Each ask's answer last sent to the draft, so leaving insert state again
does not send the same text twice.")

(defvar-local eng/bbh-inbox--deferred nil
  "A pushed outline that came while you typed, drawn when you stop.")

(defvar eng/bbh-inbox-reload-delay 0.5
  "Seconds of idleness a pushed change waits for before it reloads the Inbox.
Pushes inside that time make one reload, and none runs while you type.")

(defvar eng/bbh-inbox--reload nil
  "Where the pushed reload is: nil, `pending' while it waits for idleness,
`running' while bbh runs, `again' when a push came during that run.")

(defvar eng/bbh-inbox--stale nil
  "Whether a push came while no window showed the Inbox.")

(defconst eng/bbh-inbox--thread-re "^\\* \\([A-Z-]+\\) \\(T[0-9]+\\)\\_>")
(defconst eng/bbh-inbox--ask-re "^\\*\\* \\(a[0-9]+\\)\\_>")
(defconst eng/bbh-inbox--part-re "^\\*\\*\\* \\(Details\\|Answer\\)$")

(defun eng/bbh-inbox--command (&rest args)
  (append (or eng/bbh-inbox-bbh
              (list (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))))
          (list "inbox") args))

(defun eng/bbh-inbox--run (args on-done &optional on-fail)
  "Run `bbh inbox ARGS' and call ON-DONE with its output when it succeeds.
A failure calls ON-FAIL with bbh's message, or shows it without ON-FAIL."
  (let ((out (generate-new-buffer " *bbh inbox out*"))
        (err (generate-new-buffer " *bbh inbox err*"))
        ;; bbh starts in the current buffer's directory.  A finished Worker's
        ;; buffers outlive its workspace, so that directory may be gone.
        (default-directory (expand-file-name "~/")))
    (make-process
     :name "bbh-inbox-cmd" :noquery t :buffer out :stderr err :connection-type 'pipe
     :command (apply #'eng/bbh-inbox--command args)
     :sentinel (lambda (process _)
                 (when (memq (process-status process) '(exit signal))
                   (let ((text (with-current-buffer out (buffer-string)))
                         (problem (with-current-buffer err (string-trim (buffer-string)))))
                     (kill-buffer out)
                     (when (buffer-live-p err) (kill-buffer err))
                     (if (zerop (process-exit-status process))
                         (when on-done (funcall on-done text))
                       (let ((problem (if (string-empty-p problem) (format "bbh inbox %s failed" (car args)) problem)))
                         (if on-fail (funcall on-fail problem) (message "%s" problem))))))))))

(defun eng/bbh-inbox--buffer ()
  (get-buffer-create "*bbh inbox*"))

;;;; Reading the outline

(defun eng/bbh-inbox--at-point ()
  "The thread whose heading is at or above the cursor."
  (save-excursion
    (end-of-line)
    (when (re-search-backward eng/bbh-inbox--thread-re nil t) (match-string-no-properties 2))))

(defun eng/bbh-inbox--thread ()
  (or (eng/bbh-inbox--at-point) (user-error "No thread here")))

(defun eng/bbh-inbox--ask ()
  "The ask whose heading is at or above the cursor within its thread, or nil."
  (save-excursion
    (end-of-line)
    (when (and (re-search-backward "^\\*\\*? " nil t) (looking-at eng/bbh-inbox--ask-re))
      (match-string-no-properties 1))))

(defun eng/bbh-inbox--next-heading (pos level)
  "The start of the first heading after POS's line of LEVEL or shallower, else point-max."
  (save-excursion
    (goto-char pos)
    (forward-line 1)
    (if (re-search-forward (format "^\\*\\{1,%d\\} " level) nil t) (match-beginning 0) (point-max))))

(defun eng/bbh-inbox--asks (&optional thread)
  "The ask ids of THREAD, or of every thread, in the order shown."
  (save-excursion
    (let* ((start (if thread
                      (progn (goto-char (point-min))
                             (and (re-search-forward (concat "^\\* [A-Z-]+ " (regexp-quote thread) "\\_>") nil t)
                                  (line-beginning-position)))
                    (point-min)))
           (end (if thread (and start (eng/bbh-inbox--next-heading start 1)) (point-max)))
           asks)
      (when start
        (goto-char start)
        (while (re-search-forward eng/bbh-inbox--ask-re end t)
          (push (match-string-no-properties 1) asks)))
      (nreverse asks))))

(defun eng/bbh-inbox--answer-region (ask)
  "(BEG . END) of ASK's Answer body: from the line after its heading to the
next heading, END after the body's last newline."
  (save-excursion
    (goto-char (point-min))
    (when (re-search-forward (concat "^\\*\\* " (regexp-quote ask) "\\_>") nil t)
      (let ((end (eng/bbh-inbox--next-heading (point) 2)))
        (when (re-search-forward "^\\*\\*\\* Answer$" end t)
          (cons (min (1+ (line-end-position)) (point-max)) (eng/bbh-inbox--next-heading (point) 3)))))))

(defun eng/bbh-inbox--answer-text (ask)
  "What ASK's Answer body says, each line trimmed."
  (if-let* ((region (eng/bbh-inbox--answer-region ask)))
      (string-trim (mapconcat #'string-trim (split-string (buffer-substring-no-properties (car region) (cdr region)) "\n") "\n"))
    ""))

(defun eng/bbh-inbox--in-answer-p (&optional pos)
  "Whether POS, or the cursor, is in an Answer body."
  (let ((pos (or pos (point))))
    (when-let* ((ask (save-excursion (goto-char pos) (eng/bbh-inbox--ask)))
                (region (eng/bbh-inbox--answer-region ask)))
      (and (<= (car region) pos) (or (< pos (cdr region)) (= pos (point-max)))))))

(defun eng/bbh-inbox--threads ()
  "The Inbox's threads in order, each (ID . ARCHIVED)."
  (save-excursion
    (goto-char (point-min))
    (let (threads)
      (while (re-search-forward eng/bbh-inbox--thread-re nil t)
        (push (cons (match-string-no-properties 2) (equal "ARCHIVED" (match-string-no-properties 1))) threads))
      (nreverse threads))))

(defun eng/bbh-inbox--key ()
  "The key of the heading at the start of this line: a thread or ask id, or
ASK/Details and ASK/Answer."
  (cond ((looking-at eng/bbh-inbox--thread-re) (match-string-no-properties 2))
        ((looking-at eng/bbh-inbox--ask-re) (match-string-no-properties 1))
        ((looking-at eng/bbh-inbox--part-re)
         (let ((part (match-string-no-properties 1)))
           (concat (eng/bbh-inbox--ask) "/" part)))))

(defun eng/bbh-inbox--headings ()
  "Each heading's (KEY . START), in order."
  (save-excursion
    (goto-char (point-min))
    (let (all)
      (while (re-search-forward "^\\*+ " nil t)
        (beginning-of-line)
        (when-let* ((key (eng/bbh-inbox--key))) (push (cons key (point)) all))
        (forward-line 1))
      (nreverse all))))

;;;; Drawing it, keeping your place, folds and unsaved answers

(defun eng/bbh-inbox--place (pos)
  "Where POS is: (THREAD LINE KEY OFFSET), its thread, line number, the heading
above it and how many lines below that heading."
  (save-excursion
    (goto-char pos)
    (let* ((thread (eng/bbh-inbox--at-point))
           (line (line-number-at-pos))
           (heading (save-excursion (end-of-line) (when (re-search-backward "^\\*+ " nil t) (point))))
           (key (when heading (save-excursion (goto-char heading) (eng/bbh-inbox--key)))))
      (list thread line key (if heading (count-lines heading (line-beginning-position)) 0)))))

(defun eng/bbh-inbox--find (place)
  "The start of PLACE's thread's heading, else of its line number."
  (save-excursion
    (goto-char (point-min))
    (if (and (car place) (re-search-forward (concat "^\\* [A-Z-]+ " (regexp-quote (car place)) "\\_>") nil t))
        (line-beginning-position)
      (forward-line (1- (if (consp (cdr place)) (cadr place) (cdr place))))
      (line-beginning-position))))

(defun eng/bbh-inbox--land (place before)
  "Where PLACE lands after a redraw, BEFORE being `eng/bbh-inbox--threads' then.
On its heading and line, while its thread is still there; else on the
thread, unless that is gone or has just moved to Archived at the bottom, as
archiving does; then on the nearest thread below it, else above it, that is
still live, or still archived if the thread was."
  (let* ((now (eng/bbh-inbox--threads))
         (tail (member (assoc (car place) before) before))
         (archived (cdr (car tail)))
         (like (lambda (id) (when-let* ((cell (assoc id now))) (eq (and (cdr cell) t) (and archived t)))))
         (id (seq-find like (append (when (car place) (list (car place)))
                                    (mapcar #'car (cdr tail))
                                    (when tail (reverse (mapcar #'car (seq-take before (- (length before) (length tail))))))))))
    (or (when-let* (((and id (equal id (car place))))
                    (key (nth 2 place))
                    (start (cdr (assoc key (eng/bbh-inbox--headings)))))
          (save-excursion
            (goto-char start)
            (let ((stop (eng/bbh-inbox--next-heading start 9)))
              (forward-line (nth 3 place))
              (if (< (point) stop) (point) start))))
        (eng/bbh-inbox--find (cons id (cdr place))))))

(defun eng/bbh-inbox--folds ()
  "Each heading's key to whether its body is folded now."
  (mapcar (lambda (heading)
            (cons (car heading)
                  (save-excursion (goto-char (cdr heading)) (end-of-line)
                                  (and (not (eobp)) (invisible-p (point)) t))))
          (eng/bbh-inbox--headings)))

(defun eng/bbh-inbox--edited ()
  "Each ask's answer you typed and have not saved, ask id to text."
  (seq-keep (lambda (ask)
              (let ((text (eng/bbh-inbox--answer-text ask)))
                (unless (equal text (or (cdr (assoc ask eng/bbh-inbox--rendered)) ""))
                  (cons ask text))))
            (eng/bbh-inbox--asks)))

(defun eng/bbh-inbox--protect (edited)
  "Make all but the Answer bodies read-only, record each body's text as
rendered, and put back the answers in EDITED, typed but unsaved."
  (setq eng/bbh-inbox--rendered (mapcar (lambda (ask) (cons ask (eng/bbh-inbox--answer-text ask))) (eng/bbh-inbox--asks)))
  (pcase-dolist (`(,ask . ,text) edited)
    (when-let* ((region (eng/bbh-inbox--answer-region ask)))
      (save-excursion
        (goto-char (car region))
        (delete-region (car region) (max (car region) (1- (cdr region))))
        (insert (mapconcat (lambda (line) (concat "    " line)) (split-string text "\n") "\n")))))
  (put-text-property (point-min) (point-max) 'read-only t)
  ;; Nothing precedes the first character, so only front-stickiness guards it.
  (when (< (point-min) (point-max)) (put-text-property (point-min) (1+ (point-min)) 'front-sticky '(read-only)))
  (dolist (ask (eng/bbh-inbox--asks))
    (when-let* ((region (eng/bbh-inbox--answer-region ask)))
      ;; The body's last newline stays read-only, so the next heading never joins it.
      (remove-text-properties (car region) (max (car region) (1- (cdr region))) '(read-only nil))
      (when (> (car region) (point-min))
        (put-text-property (1- (car region)) (car region) 'rear-nonsticky '(read-only))))))

(defun eng/bbh-inbox--fold (before)
  "Fold the headings that BEFORE, from `eng/bbh-inbox--folds', had folded,
and new ones by default: Details, archived threads and threads with no ask."
  (when (fboundp 'org-fold-show-all)
    (org-fold-show-all)
    (pcase-dolist (`(,key . ,start) (reverse (eng/bbh-inbox--headings)))
      (when (if-let* ((cell (assoc key before)))
                (cdr cell)
              (save-excursion
                (goto-char start)
                (cond ((string-suffix-p "/Details" key) t)
                      ((looking-at eng/bbh-inbox--thread-re)
                       (or (equal "ARCHIVED" (match-string 1))
                           (not (eng/bbh-inbox--asks key)))))))
        (save-excursion (goto-char start) (org-fold-hide-subtree))))))

(defun eng/bbh-inbox--typing-p ()
  (and (bound-and-true-p evil-local-mode) (fboundp 'evil-insert-state-p) (evil-insert-state-p)))

(defun eng/bbh-inbox--render (text &optional quiet)
  "Show TEXT, `bbh inbox org' output: the header line, then the outline.
The cursor stays on its heading, or the nearest thread when that thread is
gone or archived, every window keeps that line where it was on screen, and
folds and answers you typed but have not saved stay.  QUIET, for a pushed
reload, waits while you type in the buffer."
  (with-current-buffer (eng/bbh-inbox--buffer)
    (if (and quiet (eng/bbh-inbox--typing-p))
        (setq eng/bbh-inbox--deferred text)
      (setq eng/bbh-inbox--deferred nil)
      (let* ((lines (split-string (string-trim-right text) "\n"))
             (body (mapconcat #'identity (cdr lines) "\n")))
        (setq header-line-format (car lines))
        (unless (equal body eng/bbh-inbox--text)
          (let ((here (eng/bbh-inbox--place (point)))
                (windows (mapcar (lambda (window)
                                   (list window (eng/bbh-inbox--place (window-point window))
                                         (count-lines (window-start window)
                                                      (save-excursion (goto-char (window-point window)) (line-beginning-position)))))
                                 (get-buffer-window-list (current-buffer) nil t)))
                (before (eng/bbh-inbox--threads))
                (edited (eng/bbh-inbox--edited))
                (folds (eng/bbh-inbox--folds))
                (inhibit-read-only t))
            (setq eng/bbh-inbox--text body)
            (let ((buffer-undo-list t))
              (erase-buffer)
              (unless (string-empty-p body) (insert body "\n"))
              (eng/bbh-inbox--protect (seq-filter (lambda (cell) (member (car cell) (eng/bbh-inbox--asks))) edited)))
            (setq buffer-undo-list nil)
            (set-buffer-modified-p nil)
            (eng/bbh-inbox--fold folds)
            (goto-char (eng/bbh-inbox--land here before))
            (pcase-dolist (`(,window ,place ,row) windows)
              (let ((pos (eng/bbh-inbox--land place before)))
                (set-window-point window pos)
                (set-window-start window (save-excursion (goto-char pos) (forward-line (- row)) (point)) t)))
            (when (bound-and-true-p hl-line-mode) (hl-line-highlight))))))))

(defun eng/bbh-inbox-refresh ()
  "Reload the Inbox from the controller."
  (interactive)
  (eng/bbh-inbox--run '("org") #'eng/bbh-inbox--render))

(defun eng/bbh-inbox--shown-p ()
  (when-let* ((buffer (get-buffer "*bbh inbox*"))) (get-buffer-window buffer t)))

(defun eng/bbh-inbox--changed (&rest _)
  "The controller committed a change: reload once Emacs is idle, if a window
shows the Inbox, else when one does."
  (cond ((not (eng/bbh-inbox--shown-p)) (setq eng/bbh-inbox--stale t))
        ((eq eng/bbh-inbox--reload 'running) (setq eng/bbh-inbox--reload 'again))
        ((null eng/bbh-inbox--reload)
         (setq eng/bbh-inbox--reload 'pending)
         ;; Idle since before the push counts, or an idle Emacs would wait for a key.
         (run-with-idle-timer (time-add (or (current-idle-time) 0) eng/bbh-inbox-reload-delay)
                              nil #'eng/bbh-inbox--reload))))

(defun eng/bbh-inbox--reload ()
  "The pushed reload: quiet, one bbh at a time, again if a push came meanwhile."
  (if (not (eng/bbh-inbox--shown-p))
      (setq eng/bbh-inbox--reload nil eng/bbh-inbox--stale t)
    (setq eng/bbh-inbox--reload 'running eng/bbh-inbox--stale nil)
    (let* ((done (lambda ()
                   (let ((again (eq eng/bbh-inbox--reload 'again)))
                     (setq eng/bbh-inbox--reload nil)
                     (when again (eng/bbh-inbox--changed)))))
           (fail (lambda (problem)
                   (with-current-buffer (eng/bbh-inbox--buffer)
                     (setq header-line-format (format "bbh inbox · reload failed: %s   gr retry" problem)))
                   (funcall done))))
      (condition-case err
          (eng/bbh-inbox--run '("org")
                              (lambda (text) (unwind-protect (eng/bbh-inbox--render text t) (funcall done)))
                              fail)
        (error (funcall fail (error-message-string err)))))))

(defun eng/bbh-inbox--on-show (_window)
  "A window began to show the Inbox: catch up on what was pushed while hidden."
  (when eng/bbh-inbox--stale (eng/bbh-inbox--changed)))

(defun eng/bbh-inbox--moved ()
  "After a command, tell bbh which thread and ask your cursor is on, for voice."
  (let ((here (cons (eng/bbh-inbox--at-point) (eng/bbh-inbox--ask))))
    (unless (equal here eng/bbh-inbox--thread)
      (setq eng/bbh-inbox--thread here)
      (when (timerp eng/bbh-inbox--timer) (cancel-timer eng/bbh-inbox--timer))
      (when (car here)
        (setq eng/bbh-inbox--timer
              (run-at-time 0.15 nil (lambda ()
                                      (eng/bbh-inbox--run (delq nil (list "select" (car here) (cdr here))) nil))))))))

;;;; Answering

(defun eng/bbh-inbox--act (&rest args)
  "Run `bbh inbox ARGS' on the thread under the cursor, then reload."
  (let ((thread (eng/bbh-inbox--thread)))
    (eng/bbh-inbox--run (append (list (car args) thread) (delq nil (cdr args)))
                        (lambda (_) (eng/bbh-inbox-refresh)))))

(defun eng/bbh-inbox--ask-here ()
  "The ask under the cursor, else the thread's first, else nil."
  (or (eng/bbh-inbox--ask) (car (eng/bbh-inbox--asks (eng/bbh-inbox--thread)))))

(defun eng/bbh-inbox--option-here ()
  "The number of the option on this line, or nil."
  (save-excursion
    (beginning-of-line)
    (when (and (eng/bbh-inbox--ask) (not (eng/bbh-inbox--in-answer-p)) (looking-at "^   \\([0-9]+\\)\\. "))
      (string-to-number (match-string 1)))))

(defun eng/bbh-inbox-pick (n)
  "Pick option N of the ask under the cursor, else of the thread's newest."
  (interactive (list (- last-command-event ?0)))
  (eng/bbh-inbox--act "pick" (number-to-string n) (eng/bbh-inbox--ask)))

(defun eng/bbh-inbox-pick-recommended ()
  "Pick the recommended option of the ask under the cursor, else the thread's first."
  (interactive)
  (let ((ask (or (eng/bbh-inbox--ask-here) (user-error "No open ask here"))))
    (save-excursion
      (goto-char (point-min))
      (re-search-forward (concat "^\\*\\* " (regexp-quote ask) "\\_>"))
      (if (re-search-forward "^   \\([0-9]+\\)\\. .*  (recommended)" (eng/bbh-inbox--next-heading (point) 3) t)
          (eng/bbh-inbox--act "pick" (match-string-no-properties 1) ask)
        (user-error "%s has no recommended option" ask)))))

(defun eng/bbh-inbox-ret ()
  "Open the link here, pick the option on this line, or open the thread's Worker."
  (interactive)
  (cond ((and (fboundp 'org-in-regexp) (boundp 'org-link-bracket-re) (org-in-regexp org-link-bracket-re 1))
         (org-open-at-point))
        ((eng/bbh-inbox--option-here) (eng/bbh-inbox-pick (eng/bbh-inbox--option-here)))
        ((eng/bbh-inbox--in-answer-p) (newline))
        (t (eng/bbh-inbox-open))))

(defun eng/bbh-inbox-answer ()
  "Type an answer to the ask under the cursor, or the thread's first, in its Answer."
  (interactive)
  (unless (eng/bbh-inbox--in-answer-p)
    (let* ((ask (or (eng/bbh-inbox--ask-here) (user-error "No open ask here; r writes a note")))
           (region (eng/bbh-inbox--answer-region ask)))
      (when (fboundp 'org-fold-show-context) (save-excursion (goto-char (car region)) (org-fold-show-context)))
      (goto-char (max (car region) (1- (cdr region))))
      (when (and (bolp) (eolp)) (insert "    "))))
  (when (fboundp 'evil-insert-state) (evil-insert-state)))

(defun eng/bbh-inbox-reply ()
  "Answer the ask under the cursor; on a thread with no ask, write its note."
  (interactive)
  (if (eng/bbh-inbox--ask-here) (eng/bbh-inbox-answer) (call-interactively #'eng/bbh-inbox-note)))

(defun eng/bbh-inbox--edn (answers)
  "ANSWERS, ((ASK . TEXT) ...), as an EDN map."
  (concat "{" (mapconcat (lambda (cell) (format "%S %S" (car cell) (substring-no-properties (cdr cell)))) answers " ") "}"))

(defun eng/bbh-inbox--save (&optional thread)
  "Save each answer you changed in THREAD, or the thread under the cursor, to its draft.
The text stays yours until a reload shows the draft has it, so a reload
already on its way with the older draft does not overwrite it."
  (when-let* ((thread (or thread (eng/bbh-inbox--at-point))))
    (dolist (ask (eng/bbh-inbox--asks thread))
      (let ((text (eng/bbh-inbox--answer-text ask)))
        (unless (or (equal text (or (cdr (assoc ask eng/bbh-inbox--rendered)) ""))
                    (equal text (cdr (assoc ask eng/bbh-inbox--saved))))
          (setf (alist-get ask eng/bbh-inbox--saved nil nil #'equal) text)
          (eng/bbh-inbox--run (list "answer" thread ask text) nil))))))

(defun eng/bbh-inbox--stopped-typing ()
  "Leaving insert state saves the answers you changed, then draws a pushed reload."
  (eng/bbh-inbox--save)
  (when-let* ((text eng/bbh-inbox--deferred)) (eng/bbh-inbox--render text t)))

(defun eng/bbh-inbox--send-thread (thread on-done)
  (eng/bbh-inbox--run (list "send" thread
                            (eng/bbh-inbox--edn (mapcar (lambda (ask) (cons ask (eng/bbh-inbox--answer-text ask)))
                                                        (eng/bbh-inbox--asks thread))))
                      on-done))

(defun eng/bbh-inbox-send ()
  "Send the thread's picks, typed answers and note as one reply."
  (interactive)
  (let ((thread (eng/bbh-inbox--thread)))
    (when (eng/bbh-inbox--typing-p) (evil-normal-state))
    (eng/bbh-inbox--send-thread thread (lambda (_) (message "Sent %s" thread) (eng/bbh-inbox-refresh)))))

(defun eng/bbh-inbox--has-draft-p (thread)
  "Whether THREAD shows a pick, a note or a typed answer."
  (or (seq-some (lambda (ask) (not (string-empty-p (eng/bbh-inbox--answer-text ask)))) (eng/bbh-inbox--asks thread))
      (save-excursion
        (goto-char (eng/bbh-inbox--find (cons thread 1)))
        (re-search-forward "  <- picked$\\|^  note: " (eng/bbh-inbox--next-heading (point) 1) t))))

(defun eng/bbh-inbox-send-all ()
  "Send every thread's draft, each as its own reply, after one question."
  (interactive)
  (when (eng/bbh-inbox--typing-p) (evil-normal-state))
  (let ((threads (seq-filter #'eng/bbh-inbox--has-draft-p (mapcar #'car (eng/bbh-inbox--threads)))))
    (cond ((null threads) (message "Nothing to send"))
          ((y-or-n-p (format "Send %s? " (string-join threads ", ")))
           (let ((next nil))
             (setq next (lambda (left)
                          (if left
                              (eng/bbh-inbox--send-thread (car left) (lambda (_) (funcall next (cdr left))))
                            (message "Sent %s" (string-join threads ", "))
                            (eng/bbh-inbox-refresh))))
             (funcall next threads))))))

(defun eng/bbh-inbox-clear ()
  "Discard the thread's draft: its picks, answers and note."
  (interactive)
  (let ((thread (eng/bbh-inbox--thread))
        (inhibit-read-only t))
    (when (eng/bbh-inbox--typing-p) (evil-normal-state))
    (dolist (ask (eng/bbh-inbox--asks thread))
      (when-let* ((region (eng/bbh-inbox--answer-region ask)))
        (delete-region (car region) (max (car region) (1- (cdr region))))
        (setf (alist-get ask eng/bbh-inbox--rendered nil nil #'equal) "")))
    (eng/bbh-inbox--act "clear")))

(defun eng/bbh-inbox-approve () "Approve the review under the cursor, else the thread's." (interactive) (eng/bbh-inbox--act "approve" (eng/bbh-inbox--ask)))
(defun eng/bbh-inbox-dismiss () "Mark the thread read." (interactive) (eng/bbh-inbox--act "dismiss"))
(defun eng/bbh-inbox-archive () "Archive the thread." (interactive) (eng/bbh-inbox--act "archive"))
(defun eng/bbh-inbox-resume () "Reopen the session of the thread's Worker after it ended." (interactive) (eng/bbh-inbox--act "resume"))
(defun eng/bbh-inbox-cancel ()
  "Cancel the thread's Worker, archived or not, after asking."
  (interactive)
  (when (y-or-n-p (format "Cancel %s's Worker? " (eng/bbh-inbox--thread)))
    (eng/bbh-inbox--act "cancel")))

(defun eng/bbh-inbox-note (text)
  "Save TEXT as the thread's note, sent with its picks."
  (interactive (list (read-string (format "%s note: " (eng/bbh-inbox--thread)))))
  (eng/bbh-inbox--act "note" text))

(defun eng/bbh-inbox-router ()
  "Show the Router's conversation, where what you type goes to the Router."
  (interactive)
  (eng/bbh-super-visit (buffer-name (or (eng/bbh-super--shell "router")
                                        (user-error "The Router is not running; start it with `bbh super start-router'")))))

(defun eng/bbh-inbox-open ()
  "Show the thread's Worker, and mark the thread seen."
  (interactive)
  (let ((thread (eng/bbh-inbox--thread)))
    (eng/bbh-inbox--run (list "seen" thread) (lambda (_) (eng/bbh-inbox-refresh)))
    (eng/bbh-inbox--run (list "buffer" thread)
                        (lambda (name) (eng/bbh-super-visit (string-trim name))))))

(defun eng/bbh-inbox-jjui ()
  "Open jjui on the thread's Worker workspace, or its repo once that is gone.
It runs in a Herdr popup, `herdr-jjui', which q in jjui closes, as does
\[eng/bbh-inbox-jjui-close] here.  Herdr shows one popup at a time, so
it replaces a terminal Inbox, which comes back when jjui quits."
  (interactive)
  (eng/bbh-inbox--run
   (list "dir" (eng/bbh-inbox--thread))
   (lambda (dir)
     (let ((err (generate-new-buffer " *herdr-jjui*")))
       (make-process
        :name "herdr-jjui" :noquery t :buffer err :connection-type 'pipe
        :command (append (list "herdr-jjui" (string-trim dir))
                          (unless (display-graphic-p) (eng/bbh-inbox--command)))
        :sentinel (lambda (process _)
                    (when (memq (process-status process) '(exit signal))
                      (unless (zerop (process-exit-status process))
                        (message "%s" (string-trim (with-current-buffer err (buffer-string)))))
                      (kill-buffer err))))))))

(defun eng/bbh-inbox-jjui-close ()
  "Close the jjui popup that `eng/bbh-inbox-jjui' opened, as q in jjui does.
Herdr closes its open popup and ends jjui.  From the terminal Inbox, which
jjui replaces, press q in jjui instead."
  (interactive)
  (let ((err (generate-new-buffer " *herdr-jjui*")))
    (make-process
     :name "herdr-jjui-close" :noquery t :buffer err :connection-type 'pipe
     :command (list "herdr-jjui" "--close")
     :sentinel (lambda (process _)
                 (when (memq (process-status process) '(exit signal))
                   (unless (zerop (process-exit-status process))
                     (message "%s" (string-trim (with-current-buffer err (buffer-string)))))
                   (kill-buffer err))))))

(defconst eng/bbh-inbox-keys
  `(("RET" eng/bbh-inbox-ret "Pick the option here, open a link, or open the thread's Worker")
    ,@(mapcar (lambda (n) (list (number-to-string n) 'eng/bbh-inbox-pick (format "Pick option %d" n)))
              (number-sequence 1 9))
    ("." eng/bbh-inbox-pick-recommended "Pick the recommended option")
    ("i" eng/bbh-inbox-answer "Answer the ask here in your own words")
    ("r" eng/bbh-inbox-reply "Answer the ask here, or write the thread's note")
    ("s" eng/bbh-inbox-send "Send the thread's picks, answers and note")
    ("S" eng/bbh-inbox-send-all "Send every thread's draft")
    ("C-c C-c" eng/bbh-inbox-send "Send the thread's picks, answers and note")
    ("C-c C-k" eng/bbh-inbox-clear "Discard the thread's draft")
    ("A" eng/bbh-inbox-approve "Approve the review")
    ("m" eng/bbh-inbox-dismiss "Mark read")
    ("a" eng/bbh-inbox-archive "Archive")
    ("x" eng/bbh-inbox-cancel "Cancel the Worker")
    ("u" eng/bbh-inbox-resume "Resume a Worker whose session ended")
    ("R" eng/bbh-inbox-router "Open the Router")
    ("J" eng/bbh-inbox-jjui "jjui on the thread's workspace, in a Herdr popup; q in jjui closes it")
    ("gJ" eng/bbh-inbox-jjui-close "Close the jjui popup")
    ("gr" eng/bbh-inbox-refresh "Reload now; pushed changes reload by themselves")
    ("g?" describe-mode "Help")
    ("q" quit-window "Quit"))
  "The Inbox's evil normal-state keys: (KEYS COMMAND DESCRIPTION).
Org's and evil-org's stay: TAB and S-TAB fold, zo, zc and za too, gj, gk,
gh and gl move by heading, and j, k, gg, G and / move as anywhere.  The
single letters replace evil commands that edit text, which only an Answer
body has; i there types as usual.  s also shadows the global avy jump.")

(defun eng/bbh-inbox--outside-answer (command)
  "COMMAND, unless the cursor is in an Answer body, where keys type."
  (unless (and (derived-mode-p 'eng/bbh-inbox-mode) (eng/bbh-inbox--in-answer-p)) command))

(defvar eng/bbh-inbox-mode-map (make-sparse-keymap)
  "The Inbox keys, for Emacs without evil: outside an Answer body a letter is
its command, inside it types.")

;; Filled on every load, since `defvar' keeps the map an older load made and
;; a Doom reload must not leave its keys behind.
(let ((map eng/bbh-inbox-mode-map)
      (parent (keymap-parent eng/bbh-inbox-mode-map)))
  (setcdr map nil)
  (when parent (set-keymap-parent map parent))
  (define-key map "g" (make-sparse-keymap))
  (dolist (key eng/bbh-inbox-keys)
    (define-key map (kbd (car key))
                (if (string-prefix-p "C-" (car key))
                    (cadr key)
                  `(menu-item "" ,(cadr key) :filter eng/bbh-inbox--outside-answer)))))

(defun eng/bbh-inbox--setup ()
  "The Inbox buffer's settings and hooks. The mode runs it, and loading this
file runs it again in a live Inbox, so a Doom reload reaches it."
  (setq truncate-lines t)
  (hl-line-mode 1)
  (add-hook 'post-command-hook #'eng/bbh-inbox--moved nil t)
  (add-hook 'window-buffer-change-functions #'eng/bbh-inbox--on-show nil t)
  (add-hook 'evil-insert-state-exit-hook #'eng/bbh-inbox--stopped-typing nil t)
  ;; Buffer-local keys win over evil-org's, such as its A and I.
  (when (fboundp 'evil-local-set-key)
    (dolist (key eng/bbh-inbox-keys)
      (evil-local-set-key 'normal (kbd (car key)) (cadr key)))
    (evil-local-set-key 'insert (kbd "C-c C-c") #'eng/bbh-inbox-send)
    (evil-local-set-key 'insert (kbd "C-c C-k") #'eng/bbh-inbox-clear)))

(define-derived-mode eng/bbh-inbox-mode org-mode "bbh inbox"
  "The threads that need you, from `bbh super', as an org outline you answer in."
  (eng/bbh-inbox--setup))

(with-eval-after-load 'evil
  (evil-set-initial-state 'eng/bbh-inbox-mode 'normal))

;; The same commands under SPC m, where which-key lists them.
(when (fboundp 'map!)
  (map! :map eng/bbh-inbox-mode-map
        :localleader
        :desc "Open Worker"   "o" #'eng/bbh-inbox-open
        :desc "jjui"          "j" #'eng/bbh-inbox-jjui
        :desc "Close jjui"    "J" #'eng/bbh-inbox-jjui-close
        :desc "Send"          "s" #'eng/bbh-inbox-send
        :desc "Send all"      "S" #'eng/bbh-inbox-send-all
        :desc "Answer"        "i" #'eng/bbh-inbox-answer
        :desc "Recommended"   "." #'eng/bbh-inbox-pick-recommended
        :desc "Discard draft" "k" #'eng/bbh-inbox-clear
        :desc "Approve"       "A" #'eng/bbh-inbox-approve
        :desc "Note"          "n" #'eng/bbh-inbox-note
        :desc "Mark read"     "m" #'eng/bbh-inbox-dismiss
        :desc "Archive"       "a" #'eng/bbh-inbox-archive
        :desc "Cancel Worker" "x" #'eng/bbh-inbox-cancel
        :desc "Resume Worker" "u" #'eng/bbh-inbox-resume
        :desc "Reload"        "g" #'eng/bbh-inbox-refresh))

(defun eng/bbh-inbox--on-push (connection _)
  "The controller connected to say something changed."
  (delete-process connection)
  (eng/bbh-inbox--changed))

(defun eng/bbh-inbox--listen ()
  "Listen for the controller's reload calls and register the port. A live
listener made by older code, whose filter is not `eng/bbh-inbox--on-push',
is replaced."
  (unless (and (process-live-p eng/bbh-inbox--listener)
               (eq (process-filter eng/bbh-inbox--listener) #'eng/bbh-inbox--on-push))
    (when (process-live-p eng/bbh-inbox--listener) (delete-process eng/bbh-inbox--listener))
    (setq eng/bbh-inbox--listener
          (make-network-process
           :name "bbh-inbox-listen" :server t :host "127.0.0.1" :service t :family 'ipv4 :noquery t
           :filter #'eng/bbh-inbox--on-push))
    (eng/bbh-inbox--run (list "register" (number-to-string (process-contact eng/bbh-inbox--listener :service)))
                        nil)))

(defun eng/bbh-inbox-buffer ()
  "Make the Inbox buffer, listening for reloads, and return its name."
  (with-current-buffer (eng/bbh-inbox--buffer)
    (unless (derived-mode-p 'eng/bbh-inbox-mode) (eng/bbh-inbox-mode))
    (eng/bbh-super-mode 1))
  (eng/bbh-inbox--listen)
  (eng/bbh-inbox-refresh)
  (buffer-name (eng/bbh-inbox--buffer)))

(defun eng/bbh-inbox ()
  "Show the super-agent's Inbox alone in this frame."
  (interactive)
  ;; Make the Inbox before clearing the frame, so a failure leaves the layout.
  (let ((buffer (eng/bbh-inbox-buffer)))
    (eng/bbh-super--clear-frame)
    (switch-to-buffer buffer)))

;; Loading this file again, as a Doom reload does, brings a live Inbox up to
;; the new code: its buffer setup and the listener.  An Inbox from before it
;; was an org outline starts the mode again and reloads, and its preview goes.
(when-let* ((buffer (get-buffer "*bbh inbox preview*"))) (kill-buffer buffer))
(when-let* ((buffer (get-buffer "*bbh inbox*")))
  (with-current-buffer buffer
    (when (derived-mode-p 'eng/bbh-inbox-mode)
      (if (derived-mode-p 'org-mode)
          (eng/bbh-inbox--setup)
        (eng/bbh-inbox-mode)
        (eng/bbh-inbox-refresh))
      (eng/bbh-inbox--listen))))
