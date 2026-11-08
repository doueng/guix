;;; agent-shell-test.el --- bbh acp through Doom's agent-shell config -*- lexical-binding: t; -*-

;; Run with: bb test:emacs

(require 'ert)
(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(dolist (pkg '("goto-chg" "evil"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(require 'evil)
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)
(require 'agent-shell)

(ert-deftest bbh-inbox-keys-are-evil-normal-state ()
  (with-current-buffer (get-buffer-create "*bbh-inbox-keys*")
    (eng/bbh-inbox-mode)
    (evil-local-mode 1)
    (should (eq 'normal evil-state))
    (dolist (key '(("gr" . eng/bbh-inbox-refresh) ("s" . eng/bbh-inbox-send) ("2" . eng/bbh-inbox-pick)
                   ("RET" . eng/bbh-inbox-ret) ("R" . eng/bbh-inbox-router)
                   ("." . eng/bbh-inbox-pick-recommended) ("i" . eng/bbh-inbox-answer) ("A" . eng/bbh-inbox-approve) ("J" . eng/bbh-inbox-jjui) ("gJ" . eng/bbh-inbox-jjui-close) ("q" . quit-window)
                   ("j" . evil-next-line) ("gg" . evil-goto-first-line) ("G" . evil-goto-line)))
      (should (eq (cdr key) (key-binding (kbd (car key))))))))

(defun bbh-test--wait (pred secs)
  (let ((end (+ (float-time) secs)))
    (while (and (not (funcall pred)) (< (float-time) end))
      (accept-process-output nil 0.1))
    (funcall pred)))

(defun bbh-test--start ()
  (let ((buf (agent-shell-start :config (eng/bbh-agent-config))))
    (with-current-buffer buf
      (should (bbh-test--wait (lambda () (and (map-nested-elt agent-shell--state '(:session :id))
                                              (string-match-p "Ready" (buffer-string))))
                              30))
      (accept-process-output nil 0.5))
    buf))

(defun bbh-test--submit (buf text)
  (with-current-buffer buf
    (goto-char (point-max))
    (insert text)
    (shell-maker-submit)))

(defun bbh-test--idle-p (buf)
  (with-current-buffer buf (not (shell-maker-busy))))

(defun bbh-test--resumed-p (shell transcript id)
  "Whether the /resume of ID has shown in TRANSCRIPT and SHELL's turn ended."
  (and (string-match-p (concat "^\\* /resume " id "$") (bbh-test--text transcript))
       (bbh-test--idle-p shell)))

(defun bbh-test--text (buf)
  (with-current-buffer buf (buffer-substring-no-properties (point-min) (point-max))))

(ert-deftest bbh-resume-clears-the-transcript-unless-the-id-is-unknown ()
  (save-window-excursion
    (delete-other-windows)
    (let* ((old (bbh-test--start))
           (old-id (with-current-buffer old (map-nested-elt agent-shell--state '(:session :id))))
           (_ (eng/bbh-layout old))
           (transcript (buffer-local-value 'eng/bbh-transcript old)))
      (insert "add forty")
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (bbh-test--idle-p old)) 60))
      (insert "/new")
      (eng/bbh-send)
      (let ((shell (eng/bbh--shell)))
        (should (bbh-test--wait (lambda () (with-current-buffer shell
                                             (and (map-nested-elt agent-shell--state '(:session :id))
                                                  (string-match-p "Ready" (buffer-string)))))
                                30))
        (insert "/port")
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
        (insert "/nope")
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (string-match-p "^Error: Unknown command /nope\\. /help lists commands\\.$" (bbh-test--text transcript))) 30))
        (with-current-buffer transcript
          (goto-char (point-min))
          (search-forward "Error: Unknown")
          (should (eq 'error (get-text-property (match-beginning 0) 'font-lock-face))))
        (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
        (insert "/resume nope")
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (string-match-p "No such conversation" (bbh-test--text transcript))) 30))
        (should (string-prefix-p "* /port" (bbh-test--text transcript)))
        (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
        (insert (concat "/resume " old-id))
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (bbh-test--resumed-p shell transcript old-id)) 30))
        (should (string-prefix-p "* add forty\n" (bbh-test--text transcript)))
        (should (bbh-test--wait (lambda () (string-match-p "The answer is 42" (bbh-test--text transcript))) 30))
        (should-not (string-match-p "Last reply\\|Resumed " (bbh-test--text transcript)))
        (should-not (string-match-p "No such conversation" (bbh-test--text transcript)))))))

(ert-deftest bbh-replay-renders-saved-messages ()
  (let* ((data (make-temp-file "bbh-replay-test" t))
         (process-environment (cons (concat "XDG_DATA_HOME=" data) process-environment))
         (file (eng/bbh--conversation-file "20200101-000000-000" "messages.jsonl")))
    (make-directory (file-name-directory file) t)
    (write-region
     (concat
      "{\"role\":\"user\",\"content\":\"<skill>long</skill>\\n\\nfirst\\nsecond\"}\n"
      "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"clojure\",\"arguments\":\"{\\\"code\\\":\\\"(+ 1 2)\\\"}\"}}]}\n"
      "{\"role\":\"tool\",\"tool_call_id\":\"a\",\"content\":\"=> 3\"}\n"
      "{\"role\":\"assistant\",\"content\":\"Done.\"}\n"
      "{\"role\":\"user\",\"content\":\"[bbh] A note.\\n\\nagain\"}\n"
      "{\"role\":\"assistant\",\"content\":\"Fine.\"}\n")
     nil file)
    (unwind-protect
        (with-temp-buffer
          (eng/bbh-transcript-mode)
          (setq eng/bbh-transcript--tools (make-hash-table :test #'equal))
          (eng/bbh-transcript--replay "20200101-000000-000")
          (should (equal (buffer-string)
                         (concat "* first\n#+begin_quote\nsecond\n#+end_quote\n\n"
                                 "#+begin_src clojure\n(+ 1 2)\n#+end_src\n"
                                 "#+begin_result done 1\n=> 3\n#+end_result\n\n"
                                 "Done.\n\n* again\n\nFine.\n"))))
      (delete-directory data t))))

(ert-deftest bbh-resuming-this-conversation-keeps-the-transcript ()
  (save-window-excursion
    (delete-other-windows)
    (let* ((shell (bbh-test--start))
           (id (with-current-buffer shell (map-nested-elt agent-shell--state '(:session :id))))
           (_ (eng/bbh-layout shell))
           (transcript (buffer-local-value 'eng/bbh-transcript shell)))
      (insert "add forty")
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 60))
      (insert (concat "/resume " id))
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (string-match-p "No such conversation" (bbh-test--text transcript))) 30))
      (should (string-match-p "The answer is 42\\." (bbh-test--text transcript))))))

(ert-deftest bbh-resume-waits-for-the-running-turn ()
  (save-window-excursion
    (delete-other-windows)
    (let* ((script (make-temp-file "bbh-sleep" nil ".edn" "[{:code \"(Thread/sleep 60000)\"} \"not reached\"]"))
           (shell (bbh-test--start))
           (composer (eng/bbh-layout shell))
           (transcript (buffer-local-value 'eng/bbh-transcript shell)))
      (insert (concat "/model script/" script))
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
      (insert "sleep")
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (string-match-p "Thread/sleep" (bbh-test--text transcript))) 30))
      (dolist (draft '("/resume" "/resume last"))
        (with-current-buffer composer
          (insert draft)
          (should-error (eng/bbh-send) :type 'user-error)
          (should (equal (string-trim (buffer-string)) draft))
          (erase-buffer)))
      (should (string-match-p "Thread/sleep" (bbh-test--text transcript)))
      (should-not (buffer-local-value 'eng/bbh-conversation shell))
      (with-current-buffer shell (agent-shell-interrupt t))
      (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 15)))))

(ert-deftest bbh-resume-drops-the-old-conversations-usage ()
  (save-window-excursion
    (delete-other-windows)
    (let* ((script (make-temp-file "bbh-usage" nil ".edn" "[{:text \"Hello.\" :usage {:input 1000 :cached 0 :output 10 :size 200000}}]"))
           (old (bbh-test--start))
           (old-id (with-current-buffer old (map-nested-elt agent-shell--state '(:session :id))))
           (_ (eng/bbh-layout old)))
      (insert "add forty")
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (bbh-test--idle-p old)) 60))
      (insert "/new")
      (eng/bbh-send)
      (let* ((shell (eng/bbh--shell))
             (transcript (buffer-local-value 'eng/bbh-transcript shell)))
        (should (bbh-test--wait (lambda () (with-current-buffer shell
                                             (and (map-nested-elt agent-shell--state '(:session :id))
                                                  (string-match-p "Ready" (buffer-string)))))
                                30))
        (insert (concat "/model script/" script))
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
        (insert "hi")
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (eng/bbh-usage-header shell)) 30))
        (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
        (insert (concat "/resume " old-id))
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (bbh-test--resumed-p shell transcript old-id)) 30))
        (should-not (eng/bbh-usage-header shell))))))

(ert-deftest bbh-startup-resume-shows-the-conversation ()
  (save-window-excursion
    (delete-other-windows)
    (let* ((old (bbh-test--start))
           (id (with-current-buffer old (map-nested-elt agent-shell--state '(:session :id))))
           (_ (eng/bbh-layout old)))
      (insert "add forty")
      (eng/bbh-send)
      (should (bbh-test--wait (lambda () (bbh-test--idle-p old)) 60))
      (eng/bbh id)
      (let ((text (bbh-test--text (eng/bbh--transcript (eng/bbh--shell)))))
        (should-not (eq (eng/bbh--shell) old))
        (should (string-prefix-p "* add forty\n" text))
        (should (string-suffix-p (concat "The answer is 42.\n\nResumed " id "\n") text))))))

(ert-deftest bbh-conversation-follows-resume ()
  (let ((shell (generate-new-buffer " bbh-test-shell")))
    (unwind-protect
        (with-current-buffer shell
          (setq-local agent-shell--state (list (cons :session (list (cons :id "20200101-000000-000")))))
          (should (equal (eng/bbh--conversation shell) "20200101-000000-000"))
          (setq eng/bbh-conversation "20210101-000000-000")
          (should (equal (eng/bbh--conversation shell) "20210101-000000-000")))
      (kill-buffer shell))))

;;; Website mailbox

(defun bbh-test--mail (conversation id text &optional kind)
  "Leave TEXT in CONVERSATION's mailbox as bbh does, under message ID."
  (let ((dir (eng/bbh-mail-dir conversation)))
    (make-directory dir t)
    (with-temp-file (expand-file-name (concat id ".json") dir)
      (insert (json-serialize (append (list :id id :text text) (when kind (list :kind kind))))))
    (expand-file-name (concat id ".json") dir)))

(defmacro bbh-test--with-shell (spec &rest body)
  "Bind SPEC, (SHELL COMPOSER TRANSCRIPT), around BODY in a fresh layout."
  (declare (indent 1))
  (pcase-let ((`(,shell ,composer ,transcript) spec))
    `(save-window-excursion
       (delete-other-windows)
       (let* ((,shell (bbh-test--start))
              (,composer (eng/bbh-layout ,shell))
              (,transcript (buffer-local-value 'eng/bbh-transcript ,shell)))
         (ignore ,composer ,transcript)
         ,@body))))

(ert-deftest bbh-mail-wakes-an-idle-shell-and-keeps-the-draft ()
  (bbh-test--with-shell (shell composer transcript)
    (with-current-buffer composer (insert "half typed"))
    (should (buffer-local-value 'eng/bbh-mail--watch shell))
    (let ((file (bbh-test--mail (eng/bbh--conversation shell) "1-m1" "sent from the phone")))
      (should (bbh-test--wait (lambda () (string-match-p "The answer is 42" (bbh-test--text transcript))) 30))
      (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
      (should (string-match-p "^\\* \\[mail\\]$" (bbh-test--text transcript)))
      (should (string-match-p "sent from the phone" (bbh-test--text transcript)))
      (should-not (file-exists-p file))
      (should (equal "half typed" (with-current-buffer composer (string-trim (buffer-string))))))))

(ert-deftest bbh-mail-waits-while-the-shell-is-stuck-busy ()
  ;; T36: shell-maker-busy stayed t after a turn, and every wake was lost.
  (bbh-test--with-shell (shell composer transcript)
    (let (file)
      (cl-letf (((symbol-function 'shell-maker-busy) (lambda () t)))
        (setq file (bbh-test--mail (eng/bbh--conversation shell) "2-m2" "sent while stuck"))
        (bbh-test--wait (lambda () nil) 1.5)
        (should-not (string-match-p "\\[mail\\]" (bbh-test--text transcript)))
        (should (file-exists-p file)))
      (eng/bbh-mail--on-event shell '((:event . turn-complete)))
      (should (bbh-test--wait (lambda () (not (file-exists-p file))) 30))
      (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
      (should (= 1 (with-current-buffer transcript (how-many "^\\* \\[mail\\]$" (point-min) (point-max)))))
      (should (string-match-p "sent while stuck" (bbh-test--text transcript))))))

(ert-deftest bbh-a-cancel-in-the-mailbox-starts-no-turn ()
  (bbh-test--with-shell (shell composer transcript)
    (bbh-test--mail (eng/bbh--conversation shell) "0-cancel-W1" "[Cancelled] stop" "cancel")
    (bbh-test--wait (lambda () nil) 1.5)
    (should-not (string-match-p "\\[mail\\]" (bbh-test--text transcript)))))

(ert-deftest bbh-mail-watch-follows-new-and-resume ()
  (bbh-test--with-shell (shell composer transcript)
    (let ((first-id (eng/bbh--conversation shell)))
      (with-current-buffer composer
        (insert "add forty")
        (eng/bbh-send))
      (should (bbh-test--wait (lambda () (string-match-p "The answer is 42" (bbh-test--text transcript))) 30))
      (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
      (with-current-buffer composer
        (insert "/new")
        (eng/bbh-send))
      (let ((next (eng/bbh--shell)))
        (should-not (eq next shell))
        (should (bbh-test--wait (lambda () (with-current-buffer next
                                             (and (map-nested-elt agent-shell--state '(:session :id))
                                                  (string-match-p "Ready" (buffer-string)))))
                                30))
        (insert "/resume " first-id)
        (eng/bbh-send)
        (should (bbh-test--wait (lambda () (and (equal first-id (eng/bbh--conversation next)) (bbh-test--idle-p next))) 30))
        (let ((file (bbh-test--mail first-id "3-m3" "to the resumed conversation")))
          (should (bbh-test--wait (lambda () (not (file-exists-p file))) 30))
          (should (bbh-test--wait (lambda () (string-match-p "to the resumed conversation" (bbh-test--text transcript)))
                                  30)))))))


;;; Super-agent

(defun bbh-test--environ (pid)
  (with-temp-buffer
    (insert-file-contents-literally (format "/proc/%d/environ" pid))
    (split-string (buffer-string) "\0" t)))

(ert-deftest bbh-super-open-runs-the-conversation-in-its-workspace-with-its-environment ()
  (let* ((dir (make-temp-file "bbh-super-ws" t))
         (name (eng/bbh-super-open "super-W7" dir "T7 test"
                                   '(("BBH_SUPER_TOKEN" . "tok-7") ("WF_RUN" . "run-7"))))
         (shell (get-buffer name)))
    (unwind-protect
        (progn
          (should (equal "T7 test" name))
          (should (bbh-test--wait (lambda () (with-current-buffer shell (map-nested-elt agent-shell--state '(:session :id)))) 30))
          (should (equal "super-W7" (eng/bbh--conversation shell)))
          (should (equal "T7 test transcript" (buffer-name (buffer-local-value 'eng/bbh-transcript shell))))
          ;; The renamed shell still takes a prompt and runs it.
          (bbh-test--submit shell "add forty and two")
          (should (bbh-test--wait (lambda () (and (bbh-test--idle-p shell) (string-match-p "=> 42" (bbh-test--text shell)))) 60))
          (let ((acp (seq-find (lambda (p) (and (member "acp" (process-command p)) (member "super-W7" (process-command p)))) (process-list))))
            (should acp)
            (should (equal '("acp" "--id" "super-W7") (last (process-command acp) 3)))
            (should (member "BBH_SUPER_TOKEN=tok-7" (bbh-test--environ (process-id acp))))
            (should (member "WF_RUN=run-7" (bbh-test--environ (process-id acp))))
            (should (member "PATH" (mapcar (lambda (v) (car (split-string v "="))) (bbh-test--environ (process-id acp)))))
            (should (file-equal-p dir (file-truename (format "/proc/%d/cwd" (process-id acp))))))
          ;; Shown, it has a composer; replacing it must not ask about the shell.
          (eng/bbh-super-visit name)
          (should (agent-shell-viewport--buffer :shell-buffer shell :existing-only t))
          (let ((again (cl-letf (((symbol-function 'y-or-n-p) (lambda (p) (error "Asked: %s" p))))
                         (eng/bbh-super-open "super-W7" dir "T7 test" nil))))
            (should (equal "T7 test" again))
            (should-not (buffer-live-p shell))
            (should (= 1 (seq-count (lambda (b) (equal "super-W7" (buffer-local-value 'eng/bbh-super-conversation b)))
                                    (buffer-list))))))
      (when-let* ((s (eng/bbh-super--shell "super-W7"))) (eng/bbh-super--kill s)))))

(ert-deftest bbh-super-finished-session-runs-nothing-after-its-workspace-is-deleted ()
  ;; super-done! deletes the Worker's workspace, the shell's directory, in the
  ;; middle of its last turn, after the controller released the session. The
  ;; shell must still go idle, and then run nothing in the deleted directory:
  ;; mail does not wake it, and what the person types goes to the Router.
  (let* ((ws (make-temp-file "bbh-super-ws" t))
         (repo (make-temp-file "bbh-super-repo" t))
         (shell (get-buffer (eng/bbh-super-open "super-W9" ws "T9 test" nil))))
    (unwind-protect
        (progn
          (should (bbh-test--wait (lambda () (with-current-buffer shell (map-nested-elt agent-shell--state '(:session :id)))) 30))
          (bbh-test--submit shell (concat "/model script/"
                                          (make-temp-file "bbh-inbox" nil ".edn"
                                                          "[{:code \"(Thread/sleep 1500)\"} \"landed\" \"ran-a-wake\" \"ran-a-typed-prompt\"]")))
          (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
          (bbh-test--submit shell "finish")
          (should (bbh-test--wait (lambda () (string-match-p "Thread/sleep" (bbh-test--text shell))) 30))
          ;; What the controller does while the turn runs: release, then retire.
          (should (equal "T9 test" (eng/bbh-super-release "super-W9" repo)))
          (should-not (eng/bbh-super-release "super-W404" repo))
          (delete-directory ws t)
          (should (bbh-test--wait (lambda () (string-match-p "landed" (bbh-test--text shell))) 30))
          (should (bbh-test--wait (lambda () (bbh-test--idle-p shell)) 30))
          ;; Mail to the finished Worker wakes nothing, so it stays unread.
          (let ((file (bbh-test--mail "super-W9" "9-m9" "job exited")))
            (sleep-for 2)
            (should (file-exists-p file)))
          ;; What the person types goes to the Router, naming the thread.
          (let* ((said nil)
                 (this-command 'shell-maker-submit))
            (cl-letf (((symbol-function 'eng/bbh-super-say)
                       (lambda (text on-done) (setq said text) (funcall on-done))))
              (bbh-test--submit shell "are you there"))
            (should (equal "About T9, whose Worker is finished: are you there" said)))
          (sleep-for 2)
          (should (bbh-test--idle-p shell))
          (should-not (string-match-p "ran-a-" (bbh-test--text shell))))
      (when-let* ((s (eng/bbh-super--shell "super-W9"))) (eng/bbh-super--kill s))
      (delete-directory repo t))))

(ert-deftest bbh-super-open-refuses-a-missing-directory ()
  (should-error (eng/bbh-super-open "super-W8" "/nonexistent/bbh" "T8" nil)))

(defconst bbh-test--outline
  (concat "* STOPPED T1 first\n"
          "** a1 Keep the alias?\n"
          "   1. No  (recommended)\n"
          "   2. Yes\n"
          "*** Details\n"
          "    Because of reasons\n"
          "*** Answer\n"
          "\n"
          "* NEW T2 second\n"
          "  its summary")
  "`bbh inbox org' output after its header: a thread with one ask, and one without.")

(defun bbh-test--key (keys)
  "The command KEYS run in the current buffer."
  (key-binding (kbd keys)))

(ert-deftest bbh-inbox-shows-the-outline-and-its-keys-call-bbh ()
  (let* ((log (make-temp-file "bbh-inbox-log"))
         (outline (make-temp-file "bbh-inbox-outline" nil nil (concat "bbh inbox · 1 need you\n" bbh-test--outline)))
         (script (make-temp-file "bbh-fake" nil nil
                                 (concat "#!/bin/sh\necho \"$@\" >> " log "\n"
                                         "case \"$2\" in\n"
                                         "org) cat " outline ";;\n"
                                         "esac\n")))
         (eng/bbh-inbox-bbh (list script))
         (eng/bbh-inbox-view 'outline)
         (logged (lambda (line) (with-temp-buffer (insert-file-contents log)
                                                  (string-match-p (concat "^" (regexp-quote line) "$") (buffer-string))))))
    (set-file-modes script #o700)
    (eng/bbh-inbox)
    (with-current-buffer "*bbh inbox*"
      (should (bbh-test--wait (lambda () (string-match-p "T2 second" (buffer-string))) 10))
      (should (derived-mode-p 'org-mode))
      (should (equal "bbh inbox · 1 need you" header-line-format))
      (should (bbh-test--wait (lambda () (with-temp-buffer (insert-file-contents log) (string-match-p "^inbox register [0-9]+$" (buffer-string)))) 0))
      (goto-char (point-min))
      (should (equal "T1" (eng/bbh-inbox--at-point)))
      ;; On the thread's heading a digit picks on its newest ask; on the ask, on that ask.
      (let ((last-command-event ?2)) (call-interactively (bbh-test--key "2")))
      (should (bbh-test--wait (lambda () (funcall logged "inbox pick T1 2")) 10))
      (forward-line 1)
      (should (equal "a1" (eng/bbh-inbox--ask)))
      (let ((last-command-event ?2)) (call-interactively (bbh-test--key "2")))
      (should (bbh-test--wait (lambda () (funcall logged "inbox pick T1 2 a1")) 10))
      (call-interactively (bbh-test--key "."))
      (should (bbh-test--wait (lambda () (funcall logged "inbox pick T1 1 a1")) 10))
      ;; RET on an option picks it.
      (forward-line 2)
      (call-interactively (bbh-test--key "RET"))
      (should (bbh-test--wait (lambda () (funcall logged "inbox pick T1 2 a1")) 10))
      ;; Details start folded; the question and options show.
      (should (invisible-p (save-excursion (goto-char (point-min)) (search-forward "Because") (point))))
      (should-not (invisible-p (save-excursion (goto-char (point-min)) (search-forward "2. Yes") (point))))
      ;; Only the Answer body takes text.
      (goto-char (point-min))
      (should-error (insert "x") :type 'text-read-only)
      (forward-line 1)
      (call-interactively (bbh-test--key "i"))
      (should (eng/bbh-inbox--in-answer-p))
      (insert "call it Zed")
      ;; Letters type there, as keys do anywhere in an Answer.
      (should (memq (bbh-test--key "s") '(self-insert-command org-self-insert-command)))
      (should (equal "call it Zed" (eng/bbh-inbox--answer-text "a1")))
      (call-interactively (bbh-test--key "C-c C-c"))
      (should (bbh-test--wait (lambda () (funcall logged "inbox send T1 {\"a1\" \"call it Zed\"}")) 10))
      (goto-char (point-min))
      (search-forward "T2 second")
      (eng/bbh-inbox--moved)
      (should (bbh-test--wait (lambda () (funcall logged "inbox select T2")) 10)))
    (kill-buffer "*bbh inbox*")))

(ert-deftest bbh-inbox-reload-keeps-unsaved-answers-and-folds ()
  (bbh-test--with-fake-inbox (view log)
    (eng/bbh-inbox--render (concat "head\n" bbh-test--outline) t)
    (goto-char (car (eng/bbh-inbox--answer-region "a1")))
    (insert "not saved yet")
    ;; Show the Details, fold T2's summary open, and reload with something changed.
    (save-excursion (goto-char (point-min)) (search-forward "*** Details") (org-fold-show-subtree))
    (eng/bbh-inbox--render (concat "head\n" bbh-test--outline "\n* NEW T3 third") t)
    (should (equal "not saved yet" (eng/bbh-inbox--answer-text "a1")))
    (should-not (invisible-p (save-excursion (goto-char (point-min)) (search-forward "Because") (point))))
    (should (eng/bbh-inbox--in-answer-p))
    ;; Saving sends it to the draft.  A reload still carrying the older draft
    ;; keeps your text; once the draft has it, the draft's text is what shows.
    (eng/bbh-inbox--save "T1")
    (should (bbh-test--wait (lambda () (member "answer" (bbh-test--inbox-log log))) 10))
    (eng/bbh-inbox--render (concat "head\n" bbh-test--outline "\n* NEW T4 fourth") t)
    (should (equal "not saved yet" (eng/bbh-inbox--answer-text "a1")))
    (let ((with (lambda (text) (concat "head\n" (replace-regexp-in-string "\\*\\*\\* Answer\n\n" (concat "*** Answer\n    " text "\n") bbh-test--outline)))))
      (eng/bbh-inbox--render (funcall with "not saved yet") t)
      (eng/bbh-inbox--render (funcall with "from the phone") t)
      (should (equal "from the phone" (eng/bbh-inbox--answer-text "a1"))))))

(ert-deftest bbh-inbox-opens-from-a-buffer-whose-workspace-is-gone ()
  ;; A finished Worker's workspace is removed while its buffers stay, so SPC m i
  ;; runs with a default-directory that no longer exists.
  (let* ((log (make-temp-file "bbh-inbox-log"))
         (script (make-temp-file "bbh-fake" nil nil
                                 (concat "#!/bin/sh\necho \"$@\" >> " log "\n"
                                         "case \"$2\" in\norg) printf 'bbh inbox · 0 need you\\n* NEW T1 first\\n';;\nesac\n")))
         (eng/bbh-inbox-bbh (list script))
         (eng/bbh-inbox-view 'outline)
         (gone (make-temp-file "bbh-workspace" t))
         (worker (get-buffer-create "T9 done [viewport]")))
    (set-file-modes script #o700)
    (with-current-buffer worker (setq default-directory (file-name-as-directory gone)))
    (delete-directory gone)
    (delete-other-windows)
    (switch-to-buffer worker)
    (split-window)
    (eng/bbh-inbox)
    (should (equal "*bbh inbox*" (buffer-name (window-buffer (selected-window)))))
    (with-current-buffer "*bbh inbox*"
      (should (bbh-test--wait (lambda () (string-match-p "T1 first" (buffer-string))) 10)))
    (kill-buffer worker)))

(ert-deftest bbh-inbox-reload-replaces-a-stale-listener-and-rehooks-the-buffer ()
  ;; A Doom reload loads this file again; the live Inbox must follow the new code.
  (let* ((log (make-temp-file "bbh-inbox-log"))
         (script (make-temp-file "bbh-fake" nil nil
                                 (concat "#!/bin/sh\necho \"$@\" >> " log "\n"
                                         "case \"$2\" in\norg) printf 'bbh inbox · 0 need you\\n* NEW T1 first\\n';;\nesac\n")))
         (eng/bbh-inbox-bbh (list script))
         (registered (lambda () (with-temp-buffer (insert-file-contents log)
                                  (how-many "^inbox register [0-9]+$" (point-min) (point-max))))))
    (set-file-modes script #o700)
    (when (process-live-p eng/bbh-inbox--listener) (delete-process eng/bbh-inbox--listener))
    ;; The listener as older code made it: a closure filter.
    (setq eng/bbh-inbox--listener
          (make-network-process :name "bbh-inbox-listen" :server t :host "127.0.0.1" :service t
                                :family 'ipv4 :noquery t :filter (lambda (c _) (delete-process c))))
    (let ((old eng/bbh-inbox--listener))
      (with-current-buffer (eng/bbh-inbox--buffer)
        (eng/bbh-inbox-mode)
        (remove-hook 'window-buffer-change-functions #'eng/bbh-inbox--on-show t)
        (load (getenv "BBH_DOOM_AGENT_SHELL") nil t)
        (should (memq #'eng/bbh-inbox--on-show window-buffer-change-functions)))
      (should-not (process-live-p old))
      (should (eq #'eng/bbh-inbox--on-push (process-filter eng/bbh-inbox--listener)))
      (should (bbh-test--wait (lambda () (= 1 (funcall registered))) 10))
      ;; A current listener is kept, and not registered again.
      (let ((current eng/bbh-inbox--listener))
        (eng/bbh-inbox--listen)
        (should (eq current eng/bbh-inbox--listener))
        (sleep-for 0.3)
        (should (= 1 (funcall registered)))
        (delete-process current)
        (setq eng/bbh-inbox--listener nil)
        (kill-buffer (eng/bbh-inbox--buffer))))))

(defun bbh-test--windows ()
  "The buffer names in the selected frame's windows, sorted."
  (sort (mapcar (lambda (w) (buffer-name (window-buffer w))) (window-list nil 'nomini)) #'string<))

(ert-deftest bbh-super-views-switch-with-their-own-windows ()
  ;; The Inbox and a session's transcript and composer are each
  ;; view's whole frame; nothing of the view you left stays behind.
  (let* ((script (make-temp-file "bbh-fake" nil nil
                                 (concat "#!/bin/sh\ncase \"$2\" in\n"
                                         "org) printf 'bbh inbox · 0 need you\\n* NEW T1 first\\n';;\n"
                                         "esac\n")))
         (eng/bbh-inbox-bbh (list script))
         (eng/bbh-inbox-view 'outline)
         (dir (make-temp-file "bbh-super-ws" t))
         (shell (get-buffer (eng/bbh-super-open "router" dir "Router" nil))))
    (set-file-modes script #o700)
    (when (get-buffer "*bbh inbox*") (kill-buffer "*bbh inbox*"))
    (unwind-protect
        (progn
          (should (bbh-test--wait (lambda () (with-current-buffer shell (map-nested-elt agent-shell--state '(:session :id)))) 30))
          (eng/bbh-inbox)
          (with-current-buffer "*bbh inbox*"
            (should (bbh-test--wait (lambda () (string-match-p "T1 first" (buffer-string))) 10)))
          (should (equal '("*bbh inbox*") (bbh-test--windows)))
          ;; SPC m r in the Inbox: the Router alone, its composer selected.
          (with-current-buffer "*bbh inbox*" (call-interactively (key-binding (kbd "C-c r"))))
          (let ((composer (agent-shell-viewport--buffer :shell-buffer shell :existing-only t)))
            (should (equal (sort (list "Router transcript" (buffer-name composer)) #'string<) (bbh-test--windows)))
            (should (eq composer (window-buffer (selected-window))))
            ;; SPC m i in the composer and in the transcript: the Inbox alone again.
            (dolist (from (list composer (buffer-local-value 'eng/bbh-transcript shell)))
              (eng/bbh-super-visit "Router")
              (with-current-buffer from (call-interactively (key-binding (kbd "C-c i"))))
              (should (bbh-test--wait (lambda () (equal '("*bbh inbox*") (bbh-test--windows))) 10))
              (should (equal "*bbh inbox*" (buffer-name (window-buffer (selected-window))))))))
      (eng/bbh-super--kill shell))
    ;; An ordinary session in another Emacs has neither key.
    (with-temp-buffer
      (eng/bbh-transcript-mode)
      (should-not (memq (key-binding (kbd "C-c i")) '(eng/bbh-inbox eng/bbh-inbox-router))))))

(ert-deftest bbh-super-refuses-a-prompt-outside-a-command ()
  (let ((this-command nil))
    (should-error (eng/bbh-super--refuse-prompt #'y-or-n-p "Continue? ")))
  (let ((this-command 'some-command))
    (should (equal '("a" "b") (eng/bbh-super--refuse-prompt #'list "a" "b")))))

(ert-deftest bbh-super-session-off-screen-does-not-echo ()
  (let (shown)
    (cl-letf (((symbol-function 'message) (lambda (&rest args) (push (apply #'format args) shown))))
      (with-temp-buffer
        (setq-local eng/bbh-super-conversation "super-W9")
        (agent-shell--echo "queued  %s" "brief"))
      (with-temp-buffer
        (agent-shell--echo "queued  %s" "mine")))
    (should (equal '("queued  mine") shown))))

(ert-deftest bbh-prompt-queue-never-echoes-the-queue ()
  ;; agent-shell listed the active and queued prompts in the echo area on every
  ;; enqueue, such as an inbox delivery, and it vanished on the next keystroke.
  ;; The composer header's count is where to look instead.
  (let (shown)
    (with-temp-buffer
      (setq major-mode 'agent-shell-mode)
      (setq-local agent-shell--state (list (cons :pending-prompts nil)))
      (cl-letf (((symbol-function 'message) (lambda (&rest args) (push (apply #'format args) shown))))
        (agent-shell--prompt-queue-enqueue :prompt "sent from the phone")
        (agent-shell--prompt-queue-enqueue :prompt "and another"))
      (should (equal "2 queued" (eng/bbh--queued (current-buffer)))))
    (should-not shown)))

;;; The super-agent Inbox reloads itself

(defun bbh-test--inbox-view (ids)
  "`bbh inbox org' output listing thread IDS."
  (mapconcat #'identity (cons "bbh inbox · 0 need you" (mapcar (lambda (id) (format "* NEW %s %s row" id id)) ids)) "\n"))

(defun bbh-test--inbox-ids (from to)
  (mapcar (lambda (n) (format "T%d" n)) (number-sequence from to)))

(defmacro bbh-test--with-fake-inbox (spec &rest body)
  "Run BODY with the Inbox shown and a fake bbh: `org' prints file VIEW,
and each verb is appended to file LOG.  SPEC is (VIEW LOG)."
  (declare (indent 1))
  (let ((view (car spec)) (log (cadr spec)))
    `(let* ((,view (make-temp-file "bbh-inbox-view"))
            (,log (make-temp-file "bbh-inbox-log"))
            (eng/bbh-inbox-bbh
             (list "sh" "-c" (format "echo \"$2\" >> %s; case $2 in org) cat %s || exit 1;; answer) ;; *) exit 1;; esac"
                                     (shell-quote-argument ,log) (shell-quote-argument ,view))
                   "sh"))
            (eng/bbh-inbox-view 'outline)
            (eng/bbh-inbox--reload nil)
            (eng/bbh-inbox--stale nil))
       (unwind-protect
           (progn
             (delete-other-windows)
             (switch-to-buffer (eng/bbh-inbox--buffer))
             (unless (derived-mode-p 'eng/bbh-inbox-mode) (eng/bbh-inbox-mode))
             (setq eng/bbh-inbox--text nil)
             ,@body)
         (dolist (timer timer-idle-list)
           (when (eq (timer--function timer) #'eng/bbh-inbox--reload) (cancel-timer timer)))
         (kill-buffer (eng/bbh-inbox--buffer))))))

(defun bbh-test--inbox-log (log)
  (with-temp-buffer (insert-file-contents log) (split-string (buffer-string) "\n" t)))

(defun bbh-test--inbox-reloads ()
  (seq-filter (lambda (timer) (eq (timer--function timer) #'eng/bbh-inbox--reload)) timer-idle-list))

(ert-deftest bbh-inbox-reload-keeps-the-thread-under-the-cursor-on-its-screen-line ()
  (bbh-test--with-fake-inbox (view log)
    (eng/bbh-inbox--render (bbh-test--inbox-view (bbh-test--inbox-ids 1 30)) t)
    (goto-char (eng/bbh-inbox--find '("T20" . 1)))
    (set-window-start (selected-window) (eng/bbh-inbox--find '("T16" . 1)))
    ;; Three new threads above, and one gone above the cursor.
    (eng/bbh-inbox--render (bbh-test--inbox-view (append '("T31" "T32" "T33") (delete "T5" (bbh-test--inbox-ids 1 30)))) t)
    (should (equal "T20" (eng/bbh-inbox--at-point)))
    (should (equal "* NEW T31 T31 row" (save-excursion (goto-char (point-min)) (buffer-substring-no-properties (point) (line-end-position)))))
    (should (= 4 (count-lines (window-start) (point))))))

(defun bbh-test--inbox-rows (rows)
  "`bbh inbox org' outline for ROWS, each (ID SECTION) as `bbh inbox' heads it."
  (mapconcat (pcase-lambda (`(,id ,section)) (format "* %s %s %s row" (upcase section) id id))
             rows "\n"))

(ert-deftest bbh-inbox-archiving-leaves-the-cursor-on-the-next-thread ()
  ;; Archiving moves the thread to the Archived section at the bottom; the
  ;; cursor stays in place, on the thread below, and the window does not scroll.
  (bbh-test--with-fake-inbox (view log)
    (let ((before (mapcar (lambda (id) (list id "waiting")) (bbh-test--inbox-ids 1 30))))
      (eng/bbh-inbox--render (concat "head\n" (bbh-test--inbox-rows (append before '(("T40" "archived"))))) t)
      (goto-char (eng/bbh-inbox--find '("T20" . 1)))
      (set-window-start (selected-window) (eng/bbh-inbox--find '("T16" . 1)))
      (eng/bbh-inbox--render (concat "head\n" (bbh-test--inbox-rows (append (assoc-delete-all "T20" (copy-tree before))
                                                                             '(("T20" "archived") ("T40" "archived")))))
                             t)
      (should (equal "T21" (eng/bbh-inbox--at-point)))
      (should (= 4 (count-lines (window-start) (point))))
      ;; The last live thread archived: the cursor goes to the one above.
      (goto-char (eng/bbh-inbox--find '("T30" . 1)))
      (eng/bbh-inbox--render (concat "head\n" (bbh-test--inbox-rows (append (bbh-test--inbox-rows-without before "T20" "T30")
                                                                             '(("T30" "archived") ("T20" "archived") ("T40" "archived")))))
                             t)
      (should (equal "T29" (eng/bbh-inbox--at-point)))
      ;; A thread already archived, and one moving between live sections, keep the cursor.
      (goto-char (eng/bbh-inbox--find '("T40" . 1)))
      (eng/bbh-inbox--render (concat "head\n" (bbh-test--inbox-rows (append (bbh-test--inbox-rows-without before "T20" "T30")
                                                                             '(("T40" "archived") ("T20" "archived") ("T30" "archived")))))
                             t)
      (should (equal "T40" (eng/bbh-inbox--at-point)))
      (goto-char (eng/bbh-inbox--find '("T10" . 1)))
      (eng/bbh-inbox--render (concat "head\n" (bbh-test--inbox-rows (cons '("T10" "stopped") (bbh-test--inbox-rows-without before "T10" "T20" "T30"))))
                             t)
      (should (equal "T10" (eng/bbh-inbox--at-point)))
      ;; T10, now first, gone from the view leaves the cursor on the one below it.
      (eng/bbh-inbox--render (concat "head\n" (bbh-test--inbox-rows (bbh-test--inbox-rows-without before "T10" "T20" "T30"))) t)
      (should (equal "T1" (eng/bbh-inbox--at-point))))))

(defun bbh-test--inbox-rows-without (rows &rest ids)
  (seq-remove (lambda (row) (member (car row) ids)) rows))

(ert-deftest bbh-inbox-pushes-make-one-quiet-reload-and-a-hidden-inbox-waits ()
  (bbh-test--with-fake-inbox (view log)
    (with-temp-file view (insert (bbh-test--inbox-view '("T1" "T2"))))
    (let ((messages (with-current-buffer (messages-buffer) (buffer-size))))
      (dotimes (_ 3) (eng/bbh-inbox--changed))
      (should (eq 'pending eng/bbh-inbox--reload))
      (should (= 1 (length (bbh-test--inbox-reloads))))
      (mapc #'cancel-timer (bbh-test--inbox-reloads))
      (eng/bbh-inbox--reload)
      (eng/bbh-inbox--changed)
      (should (eq 'again eng/bbh-inbox--reload))
      (should (bbh-test--wait (lambda () (eq 'pending eng/bbh-inbox--reload)) 10))
      (should (equal "* NEW T1 T1 row\n* NEW T2 T2 row\n" (buffer-substring-no-properties (point-min) (point-max))))
      (should (equal '("org") (seq-filter (lambda (verb) (equal verb "org")) (bbh-test--inbox-log log))))
      ;; A failed reload says so in the header, not the echo area.
      (mapc #'cancel-timer (bbh-test--inbox-reloads))
      (setq eng/bbh-inbox--reload nil)
      (delete-file view)
      (eng/bbh-inbox--changed)
      (mapc #'cancel-timer (bbh-test--inbox-reloads))
      (eng/bbh-inbox--reload)
      (should (bbh-test--wait (lambda () (null eng/bbh-inbox--reload)) 10))
      (should (string-match-p "reload failed" header-line-format))
      (should (= messages (with-current-buffer (messages-buffer) (buffer-size)))))
    ;; Hidden, a push only marks the Inbox stale; showing it reloads.
    (switch-to-buffer "*scratch*")
    (eng/bbh-inbox--changed)
    (should (and eng/bbh-inbox--stale (null eng/bbh-inbox--reload)))
    (switch-to-buffer (eng/bbh-inbox--buffer))
    ;; Redisplay runs this hook; batch Emacs has none.
    (run-hook-with-args 'window-buffer-change-functions (selected-window))
    (should (eq 'pending eng/bbh-inbox--reload))))

;;; The split view: the thread list beside the selected thread

(defun bbh-test--split (rows &optional selected progress)
  "`bbh inbox split' output: ROWS, each (ID SECTION EXECUTION ASKS UNSEEN TITLE),
SELECTED's PROGRESS lines, then `bbh-test--outline' and a subtree per other row."
  (concat "bbh inbox · 2 need you   keys\n"
          (mapconcat (pcase-lambda (`(,id ,section ,execution ,asks ,unseen ,title))
                       (format "#+thread: %s\t%s\t%s\t%d\t5m\t%s\tW%s\t%s\n" id section execution asks (if unseen 1 0) (substring id 1) title))
                     rows "")
          (if selected (format "#+selected: %s\n" selected) "")
          (mapconcat (lambda (line) (format "#+progress: %s\t11-08 10:00\tmilestone\t%s\n" selected line)) progress "")
          bbh-test--outline "\n"
          "* WAITING T3 third\n** a3 Go?\n   1. Yes\n*** Answer\n\n"
          "* ARCHIVED T9 old one\n"))

(defconst bbh-test--split-rows
  '(("T1" "stopped" "waiting" 1 t "first")
    ("T2" "new" "working" 0 nil "second")
    ("T3" "waiting" "waiting" 1 nil "third")
    ("T4" "needs-attention" "failed" 0 nil "fourth")
    ("T5" "quiet" "done" 0 nil "fifth")
    ("T9" "archived" "none" 0 nil "old one")))

(ert-deftest bbh-inbox-split-lists-a-line-per-thread-with-its-status ()
  (let* ((data (eng/bbh-inbox--parse-split (bbh-test--split bbh-test--split-rows "T1" '("half way"))))
         (eng/bbh-inbox--show-archived nil)
         (lines (split-string (substring-no-properties (eng/bbh-inbox--list-text (plist-get data :rows) 40)) "\n")))
    (should (equal "T1" (plist-get data :selected)))
    (should (equal '(("11-08 10:00" "milestone" "half way")) (plist-get data :progress)))
    (should (string-prefix-p "* STOPPED T1 first" (plist-get data :outline)))
    (should (equal "W1" (plist-get (car (plist-get data :rows)) :worker)))
    ;; Unseen mark, status, id, title, open asks and age, in the controller's order.
    (should (string-match-p "^•● T1   first +1\\? 5m$" (nth 0 lines)))
    (should (string-match-p "^ ◐ T2   second +5m$" (nth 1 lines)))
    (should (string-match-p "^ ● T3 " (nth 2 lines)))
    (should (string-match-p "^ ✖ T4 " (nth 3 lines)))
    (should (string-match-p "^ ✓ T5 " (nth 4 lines)))
    (should (= 40 (string-width (nth 0 lines))))
    ;; Archived threads fold into one row.
    (should (equal "  ▸ 1 archived" (nth 5 lines)))
    (should-not (seq-some (lambda (line) (string-match-p "T9" line)) lines))
    ;; A selection that leaves the list goes to the row that took its place.
    (let ((rows (plist-get data :rows)))
      (should (equal "T3" (eng/bbh-inbox--choose "T2" rows (seq-remove (lambda (r) (equal "T2" (plist-get r :id))) rows))))
      (should (equal "T1" (eng/bbh-inbox--choose nil nil rows))))))

(defmacro bbh-test--with-split-inbox (spec &rest body)
  "Run BODY with the split Inbox and a fake bbh: `split' prints file VIEW,
`org' prints file ORG, and each call's arguments are appended to file LOG.
SPEC is (VIEW ORG LOG)."
  (declare (indent 1))
  (pcase-let ((`(,view ,org ,log) spec))
    `(let* ((,view (make-temp-file "bbh-split-view"))
            (,org (make-temp-file "bbh-split-org"))
            (,log (make-temp-file "bbh-split-log"))
            (eng/bbh-inbox-bbh
             (list "sh" "-c" (format "shift; echo \"$*\" >> %s; case $1 in split) cat %s;; org) cat %s;; esac"
                                     (shell-quote-argument ,log) (shell-quote-argument ,view) (shell-quote-argument ,org))
                   "sh"))
            (eng/bbh-inbox-view 'split)
            (eng/bbh-inbox-split-min-width 0)
            (eng/bbh-inbox-select-delay 0.05)
            (eng/bbh-inbox--selected nil)
            (eng/bbh-inbox--rows nil)
            (eng/bbh-inbox--progress nil)
            (eng/bbh-inbox--show-archived nil)
            (eng/bbh-inbox--reload nil)
            (eng/bbh-inbox--stale nil))
       (unwind-protect
           (progn ,@body)
         (dolist (name '("*bbh inbox*" "*bbh threads*"))
           (when (get-buffer name) (kill-buffer name)))))))

(defun bbh-test--logged-p (log line)
  (with-temp-buffer (insert-file-contents log)
                    (string-match-p (concat "^" (regexp-quote line) "$") (buffer-string))))

(defun bbh-test--right ()
  (with-current-buffer "*bbh inbox*" (buffer-substring-no-properties (point-min) (point-max))))

(ert-deftest bbh-inbox-split-shows-the-selected-thread-beside-the-list ()
  (bbh-test--with-split-inbox (view org log)
    (with-temp-file view (insert (bbh-test--split bbh-test--split-rows "T1" '("half way"))))
    (with-temp-file org (insert "bbh inbox · 2 need you\n" bbh-test--outline))
    (eng/bbh-inbox)
    (should (bbh-test--wait (lambda () (string-match-p "half way" (bbh-test--right))) 10))
    ;; The list on the left, selected; the thread on the right.
    (should (equal '("*bbh inbox*" "*bbh threads*") (bbh-test--windows)))
    (should (equal "*bbh threads*" (buffer-name (window-buffer (selected-window)))))
    (should (> (window-left-column (get-buffer-window "*bbh inbox*")) 0))
    (should (bbh-test--logged-p log "split"))
    (let ((right (bbh-test--right)))
      (should (string-match-p "^\\*\\* a1 Keep the alias\\?$" right))
      (should (string-match-p "^\\*\\* Progress\n   11-08 10:00  milestone +half way$" right))
      (should (string-match-p "\\[\\[bbh-worker:T1\\]\\[W1's buffer\\]\\]" right))
      (should-not (string-match-p "T2 second" right)))
    (with-current-buffer "*bbh inbox*"
      (should (string-prefix-p "T1 " header-line-format))
      ;; The cursor starts on the ask, where T82's keys answer it.
      (should (equal "a1" (eng/bbh-inbox--ask)))
      (let ((last-command-event ?2)) (call-interactively (bbh-test--key "2")))
      (should (bbh-test--wait (lambda () (bbh-test--logged-p log "pick T1 2 a1")) 10))
      (should (eq 'eng/bbh-inbox-pick-recommended (bbh-test--key ".")))
      (should (eq 'eng/bbh-inbox-answer (bbh-test--key "i")))
      (should (eq 'eng/bbh-inbox-send (bbh-test--key "C-c C-c")))
      (should (eq 'eng/bbh-inbox-other-pane (bbh-test--key "o")))
      ;; i types the answer in place, above the progress.
      (eng/bbh-inbox-answer)
      (should (eng/bbh-inbox--in-answer-p))
      (insert "call it Zed")
      (should (equal "call it Zed" (eng/bbh-inbox--answer-text "a1")))
      (should (string-match-p "^    call it Zed\n\\*\\* Progress$" (bbh-test--right))))
    (with-current-buffer "*bbh threads*"
      (should (equal "T1" (eng/bbh-inbox--at-point)))
      (dolist (key '(("j" . eng/bbh-threads-next) ("k" . eng/bbh-threads-previous) ("n" . eng/bbh-threads-next)
                     ("]t" . eng/bbh-inbox-next-waiting) ("[t" . eng/bbh-inbox-previous-waiting)
                     ("o" . eng/bbh-inbox-other-pane) ("RET" . eng/bbh-threads-ret) ("gv" . eng/bbh-inbox-toggle-view)
                     ("J" . eng/bbh-inbox-jjui) ("gr" . eng/bbh-inbox-refresh)))
        (should (eq (cdr key) (bbh-test--key (car key)))))
      ;; j moves the selection and the right side follows at once, then fetches it.
      (eng/bbh-threads-next)
      (eng/bbh-threads--moved)
      (should (equal "T2" eng/bbh-inbox--selected))
      (should (string-match-p "T2 second" (bbh-test--right)))
      (should-not (string-match-p "a1 Keep" (bbh-test--right)))
      (should (bbh-test--wait (lambda () (bbh-test--logged-p log "split T2 select")) 10))
      ;; ]t goes to the next thread waiting on you, and wraps.
      (eng/bbh-inbox-next-waiting)
      (should (equal "T3" eng/bbh-inbox--selected))
      (should (equal "T3" (eng/bbh-inbox--at-point)))
      (eng/bbh-inbox-next-waiting)
      (should (equal "T1" eng/bbh-inbox--selected))
      ;; o goes to the thread and back.
      (eng/bbh-inbox-other-pane)
      (should (equal "*bbh inbox*" (buffer-name (window-buffer (selected-window))))))
    (with-current-buffer "*bbh inbox*" (eng/bbh-inbox-other-pane))
    (should (equal "*bbh threads*" (buffer-name (window-buffer (selected-window)))))))

(ert-deftest bbh-inbox-split-reload-keeps-the-selection-and-gv-switches-views ()
  (bbh-test--with-split-inbox (view org log)
    (with-temp-file view (insert (bbh-test--split bbh-test--split-rows)))
    (with-temp-file org (insert "bbh inbox · 2 need you\n" bbh-test--outline "\n* WAITING T3 third\n"))
    (eng/bbh-inbox)
    (should (bbh-test--wait (lambda () (string-match-p "T1 first" (bbh-test--right))) 10))
    (eng/bbh-inbox--select "T3")
    (with-current-buffer "*bbh threads*"
      (let ((row (count-lines (window-start) (point))))
        ;; A push with a new thread above: the selection and its screen line stay.
        (with-temp-file view (insert (bbh-test--split (cons '("T0" "stopped" "waiting" 1 t "zeroth") bbh-test--split-rows))))
        (eng/bbh-inbox--changed)
        (mapc #'cancel-timer (bbh-test--inbox-reloads))
        (eng/bbh-inbox--reload)
        (should (bbh-test--wait (lambda () (string-match-p "T0" (buffer-string))) 10))
        (should (equal "T3" eng/bbh-inbox--selected))
        (should (equal "T3" (eng/bbh-inbox--at-point)))
        (should (= row (count-lines (window-start) (point))))
        (should (string-match-p "third" (bbh-test--right)))
        (should (bbh-test--logged-p log "split T3")))
      ;; RET on the archived row shows the archived threads.
      (goto-char (text-property-any (point-min) (point-max) 'bbh-archived t))
      (eng/bbh-threads-ret)
      (should (string-match-p "T9" (buffer-string)))
      (goto-char (eng/bbh-inbox--row-start "T3"))
      ;; gv: every thread in one buffer, the cursor on the selected one.
      (eng/bbh-inbox-toggle-view))
    (should (eq 'outline eng/bbh-inbox-view))
    (should (bbh-test--wait (lambda () (string-match-p "T2 second" (bbh-test--right))) 10))
    (should (equal '("*bbh inbox*") (bbh-test--windows)))
    (with-current-buffer "*bbh inbox*"
      (should (equal "T3" (eng/bbh-inbox--at-point)))
      (eng/bbh-inbox-toggle-view))
    (should (eq 'split eng/bbh-inbox-view))
    (should (equal '("*bbh inbox*" "*bbh threads*") (bbh-test--windows)))
    (should (equal "T3" eng/bbh-inbox--selected))))
