;;; cursor-test.el --- Insert state hides the tty cursor at once -*- lexical-binding: t; -*-
;; emacs --batch -l desktop/build/doom/cursor-test.el
;; desktop/configs/doom/cursor.el with Doom's evil: every way into insert
;; state turns the cursor off as the state starts, before anything in the
;; command can redisplay, and Escape turns it on again (T223: o left it
;; visible after a redisplay inside the command).

(require 'ert)
(require 'cl-lib)
(defvar cursor-test--root (expand-file-name "../../../" (file-name-directory load-file-name)))
(let* ((straight (expand-file-name "~/.config/emacs/.local/straight/"))
       (build (car (sort (file-expand-wildcards (concat straight "build-*")) #'string>))))
  (setq build (or (car (file-expand-wildcards (format "%sbuild-%s" straight emacs-version))) build))
  (dolist (p '("evil" "goto-chg"))
    (add-to-list 'load-path (expand-file-name p build))))
(setq evil-want-keybinding nil)
(require 'evil)
(defvar cursor-test--sent nil "DECTCEM strings sent, newest first.")
(advice-add 'send-string-to-terminal :override (lambda (s &rest _) (push s cursor-test--sent)))
(load (expand-file-name "desktop/configs/doom/cursor.el" cursor-test--root) nil t)
(evil-mode 1)

(defmacro cursor-test--with-buffer (&rest body)
  "BODY in a three-line buffer in the selected window, in normal state,
recording what goes to the terminal."
  (declare (indent 0))
  `(let ((buf (generate-new-buffer "cursor-test")))
     (unwind-protect
         (progn
           (switch-to-buffer buf)
           (insert "one two\nthree four\nfive six\n")
           (goto-char (point-min))
           (evil-local-mode 1)
           (evil-normal-state)
           (cursor-test--run-timer)
           (setq cursor-test--sent nil)
           ,@body)
       (kill-buffer buf))))

(defun cursor-test--run-timer ()
  "Run the pending DECTCEM timer, as the command loop would after redisplay."
  (when eng/cursor--timer
    (cancel-timer eng/cursor--timer)
    (eng/cursor-sync-terminal)))

(defconst cursor-test--routes
  `(("o" . ,(lambda () (evil-open-below 1)))
    ("O" . ,(lambda () (evil-open-above 1)))
    ("a" . ,(lambda () (evil-append 1)))
    ("A" . ,(lambda () (evil-append-line 1)))
    ("i" . ,(lambda () (evil-insert 1)))
    ("I" . ,(lambda () (evil-insert-line 1)))
    ("gi" . ,(lambda () (evil-insert-resume 1)))
    ("cc" . ,(lambda () (evil-change (line-beginning-position) (line-beginning-position 2) 'line)))
    ("cw" . ,(lambda () (evil-change (point) (+ 3 (point)) 'exclusive)))
    ("C" . ,(lambda () (evil-change-line (point) (line-end-position))))
    ("s" . ,(lambda () (evil-substitute (point) (1+ (point)))))
    ("S" . ,(lambda () (evil-change-whole-line (line-beginning-position) (line-beginning-position 2)))))
  "Each way into insert state, called as its command would be, with no
command loop: no post-command-hook, so only the state change hides it.")

(ert-deftest cursor-insert-state-hides-the-cursor-at-once ()
  (dolist (route cursor-test--routes)
    (cursor-test--with-buffer
      (should (internal-show-cursor-p))
      (funcall (cdr route))
      (should (eq evil-state 'insert))
      (should-not cursor-type)
      (ert-info ((format "after %s" (car route)))
        (should-not (internal-show-cursor-p))
        (should (equal (car cursor-test--sent) "\e[?25l")))
      ;; Escape: the window's cursor is on at once; DECTCEM follows the
      ;; redisplay, from a timer.
      (evil-normal-state)
      (ert-info ((format "Escape after %s" (car route)))
        (should (eq cursor-type 'hbar))
        (should (internal-show-cursor-p))
        (should (equal (car cursor-test--sent) "\e[?25l"))
        (should eng/cursor--timer)
        (cursor-test--run-timer)
        (should (equal (car cursor-test--sent) "\e[?25h"))))))

(ert-deftest cursor-sends-dectcem-only-when-visibility-changes ()
  (cursor-test--with-buffer
    (evil-insert 1)
    (setq cursor-test--sent nil)
    (eng/cursor-sync-visibility)
    (with-selected-window (selected-window) (evil-refresh-cursor))
    (evil-insert-state)
    (should-not cursor-test--sent)
    (evil-normal-state)
    (evil-visual-state)
    (evil-normal-state)
    (cursor-test--run-timer)
    (should (equal cursor-test--sent '("\e[?25h")))))

(ert-deftest cursor-a-quick-escape-and-insert-sends-no-show ()
  ;; The deferred show looks again when it runs: i, Escape, i before the
  ;; redisplay leaves the cursor hidden.
  (cursor-test--with-buffer
    (evil-insert 1)
    (evil-normal-state)
    (evil-insert 1)
    (setq cursor-test--sent nil)
    (cursor-test--run-timer)
    (should-not cursor-test--sent)
    (should-not (internal-show-cursor-p))))

(ert-run-tests-batch-and-exit)
