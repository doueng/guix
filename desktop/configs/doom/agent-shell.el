;;; agent-shell.el --- bbh in agent-shell -*- lexical-binding: t; -*-

(defun eng/bbh-agent-config (&optional resume)
  "agent-shell configuration for bbh, the babashka agent harness.
RESUME is a saved conversation id the session continues under that id."
  (agent-shell-make-agent-config
   :identifier 'bbh
   :mode-line-name "bbh"
   :buffer-name "bbh"
   :shell-prompt "bbh> "
   :shell-prompt-regexp "bbh> "
   :client-maker (lambda (buffer)
                   ;; A client made again after bbh died continues the conversation (T162).
                   (let ((resume (or (and (buffer-live-p buffer)
                                          (when-let* ((id (buffer-local-value 'eng/bbh-conversation buffer)))
                                            (and (file-exists-p (eng/bbh--conversation-file id "messages.jsonl")) id)))
                                     resume)))
                     (agent-shell--make-acp-client
                      :command (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))
                      :command-params (append '("acp") (when resume (list "--resume" resume)))
                      :context-buffer buffer)))))

(defvar-local eng/bbh-conversation nil
  "The conversation a /resume in this shell moved to.  The session id stays the same.")

(defun eng/bbh (&optional resume)
  "Start a bbh session in agent-shell.
RESUME is a saved conversation id to continue, as `emacs-bbh --resume' passes
when herdr reopens a pane.  A conversation that was never saved starts fresh."
  (interactive)
  (require 'agent-shell)
  (let* ((resume (and resume (file-exists-p (eng/bbh--conversation-file resume "messages.jsonl")) resume))
         (layout (eng/bbh-layout (agent-shell--start :config (eng/bbh-agent-config resume)
                                                      :new-session t :no-focus t))))
    (when resume
      (with-current-buffer (eng/bbh--transcript (eng/bbh--shell))
        (eng/bbh-transcript--replay resume)
        (eng/bbh-transcript--edit
         (lambda () (insert (eng/bbh-transcript--separator) "Resumed " resume "\n")))))
    layout))

(defun eng/bbh-send ()
  "Send the draft and keep the bottom composer ready for another prompt.
/new starts a fresh conversation instead, as it does in Pi, /quit exits Emacs
as `C-x C-c' does, and resuming a saved conversation replaces the transcript
with that conversation.  A bare
/model or /resume opens a popup to choose the model or conversation first.
/resume waits for the running turn, since that turn still writes to this
conversation."
  (interactive)
  (when (and (string-match-p "\\`/resume\\(?:[ \t]\\|\\'\\)" (string-trim (buffer-string)))
             (agent-shell-viewport--busy-p))
    (user-error "Let the turn finish or interrupt it before /resume"))
  (let ((draft (eng/bbh--choose (string-trim (buffer-string)))))
    (unless (equal draft (string-trim (buffer-string)))
      (erase-buffer)
      (insert draft))
    (pcase draft
      ("/new" (erase-buffer) (eng/bbh-new))
      ("/quit" (erase-buffer) (save-buffers-kill-terminal))
      (_
       (when-let* ((id (eng/bbh--resumes-p draft)))
         (eng/bbh-clear-transcript)
         (with-current-buffer (eng/bbh--transcript (eng/bbh--shell))
           (eng/bbh-transcript--replay id))
         (with-current-buffer (eng/bbh--shell)
           (setq eng/bbh-conversation id
                 eng/bbh-usage-last nil))
         (eng/bbh-inbox-switch (eng/bbh--shell)))
       (if (eng/bbh-super--to-router-p (eng/bbh--shell))
           (let ((composer (current-buffer)))
             (eng/bbh-super--say-from (eng/bbh--shell) draft
                                      (lambda () (when (buffer-live-p composer)
                                                   (with-current-buffer composer (erase-buffer))))))
         (agent-shell-viewport-compose-send t))))))

(defun eng/bbh-super-say (text &optional on-done)
  "Give TEXT to the Router through `bbh super say!', then call ON-DONE.
The controller numbers it, so a Worker the Router starts for it can name it,
routes a leading thread handle to that Worker, and delivers the rest to the
Router's mailbox, which the Router's buffer runs as a prompt.  A failure
keeps the text and shows bbh's message."
  (let ((err (generate-new-buffer " *bbh say err*")))
    (make-process
     :name "bbh-say" :noquery t :buffer nil :stderr err :connection-type 'pipe
     :command (list (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))
                    "super" "say!" (format "{:origin :emacs :text %S}" (substring-no-properties text)))
     :sentinel (lambda (process _)
                 (when (memq (process-status process) '(exit signal))
                   (let ((problem (with-current-buffer err (string-trim (buffer-string)))))
                     (kill-buffer err)
                     (if (zerop (process-exit-status process))
                         (when on-done (funcall on-done))
                       (message "Router: %s"
                                (cond ((string-empty-p problem) "bbh super say! failed")
                                      ((string-match "^Message: +\\(.*\\)$" problem) (match-string 1 problem))
                                      (t problem))))))))))

(defun eng/bbh--conversation-file (id file)
  (expand-file-name (format "bbh/conversations/%s/%s" id file)
                    (or (getenv "XDG_DATA_HOME") "~/.local/share")))

(defun eng/bbh--choose (draft)
  "DRAFT with an argument chosen in a popup when it is a bare /model or /resume."
  (pcase draft
    ("/model" (concat "/model " (eng/bbh--read-model)))
    ("/resume" (concat "/resume " (eng/bbh--read-conversation)))
    (_ draft)))

(defun eng/bbh--read-model ()
  "Choose one of the models the bbh session offers."
  (let* ((shell (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
         (state (buffer-local-value 'agent-shell--state shell))
         (ids (or (mapcar (lambda (model) (map-elt model :model-id))
                          (agent-shell--get-available-models state))
                  (user-error "bbh offers no models")))
         (current (agent-shell--current-model-id state)))
    (completing-read (format "Model (%s): " current) ids nil t nil nil current)))

(defun eng/bbh--prompt-text (content)
  "The user's words in CONTENT, a saved user message, without skill text or bbh notes."
  (let ((start 0))
    (while-let ((end (string-search "</skill>" content start)))
      (setq start (+ end (length "</skill>"))))
    (string-trim (replace-regexp-in-string "\\`\\[bbh\\] .*\n+" "" (string-trim (substring content start))))))

(defun eng/bbh--first-prompt (id)
  "The first user prompt of conversation ID on one line, without skill text."
  (with-temp-buffer
    (insert-file-contents (eng/bbh--conversation-file id "messages.jsonl") nil 0 200000)
    (goto-char (point-min))
    (let (prompt)
      (while (and (not prompt) (not (eobp)))
        (let ((message (ignore-errors
                         (json-parse-string (buffer-substring (point) (line-end-position))
                                            :object-type 'alist))))
          (when (equal "user" (alist-get 'role message))
            (setq prompt (alist-get 'content message))))
        (forward-line 1))
      (truncate-string-to-width (replace-regexp-in-string "[ \t\n]+" " " (eng/bbh--prompt-text (or prompt "")))
                                80 nil nil "…"))))

(defun eng/bbh--read-conversation ()
  "Choose a saved conversation other than this one, newest first."
  (let* ((ids (eng/bbh--other-conversations))
         (choices (or (mapcar (lambda (id) (cons (concat id "  " (eng/bbh--first-prompt id)) id)) ids)
                      (user-error "No other conversations"))))
    (cdr (assoc (completing-read "Resume: "
                                 (lambda (string pred action)
                                   (if (eq action 'metadata)
                                       '(metadata (display-sort-function . identity)
                                                  (cycle-sort-function . identity))
                                     (complete-with-action action choices string pred)))
                                 nil t)
                choices))))

(defun eng/bbh--other-conversations ()
  "Saved conversations other than this session's, newest first.  /resume last takes the first."
  (let ((root (file-name-directory (directory-file-name (eng/bbh--conversation-file "x" ""))))
        (current (eng/bbh--conversation (eng/bbh--shell))))
    (seq-filter (lambda (id)
                  (and (not (equal id current))
                       (file-exists-p (eng/bbh--conversation-file id "messages.jsonl"))))
                (and (file-directory-p root)
                     (reverse (directory-files root nil "\\`[^.]"))))))

(defun eng/bbh--resumes-p (draft)
  "The conversation id DRAFT's /resume switches to, or nil."
  (when (string-match "\\`/resume[ \t]+\\(\\S-+\\)\\'" draft)
    (let ((id (match-string 1 draft))
          (others (eng/bbh--other-conversations)))
      (car (if (equal id "last") others (member id others))))))

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
      ;; You were typing /new, so keep typing.
      (evil-insert-state)
      (let ((kill-buffer-query-functions nil))
        (when (buffer-live-p old-composer) (kill-buffer old-composer))
        (kill-buffer old)))))

;;; Website

;; The session takes its own mail, from the conversation's mailbox, at the
;; start of each input and before each model call, and deletes a message once
;; its transcript holds it.  This shell only wakes an idle session with the
;; prompt `[mail]', so a stuck shell delays mail but cannot lose it.

(defvar-local eng/bbh-mail--watch nil
  "The timer that checks this shell's mailbox, kept only to cancel it.")

(defun eng/bbh-mail-dir (conversation)
  "The mailbox of CONVERSATION's rollover root."
  (let* ((pointer (eng/bbh--conversation-file conversation "inbox-session"))
         (root (if (file-exists-p pointer)
                   (string-trim (with-temp-buffer (insert-file-contents pointer) (buffer-string)))
                 conversation)))
    (eng/bbh--conversation-file root "mailbox")))

(defun eng/bbh-mail-waiting-p (dir)
  "Whether mailbox DIR holds a message for the agent.  A cancel is not one:
bbh acts on it without a turn."
  (seq-some (lambda (file)
              (not (equal "cancel"
                          (ignore-errors
                            (map-elt (json-parse-string
                                      (with-temp-buffer (insert-file-contents file) (buffer-string))
                                      :object-type 'alist)
                                     'kind)))))
            (and (file-directory-p dir) (directory-files dir t "\\`[0-9].*\\.json\\'"))))

(defun eng/bbh-mail-wake (shell)
  "Send SHELL the prompt `[mail]' when its mailbox has mail and it is idle.
A running turn takes the mail itself.  A finished Worker's shell is never
woken: its workspace is gone, so it runs nothing.  Nor is a shell without a
session, one waiting to retry after a failure, or one that gave up."
  (when (buffer-live-p shell)
    (with-current-buffer shell
      (when-let* ((conversation (eng/bbh--conversation shell))
                  ((not (bound-and-true-p eng/bbh-super-finished)))
                  ((map-nested-elt agent-shell--state '(:session :id)))
                  ((not eng/bbh--attention))
                  ((not (timerp eng/bbh--retry)))
                  ((not (shell-maker-busy)))
                  ((not (map-elt agent-shell--state :pending-prompts)))
                  ((eng/bbh-mail-waiting-p (eng/bbh-mail-dir conversation))))
        (agent-shell--prompt-queue-enqueue :prompt "[mail]")
        (agent-shell--prompt-queue-process-next)))))

(defun eng/bbh-mail-watch (shell)
  "Check SHELL's mailbox every second, and now.  It reads the conversation
SHELL is on at each check, so /new and /resume need nothing more.  A timer
rather than file-notify, whose events Emacs reads only between commands."
  (with-current-buffer shell
    (unless (timerp eng/bbh-mail--watch)
      (let (timer)
        (setq timer (run-with-timer 1 1 (lambda ()
                                          (if (buffer-live-p shell)
                                              (eng/bbh-mail-wake shell)
                                            (cancel-timer timer))))
              eng/bbh-mail--watch timer)))
    (eng/bbh-mail-wake shell)))

(defun eng/bbh-mail--on-event (shell event)
  "Watch SHELL's mailbox once its session exists, and check it after each turn."
  (pcase (map-elt event :event)
    ((or 'prompt-ready 'init-finished)
     (unless (buffer-local-value 'eng/bbh-mail--watch shell) (eng/bbh-mail-watch shell))
     (run-at-time 0 nil #'eng/bbh-mail-wake shell))
    ('turn-complete
     (eng/bbh--recovered shell)
     ;; After agent-shell has sent any prompt it queued meanwhile.
     (run-at-time 0 nil #'eng/bbh-mail-wake shell))
    ('error
     (let ((why (or (map-nested-elt event '(:data :message)) "the request failed")))
       (if (string-prefix-p "Unknown session" why)
           (eng/bbh--session-lost shell why)
         (eng/bbh--failed shell why))))
    ('clean-up
     (with-current-buffer shell
       (when (timerp eng/bbh-mail--watch) (cancel-timer eng/bbh-mail--watch))
       (when (timerp eng/bbh--retry) (cancel-timer eng/bbh--retry))
       (setq eng/bbh-mail--watch nil eng/bbh--retry nil)))))

(defun eng/bbh-inbox-connect (shell)
  "Wake SHELL whenever its mailbox has mail."
  (agent-shell-subscribe-to :shell-buffer shell
                            :on-event (lambda (event) (eng/bbh-mail--on-event shell event)))
  (eng/bbh-mail-watch shell))

;;; Recovery

;; When the bbh process dies, agent-shell keeps its client, handshake and
;; session id, and acp starts a new process at the next request.  That
;; process never made the session, so bbh answers `Unknown session' (T162).
;; A bbh shell instead drops all of it and starts a new session on the same
;; conversation, which bbh reopens from its transcript as the controller's
;; resume does.  Failures in a row back off, and after
;; `eng/bbh-retry-limit' of them the shell stops and says so once.

(defvar eng/bbh-retry-delay 1
  "Seconds before the first retry after a failure.  Each next one waits twice as long.")
(defvar eng/bbh-retry-max-delay 30 "Longest wait between two retries, in seconds.")
(defvar eng/bbh-retry-limit 5
  "Failures in a row after which a shell stops retrying and asks for attention.")

(defvar-local eng/bbh--failures 0
  "Failures in a row: process exits, lost sessions and failed prompts.  A completed turn clears it.")
(defvar-local eng/bbh--retry nil "The timer of the next retry.  Mailbox wakes wait for it.")
(defvar-local eng/bbh--attention nil "Why this shell stopped retrying, or nil.")
(defvar-local eng/bbh--shown-error nil
  "The error the transcript last showed since a turn completed, so a retry does not repeat it.")

(defun eng/bbh--shell-p (buffer)
  "Whether BUFFER is a live bbh agent-shell."
  (and (buffer-live-p buffer)
       (eq 'bbh (map-nested-elt (buffer-local-value 'agent-shell--state buffer) '(:agent-config :identifier)))))

(defun eng/bbh--failed (shell why)
  "Count a failure of SHELL for reason WHY and retry later, or give up.
A failure while a retry is already waiting is the same incident."
  (when (buffer-live-p shell)
    (with-current-buffer shell
      (unless (or (timerp eng/bbh--retry) eng/bbh--attention)
        (setq eng/bbh--failures (1+ eng/bbh--failures))
        (if (>= eng/bbh--failures eng/bbh-retry-limit)
            (eng/bbh--give-up shell why)
          (setq eng/bbh--retry
                (run-at-time (min eng/bbh-retry-max-delay
                                  (* eng/bbh-retry-delay (expt 2 (1- eng/bbh--failures))))
                             nil #'eng/bbh--retry-now shell)))))))

(defun eng/bbh--give-up (shell why)
  "Stop retrying SHELL after WHY, and say so once."
  (with-current-buffer shell
    (setq eng/bbh--attention why)
    (force-mode-line-update t))
  (let ((transcript (buffer-local-value 'eng/bbh-transcript shell)))
    (when (buffer-live-p transcript)
      (with-current-buffer transcript
        (eng/bbh-transcript--note
         (format "bbh stopped retrying after %d failures: %s.  M-x eng/bbh-reconnect tries again."
                 eng/bbh-retry-limit why)))))
  (message "%s: bbh stopped retrying (%s); M-x eng/bbh-reconnect" (buffer-name shell) why))

(defun eng/bbh--retry-now (shell)
  "Start SHELL a new session if it has none, else wake it for its mail."
  (when (buffer-live-p shell)
    (with-current-buffer shell
      (setq eng/bbh--retry nil)
      (cond ((or eng/bbh--attention (bound-and-true-p eng/bbh-super-finished)))
            ((not (map-elt agent-shell--state :client))
             (agent-shell--handle :shell-buffer shell))
            (t (eng/bbh-mail-wake shell))))))

(defun eng/bbh--session-lost (shell why)
  "Drop SHELL's client and session after WHY, and start a new session on its conversation."
  (when (and (eng/bbh--shell-p shell)
             (not (with-current-buffer shell (bound-and-true-p eng/bbh-super-finished))))
    (with-current-buffer shell
      (when-let* ((conversation (eng/bbh--conversation shell)))
        (setq eng/bbh-conversation conversation))
      (agent-shell--shutdown)
      (map-put! (map-elt agent-shell--state :session) :id nil))
    (eng/bbh--failed shell why)))

(defun eng/bbh--recovered (shell)
  "SHELL completed a turn, so its next failure starts the count again."
  (with-current-buffer shell
    (when (timerp eng/bbh--retry) (cancel-timer eng/bbh--retry))
    (setq eng/bbh--failures 0 eng/bbh--retry nil eng/bbh--attention nil eng/bbh--shown-error nil)))

(defun eng/bbh-reconnect ()
  "Start this bbh shell again after it stopped retrying."
  (interactive)
  (let ((shell (or (eng/bbh--shell) (user-error "No bbh session in this buffer"))))
    (eng/bbh--recovered shell)
    (eng/bbh--retry-now shell)))

(defun eng/bbh--on-process-exit (client process event)
  "Treat the exit of PROCESS, CLIENT's bbh, as a lost session.  EVENT says how it ended.
A client shut down on purpose has no process any more, so it is left alone."
  (let ((shell (map-elt client :context-buffer)))
    (when (and (memq (process-status process) '(exit signal))
               (eq process (map-elt client :process))
               (eng/bbh--shell-p shell)
               (eq client (map-elt (buffer-local-value 'agent-shell--state shell) :client)))
      (eng/bbh--session-lost shell (format "bbh exited (%s)" (string-trim event))))))

(defun eng/bbh--watch-process (&rest args)
  "Watch the process `acp--start-client' started for the client in ARGS."
  (let* ((client (plist-get args :client))
         (process (map-elt client :process)))
    (when (and (processp process) (eng/bbh--shell-p (map-elt client :context-buffer)))
      (add-function :after (process-sentinel process)
                    (lambda (proc event) (eng/bbh--on-process-exit client proc event))))))

(advice-add 'acp--start-client :after #'eng/bbh--watch-process)

(defun eng/bbh-inbox-switch (shell)
  "Move SHELL's mailbox watch to the conversation it is on now.
Messages to the old conversation stay in its mailbox until it is resumed."
  (eng/bbh-mail-watch shell))

;;; Program status

;; Tell the terminal whether bbh is working or waiting for the user.  The
;; report is OSC 7501, the Program Status Protocol
;; <https://www.superlogical.com/rex/docs/build/program-status>.  Herdr does
;; not read OSC 7501 yet, so inside herdr the same state also goes to
;; `herdr pane report-agent', which shows it and notifies on a finished turn.
;; Once the conversation is saved, each report carries `emacs-bbh --resume ID',
;; so a herdr restart reopens the pane on the same conversation.

(defvar eng/bbh-status--seq 0
  "The last sequence number sent to herdr.  Herdr drops reports that do not increase it.")

(defun eng/bbh-status--osc (state &optional msg)
  "The OSC 7501 report of STATE, with MSG as one line of base64 UTF-8."
  (concat "\e]7501;state=" (symbol-name state)
          (unless (eq state 'clear) ":app=bbh")
          (when msg
            (concat ":msg="
                    (base64-encode-string
                     (encode-coding-string
                      (truncate-string-to-width
                       (replace-regexp-in-string (rx (+ (any (0 . 31) (127 . 159)))) " " msg) 500)
                      'utf-8)
                     t)))
          "\e\\"))

(defun eng/bbh-status--herdr (state &optional msg conversation)
  "Report STATE to herdr in the background, when Emacs runs in a herdr pane.
A saved CONVERSATION goes along as the session herdr reopens after a restart."
  (let ((bin (getenv "HERDR_BIN_PATH"))
        (pane (getenv "HERDR_PANE_ID")))
    (when (and (equal (getenv "HERDR_ENV") "1") bin pane)
      (setq eng/bbh-status--seq (max (1+ eng/bbh-status--seq)
                                     (truncate (* 1000 (float-time)))))
      (ignore-errors
        (make-process
         :name "bbh-herdr-status" :noquery t :buffer nil :connection-type 'pipe
         :command (append (list bin "pane" (if (eq state 'clear) "release-agent" "report-agent")
                                pane "--source" "bbh-emacs" "--agent" "bbh"
                                "--seq" (number-to-string eng/bbh-status--seq))
                          (unless (eq state 'clear)
                            (list "--state" (if (eq state 'working) "working" "idle")))
                          (when (and msg (not (eq state 'clear)))
                            (list "--message" msg))
                          (when (and conversation (not (eq state 'clear)))
                            (list "--agent-session-id" conversation
                                  "--" "emacs-bbh" "--resume" conversation))))))))

(defun eng/bbh-status (state &optional msg conversation)
  "Report STATE, one of working, done, error or clear, with an optional MSG.
CONVERSATION is the saved conversation herdr should resume."
  (unless (or noninteractive (display-graphic-p))
    (send-string-to-terminal (eng/bbh-status--osc state msg)))
  (eng/bbh-status--herdr state msg conversation))

(defun eng/bbh-status--on-event (shell event)
  ;; A `bbh super' session shares the daemon with others; the Inbox reports for it.
  (unless (buffer-local-value 'eng/bbh-super-conversation shell)
  (let ((conversation (when-let* ((id (eng/bbh--conversation shell)))
                        (and (file-exists-p (eng/bbh--conversation-file id "messages.jsonl")) id))))
    (pcase (map-elt event :event)
      ('input-submitted (eng/bbh-status 'working "Working" conversation))
      ('turn-complete (eng/bbh-status 'done "bbh is waiting for input" conversation))
      ('error (eng/bbh-status 'error (or (map-nested-elt event '(:data :message)) "The request failed")
                              conversation))))))

(defun eng/bbh-status--clear ()
  (eng/bbh-status 'clear))

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

(defvar-local eng/bbh-transcript--dirty nil
  "(BEG . END) that the running transcript edit changed, or nil.")

(defun eng/bbh-transcript--note-change (beg end _length)
  (setq eng/bbh-transcript--dirty
        (if eng/bbh-transcript--dirty
            (cons (min beg (car eng/bbh-transcript--dirty)) (max end (cdr eng/bbh-transcript--dirty)))
          (cons beg end))))

(defun eng/bbh-transcript--markup-overlay (beg end display)
  (let ((overlay (make-overlay beg end)))
    (overlay-put overlay 'eng/bbh-markup t)
    (overlay-put overlay 'evaporate t)
    (overlay-put overlay 'display display)))

(defun eng/bbh-transcript--decorate (beg end)
  "Hide the org markup lines of blocks between BEG and END and label results.
Overlays do this rather than font-lock, because org's block fontification
strips `display' text properties from whole blocks on partial redisplays."
  (save-excursion
    (setq beg (progn (goto-char beg) (line-beginning-position))
          end (progn (goto-char (min end (point-max))) (min (point-max) (1+ (line-end-position)))))
    (dolist (overlay (overlays-in beg end))
      (when (overlay-get overlay 'eng/bbh-markup) (delete-overlay overlay)))
    (goto-char beg)
    (while (re-search-forward "^#\\+\\(begin\\|end\\)_\\(src\\|example\\|quote\\|result\\)\\(?: \\([a-z]+\\) \\([0-9]+\\)\\)?.*$" end t)
      (let ((begin (equal (match-string 1) "begin")))
        (cond ((and begin (equal (match-string 2) "result") (match-string 3))
               (eng/bbh-transcript--markup-overlay
                (match-beginning 0) (match-end 0)
                (eng/bbh--result-label (match-string 3) (match-string 4))))
              (begin
               (eng/bbh-transcript--markup-overlay (match-beginning 0) (min (point-max) (1+ (match-end 0))) ""))
              ((> (match-beginning 0) (point-min))
               (eng/bbh-transcript--markup-overlay (1- (match-beginning 0)) (match-end 0) "")))))))

(defun eng/bbh-transcript--edit (change)
  "Call CHANGE at the end of the transcript, keeping followers at the end."
  (let ((inhibit-read-only t)
        (eng/bbh-transcript--dirty nil)
        (followers (seq-filter (lambda (w) (= (window-point w) (point-max)))
                               (get-buffer-window-list nil nil t)))
        (at-end (= (point) (point-max))))
    (save-excursion
      (goto-char (point-max))
      (funcall change)
      (when eng/bbh-transcript--dirty
        (eng/bbh-transcript--decorate (car eng/bbh-transcript--dirty) (cdr eng/bbh-transcript--dirty))))
    (when at-end (goto-char (point-max)))
    (dolist (w followers) (set-window-point w (point-max)))))

(defun eng/bbh-transcript--latest-start (window)
  "The start of WINDOW that puts the end of its transcript on the bottom row."
  (with-current-buffer (window-buffer window)
    (save-excursion
      (goto-char (point-max))
      (vertical-motion (- 1 (window-body-height window)) window)
      (point))))

(defun eng/bbh-transcript--clamp-scroll ()
  "Pull back any window that scrolled past the end of a transcript.
The last line then stays on the window's bottom row instead of leaving
empty rows below it. This runs from the global `post-command-hook'
because Emacs does not support repositioning a window from
`window-scroll-functions', and the composer scrolls the transcript while
another buffer is current."
  (walk-windows
   (lambda (window)
     (when (with-current-buffer (window-buffer window) (derived-mode-p 'eng/bbh-transcript-mode))
       (let ((latest (eng/bbh-transcript--latest-start window)))
         (when (> (window-start window) latest)
           (set-window-start window latest)))))
   'nomini 'visible))

(add-hook 'post-command-hook #'eng/bbh-transcript--clamp-scroll)

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
  "Insert PROMPT with its first line as the heading and the rest as a quote.
The heading wraps instead of truncating, so no line of the prompt shows twice."
  (setq eng/bbh-transcript--reply nil)
  (let* ((lines (split-string (string-trim prompt) "\n"))
         (rest (string-trim (string-join (cdr lines) "\n"))))
    (eng/bbh-transcript--edit
     (lambda ()
       (insert (eng/bbh-transcript--separator) "* " (car lines) "\n"
               (if (string-empty-p rest) ""
                 (concat "#+begin_quote\n" (eng/bbh--md->org rest) "\n#+end_quote\n")))))))

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

(defun eng/bbh--saved-messages (id)
  "The messages of conversation ID, skipping a line a crash left unreadable."
  (with-temp-buffer
    (insert-file-contents (eng/bbh--conversation-file id "messages.jsonl"))
    (let (messages)
      (while (not (eobp))
        (when-let* ((message (ignore-errors
                               (json-parse-string (buffer-substring (point) (line-end-position))
                                                  :object-type 'alist :array-type 'list
                                                  :null-object nil))))
          (push message messages))
        (forward-line 1))
      (nreverse messages))))

(defun eng/bbh-transcript--replay (id)
  "Render saved conversation ID at the end of this transcript.
Messages do not record whether a tool call failed, so every result shows as done."
  (let ((codes (make-hash-table :test #'equal)))
    (dolist (message (eng/bbh--saved-messages id))
      (let ((content (or (alist-get 'content message) "")))
        (pcase (alist-get 'role message)
          ("user" (eng/bbh-transcript--prompt (eng/bbh--prompt-text content)))
          ("assistant"
           (eng/bbh-transcript--reply-chunk content)
           (setq eng/bbh-transcript--reply nil)
           (dolist (call (alist-get 'tool_calls message))
             (puthash (alist-get 'id call)
                      (ignore-errors
                        (alist-get 'code (json-parse-string (map-nested-elt call '(function arguments))
                                                            :object-type 'alist)))
                      codes)))
          ("tool"
           (let ((id (alist-get 'tool_call_id message)))
             (eng/bbh-transcript--tool id `((:raw-input . ((code . ,(gethash id codes))))
                                            (:status . "completed")
                                            (:content . (((content . ((text . ,content)))))))))))))))

(defun eng/bbh-transcript--error (message)
  (setq eng/bbh-transcript--reply nil)
  (eng/bbh-transcript--edit
   (lambda ()
     (insert (eng/bbh-transcript--separator)
             (propertize (concat "Error: " message) 'font-lock-face 'error) "\n"))))

(defun eng/bbh-transcript--note (text)
  (setq eng/bbh-transcript--reply nil)
  (eng/bbh-transcript--edit
   (lambda ()
     (insert (eng/bbh-transcript--separator) (propertize text 'font-lock-face 'warning) "\n"))))

(defun eng/bbh-transcript--retrying-p (shell prompt)
  "Whether PROMPT is SHELL's mailbox wake retried after a failure the transcript shows."
  (and (equal prompt "[mail]") (buffer-live-p shell)
       (buffer-local-value 'eng/bbh--shown-error shell)))

(defun eng/bbh-transcript--shown-p (shell message)
  "Whether the transcript already shows MESSAGE as SHELL's latest error, and mark it shown."
  (when (buffer-live-p shell)
    (with-current-buffer shell
      (prog1 (equal message eng/bbh--shown-error)
        (setq eng/bbh--shown-error message)))))

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
  "Context and cache for SHELL's status line, or nil before a request."
  (when (buffer-live-p shell)
    (with-current-buffer shell
      (when eng/bbh-usage-last
        (let ((used (map-nested-elt agent-shell--state '(:usage :context-used))))
          (string-join
           (delq nil (list (when used (format "ctx %s" (eng/bbh--tokens used)))
                           (eng/bbh--cache-label eng/bbh-usage-last)))
           " · "))))))

(defun eng/bbh--cost (fmt amount)
  "AMOUNT dollars in FMT, or nil when nothing was billed, as on a subscription."
  (when (and amount (> amount 0)) (format fmt amount)))

(defun eng/bbh--turn-usage (requests cost &optional elapsed)
  "One line about a turn's REQUESTS, oldest first, which cost COST dollars
and took ELAPSED, such as 12s.  Cache misses and cost appear only when present."
  (let* ((sum (lambda (key) (apply #'+ (mapcar (lambda (r) (or (map-elt r key) 0)) requests))))
         (input (funcall sum 'input))
         (cached (funcall sum 'cached))
         (misses (seq-filter (lambda (r) (equal (map-elt r 'cache) "miss")) requests))
         (n (length requests)))
    (string-join
     (delq nil
           (list (format "Usage: %d request%s%s" n (if (= n 1) "" "s")
                         (if elapsed (concat " in " elapsed) ""))
                 (format "%s in, %d%% cached" (eng/bbh--tokens input)
                         (if (> input 0) (round (* 100.0 (/ (float cached) input))) 0))
                 (when misses
                   (mapconcat (lambda (r) (format "miss on request %d (%d%% of %s)" (map-elt r 'n)
                                                  (eng/bbh--percent (map-elt r 'ratio))
                                                  (eng/bbh--tokens (map-elt r 'input))))
                              misses ", "))
                 (format "%s out" (eng/bbh--tokens (funcall sum 'output)))
                 (eng/bbh--cost "$%.4f" cost)))
     " · ")))

(defvar-local eng/bbh-turn-started nil
  "When the current turn started, as `float-time', or nil between turns.")

(defvar-local eng/bbh-turn-timer nil
  "Repeating timer that redraws the status line while a turn runs.")

(defun eng/bbh--end-turn ()
  "Stop the elapsed-time clock of this shell's turn."
  (when (timerp eng/bbh-turn-timer) (cancel-timer eng/bbh-turn-timer))
  (setq eng/bbh-turn-timer nil eng/bbh-turn-started nil)
  (force-mode-line-update t))

(defun eng/bbh--begin-turn ()
  "Start the elapsed-time clock of this shell's turn."
  (eng/bbh--end-turn)
  (setq eng/bbh-turn-started (float-time)
        eng/bbh-turn-timer (run-with-timer 1 1 #'force-mode-line-update t)))

(defun eng/bbh-transcript--end-turn ()
  "End the turn clock of the shell behind this transcript."
  (when (buffer-live-p eng/bbh-transcript-shell)
    (with-current-buffer eng/bbh-transcript-shell (eng/bbh--end-turn))))

(defun eng/bbh-transcript--turn-start ()
  (when-let* ((shell eng/bbh-transcript-shell) ((buffer-live-p shell)))
    (with-current-buffer shell
      (setq eng/bbh-usage-requests nil
            eng/bbh-usage-turn-cost (or (map-nested-elt agent-shell--state '(:usage :cost-amount)) 0))
      (eng/bbh--begin-turn))))

(defun eng/bbh-transcript--usage (&optional elapsed)
  "Note the finished turn's usage, and its duration ELAPSED, at the end of the transcript."
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
                 (propertize (eng/bbh--turn-usage (reverse requests) cost elapsed) 'font-lock-face 'shadow) "\n"))))))

(defun eng/bbh-transcript--on-event (transcript event)
  (when (buffer-live-p transcript)
    (with-current-buffer transcript
      (let ((data (map-elt event :data)))
        (pcase (map-elt event :event)
          ('input-submitted (eng/bbh-transcript--turn-start)
                            ;; A retry repeats neither its prompt nor its error (T162).
                            (unless (eng/bbh-transcript--retrying-p eng/bbh-transcript-shell (map-elt data :prompt))
                              (when (buffer-live-p eng/bbh-transcript-shell)
                                (with-current-buffer eng/bbh-transcript-shell (setq eng/bbh--shown-error nil)))
                              (eng/bbh-transcript--prompt (map-elt data :prompt))))
          ('agent-message-chunk (eng/bbh-transcript--reply-chunk (map-elt data :text-chunk)))
          ('tool-call-update (eng/bbh-transcript--tool (map-elt data :tool-call-id)
                                                       (map-elt data :tool-call)))
          ('error (eng/bbh-transcript--end-turn)
                  (let ((message (or (map-elt data :message) "the request failed")))
                    (unless (eng/bbh-transcript--shown-p eng/bbh-transcript-shell message)
                      (eng/bbh-transcript--error message))))
          ('turn-complete (setq eng/bbh-transcript--reply nil)
                          (let ((elapsed (eng/bbh--elapsed eng/bbh-transcript-shell)))
                            (eng/bbh-transcript--end-turn)
                            (eng/bbh-transcript--usage elapsed))))
        (when (or (memq (map-elt event :event) '(input-submitted turn-complete))
                  (and (eq (map-elt event :event) 'tool-call-update)
                       (equal (map-nested-elt data '(:tool-call :status)) "completed")))
          (eng/bbh-workflow-refresh eng/bbh-transcript-shell))))))

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
  '((eng/bbh--fontify-nested-blocks))
  "Format nested blocks as code.  `eng/bbh-transcript--decorate' hides their markup.")

(define-derived-mode eng/bbh-transcript-mode org-mode "bbh"
  "A read-only org rendering of a bbh conversation."
  (setq buffer-read-only t)
  ;; Point rests on the last row, so a margin makes redisplay undo the clamp.
  (setq-local scroll-margin 0)
  ;; The composer's status line below separates the windows; the buffer
  ;; size, position and mode name here say nothing useful.
  (setq-local mode-line-format nil)
  (add-hook 'after-change-functions #'eng/bbh-transcript--note-change nil t)
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
        (agent-shell-subscribe-to :shell-buffer shell
                                  :on-event (lambda (event) (eng/bbh-status--on-event shell event)))
        (eng/bbh-inbox-connect shell)
        (with-current-buffer shell
          (add-hook 'kill-buffer-hook #'eng/bbh-status--clear nil t)
          (setq eng/bbh-transcript transcript))))))

(defun eng/bbh-toggle-tools ()
  "Expand every tool result in the transcript, or fold them all."
  (interactive)
  (with-current-buffer (eng/bbh--transcript (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
    (setq eng/bbh-transcript-tools-shown (not eng/bbh-transcript-tools-shown))
    (maphash (lambda (_ start) (eng/bbh-transcript--fold-tool start (not eng/bbh-transcript-tools-shown)))
             eng/bbh-transcript--tools)
    (message (if eng/bbh-transcript-tools-shown "Tool results expanded" "Tool results folded"))))

(define-key eng/bbh-transcript-mode-map (kbd "C-c C-t") #'eng/bbh-toggle-tools)

(when (fboundp 'map!)
  (map! :map eng/bbh-transcript-mode-map
        :localleader
        :desc "Toggle tool results" "t" #'eng/bbh-toggle-tools))

;; Doom's evil-org keys work here: gj and gk move by element, ]h and [h
;; between prompts, gh up.  S-TAB stays org's cycle; SPC m t or C-c C-t folds
;; tool results.  n, p and the other evil keys keep their meaning; q buries.
;; evil-org's C-j and C-k shadow focus, which moves between transcript and
;; composer.  The transcript is read-only, so normal state is enough.
(evil-define-key 'normal eng/bbh-transcript-mode-map
  (kbd "q") #'bury-buffer
  (kbd "C-j") #'eng/focus-down
  (kbd "C-k") #'eng/focus-up)

(defvar-local eng/bbh-composer nil)
(put 'eng/bbh-composer 'permanent-local t)

(defun eng/bbh--session-id (shell)
  "The ACP session id of SHELL, or nil.  It names the conversation SHELL started with."
  (when (buffer-live-p shell)
    (map-nested-elt (buffer-local-value 'agent-shell--state shell) '(:session :id))))

(defun eng/bbh--conversation (shell)
  "The conversation id SHELL is on now, which /resume and `emacs-bbh --resume' take, or nil."
  (when (buffer-live-p shell)
    (or (buffer-local-value 'eng/bbh-conversation shell) (eng/bbh--session-id shell))))

(defun eng/bbh--model-name (shell)
  "The model SHELL's agent-shell reports as its current model, or nil.
A provider prefix the model name repeats, as in claude/claude-opus, is dropped."
  (when-let* (((buffer-live-p shell))
              (model (seq-some (lambda (option)
                                 (and (equal (map-elt option :id) "model") (map-elt option :current-value)))
                               (agent-shell--config-options (buffer-local-value 'agent-shell--state shell)))))
    (if (string-match "\\`\\([^/]+\\)/\\(\\1.*\\)\\'" model) (match-string 2 model) model)))

(defun eng/bbh--workflow-label (shell)
  "SHELL's workflow run as WORKFLOW: PHASE from its last read, or nil.
This reads no process, so it is cheap enough for the status line."
  (when-let* (((buffer-live-p shell))
              (data (buffer-local-value 'eng/bbh-workflow--data shell))
              ((not (alist-get 'error data))))
    (let ((status (alist-get 'status data)))
      (concat (alist-get 'workflow data) ": "
              (pcase status
                ("blocked" (propertize "blocked" 'face 'error))
                ("done" (propertize "done" 'face 'success))
                (_ (alist-get 'phase data)))))))

(defun eng/bbh--effort (shell)
  "The effort bbh sent with the latest request of SHELL, or nil before one."
  (when-let* (((buffer-live-p shell))
              (effort (map-elt (buffer-local-value 'eng/bbh-usage-last shell) 'effort)))
    (format "effort %s" effort)))

(defun eng/bbh--elapsed (shell)
  "How long SHELL's current turn has run, such as 12s or 1m04s, or nil."
  (when-let* (((buffer-live-p shell))
              (started (buffer-local-value 'eng/bbh-turn-started shell)))
    (let ((s (floor (- (float-time) started))))
      (if (< s 60) (format "%ds" s) (format "%dm%02ds" (/ s 60) (% s 60))))))

(defun eng/bbh--queued (shell)
  "How many prompts are waiting behind SHELL's turn, or nil."
  (when (buffer-live-p shell)
    (let ((n (length (map-elt (buffer-local-value 'agent-shell--state shell) :pending-prompts))))
      (when (> n 0) (format "%d queued" n)))))

;; agent-shell lists the running and queued prompts in the echo area after every
;; enqueue, including each inbox delivery, and the list vanishes on the next
;; keystroke.  The count above, in the composer header, replaces it.
(advice-add 'agent-shell--prompt-queue-echo :override #'ignore)

(defun eng/bbh--header ()
  "The composer status line: state, timing, workflow phase, model, effort and usage.
The key hints and conversation id are in `eng/bbh-keys'.  % is escaped because the header is a mode-line."
  (let* ((shell (agent-shell-viewport--shell-buffer))
         (busy (agent-shell-viewport--busy-p))
         (parts (delq nil (list (if-let* ((why (and (buffer-live-p shell) (buffer-local-value 'eng/bbh--attention shell))))
                                    (propertize (concat "Stopped: " why " · M-x eng/bbh-reconnect") 'face 'error)
                                  (if busy "Working" "Ready"))
                                (and busy (eng/bbh--elapsed shell))
                                (eng/bbh--queued shell)
                                (eng/bbh--workflow-label shell)
                                (eng/bbh--model-name shell)
                                (eng/bbh--effort shell)
                                (eng/bbh-usage-header shell)))))
    (replace-regexp-in-string "%" "%%" (concat " " (string-join parts " · ")) t t)))

(defun eng/bbh-keys ()
  "Show the composer keys in the echo area."
  (interactive)
  (message "%s%s" "C-n send · RET newline · M-p/M-n history · C-u/C-d or M-k/M-j scroll · C-c C-t tools · C-c C-k stop · SPC m menu"
           (if-let* ((id (eng/bbh--conversation (eng/bbh--shell)))) (concat " · /resume " id) "")))

(with-eval-after-load 'agent-shell-viewport
  (when (fboundp 'map!)
    (map! :map agent-shell-viewport-edit-mode-map
          :localleader
          :desc "Send"                "s" #'eng/bbh-send
          :desc "Stop the turn"       "k" #'agent-shell-viewport-interrupt
          :desc "Toggle tool results" "t" #'eng/bbh-toggle-tools
          :desc "Keys"                "?" #'eng/bbh-keys)))

(defun eng/bbh-composer-header (&rest _)
  "Keep the composer status line current when agent-shell refreshes its header."
  (when (and eng/bbh-composer (derived-mode-p 'agent-shell-viewport-edit-mode))
    (setq-local header-line-format '(:eval (eng/bbh--header)))))

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

(defun eng/bbh-composer-drop-layout-newline (&rest _)
  "Remove the newline agent-shell inserts above the draft when it rebuilds the composer.
Without a graphical header, `agent-shell-viewport--initialize' inserts one on
history navigation and replies, which doubles `eng/bbh-composer-padding'."
  (when (and eng/bbh-composer
             (eq (char-after (point-min)) ?\n)
             (get-text-property (point-min) 'cursor-intangible))
    (let ((inhibit-read-only t))
      (delete-region (point-min) (1+ (point-min))))))

(defun eng/bbh--popup-next ()
  "The command that selects the next candidate in an open completion popup, or nil.
Corfu's map sits in `minor-mode-overriding-map-alist', below evil's local
keys, so a send key on C-n would otherwise win while the popup is open."
  (cond ((and (bound-and-true-p completion-in-region-mode) (bound-and-true-p corfu--candidates))
         'corfu-next)
        ((and (bound-and-true-p company-candidates) (fboundp 'company-select-next))
         'company-select-next)))

(defun eng/bbh-send-key (command &optional when)
  "A key binding for C-n that runs COMMAND, or the popup's next candidate.
With WHEN, a function, the binding is absent where it returns nil, so the
key falls through to the next keymap, such as evil's C-n."
  `(menu-item "" ,command
              :filter ,(lambda (cmd)
                         (or (eng/bbh--popup-next)
                             (and (or (null when) (funcall when)) cmd)))))

(defun eng/bbh-setup-composer ()
  "Restore bbh bindings after returning from agent-shell's history view."
  (when eng/bbh-composer
    (eng/bbh-composer-padding)
    ;; The header line carries the status; Doom's mode-line only repeats the buffer name.
    (setq-local mode-line-format nil)
    (use-local-map (copy-keymap (current-local-map)))
    ;; agent-shell's own send keys, such as normal-state ZZ, take bbh's path too,
    ;; so the Router's input always goes through the controller.
    (local-set-key [remap agent-shell-viewport-compose-send] #'eng/bbh-send)
    ;; C-n sends; C-c C-c does not, neither agent-shell's own nor an evil
    ;; binding left by older code before a Doom reload.
    (local-set-key (kbd "C-c C-c") #'undefined)
    (dolist (state '(normal insert))
      (evil-local-set-key state (kbd "C-c C-c") nil))
    (dolist (binding `(("C-n" . ,(eng/bbh-send-key #'eng/bbh-send))
                       ("M-RET" . eng/bbh-send)
                       ("C-c C-k" . agent-shell-viewport-interrupt)
                       ("C-c C-t" . eng/bbh-toggle-tools)
                       ("C-c ?" . eng/bbh-keys)
                       ("M-k" . eng/bbh-output-page-up)
                       ("M-j" . eng/bbh-output-page-down)))
      (dolist (state '(normal insert))
        (evil-local-set-key state (kbd (car binding)) (cdr binding))))
    ;; C-n sends in normal and insert state.  In normal state C-d and C-u page
    ;; the transcript, since the composer is a few lines tall.
    (dolist (binding '(("g?" . eng/bbh-keys)
                       ("C-d" . eng/bbh-output-page-down)
                       ("C-u" . eng/bbh-output-page-up)))
      (evil-local-set-key 'normal (kbd (car binding)) (cdr binding)))
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
    (eng/bbh-workflow-refresh shell)
    ;; `set-window-buffer' brings back the start the transcript had when it
    ;; was last shown, above output that arrived since.  Left to redisplay,
    ;; that would recentre on point and leave blank rows until the next
    ;; command's clamp, so a transcript following its end shows its latest
    ;; output now.
    (when (= (window-point output) (with-current-buffer transcript (point-max)))
      (set-window-start output (eng/bbh-transcript--latest-start output)))
    (select-window input)
    ;; Normal state, so leader keys work at once.  This may run outside a
    ;; command, from the Inbox's sentinel or the controller's emacsclient,
    ;; where no hook tells the terminal the new cursor shape.
    (evil-normal-state)
    (when (fboundp 'etcc--set-cursor) (etcc--set-cursor))
    composer))

;;; Workflow phase

(defvar-local eng/bbh-workflow--data nil
  "The last `wf status --json' answer for this shell's run, as an alist, or nil.
The composer header shows its phase.")

(defun eng/bbh-workflow--parse (json)
  "JSON from `wf status --json' as an alist, with null as nil."
  (json-parse-string json :object-type 'alist :array-type 'list
                     :null-object nil :false-object nil))

(defun eng/bbh-workflow--session-run (session)
  "The workflow run SESSION started, or nil when it has none."
  (let ((file (and session (eng/bbh--conversation-file session "workflow-run"))))
    (when (and file (file-exists-p file))
      (with-temp-buffer
        (insert-file-contents file)
        (string-trim (buffer-string))))))

(defun eng/bbh-workflow--fetch (dir session)
  "The run SESSION started, from `wf status --json' in DIR, or an alist with `error'.
Other runs in the repository are never read, so the header matches the session's own wf."
  (let ((run (eng/bbh-workflow--session-run session)))
    (if (null run)
        (list (cons 'error "This session has no workflow run yet."))
      (let ((default-directory (file-name-as-directory (expand-file-name dir)))
            (process-environment (cons (concat "WF_RUN=" run) process-environment)))
        (with-temp-buffer
          (let* ((code (call-process "wf" nil t nil "status" "--json"))
                 (text (buffer-string)))
            (condition-case nil
                (let ((data (eng/bbh-workflow--parse text)))
                  (cond ((alist-get 'error data) data)
                        ((zerop code) data)
                        (t (list (cons 'error "wf status failed")))))
              (error (list (cons 'error (let ((msg (string-trim text)))
                                          (if (string-empty-p msg) "wf status failed" msg))))))))))))

(defun eng/bbh-workflow-refresh (shell)
  "Read SHELL's workflow run again for the composer header, when its session has one."
  (when (buffer-live-p shell)
    (let ((session (eng/bbh--session-id shell)))
      (when (eng/bbh-workflow--session-run session)
        (let ((data (eng/bbh-workflow--fetch (buffer-local-value 'default-directory shell) session)))
          (with-current-buffer shell (setq eng/bbh-workflow--data data))
          (force-mode-line-update t))))))

;; A side panel once listed the run's phases.  Loading this file again, as a
;; Doom reload does, closes it in live frames and drops its commands.
(when-let* ((panel (get-buffer "*bbh workflow*"))) (kill-buffer panel))
(dolist (fn '(eng/bbh-workflow-show eng/bbh-workflow-toggle eng/bbh-workflow-refresh-if-shown
              eng/bbh-workflow-next eng/bbh-workflow-previous eng/bbh-workflow-mode
              eng/bbh-workflow--apply eng/bbh-workflow--render eng/bbh-workflow--move
              eng/bbh-workflow--current-index))
  (fmakunbound fn))

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

(load (expand-file-name "bbh-super.el" (file-name-directory (or load-file-name buffer-file-name))) nil 'nomessage)

(after! agent-shell-viewport
  (add-hook 'agent-shell-viewport-edit-mode-hook #'eng/bbh-setup-composer)
  ;; Loading this file again, as a Doom reload does, brings live composers up
  ;; to the new keys.
  (dolist (buffer (buffer-list))
    (with-current-buffer buffer
      (when (and eng/bbh-composer (derived-mode-p 'agent-shell-viewport-edit-mode))
        (eng/bbh-setup-composer))))
  (advice-add 'agent-shell-viewport--update-header :after #'eng/bbh-composer-header)
  (advice-add 'agent-shell-viewport--initialize :after #'eng/bbh-composer-drop-layout-newline))

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
