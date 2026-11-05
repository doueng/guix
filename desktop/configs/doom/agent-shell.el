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
                   (agent-shell--make-acp-client
                    :command (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))
                    :command-params (append '("acp") (when resume (list "--resume" resume)))
                    :context-buffer buffer))))

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
       (if (equal "router" (buffer-local-value 'eng/bbh-super-conversation (eng/bbh--shell)))
           (let ((composer (current-buffer)))
             (eng/bbh-super-say draft (lambda () (when (buffer-live-p composer)
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
      (when (fboundp 'evil-insert-state) (evil-insert-state))
      (let ((kill-buffer-query-functions nil))
        (when (buffer-live-p old-composer) (kill-buffer old-composer))
        (kill-buffer old)))))

;;; Website

;; `bbh inbox-poll' hands this shell what the person wrote on the website, and
;; it runs as a normal prompt through agent-shell's queue, which waits out a
;; running turn and leaves a composer draft alone.  A message leaves the mailbox
;; only once the turn holding it completed, so a failed prompt stays pending,
;; and the next completed turn asks for it again.  A crash can repeat a message,
;; which is why the agent gets the same text at least once rather than exactly
;; once.

(defvar eng/bbh-inbox-command nil
  "The poller's command as a list, or nil for `bbh inbox-poll'.
It is called with the conversation id and the project directory.")

(defvar-local eng/bbh-inbox--process nil
  "The poller serving this shell.")
(defvar-local eng/bbh-inbox--seen nil
  "Ids this shell took, so that a restarted poller cannot repeat one.")
(defvar-local eng/bbh-inbox--waiting nil
  "Alist of id and text for wakes not yet in a prompt.")
(defvar-local eng/bbh-inbox--inflight nil
  "Ids of wakes in the running turn.")
(defvar-local eng/bbh-inbox--failed nil
  "Ids of wakes whose turn failed.")

(defun eng/bbh-inbox--tell (line)
  "Send LINE to this shell's poller."
  (when (process-live-p eng/bbh-inbox--process)
    (process-send-string eng/bbh-inbox--process (concat line "\n"))))

(defun eng/bbh-inbox--on-line (shell line)
  "Run the delivery in LINE, a JSON object, as a prompt in SHELL."
  (with-current-buffer shell
    (let* ((delivery (json-parse-string line :object-type 'alist))
           (id (alist-get 'id delivery))
           (text (alist-get 'text delivery)))
      (unless (gethash id eng/bbh-inbox--seen)
        (puthash id t eng/bbh-inbox--seen)
        (push (cons id text) eng/bbh-inbox--waiting)
        (agent-shell--prompt-queue-enqueue :prompt text)
        (unless (shell-maker-busy)
          (agent-shell--prompt-queue-process-next))))))

(defun eng/bbh-inbox--on-event (shell event)
  "Track SHELL's wakes through EVENT, and start the poller once the session exists."
  (with-current-buffer shell
    (pcase (map-elt event :event)
      ((or 'prompt-ready 'init-finished) (eng/bbh-inbox-start shell))
      ('input-submitted
       (let ((prompt (map-nested-elt event '(:data :prompt))))
         (dolist (entry eng/bbh-inbox--waiting)
           (when (string-search (cdr entry) prompt)
             (setq eng/bbh-inbox--waiting (delq entry eng/bbh-inbox--waiting))
             (push (car entry) eng/bbh-inbox--inflight)))
         (eng/bbh-inbox--tell "busy true")))
      ('turn-complete
       (dolist (id eng/bbh-inbox--inflight)
         (eng/bbh-inbox--tell (concat "ack " id)))
       (dolist (id eng/bbh-inbox--failed)
         (remhash id eng/bbh-inbox--seen)
         (eng/bbh-inbox--tell (concat "retry " id)))
       (setq eng/bbh-inbox--inflight nil
             eng/bbh-inbox--failed nil)
       (eng/bbh-inbox--tell "busy false"))
      ('error
       (setq eng/bbh-inbox--failed (append eng/bbh-inbox--inflight eng/bbh-inbox--failed)
             eng/bbh-inbox--inflight nil)
       (eng/bbh-inbox--tell "busy false"))
      ('clean-up
       (when (process-live-p eng/bbh-inbox--process)
         (delete-process eng/bbh-inbox--process))))))

(defun eng/bbh-inbox--sentinel (shell process)
  "Restart SHELL's poller when PROCESS, its current one, dies on its own."
  (when (and (buffer-live-p shell)
             (eq process (buffer-local-value 'eng/bbh-inbox--process shell))
             (memq (process-status process) '(exit signal)))
    (run-at-time 5 nil (lambda ()
                         (when (buffer-live-p shell)
                           (with-current-buffer shell
                             (unless (process-live-p eng/bbh-inbox--process)
                               (eng/bbh-inbox-start shell))))))))

(defun eng/bbh-inbox-start (shell)
  "Serve SHELL's conversation from a poller, unless one is running."
  (with-current-buffer shell
    (when-let* (((not (process-live-p eng/bbh-inbox--process)))
                (conversation (eng/bbh--conversation shell))
                (project (directory-file-name (expand-file-name default-directory)))
                (partial ""))
      (setq eng/bbh-inbox--process
            (make-process
             :name "bbh-inbox" :noquery t :connection-type 'pipe
             :stderr (get-buffer-create " *bbh-inbox*")
             :command (append (or eng/bbh-inbox-command
                                  (list (or (executable-find "bbh") (expand-file-name "~/.local/bin/bbh"))
                                        "inbox-poll"))
                              (list conversation project))
             :sentinel (lambda (process _) (eng/bbh-inbox--sentinel shell process))
             :filter (lambda (_ output)
                       (setq partial (concat partial output))
                       (while-let ((end (string-search "\n" partial)))
                         (let ((line (substring partial 0 end)))
                           (setq partial (substring partial (1+ end)))
                           (when (buffer-live-p shell)
                             (eng/bbh-inbox--on-line shell line))))))))))

(defun eng/bbh-inbox-connect (shell)
  "Connect SHELL to its mailbox."
  (with-current-buffer shell
    (setq eng/bbh-inbox--seen (make-hash-table :test #'equal)))
  (agent-shell-subscribe-to :shell-buffer shell
                            :on-event (lambda (event) (eng/bbh-inbox--on-event shell event)))
  (eng/bbh-inbox-start shell))

(defun eng/bbh-inbox-switch (shell)
  "Move SHELL's poller to the conversation it is on now.
Messages to the old conversation stay in its mailbox until it is resumed."
  (with-current-buffer shell
    (when eng/bbh-inbox--process
      (let ((process eng/bbh-inbox--process))
        (setq eng/bbh-inbox--process nil
              eng/bbh-inbox--waiting nil
              eng/bbh-inbox--inflight nil
              eng/bbh-inbox--failed nil)
        (clrhash eng/bbh-inbox--seen)
        (delete-process process))
      (eng/bbh-inbox-start shell))))

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
                            (eng/bbh-transcript--prompt (map-elt data :prompt)))
          ('agent-message-chunk (eng/bbh-transcript--reply-chunk (map-elt data :text-chunk)))
          ('tool-call-update (eng/bbh-transcript--tool (map-elt data :tool-call-id)
                                                       (map-elt data :tool-call)))
          ('error (eng/bbh-transcript--end-turn)
                  (eng/bbh-transcript--error (or (map-elt data :message) "the request failed")))
          ('turn-complete (setq eng/bbh-transcript--reply nil)
                          (let ((elapsed (eng/bbh--elapsed eng/bbh-transcript-shell)))
                            (eng/bbh-transcript--end-turn)
                            (eng/bbh-transcript--usage elapsed))))
        (when (or (memq (map-elt event :event) '(input-submitted turn-complete))
                  (and (eq (map-elt event :event) 'tool-call-update)
                       (equal (map-nested-elt data '(:tool-call :status)) "completed")))
          (eng/bbh-workflow-refresh-if-shown eng/bbh-transcript-shell))))))

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

;; Doom's evil-org keys, which win in Doom anyway: gj and gk move by element,
;; ]h and [h between prompts, gh up.  These copies serve Emacs without
;; evil-org.  S-TAB stays org's cycle; SPC m t or C-c C-t folds tool results.
;; n, p and the other evil keys keep their meaning; q buries.
(with-eval-after-load 'evil
  (evil-define-key 'normal eng/bbh-transcript-mode-map
    (kbd "gj") #'org-forward-element
    (kbd "gk") #'org-backward-element
    (kbd "]h") #'org-forward-heading-same-level
    (kbd "[h") #'org-backward-heading-same-level
    (kbd "gh") #'org-up-element
    (kbd "q") #'bury-buffer)
  ;; evil-org's C-j and C-k shadow focus, which moves between transcript and
  ;; composer. The transcript is read-only, so normal state is enough.
  (evil-define-key 'normal eng/bbh-transcript-mode-map
    (kbd "C-j") #'eng/focus-down
    (kbd "C-k") #'eng/focus-up))

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
  "SHELL's workflow run as WORKFLOW: PHASE from the panel's last read, or nil.
This reads no process, so it is cheap enough for the status line."
  (when-let* ((buffer (get-buffer eng/bbh-workflow-buffer))
              (session (eng/bbh--session-id shell))
              ((equal session (buffer-local-value 'eng/bbh-workflow--session buffer)))
              (data (buffer-local-value 'eng/bbh-workflow--data buffer))
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
         (parts (delq nil (list (if busy "Working" "Ready")
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
    (dolist (binding '(("C-n" . eng/bbh-send)
                       ("C-c C-c" . eng/bbh-send)
                       ("M-RET" . eng/bbh-send)
                       ("C-c C-k" . agent-shell-viewport-interrupt)
                       ("C-c C-t" . eng/bbh-toggle-tools)
                       ("C-c ?" . eng/bbh-keys)
                       ("M-k" . eng/bbh-output-page-up)
                       ("M-j" . eng/bbh-output-page-down)))
      (local-set-key (kbd (car binding)) (cdr binding))
      (when (fboundp 'evil-local-set-key)
        (dolist (state '(normal insert))
          (evil-local-set-key state (kbd (car binding)) (cdr binding)))))
    ;; C-n sends in every state.  In normal state C-d and C-u page the
    ;; transcript, since the composer is a few lines tall.
    (when (fboundp 'evil-local-set-key)
      (dolist (binding '(("g?" . eng/bbh-keys)
                         ("C-d" . eng/bbh-output-page-down)
                         ("C-u" . eng/bbh-output-page-up)))
        (evil-local-set-key 'normal (kbd (car binding)) (cdr binding))))
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
    (eng/bbh-workflow-show shell)
    ;; `set-window-buffer' brings back the start the transcript had when it
    ;; was last shown, above output that arrived since.  Left to redisplay,
    ;; that would recentre on point and leave blank rows until the next
    ;; command's clamp, so a transcript following its end shows its latest
    ;; output now.  This runs after the panel, which narrows the window.
    (when (= (window-point output) (with-current-buffer transcript (point-max)))
      (set-window-start output (eng/bbh-transcript--latest-start output)))
    (select-window input)
    ;; Normal state, so leader keys work at once.  This may run outside a
    ;; command, from the Inbox's sentinel or the controller's emacsclient,
    ;; where no hook tells the terminal the new cursor shape.
    (when (fboundp 'evil-normal-state) (evil-normal-state))
    (when (fboundp 'etcc--set-cursor) (etcc--set-cursor))
    composer))

;;; Workflow panel

(defconst eng/bbh-workflow-buffer "*bbh workflow*")

(defvar-local eng/bbh-workflow--data nil
  "The last `wf status --json' answer, as an alist.")

(defvar-local eng/bbh-workflow--dir nil
  "The directory the panel runs `wf' in.")

(defvar-local eng/bbh-workflow--session nil
  "The bbh conversation whose workflow run the panel shows.")

(defvar-local eng/bbh-workflow--selected 0
  "Index of the phase whose brief and gates the panel describes.")

(defvar-local eng/bbh-workflow--run nil
  "(RUN . PHASE) last applied, so a new run or phase selects the current phase.")

(define-derived-mode eng/bbh-workflow-mode special-mode "bbh-workflow"
  "Read-only view of the pstack workflow run in a bbh session.
Moving the selection changes only what this panel shows. Only `wf' advances a phase."
  (setq buffer-read-only t)
  (setq-local mode-line-format nil)
  (visual-line-mode 1))

(define-key eng/bbh-workflow-mode-map (kbd "j") #'eng/bbh-workflow-next)
(define-key eng/bbh-workflow-mode-map (kbd "k") #'eng/bbh-workflow-previous)
(define-key eng/bbh-workflow-mode-map (kbd "<down>") #'eng/bbh-workflow-next)
(define-key eng/bbh-workflow-mode-map (kbd "<up>") #'eng/bbh-workflow-previous)
;; g is a prefix here, as in evil; special-mode's g would make gr undefinable.
(define-key eng/bbh-workflow-mode-map (kbd "g") (make-sparse-keymap))
(define-key eng/bbh-workflow-mode-map (kbd "gr") #'eng/bbh-workflow-refresh)

(with-eval-after-load 'evil
  (dolist (state '(normal motion))
    (evil-define-key state eng/bbh-workflow-mode-map
      (kbd "j") #'eng/bbh-workflow-next
      (kbd "k") #'eng/bbh-workflow-previous
      (kbd "gr") #'eng/bbh-workflow-refresh)))

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
Other runs in the repository are never shown, so the panel matches the session's own wf."
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

(defun eng/bbh-workflow--current-index (data)
  (or (seq-position (alist-get 'phases data) (alist-get 'phase data)
                    (lambda (phase name) (equal name (alist-get 'name phase))))
      0))

(defun eng/bbh-workflow--render ()
  "Draw the run and the selected phase in the current buffer."
  (let ((inhibit-read-only t)
        (data eng/bbh-workflow--data)
        (phases (alist-get 'phases eng/bbh-workflow--data)))
    (erase-buffer)
    (cond
     ((alist-get 'error data)
      (insert (alist-get 'error data) "\n"))
     (t
      (insert (format "%s  %s\n" (alist-get 'workflow data) (alist-get 'status data)))
      (when-let* ((reason (alist-get 'block_reason data)))
        (insert (propertize (format "blocked: %s\n" reason) 'face 'error)))
      (insert "\n")
      (seq-do-indexed
       (lambda (phase i)
         (let* ((name (alist-get 'name phase))
                (current (equal name (alist-get 'phase data)))
                (mark (cond ((and current (equal (alist-get 'status data) "done")) "✓")
                            (current "▶")
                            ((> (alist-get 'visits phase 0) 0) "✓")
                            (t " ")))
                (line (format "%s %s\n" mark name)))
           (insert (if (= i eng/bbh-workflow--selected) (propertize line 'face 'highlight) line))))
       phases)
      (when-let* ((phase (nth eng/bbh-workflow--selected phases)))
        (insert "\n" (propertize (alist-get 'name phase) 'face 'bold) "\n")
        (insert (or (alist-get 'brief phase) "") "\n")
        (dolist (gate (alist-get 'gates phase))
          (insert "  - " gate "\n")))))
    (goto-char (point-min))))

(defun eng/bbh-workflow--apply (data)
  "Show DATA, a `wf status --json' answer, in the panel buffer."
  (with-current-buffer (get-buffer-create eng/bbh-workflow-buffer)
    (unless (derived-mode-p 'eng/bbh-workflow-mode) (eng/bbh-workflow-mode))
    (setq eng/bbh-workflow--data data)
    (let ((key (cons (alist-get 'run data) (alist-get 'phase data))))
      (unless (equal key eng/bbh-workflow--run)
        (setq eng/bbh-workflow--run key
              eng/bbh-workflow--selected (eng/bbh-workflow--current-index data))))
    (setq eng/bbh-workflow--selected
          (min (max 0 eng/bbh-workflow--selected)
               (max 0 (1- (length (alist-get 'phases data))))))
    (eng/bbh-workflow--render)))

(defun eng/bbh-workflow-refresh ()
  "Read the newest workflow run for the panel's directory again."
  (interactive)
  (with-current-buffer (get-buffer-create eng/bbh-workflow-buffer)
    (unless (derived-mode-p 'eng/bbh-workflow-mode) (eng/bbh-workflow-mode))
    (eng/bbh-workflow--apply
     (eng/bbh-workflow--fetch (or eng/bbh-workflow--dir default-directory)
                              eng/bbh-workflow--session))))

(defun eng/bbh-workflow--move (delta)
  (with-current-buffer (get-buffer-create eng/bbh-workflow-buffer)
    (let ((count (length (alist-get 'phases eng/bbh-workflow--data))))
      (when (> count 0)
        (setq eng/bbh-workflow--selected
              (max 0 (min (1- count) (+ eng/bbh-workflow--selected delta))))
        (eng/bbh-workflow--render)))))

(defun eng/bbh-workflow-next ()
  "Select the next phase in the panel. This does not change the run."
  (interactive)
  (eng/bbh-workflow--move 1))

(defun eng/bbh-workflow-previous ()
  "Select the previous phase in the panel. This does not change the run."
  (interactive)
  (eng/bbh-workflow--move -1))

(defun eng/bbh-workflow-show (shell)
  "Show the workflow panel for SHELL's session in a side window on the right.
The panel appears only when the session has started a run.  Return the window, or nil."
  (let ((dir (or (buffer-local-value 'default-directory shell) default-directory))
        (buffer (get-buffer-create eng/bbh-workflow-buffer)))
    (with-current-buffer buffer
      (unless (derived-mode-p 'eng/bbh-workflow-mode) (eng/bbh-workflow-mode))
      (setq eng/bbh-workflow--dir dir
            eng/bbh-workflow--session (eng/bbh--session-id shell)))
    (eng/bbh-workflow-refresh)
    (if (eng/bbh-workflow--session-run (buffer-local-value 'eng/bbh-workflow--session buffer))
        (display-buffer-in-side-window buffer '((side . right) (window-width . 40)))
      (when-let* ((window (get-buffer-window buffer t))) (delete-window window))
      nil)))

(defun eng/bbh-workflow-toggle ()
  "Hide the workflow panel, or show it for the current bbh session."
  (interactive)
  (if-let* ((window (get-buffer-window eng/bbh-workflow-buffer)))
      (delete-window window)
    (unless (eng/bbh-workflow-show (or (eng/bbh--shell) (user-error "No bbh session in this buffer")))
      (message "This session has no workflow run yet."))))

(defun eng/bbh-workflow-refresh-if-shown (&optional shell)
  "Refresh the panel after a transcript event.  Show it when SHELL starts a run."
  (cond ((get-buffer-window eng/bbh-workflow-buffer t) (eng/bbh-workflow-refresh))
        ((and shell (eng/bbh-workflow--session-run (eng/bbh--session-id shell)))
         (eng/bbh-workflow-show shell))))

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

(defun eng/bbh-super--router-submit (submit &rest args)
  "Send what you typed at the Router's prompt through the controller.
Mailbox prompts, which arrive outside a command, still SUBMIT directly."
  (if (and this-command (not args) (equal "router" eng/bbh-super-conversation))
      (let* ((start (process-mark (get-buffer-process (current-buffer))))
             (text (string-trim (buffer-substring-no-properties start (point-max)))))
        (unless (string-empty-p text)
          (eng/bbh-super-say text (lambda ()
                                    (with-current-buffer (eng/bbh-super--shell "router")
                                      (delete-region (process-mark (get-buffer-process (current-buffer)))
                                                     (point-max)))))))
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
mailbox wake waits for a turn end that never comes.  Returns the shell's
name, or nil when no session runs CONVERSATION."
  (when-let* ((shell (eng/bbh-super--shell conversation)))
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
        (err (generate-new-buffer " *bbh inbox err*")))
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
  (eng/bbh-super--clear-frame)
  (switch-to-buffer (eng/bbh-inbox-buffer)))

;; Loading this file again, as a Doom reload does, brings a live Inbox up to
;; the new code: its buffer setup and the listener.
(when-let* ((buffer (get-buffer "*bbh inbox*")))
  (with-current-buffer buffer
    (when (derived-mode-p 'eng/bbh-inbox-mode)
      (eng/bbh-inbox--setup)
      (eng/bbh-inbox--listen))))

(after! agent-shell-viewport
  (add-hook 'agent-shell-viewport-edit-mode-hook #'eng/bbh-setup-composer)
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
