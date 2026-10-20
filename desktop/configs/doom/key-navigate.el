;;; key-navigate.el --- Window focus across Emacs, Herdr and Hyprland -*- lexical-binding: t; -*-

(require 'windmove)

(defun eng/keylog (keys effect)
  "Append which KEYS ran and their EFFECT in Emacs to the keybinding log.
Only bound commands are recorded, never typed text."
  (let* ((file (or (getenv "KEYLOG_FILE")
                   (expand-file-name "keybindings/keys.tsv"
                                     (or (getenv "XDG_STATE_HOME") "~/.local/state"))))
         (clean (lambda (s) (replace-regexp-in-string "[\t\n]" " " s))))
    (make-directory (file-name-directory file) t)
    (write-region (concat (format-time-string "%FT%T") "\tEmacs\t"
                          (funcall clean keys) "\t" (funcall clean effect) "\n")
                  nil file 'append 'silent)))

(defun eng/keylog-command (keys effect command)
  "Return a command that logs KEYS and EFFECT, then runs COMMAND."
  (lambda ()
    (interactive)
    (eng/keylog keys effect)
    (call-interactively command)))

(defun eng/navigate (direction)
  "Select the window in DIRECTION, a string such as \"left\".
At the frame edge, ask keyleader to move to the next Herdr pane or Hyprland window."
  (condition-case nil
      (windmove-do-window-select (intern direction))
    (user-error
     (call-process (expand-file-name "~/.local/bin/keyleader") nil 0 nil
                   "nav" direction "from Emacs edge"))))

(provide 'key-navigate)
