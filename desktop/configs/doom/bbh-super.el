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
  (evil-normalize-keymaps))
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
                   ;; A finished Worker's session was ended on purpose; starting
                   ;; its client again would respawn claude, perhaps in a deleted
                   ;; workspace (T55).  The Router reopens the thread instead.
                   (when (and (buffer-live-p buffer)
                              (buffer-local-value 'eng/bbh-super-finished buffer))
                     (user-error "This Worker is finished; ask the Router to reopen its thread"))
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

(defun eng/bbh-super-end (conversation)
  "End CONVERSATION's session because its Worker ended, keeping its buffers.
The controller calls this to free the session's memory: the shell is marked
finished, so what you type goes to the Router and its client is never made
again, then the client and its claude are shut down.  The transcript stays
readable.  Returns the shell's name, or nil when no session runs CONVERSATION."
  (when-let* ((shell (eng/bbh-super--shell conversation)))
    (with-current-buffer shell
      ;; Finished first: the mailbox watch then wakes it no more.
      (setq eng/bbh-super-finished t)
      (ignore-errors (agent-shell--shutdown)))
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
  "The thread whose heading is at or above the cursor, or the thread list's row here."
  (if (derived-mode-p 'eng/bbh-threads-mode)
      (get-text-property (line-beginning-position) 'bbh-thread)
    (save-excursion
      (end-of-line)
      (when (re-search-backward eng/bbh-inbox--thread-re nil t) (match-string-no-properties 2)))))

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
                       ;; The split view shows one thread, open.
                       (and (not (eq eng/bbh-inbox-view 'split))
                            (or (equal "ARCHIVED" (match-string 1))
                                (not (eng/bbh-inbox--asks key))))))))
        (save-excursion (goto-char start) (org-fold-hide-subtree))))))

(defun eng/bbh-inbox--typing-p ()
  (evil-insert-state-p))

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

