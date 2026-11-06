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
Side windows, the Inbox preview and a Worker's workflow panel, survive
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
          (with-current-buffer buffer (setq default-directory dir))))
      (when-let* ((panel (get-buffer eng/bbh-workflow-buffer)))
        (with-current-buffer panel
          (when (equal eng/bbh-workflow--session (eng/bbh--session-id shell))
            (setq eng/bbh-workflow--dir dir)))))
    (buffer-name shell)))

(defun eng/bbh-super-visit (name)
  "Show the session in buffer NAME as its transcript above its composer, alone in the frame."
  (let ((shell (or (get-buffer name) (user-error "No buffer %s; is its Worker running?" name))))
    (eng/bbh-super--clear-frame)
    (let ((composer (eng/bbh-layout shell)))
      (with-current-buffer composer (eng/bbh-super-mode 1))
      composer)))

;;; Super-agent Inbox

(defvar eng/bbh-inbox-bbh nil
  "The bbh command the Inbox runs, as a list, or nil for bbh on the PATH.")

(defvar eng/bbh-inbox--listener nil
  "The local server the controller connects to when the Inbox should reload.")

(defvar-local eng/bbh-inbox--thread nil
  "The thread under the cursor when the Inbox last looked.")

(defvar-local eng/bbh-inbox--timer nil
  "The pending preview update.")

(defvar-local eng/bbh-inbox--rows nil
  "The row lines the Inbox shows, so a reload that changed none redraws nothing.")

