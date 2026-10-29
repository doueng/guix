;;; tty-scroll-test.el --- the transcript stops scrolling at its end -*- lexical-binding: t; -*-

;; Run with: bb test:transcript-scroll
;; Batch Emacs never redisplays, so this runs in a terminal frame and writes
;; its verdict to $BBH_TTY_RESULT. Keys go through `execute-kbd-macro' so the
;; command loop runs `post-command-hook' as it does for a real key press.

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org" "goto-chg" "evil"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(require 'evil)
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)

(global-set-key (kbd "<f5>") #'scroll-up-command)
(global-set-key (kbd "<f6>") #'evil-scroll-page-down)

(defun bbh-tty--press (key times)
  "Press KEY TIMES times, ignoring only the end-of-buffer boundary."
  (dotimes (_ times)
    (condition-case nil
        (execute-kbd-macro (kbd key))
      (end-of-buffer nil)))
  (redisplay t))

(defun bbh-tty--blank-rows (window)
  "Rows of WINDOW below the end of its buffer."
  (redisplay t)
  (- (window-body-height window)
     (count-screen-lines (window-start window) (point-max) t window)))

(defun bbh-tty--assert-no-overscroll (window label)
  (let ((blank (bbh-tty--blank-rows window)))
    (when (> blank 0)
      (error "%s leaves %d blank rows" label blank))))

(defun bbh-tty--transcript (lines &optional width)
  "Show a fresh transcript of LINES lines, each WIDTH columns wide."
  (delete-other-windows)
  (when (get-buffer "bbh transcript") (kill-buffer "bbh transcript"))
  (switch-to-buffer (get-buffer-create "bbh transcript"))
  (eng/bbh-transcript-mode)
  (let ((inhibit-read-only t))
    (dotimes (i lines)
      (insert (format "line %d" i) (make-string (or width 0) ?x) "\n")))
  (goto-char (point-min))
  (set-window-start (selected-window) (point-min))
  (redisplay t)
  (dolist (key '("<f5>" "<f6>"))
    (unless (memq (key-binding (kbd key)) '(scroll-up-command evil-scroll-page-down))
      (error "%s is shadowed by %S" key (key-binding (kbd key)))))
  (selected-window))

(defun bbh-tty--check ()
  (let ((window (bbh-tty--transcript 200)))
    (bbh-tty--press "<f5>" 1)
    (unless (> (window-start window) (point-min))
      (error "page down no longer scrolls"))
    (bbh-tty--press "<f5>" 20)
    (bbh-tty--assert-no-overscroll window "page down"))
  (let ((window (bbh-tty--transcript 200)))
    (bbh-tty--press "<f6>" 20)
    (bbh-tty--assert-no-overscroll window "evil page down"))
  (let ((window (bbh-tty--transcript 5)))
    (bbh-tty--press "<f5>" 3)
    (unless (= (window-start window) (point-min))
      (error "a short transcript moved off its first line")))
  (let* ((window (bbh-tty--transcript 0))
         (rows (window-body-height window)))
    (bbh-tty--transcript (1- rows))
    (bbh-tty--press "<f5>" 3)
    (unless (= (window-start window) (point-min))
      (error "a transcript of exactly one window moved off its first line")))
  (let ((window (bbh-tty--transcript 60 250)))
    (bbh-tty--press "<f5>" 40)
    (bbh-tty--assert-no-overscroll window "page down over wrapped lines"))
  (bbh-tty--transcript 200)
  (bbh-tty--press "C-x 2" 1)
  (bbh-tty--press "<f5>" 40)
  (bbh-tty--press "C-x 1" 1)
  (bbh-tty--assert-no-overscroll (selected-window) "growing the window at the end")
  (bbh-tty--transcript 200)
  (bbh-tty--press "C-x 2" 1)
  (let ((other (next-window)))
    (bbh-tty--press "<f5>" 40)
    (bbh-tty--assert-no-overscroll (selected-window) "page down beside a second window")
    (unless (= (window-start other) (point-min))
      (error "scrolling one window moved the other"))))

(defun bbh-tty--run ()
  (let ((result (condition-case err
                    (progn (bbh-tty--check) "ok")
                  (error (format "error: %s" (error-message-string err))))))
    (with-temp-file (getenv "BBH_TTY_RESULT") (insert result "\n"))
    (kill-emacs 0)))

(add-hook 'emacs-startup-hook #'bbh-tty--run)
