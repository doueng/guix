;;; tty-scroll-test.el --- the transcript stops scrolling at its end -*- lexical-binding: t; -*-

;; Run with: bb test:transcript-scroll
;; Batch Emacs never redisplays, so this runs in a terminal frame and writes
;; its verdict to $BBH_TTY_RESULT. Keys go through `execute-kbd-macro' so the
;; command loop runs `post-command-hook' as it does for a real key press.

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org" "goto-chg" "evil"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(require 'cl-lib)
(require 'evil)
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)

;; The settings Doom runs with. A nonzero margin makes redisplay undo the clamp.
(setq scroll-margin 2
      scroll-conservatively 10
      scroll-preserve-screen-position 'always)

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
  (with-current-buffer (window-buffer window)
    (- (window-body-height window)
       (count-screen-lines (window-start window) (point-max) t window))))

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

(defun bbh-tty--composer (window)
  "Select a composer below transcript WINDOW and return its shell buffer."
  (let ((shell (get-buffer-create "bbh shell"))
        (transcript (window-buffer window)))
    (with-current-buffer shell (setq eng/bbh-transcript transcript))
    (select-window (split-window window -5))
    (switch-to-buffer (get-buffer-create "bbh composer"))
    (local-set-key (kbd "M-k") #'eng/bbh-output-page-up)
    (local-set-key (kbd "M-j") #'eng/bbh-output-page-down)
    shell))

(defun bbh-tty--check-composer ()
  (let* ((window (bbh-tty--transcript 200))
         (shell (bbh-tty--composer window))
         (composer (selected-window)))
    (cl-letf (((symbol-function 'agent-shell-viewport--shell-buffer) (lambda () shell)))
      (bbh-tty--press "M-j" 40)
      (bbh-tty--assert-no-overscroll window "M-j from the composer")
      (unless (eq (selected-window) composer)
        (error "M-j moved focus out of the composer"))
      (let ((bottom (window-start window)))
        (bbh-tty--press "M-k" 1)
        (unless (< (window-start window) bottom)
          (error "M-k no longer scrolls back"))))))

(defun bbh-tty--rendered (n)
  "Show a fresh transcript of N turns rendered as bbh renders them: a
prompt, two tool calls whose long output is folded, and a reply."
  (delete-other-windows)
  (when (get-buffer "bbh transcript") (kill-buffer "bbh transcript"))
  (switch-to-buffer (get-buffer-create "bbh transcript"))
  (eng/bbh-transcript-mode)
  (setq eng/bbh-transcript--tools (make-hash-table :test #'equal))
  (dotimes (i n)
    (eng/bbh-transcript--prompt (format "prompt %d" i))
    (dotimes (k 2)
      (eng/bbh-transcript--tool
       (format "call-%d-%d" i k)
       `((:raw-input . ((code . ,(format "(step %d)" k)))) (:status . "completed")
         (:content . (((content . ((text . ,(concat (format "```clojure\n(step %d)\n```\n\n```\nfirst\n" k)
                                                    (make-string 4500 ?a) "\nlast\n```"))))))))))
    (eng/bbh-transcript--reply-chunk "Done.\n\n```clojure\n(a b)\n```")
    (setq eng/bbh-transcript--reply nil))
  (goto-char (point-max))
  (selected-window))

(defun bbh-tty--check-folded-end ()
  "The latest start keeps the end on the bottom row when folded long output
and hidden block markup sit in the last window, whose screen lines moving
back from the end overcounted."
  (let ((window (bbh-tty--rendered 6)))
    (set-window-start window (eng/bbh-transcript--latest-start window))
    (bbh-tty--assert-no-overscroll window "the latest start over folded tool output")
    (unless (pos-visible-in-window-p (point-max) window)
      (error "the latest start over folded tool output leaves the end below the window"))))

(defun bbh-tty--wheel (direction window times)
  "Turn the wheel TIMES notches in DIRECTION over WINDOW, as a touchpad
does: faster than Emacs redisplays, so only every third event redraws."
  (dotimes (i times)
    (execute-kbd-macro (vector (list direction (posn-at-point (window-start window) window))))
    (when (= 2 (% i 3)) (redisplay t))))

(defvar bbh-tty--wheel-ms nil "Milliseconds per wheel event, for the verdict.")

(defun bbh-tty--check-wheel ()
  "The wheel over a transcript scrolls it from the composer, a few lines an
event: up from the end, where `mwheel-scroll' and the clamp bounced the
window between the end and two lines above it (T175), and back down to
the end, which it then follows.  The clamp lays the end out again only
when the text or a fold changed, not on every event."
  (let* ((window (bbh-tty--rendered 30))
         (shell (bbh-tty--composer window))
         (layouts 0)
         (count (lambda (&rest _) (setq layouts (1+ layouts)))))
    (ignore shell)
    (set-window-start window (eng/bbh-transcript--latest-start window))
    (redisplay t)
    (advice-add 'eng/bbh-transcript--latest-start :before count)
    (unwind-protect
        (let ((t0 (float-time))
              (start (window-start window)))
          (dotimes (i 60)
            (bbh-tty--wheel 'wheel-up window 1)
            (unless (< (window-start window) start)
              (error "wheel-up %d from the end left the start at %d, from %d" i (window-start window) start))
            (setq start (window-start window)))
          (bbh-tty--wheel 'wheel-down window 200)
          (setq bbh-tty--wheel-ms (/ (* 1000 (- (float-time) t0)) 260))
          (bbh-tty--assert-no-overscroll window "the wheel down to the end")
          (unless (= (window-point window) (with-current-buffer (window-buffer window) (point-max)))
            (error "the wheel down to the end does not follow the end"))
          (unless (eq (window-buffer (selected-window)) (get-buffer "bbh composer"))
            (error "the wheel moved focus out of the composer"))
          (when (> layouts 2)
            (error "the clamp laid the end out %d times in 260 wheel events" layouts)))
      (advice-remove 'eng/bbh-transcript--latest-start count))
    ;; Showing or folding output near the end moves the latest start, and
    ;; the wheel and the clamp follow it, though no text changed.
    (dolist (fold '(off t))
      (with-current-buffer (window-buffer window)
        (save-excursion
          (goto-char (point-max))
          (re-search-backward "^#\\+begin_result ")
          (org-fold-hide-block-toggle fold t)))
      (bbh-tty--wheel 'wheel-down window 100)
      (bbh-tty--assert-no-overscroll window (format "the wheel after %s the last output" (if (eq fold 'off) "showing" "folding")))
      (unless (= (window-start window) (eng/bbh-transcript--latest-start window))
        (error "after %s the last output the wheel stops at %d, not the end's start %d"
               (if (eq fold 'off) "showing" "folding") (window-start window) (eng/bbh-transcript--latest-start window))))))

(defun bbh-tty--check ()
  (bbh-tty--check-wheel)
  (bbh-tty--check-composer)
  (bbh-tty--check-folded-end)
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
                    (progn (bbh-tty--check) (format "ok %.1f ms per wheel event" bbh-tty--wheel-ms))
                  (error (format "error: %s" (error-message-string err))))))
    (with-temp-file (getenv "BBH_TTY_RESULT") (insert result "\n"))
    (kill-emacs 0)))

(add-hook 'emacs-startup-hook #'bbh-tty--run)
