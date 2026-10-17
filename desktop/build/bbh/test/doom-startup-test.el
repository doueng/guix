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
                  (with-current-buffer shell
                    (agent-shell-chat--relabel)
                    (when (agent-shell-chat--find-live-marker-overlay)
                      (error "Duplicate input prompt remains in the transcript")))
                  (unless (eq (key-binding (kbd "C-n")) #'eng/bbh-send)
                    (error "Ctrl-N was lost after sending"))
                  (unless (string-match-p "C-n send" (format-mode-line header-line-format))
                    (error "Ready status and Ctrl-N hint missing"))
                  (unless (equal (string-trim (buffer-string)) "draft survives output")
                    (error "Composer draft was changed"))
                  (unless (< (nth 1 (window-edges (get-buffer-window shell)))
                             (nth 1 (window-edges (get-buffer-window composer))))
                    (error "Composer is not below output")))
                (when (and (get-buffer "*Warnings*")
                           (with-current-buffer "*Warnings*"
                             (string-match-p "system-sleep.*could not be initialized" (buffer-string))))
                  (error "Unsupported sleep inhibition still emits a warning"))
                "PASS: cold Doom startup, eng/bbh, split layout, send binding, reply, draft retention")
            (error (format "FAIL: %S" err)))))
     (with-temp-file (getenv "BBH_STARTUP_REPORT")
       (insert result "\n\n")
       (when (get-buffer "*Warnings*")
         (insert (with-current-buffer "*Warnings*" (buffer-string))))
       (insert (with-current-buffer "*Messages*" (buffer-string))))
     (kill-emacs (if (string-prefix-p "PASS:" result) 0 1)))))
