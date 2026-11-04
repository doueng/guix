;;; tty-visit-test.el --- entering a session's layout -*- lexical-binding: t; -*-

;; Run with: bb test:visit
;; Entering a Router or Worker layout should leave its composer in evil normal
;; state with the terminal cursor to match, and its transcript already showing
;; the latest output, whether a key or a process sentinel (the Inbox's RET)
;; shows it.  Only a terminal frame redisplays and runs evil-terminal-cursor-
;; changer, so this runs like tty-scroll-test and writes $BBH_TTY_RESULT.

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

;; Doom's settings and cursors: a bar in insert state, a box in normal state.
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
            ((symbol-function 'eng/bbh-workflow-show) #'ignore))
    (let ((worker (bbh-visit--session "Worker" 200))
          (router (bbh-visit--session "Router" 200)))
      ;; The Inbox's RET: bbh names the buffer, and its sentinel shows it.
      (bbh-visit--leave worker)
      (bbh-visit--append (buffer-local-value 'eng/bbh-transcript worker) 100)
      (let* ((done nil)
             (process (make-process :name "bbh-visit" :command '("true") :noquery t
                                    :sentinel (lambda (_ _)
                                                (eng/bbh-super-visit "Worker")
                                                (setq done t)))))
        (while (not done) (accept-process-output process 0.05)))
      (bbh-visit--check-entry worker "a sentinel visit")
      ;; SPC m r: a command.
      (bbh-visit--leave router)
      (bbh-visit--append (buffer-local-value 'eng/bbh-transcript router) 100)
      (global-set-key (kbd "<f7>") (lambda () (interactive) (eng/bbh-super-visit "Router")))
      (execute-kbd-macro (kbd "<f7>"))
      (bbh-visit--check-entry router "a key visit")
      ;; A transcript you scrolled back in stays where you were reading.
      (let ((window (get-buffer-window (buffer-local-value 'eng/bbh-transcript router))))
        (with-selected-window window (goto-char (point-min)) (set-window-start window (point-min)))
        (bbh-visit--leave router)
        (execute-kbd-macro (kbd "<f7>"))
        (redisplay t)
        (let ((window (get-buffer-window (buffer-local-value 'eng/bbh-transcript router))))
          (unless (= (window-start window) (point-min))
            (error "a transcript read from its top moved to %d" (window-start window))))))))

(defun bbh-visit--run ()
  (let ((result (condition-case err
                    (progn (bbh-visit--check) "ok")
                  (error (format "error: %s" (error-message-string err))))))
    (with-temp-file (getenv "BBH_TTY_RESULT") (insert result "\n"))
    (kill-emacs 0)))

(add-hook 'emacs-startup-hook #'bbh-visit--run)
