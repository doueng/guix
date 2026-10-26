;;; doom-startup-test.el --- Cold Doom startup check -*- lexical-binding: t; -*-

(setq persp-auto-save-opt 0
      recentf-save-file (expand-file-name "recentf" (getenv "XDG_DATA_HOME"))
      savehist-file (expand-file-name "history" (getenv "XDG_DATA_HOME")))

(defun bbh-startup-test--wait (predicate)
  (let ((deadline (+ (float-time) 30)))
    (while (and (not (funcall predicate)) (< (float-time) deadline))
      (accept-process-output nil 0.1))
    (unless (funcall predicate) (error "Timed out waiting for bbh"))))

(run-at-time
 2 nil
 (lambda ()
   (let ((result
          (condition-case err
              (progn
                (unless (commandp 'eng/bbh) (error "eng/bbh is not a command"))
                (unless (equal (getenv "BBH_STARTUP_LAUNCHED") "1")
                  (call-interactively #'eng/bbh))
                (set-buffer (window-buffer (selected-window)))
                (let* ((composer (current-buffer))
                       (shell (agent-shell-viewport--shell-buffer)))
                  (bbh-startup-test--wait
                   (lambda ()
                     (with-current-buffer shell
                       (and (map-nested-elt agent-shell--state '(:session :id))
                            (string-match-p "Ready" (buffer-string))))))
                  (unless (eq (window-buffer (selected-window)) composer)
                    (error "Startup moved input focus out of the composer"))
                  (insert "add forty and two")
                  (call-interactively (key-binding (kbd "C-n")))
                  (insert "draft survives output")
                  (bbh-startup-test--wait
                   (lambda ()
                     (with-current-buffer shell
                       (and (not (shell-maker-busy))
                            (string-match-p "The answer is 42" (buffer-string))))))
                  (let ((transcript (buffer-local-value 'eng/bbh-transcript shell)))
                    (with-current-buffer transcript
                      (unless (derived-mode-p 'org-mode)
                        (error "The transcript is not an org buffer"))
                      (unless (string-match-p "^\\* add forty and two\n\n#\\+begin_src clojure\n(\\+ 40 2)\n#\\+end_src\n#\\+begin_result done" (buffer-string))
                        (error "The transcript lacks the prompt heading and tool block")))
                    (call-interactively (key-binding (kbd "C-c C-t")))
                    (with-current-buffer transcript
                      (goto-char (point-min))
                      (re-search-forward "^#\\+begin_result")
                      (forward-line 1)
                      (when (invisible-p (point))
                        (error "C-c C-t did not expand the tool result")))
                    (unless (eq (window-buffer (selected-window)) composer)
                      (error "C-c C-t moved focus out of the composer"))
                    (unless (< (nth 1 (window-edges (get-buffer-window transcript)))
                               (nth 1 (window-edges (get-buffer-window composer))))
                      (error "Composer is not below the transcript")))
                  (unless (eq (key-binding (kbd "C-n")) #'eng/bbh-send)
                    (error "Ctrl-N was lost after sending"))
                  (unless (string-match-p "Ready" (format-mode-line header-line-format))
                    (error "Ready status missing"))
                  (unless (eq (key-binding (kbd "C-c ?")) #'eng/bbh-keys)
                    (error "C-c ? does not show the composer keys"))
                  (unless (equal (string-trim (buffer-string)) "draft survives output")
                    (error "Composer draft was changed"))
                  (let ((transcript (buffer-local-value 'eng/bbh-transcript shell)))
                    (erase-buffer)
                    (insert "/new")
                    (call-interactively (key-binding (kbd "C-n")))
                    (when (buffer-live-p shell) (error "/new kept the old session"))
                    (unless (equal (with-current-buffer transcript (buffer-string)) "")
                      (error "/new did not clear the transcript"))
                    (unless (eq (buffer-local-value 'eng/bbh-transcript (eng/bbh--shell)) transcript)
                      (error "/new did not reuse the transcript"))))
                (when (and (get-buffer "*Warnings*")
                           (with-current-buffer "*Warnings*"
                             (string-match-p "system-sleep.*could not be initialized" (buffer-string))))
                  (error "Unsupported sleep inhibition still emits a warning"))
                "PASS: cold Doom startup, eng/bbh, org transcript, tool toggle, send binding, reply, draft retention, /new")
            (error (format "FAIL: %S" err)))))
     (with-temp-file (getenv "BBH_STARTUP_REPORT")
       (insert result "\n\n")
       (when (get-buffer "*Warnings*")
         (insert (with-current-buffer "*Warnings*" (buffer-string))))
       (insert (with-current-buffer "*Messages*" (buffer-string))))
     (kill-emacs (if (string-prefix-p "PASS:" result) 0 1)))))