(defun eng/bbh-inbox--load-args ()
  "The bbh inbox verb that reloads the current view."
  (if (eq eng/bbh-inbox-view 'split) (delq nil (list "split" eng/bbh-inbox--selected)) '("org")))

(defun eng/bbh-inbox--show (text &optional quiet)
  "Draw TEXT, the output of `eng/bbh-inbox--load-args', in the current view."
  (if (eq eng/bbh-inbox-view 'split) (eng/bbh-inbox--render-split text quiet) (eng/bbh-inbox--render text quiet)))

(defun eng/bbh-inbox-refresh ()
  "Reload the Inbox from the controller."
  (interactive)
  (eng/bbh-inbox--run (eng/bbh-inbox--load-args) #'eng/bbh-inbox--show))

(defun eng/bbh-inbox--shown-p ()
  (seq-some (lambda (name) (when-let* ((buffer (get-buffer name))) (get-buffer-window buffer t)))
            '("*bbh inbox*" "*bbh threads*")))

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
          (eng/bbh-inbox--run (eng/bbh-inbox--load-args)
                              (lambda (text) (unwind-protect (eng/bbh-inbox--show text t) (funcall done)))
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
  (evil-insert-state))

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
(defun eng/bbh-inbox-dismiss () "Close the thread's asks and tell its Worker not to wait." (interactive) (eng/bbh-inbox--act "dismiss"))
(defun eng/bbh-inbox-read () "Mark the thread read without opening its Worker; every view shares it." (interactive) (eng/bbh-inbox--act "read"))
(defun eng/bbh-inbox-unread () "Mark the thread unread, so it shows as new again." (interactive) (eng/bbh-inbox--act "unread"))
(defun eng/bbh-inbox-read-all ()
  "Mark every unread thread read."
  (interactive)
  (eng/bbh-inbox--run '("read" "all") (lambda (_) (eng/bbh-inbox-refresh))))
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
  "Show the thread's Worker, and mark the thread read."
  (interactive)
  (eng/bbh-inbox--open-thread (eng/bbh-inbox--thread)))

(defun eng/bbh-inbox--open-thread (thread)
  "Show THREAD's Worker, and mark the thread read."
  (eng/bbh-inbox--run (list "read" thread) (lambda (_) (eng/bbh-inbox-refresh)))
  (eng/bbh-inbox--run (list "buffer" thread)
                      (lambda (name) (eng/bbh-super-visit (string-trim name)))))

(defvar eng/bbh-inbox-herdr-jjui "herdr-jjui"
  "The herdr-jjui command, which opens and closes the jjui popup.")

(defun eng/bbh-inbox--herdr-jjui (args frame)
  "Run herdr-jjui ARGS against the Herdr that FRAME is in, as told by the
frame's HERDR_SOCKET_PATH, and show its error if it fails.  Its buffer goes
when it ends, so repeated calls leave nothing behind."
  (let* ((out (generate-new-buffer " *herdr-jjui*"))
         (socket (getenv "HERDR_SOCKET_PATH" frame))
         (process-environment (if socket
                                  (cons (concat "HERDR_SOCKET_PATH=" socket) process-environment)
                                process-environment))
         (default-directory (expand-file-name "~/")))
    (make-process
     :name "herdr-jjui" :noquery t :buffer out :connection-type 'pipe
     :command (cons eng/bbh-inbox-herdr-jjui args)
     :sentinel (lambda (process _)
                 (when (memq (process-status process) '(exit signal))
                   (let ((text (with-current-buffer out (string-trim (buffer-string)))))
                     (kill-buffer out)
                     (unless (zerop (process-exit-status process))
                       (message "%s" text))))))))

(defun eng/bbh-inbox-jjui ()
  "Open jjui on the thread's Worker workspace, or its repo once that is gone.
It runs in a Herdr popup, `herdr-jjui', which one q in jjui closes, as does
\[eng/bbh-inbox-jjui-close] here, leaving you where you were.  Herdr shows
one popup at a time, so jjui replaces an Inbox in a popup, such as Herdr's
prefix t, and a fresh Inbox frame takes its place when jjui quits.  Over an
Inbox in an ordinary pane nothing needs bringing back.  J again replaces jjui
rather than stacking another."
  (interactive)
  (let ((frame (selected-frame)))
    (eng/bbh-inbox--run
     (list "dir" (eng/bbh-inbox--thread))
     (lambda (dir)
       (eng/bbh-inbox--herdr-jjui
        (append (list (string-trim dir))
                ;; herdr-jjui runs this after jjui only in place of a popup
                ;; it replaced.  GUI Emacs is never in one.
                (unless (display-graphic-p frame) (eng/bbh-inbox--command)))
        frame)))))

(defun eng/bbh-inbox-jjui-close ()
  "Close the jjui popup that `eng/bbh-inbox-jjui' opened, as q in jjui does.
Herdr closes its open popup and ends jjui.  From the terminal Inbox, which
jjui replaces, press q in jjui instead."
  (interactive)
  (eng/bbh-inbox--herdr-jjui (list "--close") (selected-frame)))

;;;; The split view

;; The default view (design 4 of T85): *bbh threads*, one line per thread,
;; on the left, and *bbh inbox*, holding only the selected thread, on the
;; right.  `bbh inbox split SEL' feeds both from one controller read: the
;; list as #+thread lines, SEL's recent events as #+progress lines, then the
;; outline `org' prints.  The right side is the outline view's buffer and
;; mode with one thread's subtree in it, so its keys work unchanged.  gv
;; switches to the old single-buffer view and back.

(defvar eng/bbh-inbox-view 'split
  "Which Inbox `eng/bbh-inbox' shows: `split', the thread list beside the
selected thread, or `outline', every thread in one buffer.")

(defvar eng/bbh-inbox-list-width 46
  "Columns of the thread list in the split view.")

(defvar eng/bbh-inbox-split-min-width 110
  "A frame narrower than this stacks the thread list above the thread.")

(defvar eng/bbh-inbox-select-delay 0.3
  "Seconds after the list's selection stops moving before the thread's progress is fetched.")

(defvar eng/bbh-inbox--rows nil
  "The thread list from the last `bbh inbox split', each a plist.")

(defvar eng/bbh-inbox--outline ""
  "The outline from the last `bbh inbox split', every thread's subtree.")

(defvar eng/bbh-inbox--list-header ""
  "The header line `bbh inbox split' printed.")

(defvar eng/bbh-inbox--progress nil
  "Each thread's progress lines fetched so far, thread id to a list of (TIME KIND TEXT).")

(defvar eng/bbh-inbox--selected nil
  "The thread the split view's list has selected.")

(defvar eng/bbh-inbox--show-archived nil
  "Whether the thread list shows archived threads, rather than one row counting them.")

(defvar eng/bbh-inbox--select-timer nil
  "The pending fetch of the selected thread.")

(defvar-local eng/bbh-inbox--shown-thread nil
  "The thread the split view's right side shows.")

(defconst eng/bbh-inbox--waiting-sections '("stopped" "holding-up" "waiting")
  "The sections of threads waiting on you, which sort first.")

(defun eng/bbh-inbox--list-buffer ()
  (get-buffer-create "*bbh threads*"))

(defun eng/bbh-inbox--parse-row (fields)
  "The thread row of FIELDS, `#+thread:' key=value pairs separated by tabs, as
a plist of :id :section :status :asks :age :unseen :worker :title.  A field
it does not know is kept under its own key, so a new one breaks nothing."
  (let (row)
    (dolist (field (split-string fields "\t"))
      (when (string-match "\\`\\([^=]+\\)=\\(.*\\)\\'" field)
        (setq row (plist-put row (intern (concat ":" (match-string 1 field))) (match-string 2 field)))))
    (setq row (plist-put row :asks (string-to-number (or (plist-get row :asks) "0"))))
    (setq row (plist-put row :unseen (equal "1" (plist-get row :unseen))))
    (plist-put row :worker (unless (string-empty-p (or (plist-get row :worker) "")) (plist-get row :worker)))))

(defun eng/bbh-inbox--parse-split (text)
  "TEXT, `bbh inbox split' output, as a plist: :header, :rows (each a plist,
see `eng/bbh-inbox--parse-row'), :selected, the thread whose :progress, a
list of (TIME KIND TEXT), it carries, and :outline."
  (let* ((lines (split-string (string-trim-right text) "\n"))
         rows progress selected outline)
    (dolist (line (cdr lines))
      (cond ((string-prefix-p "#+thread: " line)
             (push (eng/bbh-inbox--parse-row (substring line 10)) rows))
            ((string-prefix-p "#+selected: " line) (setq selected (substring line 12)))
            ((string-prefix-p "#+progress: " line)
             (push (cdr (split-string (substring line 12) "\t")) progress))
            (t (push line outline))))
    (list :header (car lines) :rows (nreverse rows) :selected selected :progress (nreverse progress)
          :outline (mapconcat #'identity (nreverse outline) "\n"))))

(defun eng/bbh-inbox--row (id)
  (seq-find (lambda (row) (equal id (plist-get row :id))) eng/bbh-inbox--rows))

(defun eng/bbh-inbox--waiting-p (row)
  (member (plist-get row :section) eng/bbh-inbox--waiting-sections))

(defun eng/bbh-inbox--glyph (row)
  "ROW's :status, which the controller decides, as ● on you, ✖ stuck, ◐ working, else ✓."
  (pcase (plist-get row :status)
    ("on-you" (propertize "●" 'face 'warning))
    ("stuck" (propertize "✖" 'face 'error))
    ("working" (propertize "◐" 'face 'success))
    (_ (propertize "✓" 'face 'shadow))))

(defun eng/bbh-inbox--row-details (row)
  "ROW's fields as `key: value' lines, the empty ones left out, for a tooltip."
  (let (lines)
    (cl-loop for (key value) on row by #'cddr
             unless (member value '(nil "" 0))
             do (push (format "%s: %s" (substring (symbol-name key) 1) (if (eq value t) "yes" value)) lines))
    (string-join (nreverse lines) "\n")))

(defun eng/bbh-inbox--row-line (row width)
  "ROW as one list line of WIDTH columns: unseen mark, status, id, title, open asks and age."
  (let* ((asks (plist-get row :asks))
         (right (string-trim (concat (if (> asks 0) (format "%d?" asks) "") " " (plist-get row :age))))
         (title-width (max 8 (- width 9 (max 6 (length right))))))
    (propertize
     (concat (if (plist-get row :unseen) (propertize "•" 'face 'bold) " ")
             (eng/bbh-inbox--glyph row) " "
             (string-pad (plist-get row :id) 4) " "
             (truncate-string-to-width (plist-get row :title) title-width nil ?\s "…") " "
             (string-pad right (max 6 (length right)) nil t))
     'bbh-thread (plist-get row :id)
     'help-echo (eng/bbh-inbox--row-details row))))

(defun eng/bbh-inbox--visible-rows (rows)
  "ROWS the list shows: all but the archived, unless they are expanded."
  (if eng/bbh-inbox--show-archived rows
    (seq-remove (lambda (row) (equal "archived" (plist-get row :section))) rows)))

(defun eng/bbh-inbox--list-text (rows width)
  "The thread list of ROWS at WIDTH, with the archived ones as one row unless expanded."
  (let* ((archived (seq-count (lambda (row) (equal "archived" (plist-get row :section))) rows))
         (live (seq-remove (lambda (row) (equal "archived" (plist-get row :section))) rows)))
    (concat (mapconcat (lambda (row) (concat (eng/bbh-inbox--row-line row width) "\n")) live "")
            (when (> archived 0)
              (concat (propertize (format "  %s %d archived\n" (if eng/bbh-inbox--show-archived "▾" "▸") archived)
                                  'bbh-archived t)
                      (when eng/bbh-inbox--show-archived
                        (mapconcat (lambda (row) (concat (eng/bbh-inbox--row-line row width) "\n"))
                                   (seq-filter (lambda (row) (equal "archived" (plist-get row :section))) rows) ""))))
            (propertize "\n● you  ◐ working  ✖ stuck  ✓ done  • new" 'face 'shadow))))

(defun eng/bbh-inbox--list-width ()
  (if-let* ((window (get-buffer-window (eng/bbh-inbox--list-buffer))))
      (1- (window-body-width window))
    (1- eng/bbh-inbox-list-width)))

(defun eng/bbh-inbox--choose (selected before now)
  "The thread to select once the list is NOW, ROWS shown, when SELECTED was
picked among BEFORE: itself while shown, else the row that took its place,
else the first thread waiting on you, else the first."
  (let ((ids (mapcar (lambda (row) (plist-get row :id)) now)))
    (cond ((member selected ids) selected)
          ((and selected now (seq-position (mapcar (lambda (row) (plist-get row :id)) before) selected))
           (let ((at (seq-position (mapcar (lambda (row) (plist-get row :id)) before) selected)))
             (or (seq-find (lambda (id) (member id ids))
                           (mapcar (lambda (row) (plist-get row :id)) (nthcdr (1+ at) before)))
                 (car (last ids)))))
          (t (plist-get (or (seq-find #'eng/bbh-inbox--waiting-p now) (car now)) :id)))))

(defun eng/bbh-inbox--row-start (id)
  "Where ID's row starts in the list, or nil."
  (save-excursion
    (goto-char (point-min))
    (let (found)
      (while (and (not found) (not (eobp)))
        (if (equal id (get-text-property (point) 'bbh-thread)) (setq found (point)) (forward-line 1)))
      found)))

(defun eng/bbh-inbox--draw-list ()
  "Draw the thread list, its cursor on the selected thread at the same screen line."
  (with-current-buffer (eng/bbh-inbox--list-buffer)
    (unless (derived-mode-p 'eng/bbh-threads-mode) (eng/bbh-threads-mode))
    (let* ((windows (mapcar (lambda (window)
                              (list window (count-lines (window-start window)
                                                        (save-excursion (goto-char (window-point window)) (line-beginning-position)))))
                            (get-buffer-window-list (current-buffer) nil t)))
           (on-archived (get-text-property (line-beginning-position) 'bbh-archived))
           (text (eng/bbh-inbox--list-text eng/bbh-inbox--rows (eng/bbh-inbox--list-width)))
           (inhibit-read-only t))
      (setq header-line-format (car (split-string eng/bbh-inbox--list-header "   ")))
      (unless (equal text (buffer-string))
        (erase-buffer)
        (insert text))
      (let ((pos (or (and on-archived (text-property-any (point-min) (point-max) 'bbh-archived t))
                     (eng/bbh-inbox--row-start eng/bbh-inbox--selected)
                     (point-min))))
        (goto-char pos)
        (pcase-dolist (`(,window ,row) windows)
          (set-window-point window pos)
          (set-window-start window (save-excursion (goto-char pos) (forward-line (- row)) (point)) t)))
      (when (bound-and-true-p hl-line-mode) (hl-line-highlight)))))

(defun eng/bbh-inbox--subtree (outline id)
  "Thread ID's subtree in OUTLINE, or nil."
  (when (string-match (concat "^\\* [A-Z-]+ " (regexp-quote id) "\\_>") outline)
    (let ((start (match-beginning 0)))
      (substring outline start (when (string-match "^\\* " outline (1+ start)) (match-beginning 0))))))

(defun eng/bbh-inbox--thread-text (id)
  "The right side for thread ID: its subtree, its progress, and its Worker."
  (let ((row (eng/bbh-inbox--row id))
        (progress (assoc id eng/bbh-inbox--progress)))
    (concat (let ((subtree (string-trim-right (or (eng/bbh-inbox--subtree eng/bbh-inbox--outline id) (format "* GONE %s" id)) "\n+")))
              ;; A blank Answer keeps its empty line, the one you type on.
              (concat subtree "\n" (when (string-match-p "^\\*\\*\\* Answer\\'" subtree) "\n")))
            "** Progress\n"
            (cond ((not progress) "   …\n")
                  ((null (cdr progress)) "   nothing yet\n")
                  (t (mapconcat (pcase-lambda (`(,time ,kind ,text)) (format "   %s  %-9s %s\n" time kind text))
                                (cdr progress) "")))
            "** Worker\n"
            (format "   [[bbh-worker:%s][%s]]   J jjui" id
                    (if-let* ((worker (plist-get row :worker))) (format "%s's buffer" worker) "its Worker's buffer")))))

(defun eng/bbh-inbox--thread-header (id)
  (concat (if id (format "%s   " id) "")
          "1-9 pick  . recommended  i answer  s send (C-n in an Answer)  TAB fold  RET open  o list  ]t next waiting  J jjui  gv one buffer  g? help"))

(defun eng/bbh-inbox--draw-thread (&optional quiet)
  "Show the selected thread on the right. QUIET, for a pushed reload, waits
while you type there; a new selection does not."
  (with-current-buffer (eng/bbh-inbox--buffer)
    (unless (derived-mode-p 'eng/bbh-inbox-mode) (eng/bbh-inbox-mode))
    (let* ((id eng/bbh-inbox--selected)
           (switched (not (equal id eng/bbh-inbox--shown-thread))))
      (when (and switched eng/bbh-inbox--shown-thread (not (eng/bbh-inbox--typing-p)))
        (eng/bbh-inbox--save eng/bbh-inbox--shown-thread))
      (eng/bbh-inbox--render (concat (eng/bbh-inbox--thread-header id) "\n" (if id (eng/bbh-inbox--thread-text id) ""))
                             (and quiet (not switched)))
      (when (and switched (not eng/bbh-inbox--deferred))
        (setq eng/bbh-inbox--shown-thread id)
        (let ((pos (save-excursion (goto-char (point-min))
                                   (if (re-search-forward eng/bbh-inbox--ask-re nil t) (line-beginning-position) (point-min)))))
          (goto-char pos)
          (dolist (window (get-buffer-window-list (current-buffer) nil t))
            (set-window-point window pos)
            (set-window-start window (point-min) t)))))))

(defun eng/bbh-inbox--render-split (text &optional quiet)
  "Show TEXT, `bbh inbox split' output, in both panes. The selection stays
on its thread while that is listed. QUIET, for a pushed reload, leaves the
right side and the selection alone while you type an answer there."
  (let* ((data (eng/bbh-inbox--parse-split text))
         (before (eng/bbh-inbox--visible-rows eng/bbh-inbox--rows))
         (typing (with-current-buffer (eng/bbh-inbox--buffer) (eng/bbh-inbox--typing-p))))
    (setq eng/bbh-inbox--rows (plist-get data :rows)
          eng/bbh-inbox--outline (plist-get data :outline)
          eng/bbh-inbox--list-header (plist-get data :header))
    (when-let* ((id (plist-get data :selected)))
      (setf (alist-get id eng/bbh-inbox--progress nil nil #'equal) (plist-get data :progress)))
    (unless (and quiet typing)
      (setq eng/bbh-inbox--selected
            (eng/bbh-inbox--choose eng/bbh-inbox--selected before (eng/bbh-inbox--visible-rows eng/bbh-inbox--rows))))
    (eng/bbh-inbox--draw-list)
    (eng/bbh-inbox--draw-thread quiet)
    (unless (assoc eng/bbh-inbox--selected eng/bbh-inbox--progress) (eng/bbh-inbox--fetch-selected))))

(defun eng/bbh-inbox--fetch-selected ()
  "Soon fetch the selected thread, with its progress, and tell bbh it is selected, for voice."
  (when (timerp eng/bbh-inbox--select-timer) (cancel-timer eng/bbh-inbox--select-timer))
  (setq eng/bbh-inbox--select-timer
        (run-at-time eng/bbh-inbox-select-delay nil
                     (lambda ()
                       (setq eng/bbh-inbox--select-timer nil)
                       (when-let* ((id eng/bbh-inbox--selected))
                         (eng/bbh-inbox--run (list "split" id "select")
                                             (lambda (text) (when (eq eng/bbh-inbox-view 'split)
                                                              (eng/bbh-inbox--render-split text t)))))))))

(defun eng/bbh-inbox--select (id)
  "Select thread ID: the list's cursor goes to it and the right side shows it."
  (unless (equal id eng/bbh-inbox--selected)
    (setq eng/bbh-inbox--selected id)
    (with-current-buffer (eng/bbh-inbox--list-buffer)
      (when-let* ((pos (eng/bbh-inbox--row-start id)))
        (goto-char pos)
        (dolist (window (get-buffer-window-list (current-buffer) nil t)) (set-window-point window pos))))
    (eng/bbh-inbox--draw-thread)
    (eng/bbh-inbox--fetch-selected)))

(defun eng/bbh-threads--moved ()
  "After a command in the list, show the thread its cursor is on."
  (when-let* ((id (get-text-property (line-beginning-position) 'bbh-thread)))
    (unless (equal id eng/bbh-inbox--selected)
      (eng/bbh-inbox--select id))))

(defun eng/bbh-threads--step (n)
  "Move N rows in the list, skipping lines that are not a thread or the archived row."
  (let ((start (point)) (moved nil))
    (while (and (not moved) (zerop (forward-line (if (> n 0) 1 -1))) (not (eobp)))
      (when (or (get-text-property (point) 'bbh-thread) (get-text-property (point) 'bbh-archived))
        (setq n (if (> n 0) (1- n) (1+ n)))
        (when (zerop n) (setq moved t))))
    (unless moved (goto-char start))))

(defun eng/bbh-threads-next () "Next thread." (interactive) (eng/bbh-threads--step 1))
(defun eng/bbh-threads-previous () "Previous thread." (interactive) (eng/bbh-threads--step -1))

(defun eng/bbh-inbox-next-waiting (&optional back)
  "Go to the next thread waiting on you, after the selected one or the
cursor's, wrapping; with BACK, the previous one."
  (interactive)
  (if (eq eng/bbh-inbox-view 'split)
      (let* ((waiting (mapcar (lambda (row) (plist-get row :id))
                              (seq-filter #'eng/bbh-inbox--waiting-p (eng/bbh-inbox--visible-rows eng/bbh-inbox--rows))))
             (ids (mapcar (lambda (row) (plist-get row :id)) (eng/bbh-inbox--visible-rows eng/bbh-inbox--rows)))
             (order (if back (reverse ids) ids))
             (after (cdr (member eng/bbh-inbox--selected order)))
             (next (or (seq-find (lambda (id) (member id waiting)) after)
                       (seq-find (lambda (id) (member id waiting)) order))))
        (if next (eng/bbh-inbox--select next) (message "Nothing is waiting on you")))
    (let ((re (concat "^\\* " (regexp-opt (mapcar #'upcase eng/bbh-inbox--waiting-sections)) " ")))
      (if-let* ((pos (save-excursion
                       (if back (beginning-of-line) (end-of-line))
                       (or (if back (re-search-backward re nil t) (re-search-forward re nil t))
                           (progn (goto-char (if back (point-max) (point-min)))
                                  (if back (re-search-backward re nil t) (re-search-forward re nil t)))))))
          (progn (goto-char pos) (beginning-of-line))
        (message "Nothing is waiting on you")))))

(defun eng/bbh-inbox-previous-waiting ()
  "Go to the previous thread waiting on you."
  (interactive)
  (eng/bbh-inbox-next-waiting t))

(defun eng/bbh-inbox--layout ()
  "Show the split view alone in the frame: the list on the left, or on top
in a narrow frame, and the thread beside it. Returns the list's window."
  (eng/bbh-super--clear-frame)
  (switch-to-buffer (eng/bbh-inbox--list-buffer))
  (let ((thread (if (>= (frame-width) eng/bbh-inbox-split-min-width)
                    (split-window-right (min eng/bbh-inbox-list-width (/ (frame-width) 2)))
                  (split-window-below (max 6 (min 14 (/ (frame-height) 3)))))))
    (set-window-buffer thread (eng/bbh-inbox--buffer))
    (selected-window)))

(defun eng/bbh-inbox-other-pane ()
  "Move between the thread list and the selected thread."
  (interactive)
  (unless (eq eng/bbh-inbox-view 'split) (user-error "gv shows the split view, which has two panes"))
  (let ((target (if (derived-mode-p 'eng/bbh-threads-mode) (eng/bbh-inbox--buffer) (eng/bbh-inbox--list-buffer))))
    (unless (get-buffer-window target) (eng/bbh-inbox--layout))
    (select-window (get-buffer-window target))))

(defun eng/bbh-threads-ret ()
  "On the archived row show or hide the archived threads, else open the thread's Worker."
  (interactive)
  (if (get-text-property (line-beginning-position) 'bbh-archived)
      (progn (setq eng/bbh-inbox--show-archived (not eng/bbh-inbox--show-archived))
             (eng/bbh-inbox--draw-list))
    (eng/bbh-inbox-open)))

(defun eng/bbh-inbox-toggle-view ()
  "Switch between the split view and the one-buffer outline of every thread."
  (interactive)
  (let ((here (eng/bbh-inbox--at-point)))
    (setq eng/bbh-inbox-view (if (eq eng/bbh-inbox-view 'split) 'outline 'split))
    (when here (setq eng/bbh-inbox--selected here))
    (with-current-buffer (eng/bbh-inbox--buffer)
      (setq eng/bbh-inbox--text nil eng/bbh-inbox--shown-thread nil))
    (eng/bbh-inbox)
    (message "Inbox: %s view; gv switches back" (if (eq eng/bbh-inbox-view 'split) "split" "one-buffer"))))

(defun eng/bbh-inbox--client-frame ()
  "A live emacsclient frame, one showing the Inbox first."
  (let ((frames (seq-filter (lambda (f) (and (frame-live-p f) (processp (frame-parameter f 'client))
                                            (not (frame-parameter f 'parent-frame))))
                            (frame-list))))
    (or (seq-find (lambda (f) (or (get-buffer-window (eng/bbh-inbox--buffer) f)
                                  (get-buffer-window (eng/bbh-inbox--list-buffer) f)))
                  frames)
        (car frames))))

(defun eng/bbh-inbox--jump (thread ask)
  "Put the cursor on ASK, else on THREAD, in the Inbox's window, and select it."
  (with-current-buffer (eng/bbh-inbox--buffer)
    (let ((pos (save-excursion
                 (goto-char (point-min))
                 (when (eq eng/bbh-inbox-view 'outline)
                   (re-search-forward (concat "^\\* [A-Z-]+ " (regexp-quote thread) "\\_>") nil t))
                 (when ask (re-search-forward (concat "^\\*\\* " (regexp-quote ask) "\\_>") nil t))
                 (line-beginning-position))))
      (goto-char pos)
      (when-let* ((window (get-buffer-window (current-buffer))))
        (set-window-point window pos)
        (select-window window)))))

(defun eng/bbh-inbox-goto (thread &optional ask)
  "Show the Inbox at THREAD and its ASK in an emacsclient frame, as a click on
a bbh notification does.  Returns that frame's tty, for the controller to
focus the window it is in, or nil when no frame is open."
  (when-let* ((frame (eng/bbh-inbox--client-frame)))
    (select-frame-set-input-focus frame)
    (eng/bbh-inbox)
    (if (eq eng/bbh-inbox-view 'split)
        (eng/bbh-inbox--select thread)
      (setq eng/bbh-inbox--selected thread))
    (eng/bbh-inbox--jump thread ask)
    ;; The Inbox reloads on its own; land on the ask again once it has.
    (eng/bbh-inbox--run (eng/bbh-inbox--load-args)
                        (lambda (text)
                          (eng/bbh-inbox--show text)
                          (when (eq eng/bbh-inbox-view 'split) (eng/bbh-inbox--select thread))
                          (eng/bbh-inbox--jump thread ask)))
    (terminal-name (frame-terminal frame))))

(defun eng/bbh-inbox-do-not-disturb ()
  "Turn bbh's desktop notifications off, or back on: :do-not-disturb in policy.edn."
  (interactive)
  (eng/bbh-inbox--run (list "dnd") (lambda (text) (message "%s" (string-trim text)))))

(with-eval-after-load 'org
  (when (fboundp 'org-link-set-parameters)
    (org-link-set-parameters "bbh-worker" :follow (lambda (id _) (eng/bbh-inbox--open-thread id)))))

(defconst eng/bbh-threads-keys
  '(("j" eng/bbh-threads-next "Next thread")
    ("k" eng/bbh-threads-previous "Previous thread")
    ("n" eng/bbh-threads-next "Next thread")
    ("p" eng/bbh-threads-previous "Previous thread")
    ("]t" eng/bbh-inbox-next-waiting "Next thread waiting on you")
    ("[t" eng/bbh-inbox-previous-waiting "Previous thread waiting on you")
    ("o" eng/bbh-inbox-other-pane "Go to the selected thread")
    ("TAB" eng/bbh-inbox-other-pane "Go to the selected thread")
    ("RET" eng/bbh-threads-ret "Open the thread's Worker, or show the archived threads")
    ("A" eng/bbh-inbox-approve "Approve the review")
    ("m" eng/bbh-inbox-read "Mark read, without opening")
    ("M" eng/bbh-inbox-read-all "Mark every thread read")
    ("U" eng/bbh-inbox-unread "Mark unread")
    ("D" eng/bbh-inbox-dismiss "Dismiss the asks: the Worker stops waiting")
    ("a" eng/bbh-inbox-archive "Archive")
    ("x" eng/bbh-inbox-cancel "Cancel the Worker")
    ("u" eng/bbh-inbox-resume "Resume a Worker whose session ended")
    ("R" eng/bbh-inbox-router "Open the Router")
    ("J" eng/bbh-inbox-jjui "jjui on the thread's workspace")
    ("gJ" eng/bbh-inbox-jjui-close "Close the jjui popup")
    ("S" eng/bbh-inbox-send-all "Send every thread's draft")
    ("gr" eng/bbh-inbox-refresh "Reload now")
    ("gn" eng/bbh-inbox-do-not-disturb "Turn desktop notifications off or on")
    ("gv" eng/bbh-inbox-toggle-view "The one-buffer view")
    ("g?" describe-mode "Help")
    ("q" quit-window "Quit"))
  "The thread list's evil normal-state keys: (KEYS COMMAND DESCRIPTION).
Bare C-h and C-l still move between the panes, as between any windows.")

(defun eng/bbh-threads--setup ()
  "The thread list's settings and hooks; loading this file runs it again in a live list."
  (setq truncate-lines t)
  (hl-line-mode 1)
  (add-hook 'post-command-hook #'eng/bbh-threads--moved nil t)
  (add-hook 'window-buffer-change-functions #'eng/bbh-inbox--on-show nil t)
  (dolist (key eng/bbh-threads-keys)
    (evil-local-set-key 'normal (kbd (car key)) (cadr key))))

(define-derived-mode eng/bbh-threads-mode special-mode "bbh threads"
  "The super-agent's threads, one per line, beside the selected one.
● waits on you, ◐ is working, ✖ is stuck, ✓ is done, and • marks a change
you have not read; m marks it read without opening it."
  (eng/bbh-threads--setup))

(evil-set-initial-state 'eng/bbh-threads-mode 'normal)

(defconst eng/bbh-inbox-keys
  `(("RET" eng/bbh-inbox-ret "Pick the option here, open a link, or open the thread's Worker")
    ,@(mapcar (lambda (n) (list (number-to-string n) 'eng/bbh-inbox-pick (format "Pick option %d" n)))
              (number-sequence 1 9))
    ("." eng/bbh-inbox-pick-recommended "Pick the recommended option")
    ("i" eng/bbh-inbox-answer "Answer the ask here in your own words")
    ("r" eng/bbh-inbox-reply "Answer the ask here, or write the thread's note")
    ("s" eng/bbh-inbox-send "Send the thread's picks, answers and note")
    ("S" eng/bbh-inbox-send-all "Send every thread's draft")
    ("C-c C-k" eng/bbh-inbox-clear "Discard the thread's draft")
    ("A" eng/bbh-inbox-approve "Approve the review")
    ("m" eng/bbh-inbox-read "Mark read, without opening")
    ("M" eng/bbh-inbox-read-all "Mark every thread read")
    ("U" eng/bbh-inbox-unread "Mark unread")
    ("D" eng/bbh-inbox-dismiss "Dismiss the asks: the Worker stops waiting")
    ("a" eng/bbh-inbox-archive "Archive")
    ("x" eng/bbh-inbox-cancel "Cancel the Worker")
    ("u" eng/bbh-inbox-resume "Resume a Worker whose session ended")
    ("R" eng/bbh-inbox-router "Open the Router")
    ("]t" eng/bbh-inbox-next-waiting "Next thread waiting on you")
    ("[t" eng/bbh-inbox-previous-waiting "Previous thread waiting on you")
    ("o" eng/bbh-inbox-other-pane "Go to the thread list, in the split view")
    ("gv" eng/bbh-inbox-toggle-view "Switch between the split view and one buffer")
    ("J" eng/bbh-inbox-jjui "jjui on the thread's workspace, in a Herdr popup; q in jjui closes it")
    ("gJ" eng/bbh-inbox-jjui-close "Close the jjui popup")
    ("gr" eng/bbh-inbox-refresh "Reload now; pushed changes reload by themselves")
    ("gn" eng/bbh-inbox-do-not-disturb "Turn desktop notifications off or on (do not disturb)")
    ("g?" describe-mode "Help")
    ("q" quit-window "Quit"))
  "The Inbox's evil normal-state keys: (KEYS COMMAND DESCRIPTION).
Org's and evil-org's stay: TAB and S-TAB fold, zo, zc and za too, gj, gk,
gh and gl move by heading, and j, k, gg, G and / move as anywhere.  The
single letters replace evil commands that edit text, which only an Answer
body has; i there types as usual.  s also shadows the global avy jump.")



(defun eng/bbh-inbox--setup ()
  "The Inbox buffer's settings and hooks. The mode runs it, and loading this
file runs it again in a live Inbox, so a Doom reload reaches it.  The org
outline and the split view's right pane are this one buffer, so its text
wraps at the window in both, and `org-indent-mode' keeps a wrapped line
under its heading's text or a list item's text.  It sets the wrap prefix
itself, so `visual-wrap-prefix-mode' and adaptive-wrap would fight it.  The
thread list, in its own mode, stays one truncated line per thread."
  (setq truncate-lines nil word-wrap t)
  (visual-line-mode 1)
  (org-indent-mode 1)
  (hl-line-mode 1)
  (add-hook 'post-command-hook #'eng/bbh-inbox--moved nil t)
  (add-hook 'window-buffer-change-functions #'eng/bbh-inbox--on-show nil t)
  (add-hook 'evil-insert-state-exit-hook #'eng/bbh-inbox--stopped-typing nil t)
  ;; Buffer-local keys win over evil-org's, such as its A and I.  Insert
  ;; state types every printable key; only these send or discard.
  (dolist (key eng/bbh-inbox-keys)
    (evil-local-set-key 'normal (kbd (car key)) (cadr key)))
  ;; C-n sends, unless a completion popup is open.  In normal state it sends
  ;; only from an Answer; elsewhere evil's own C-n runs.
  (evil-local-set-key 'insert (kbd "C-n") (eng/bbh-send-key #'eng/bbh-inbox-send))
  (evil-local-set-key 'normal (kbd "C-n") (eng/bbh-send-key #'eng/bbh-inbox-send #'eng/bbh-inbox--in-answer-p))
  ;; C-c C-c does not send; clear it from an Inbox set up by older code.
  (dolist (state '(normal insert))
    (evil-local-set-key state (kbd "C-c C-c") nil))
  (evil-local-set-key 'insert (kbd "C-c C-k") #'eng/bbh-inbox-clear))

(define-derived-mode eng/bbh-inbox-mode org-mode "bbh inbox"
  "The threads that need you, from `bbh super', as an org outline you answer in."
  (eng/bbh-inbox--setup))

(evil-set-initial-state 'eng/bbh-inbox-mode 'normal)

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
        :desc "Mark read"     "m" #'eng/bbh-inbox-read
        :desc "All read"      "M" #'eng/bbh-inbox-read-all
        :desc "Mark unread"   "U" #'eng/bbh-inbox-unread
        :desc "Dismiss asks"  "D" #'eng/bbh-inbox-dismiss
        :desc "Archive"       "a" #'eng/bbh-inbox-archive
        :desc "Cancel Worker" "x" #'eng/bbh-inbox-cancel
        :desc "Resume Worker" "u" #'eng/bbh-inbox-resume
        :desc "Reload"        "g" #'eng/bbh-inbox-refresh
        :desc "Do not disturb" "N" #'eng/bbh-inbox-do-not-disturb))

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
  (when (eq eng/bbh-inbox-view 'split)
    (with-current-buffer (eng/bbh-inbox--list-buffer)
      (unless (derived-mode-p 'eng/bbh-threads-mode) (eng/bbh-threads-mode))
      (eng/bbh-super-mode 1)))
  (eng/bbh-inbox--listen)
  (eng/bbh-inbox-refresh)
  (buffer-name (eng/bbh-inbox--buffer)))

(defun eng/bbh-inbox ()
  "Show the super-agent's Inbox alone in this frame: by default the thread
list beside the selected thread, or with gv every thread in one buffer."
  (interactive)
  ;; Make the Inbox before clearing the frame, so a failure leaves the layout.
  (let ((buffer (eng/bbh-inbox-buffer)))
    (if (eq eng/bbh-inbox-view 'split)
        (eng/bbh-inbox--layout)
      (eng/bbh-super--clear-frame)
      (switch-to-buffer buffer))))

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
(when-let* ((buffer (get-buffer "*bbh threads*")))
  (with-current-buffer buffer
    (when (derived-mode-p 'eng/bbh-threads-mode) (eng/bbh-threads--setup))))
