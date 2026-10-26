;;; key-navigate.el --- Window focus across Emacs, Herdr and Hyprland -*- lexical-binding: t; -*-

(require 'windmove)

(defun eng/keylog (keys effect)
  "Append that KEYS ran with EFFECT in Emacs to the keybinding log, as a
schema 2 row of desktop/lib/keylog.clj. Only bound commands are recorded,
never typed text."
  (let* ((file (or (getenv "KEYLOG_FILE")
                   (expand-file-name "keybindings/keys.tsv"
                                     (or (getenv "XDG_STATE_HOME") "~/.local/state"))))
         (clean (lambda (s) (replace-regexp-in-string "[\t\n]" " " s))))
    (make-directory (file-name-directory file) t)
    (write-region (concat (format-time-string "%FT%T") "\temacs\t"
                          (funcall clean keys) "\t" (funcall clean effect) "\taction\t2\n")
                  nil file 'append 'silent)))

(defvar eng/keylog-commands (make-hash-table :test #'eq :weakness 'key)
  "The command that each eng/keylog-command wrapper runs, for eng/palette.")

(defun eng/keylog-command (keys effect command)
  "Return a command that logs KEYS and EFFECT, then runs COMMAND."
  (let ((wrapper (lambda ()
                   (interactive)
                   (eng/keylog keys effect)
                   (call-interactively command))))
    (puthash wrapper command eng/keylog-commands)
    wrapper))

(defun eng/navigate (direction)
  "Select the window in DIRECTION, a string such as \"left\".
Windows marked `no-other-window', such as Treemacs under Doom, are included.
At the frame edge, ask keyleader to move to the next Herdr pane or Hyprland window."
  (let ((windmove-allow-all-windows t))
    (condition-case nil
        (windmove-do-window-select (intern direction))
      (user-error
       (call-process (expand-file-name "~/.local/bin/keyleader") nil 0 nil
                     "nav" direction "from Emacs edge")))))

(provide 'key-navigate)
