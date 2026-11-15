;;; tty-visit-test.el --- entering a session's layout -*- lexical-binding: t; -*-

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org" "goto-chg" "evil"
               "evil-terminal-cursor-changer"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(require 'cl-lib)
(require 'evil)
(evil-mode 1)
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)
(require 'agent-shell-viewport)

(setq scroll-margin 2
      scroll-conservatively 10
      scroll-preserve-screen-position 'always
      evil-insert-state-cursor 'bar
      evil-normal-state-cursor 'box)

(require 'evil-terminal-cursor-changer)
(defvar bbh-visit--shapes nil "Cursor shape sequences sent to the terminal, latest first.")
(advice-add 'etcc--apply-to-terminal :before
            (lambda (seq) (when (and (stringp seq) (string-suffix-p " q" seq)) (push seq bbh-visit--shapes))))
(evil-terminal-cursor-changer-activate)

(defun bbh-visit--session (name lines)
  "A fake session NAME: a shell, its transcript of LINES lines, and a composer."
  (let ((shell (get-buffer-create name))
        (transcript (get-buffer-create (concat name " transcript")))
        (composer (get-buffer-create (concat name " [viewport]"))))
    (with-current-buffer transcript
      (eng/bbh-transcript-mode)
      (bbh-visit--append transcript lines))
    (with-current-buffer shell (setq eng/bbh-transcript transcript))
    (with-current-buffer composer (text-mode))
    shell))

(defun bbh-visit--append (transcript lines)
  "Write LINES lines of output to TRANSCRIPT as a turn does."
  (with-current-buffer transcript
    (eng/bbh-transcript--edit
     (lambda () (dotimes (i lines) (insert (format "output %d\n" i)))))))

(defun bbh-visit--composer (shell)
  (get-buffer (concat (buffer-name shell) " [viewport]")))

(defun bbh-visit--check-entry (shell how)
  "Check the layout SHELL's visit, HOW, left behind, after redisplay."
  (redisplay t)
  (let* ((transcript (buffer-local-value 'eng/bbh-transcript shell))
         (window (or (get-buffer-window transcript) (error "%s: no transcript window" how)))
         (composer (bbh-visit--composer shell)))
    (unless (eq (window-buffer (selected-window)) composer)
      (error "%s: the composer is not selected" how))
    (with-current-buffer composer
      (unless (eq evil-state 'normal)
        (error "%s: the composer is in %s state, not normal" how evil-state)))
    (let ((want (etcc--make-cursor-shape-seq 'box)))
      (unless (equal (car bbh-visit--shapes) want)
        (error "%s: the terminal cursor is %S, not normal state's %S" how (car bbh-visit--shapes) want)))
    (with-current-buffer transcript
      (unless (pos-visible-in-window-p (point-max) window)
        (error "%s: the latest output is off screen (start %d of %d)" how (window-start window) (point-max)))
      (let ((blank (- (window-body-height window)
                      (count-screen-lines (window-start window) (point-max) t window))))
        (unless (= blank 0)
          (error "%s: the transcript leaves %d blank rows below its end" how blank))))))

(defun bbh-visit--leave (shell)
  "Show SHELL, type in its composer, then leave for another buffer as the Inbox does."
  (eng/bbh-super-visit (buffer-name shell))
  (with-current-buffer (bbh-visit--composer shell) (evil-insert-state))
  (etcc--set-cursor)
  (redisplay t)
  (eng/bbh-super--clear-frame)
  (switch-to-buffer "*scratch*")
  (redisplay t))

(defun bbh-visit--check ()
  (cl-letf (((symbol-function 'agent-shell-viewport--buffer)
             (lambda (&rest args) (bbh-visit--composer (plist-get args :shell-buffer))))
            ((symbol-function 'eng/bbh-workflow-refresh) #'ignore))
    (let ((worker (bbh-visit--session "Worker" 200))
          (router (bbh-visit--session "Router" 200)))
      (bbh-visit--leave worker)
      (bbh-visit--append (buffer-local-value 'eng/bbh-transcript worker) 100)
      (let* ((done nil)
             (process (make-process :name "bbh-visit" :command '("true") :noquery t
                                    :sentinel (lambda (_ _)
                                                (eng/bbh-super-visit "Worker")
                                                (setq done t)))))
        (while (not done) (accept-process-output process 0.05)))
      (bbh-visit--check-entry worker "a sentinel visit")
      (bbh-visit--leave router)
      (bbh-visit--append (buffer-local-value 'eng/bbh-transcript router) 100)
      (global-set-key (kbd "<f7>") (lambda () (interactive) (eng/bbh-super-visit "Router")))
      (execute-kbd-macro (kbd "<f7>"))
      (bbh-visit--check-entry router "a key visit")
      (let ((window (get-buffer-window (buffer-local-value 'eng/bbh-transcript router))))
        (with-selected-window window (goto-char (point-min)) (set-window-start window (point-min)))
        (bbh-visit--leave router)
        (execute-kbd-macro (kbd "<f7>"))
        (redisplay t)
        (let ((window (get-buffer-window (buffer-local-value 'eng/bbh-transcript router))))
          (unless (= (window-start window) (point-min))
            (error "a transcript read from its top moved to %d" (window-start window)))))
      (bbh-visit--check-output worker)
      (let ((transcript (buffer-local-value 'eng/bbh-transcript router)))
        (eng/bbh-super-visit (buffer-name router))
        (let ((window (get-buffer-window transcript)))
          (with-selected-window window (goto-char (point-max))))
        (bbh-visit--leave router)
        (bbh-visit--append transcript 100)
        (global-set-key (kbd "<f8>") (lambda () (interactive)
                                                (let ((switch-to-buffer-preserve-window-point nil))
                                                  (switch-to-buffer transcript))))
        (execute-kbd-macro (kbd "<f8>"))
        (redisplay t)
        (let ((blank (bbh-visit--blank-rows (selected-window))))
          (unless (eql blank 0)
            (error "switch-to-buffer to a transcript at its end shows %s (point %d of %d, start %d, latest %d)"
                   (if blank (format "%d blank rows" blank) "no latest output")
                   (window-point) (point-max) (window-start) (eng/bbh-transcript--latest-start (selected-window)))))))))

(defun bbh-visit--blank-rows (window)
  "Rows of WINDOW below the end of its buffer, or nil when the end is off screen."
  (with-current-buffer (window-buffer window)
    (when (pos-visible-in-window-p (point-max) window)
      (- (window-body-height window)
         (count-screen-lines (window-start window) (point-max) t window)))))

(defun bbh-visit--check-output (shell)
  "Output from a timer, outside any command, to SHELL's transcript in two windows."
  (eng/bbh-super-visit (buffer-name shell))
  (let* ((transcript (buffer-local-value 'eng/bbh-transcript shell))
         (window (get-buffer-window transcript))
         (other (with-selected-window window (split-window-right))))
    (redisplay t)
    (dolist (lines '(3 40 100))
      (let ((done nil))
        (run-at-time 0 nil (lambda () (bbh-visit--append transcript lines) (setq done t)))
        (while (not done) (sit-for 0.05)))
      (redisplay t)
      (dolist (w (list window other))
        (let ((blank (bbh-visit--blank-rows w)))
          (unless (eql blank 0)
            (error "after %d lines of output a following window shows %s (start %d of %d)"
                   lines (if blank (format "%d blank rows" blank) "no latest output")
                   (window-start w) (with-current-buffer transcript (point-max)))))))
    (with-selected-window other (goto-char (point-min)) (set-window-start other (point-min)))
    (redisplay t)
    (let ((done nil))
      (run-at-time 0 nil (lambda () (bbh-visit--append transcript 100) (setq done t)))
      (while (not done) (sit-for 0.05)))
    (redisplay t)
    (unless (= (window-start other) (with-current-buffer transcript (point-min)))
      (error "output moved a window read from its top to %d" (window-start other)))
    (unless (eql (bbh-visit--blank-rows window) 0)
      (error "output beside a scrolled-up window left the following one off its end"))))

(defun bbh-visit--run ()
  (let ((result (condition-case err
                    (progn (bbh-visit--check) "ok")
                  (error (format "error: %s" (error-message-string err))))))
    (with-temp-file (getenv "BBH_TTY_RESULT") (insert result "\n"))
    (kill-emacs 0)))

(add-hook 'emacs-startup-hook #'bbh-visit--run)