(defvar eng/bbh-inbox-reload-delay 0.5
  "Seconds of idleness a pushed change waits for before it reloads the Inbox.
Pushes inside that time make one reload, and none runs while you type.")

(defvar eng/bbh-inbox--reload nil
  "Where the pushed reload is: nil, `pending' while it waits for idleness,
`running' while bbh runs, `again' when a push came during that run.")

(defvar eng/bbh-inbox--stale nil
  "Whether a push came while no window showed the Inbox.")

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

(defun eng/bbh-inbox--place (pos)
  "Where POS is in the Inbox: its line's thread and line number."
  (cons (get-text-property (save-excursion (goto-char pos) (line-beginning-position)) 'eng/bbh-thread)
        (line-number-at-pos pos)))

(defun eng/bbh-inbox--find (place)
  "The start of PLACE's thread's line, else of its line number."
  (save-excursion
    (goto-char (point-min))
    (if (and (car place) (text-property-search-forward 'eng/bbh-thread (car place) t))
        (line-beginning-position)
      (forward-line (1- (cdr place)))
      (line-beginning-position))))

(defun eng/bbh-inbox--threads ()
  "The Inbox's threads in order, each (ID . ARCHIVED)."
  (save-excursion
    (goto-char (point-min))
    (let (threads)
      (while (not (eobp))
        (when-let* ((id (get-text-property (point) 'eng/bbh-thread)))
          (push (cons id (get-text-property (point) 'eng/bbh-archived)) threads))
        (forward-line 1))
      (nreverse threads))))

(defun eng/bbh-inbox--land (place before)
  "Where PLACE lands after a redraw, BEFORE being `eng/bbh-inbox--threads' then.
On its thread, unless that is gone or has just moved to Archived at the
bottom, as archiving does; then on the nearest thread below it, else above
it, that is still live, or still archived if the thread was."
  (let* ((now (eng/bbh-inbox--threads))
         (tail (member (assoc (car place) before) before))
         (archived (cdr (car tail)))
         (like (lambda (id) (when-let* ((cell (assoc id now))) (eq (and (cdr cell) t) (and archived t)))))
         (id (seq-find like (append (when (car place) (list (car place)))
                                    (mapcar #'car (cdr tail))
                                    (when tail (reverse (mapcar #'car (seq-take before (- (length before) (length tail))))))))))
    (eng/bbh-inbox--find (cons id (cdr place)))))

(defun eng/bbh-inbox--render (text &optional quiet)
  "Show TEXT, `bbh inbox view' output: the header line, then id TAB row lines.
The cursor stays on its thread, or the nearest one when that thread is gone
or archived, and every window keeps that line where it was on screen.  QUIET, for a pushed reload, leaves a hidden preview hidden."
  (let ((buffer (eng/bbh-inbox--buffer)))
    (with-current-buffer buffer
      (let ((lines (split-string (string-trim-right text) "\n"))
            (inhibit-read-only t))
        (setq header-line-format (car lines))
        (unless (equal (cdr lines) eng/bbh-inbox--rows)
          (let ((here (eng/bbh-inbox--place (point)))
                (windows (mapcar (lambda (window)
                                   (list window (eng/bbh-inbox--place (window-point window))
                                         (count-lines (window-start window)
                                                      (save-excursion (goto-char (window-point window)) (line-beginning-position)))))
                                 (get-buffer-window-list buffer nil t)))
                (before (eng/bbh-inbox--threads)))
            (setq eng/bbh-inbox--rows (cdr lines))
            (erase-buffer)
            (dolist (row (cdr lines))
              (pcase-let ((`(,id ,shown) (split-string row "\t")))
                ;; `bbh inbox view' starts a row with its section's title.
                (insert (propertize (or shown "") 'eng/bbh-thread id
                                    'eng/bbh-archived (string-prefix-p "ARCHIVED " (or shown "")))
                        "\n")))
            (goto-char (eng/bbh-inbox--land here before))
            (pcase-dolist (`(,window ,place ,row) windows)
              (let ((pos (eng/bbh-inbox--land place before)))
                (set-window-point window pos)
                (set-window-start window (save-excursion (goto-char pos) (forward-line (- row)) (point)) t)))
            (when (bound-and-true-p hl-line-mode) (hl-line-highlight))))))
    (eng/bbh-inbox--preview quiet)))

(defun eng/bbh-inbox-refresh ()
  "Reload the Inbox from the controller."
  (interactive)
  (eng/bbh-inbox--run '("view") #'eng/bbh-inbox--render))

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
          (eng/bbh-inbox--run '("view")
                              (lambda (text) (unwind-protect (eng/bbh-inbox--render text t) (funcall done)))
                              fail)
        (error (funcall fail (error-message-string err)))))))

(defun eng/bbh-inbox--on-show (_window)
  "A window began to show the Inbox: catch up on what was pushed while hidden."
  (when eng/bbh-inbox--stale (eng/bbh-inbox--changed)))

(defun eng/bbh-inbox--at-point ()
  (get-text-property (line-beginning-position) 'eng/bbh-thread))

(defun eng/bbh-inbox--thread ()
  (or (eng/bbh-inbox--at-point) (user-error "No thread on this line")))

(defvar-local eng/bbh-inbox--previewed nil
  "The thread the preview shows.")

(defun eng/bbh-inbox--preview (&optional quiet)
  "Show the thread under the cursor in the preview below the list.
QUIET, for a pushed reload, updates a preview on screen only, says nothing
on failure, and keeps your place in it while it shows the same thread."
  (when-let* ((buffer (get-buffer "*bbh inbox*"))
              (thread (with-current-buffer buffer (eng/bbh-inbox--at-point)))
              ((or (not quiet) (get-buffer-window "*bbh inbox preview*" t))))
    (eng/bbh-inbox--run
     (list "preview" thread)
     (lambda (text)
       (with-current-buffer (get-buffer-create "*bbh inbox preview*")
         (unless (and (equal thread eng/bbh-inbox--previewed) (equal text (buffer-string)))
           (let ((inhibit-read-only t)
                 (same (equal thread eng/bbh-inbox--previewed))
                 (windows (mapcar (lambda (w) (list w (window-start w) (window-point w)))
                                  (get-buffer-window-list (current-buffer) nil t)))
                 (here (point)))
             (unless (derived-mode-p 'special-mode) (special-mode))
             (erase-buffer)
             (insert text)
             (setq eng/bbh-inbox--previewed thread)
             (goto-char (if same (min here (point-max)) (point-min)))
             (pcase-dolist (`(,w ,start ,at) windows)
               (set-window-start w (if same (min start (point-max)) (point-min)) t)
               (set-window-point w (if same (min at (point-max)) (point-min)))))))
       (when (and (not quiet) (get-buffer-window buffer))
         (display-buffer-in-side-window (get-buffer "*bbh inbox preview*")
                                        '((side . bottom) (window-height . 0.5)))))
     (when quiet #'ignore))))

(defun eng/bbh-inbox--moved ()
  "After a command, tell bbh which thread your cursor is on, for voice, and preview it."
  (let ((thread (eng/bbh-inbox--at-point)))
    (unless (equal thread eng/bbh-inbox--thread)
      (setq eng/bbh-inbox--thread thread)
      (when (timerp eng/bbh-inbox--timer) (cancel-timer eng/bbh-inbox--timer))
      (when thread
        (setq eng/bbh-inbox--timer
              (run-at-time 0.15 nil (lambda ()
                                      (eng/bbh-inbox--run (list "select" thread) nil)
                                      (eng/bbh-inbox--preview))))))))

(defun eng/bbh-inbox--act (&rest args)
  "Run `bbh inbox ARGS' on the thread under the cursor, then reload."
  (let ((thread (eng/bbh-inbox--thread)))
    (eng/bbh-inbox--run (append (list (car args) thread) (cdr args))
                        (lambda (_) (eng/bbh-inbox-refresh)))))

(defun eng/bbh-inbox-pick (n)
  "Pick option N of the thread's open ask."
  (interactive (list (- last-command-event ?0)))
  (eng/bbh-inbox--act "pick" (number-to-string n)))

(defun eng/bbh-inbox-send () "Send the thread's picks and note." (interactive) (eng/bbh-inbox--act "send"))
(defun eng/bbh-inbox-approve () "Approve the thread's review." (interactive) (eng/bbh-inbox--act "approve"))
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
It runs in a Herdr popup, `herdr-jjui'.  Herdr shows one popup at a time, so
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

(defconst eng/bbh-inbox-keys
  `(("RET" eng/bbh-inbox-open "Open the thread's Worker")
    ("1" eng/bbh-inbox-pick "Pick option 1")
    ("2" eng/bbh-inbox-pick "Pick option 2")
    ("3" eng/bbh-inbox-pick "Pick option 3")
    ("4" eng/bbh-inbox-pick "Pick option 4")
    ("s" eng/bbh-inbox-send "Send picks and note")
    ("A" eng/bbh-inbox-approve "Approve the review")
    ("r" eng/bbh-inbox-note "Write a note")
    ("m" eng/bbh-inbox-dismiss "Mark read")
    ("a" eng/bbh-inbox-archive "Archive")
    ("x" eng/bbh-inbox-cancel "Cancel the Worker")
    ("u" eng/bbh-inbox-resume "Resume a Worker whose session ended")
    ("R" eng/bbh-inbox-router "Open the Router")
    ("J" eng/bbh-inbox-jjui "jjui on the thread's workspace")
    ("gr" eng/bbh-inbox-refresh "Reload now; pushed changes reload by themselves")
    ("g?" describe-mode "Help")
    ("q" quit-window "Quit"))
  "The Inbox's evil normal-state keys: (KEYS COMMAND DESCRIPTION).
Motions stay evil's own, so j, k, gg, G, / and C-j/C-k work as anywhere.
The single letters replace evil commands that edit text, which this
read-only list has no use for; s also shadows the global avy jump here.")

(defvar eng/bbh-inbox-mode-map
  (let ((map (make-sparse-keymap)))
    ;; g is a prefix here, as in evil, not special-mode's revert.
    (define-key map "g" (make-sparse-keymap))
    (dolist (key eng/bbh-inbox-keys)
      (define-key map (kbd (car key)) (cadr key)))
    map)
  "The Inbox keys, for Emacs without evil.")

(defun eng/bbh-inbox--setup ()
  "The Inbox buffer's settings and hooks. The mode runs it, and loading this
file runs it again in a live Inbox, so a Doom reload reaches it."
  (setq truncate-lines t)
  (hl-line-mode 1)
  (add-hook 'post-command-hook #'eng/bbh-inbox--moved nil t)
  (add-hook 'window-buffer-change-functions #'eng/bbh-inbox--on-show nil t))

(define-derived-mode eng/bbh-inbox-mode special-mode "bbh inbox"
  "The threads that need you, from `bbh super'."
  (eng/bbh-inbox--setup))

(with-eval-after-load 'evil
  (evil-set-initial-state 'eng/bbh-inbox-mode 'normal)
  (dolist (key eng/bbh-inbox-keys)
    (evil-define-key* '(normal motion) eng/bbh-inbox-mode-map (kbd (car key)) (cadr key))))

;; The same commands under SPC m, where which-key lists them.
(when (fboundp 'map!)
  (map! :map eng/bbh-inbox-mode-map
        :localleader
        :desc "Open Worker"   "o" #'eng/bbh-inbox-open
        :desc "jjui"          "j" #'eng/bbh-inbox-jjui
        :desc "Send"          "s" #'eng/bbh-inbox-send
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
  "Show the super-agent's Inbox alone in this frame, its preview below."
  (interactive)
  ;; Make the Inbox before clearing the frame, so a failure leaves the layout.
  (let ((buffer (eng/bbh-inbox-buffer)))
    (eng/bbh-super--clear-frame)
    (switch-to-buffer buffer)))

;; Loading this file again, as a Doom reload does, brings a live Inbox up to
;; the new code: its buffer setup and the listener.
(when-let* ((buffer (get-buffer "*bbh inbox*")))
  (with-current-buffer buffer
    (when (derived-mode-p 'eng/bbh-inbox-mode)
      (eng/bbh-inbox--setup)
      (eng/bbh-inbox--listen))))
