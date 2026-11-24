;;; emacs-cursor.el --- Terminal Emacs for ghostty-rex's Bombadil cursor spec -*- lexical-binding: t; -*-
;; emacs -nw -Q --eval '(setq emacs-cursor-straight "DIR")' -l this-file
;; Tab 4 of test/bombadil/cursor.ts (T223): evil and
;; evil-terminal-cursor-changer from Doom's straight build DIR, with the
;; person's desktop/configs/doom/cursor.el. The mode line says MODE=<evil
;; state>, so the spec knows which cursor to expect. In the person's bbh
;; composer, evil's -- INSERT -- echo updated the terminal inside the command
;; and showed the cursor, and the redisplay after it drew nothing to hide it
;; again (T223, W301). Here the mode line changes, so that redisplay would hide
;; it; a redisplay at the start of post-command-hook stands in for the echo.

(defvar emacs-cursor-straight)
(dolist (p '("evil" "goto-chg" "evil-terminal-cursor-changer"))
  (add-to-list 'load-path (expand-file-name p emacs-cursor-straight)))
(setq evil-want-keybinding nil
      inhibit-startup-screen t
      initial-scratch-message nil
      make-backup-files nil
      auto-save-default nil)
(require 'evil)
(menu-bar-mode -1)
;; As in Doom: a blinking tty cursor toggles the window's cursor off and on.
(blink-cursor-mode -1)
(require 'evil-terminal-cursor-changer)
(load (expand-file-name "../../../../configs/doom/cursor.el" (file-name-directory load-file-name)) nil t)
(evil-mode 1)
(evil-terminal-cursor-changer-activate)
(setq-default mode-line-format '(" MODE=" (:eval (symbol-name evil-state))))
;; Each command ends with a redisplay before the rest of post-command-hook,
;; where evil-terminal-cursor-changer runs: what a mode that redisplays inside
;; a command does, deterministically, so every way into insert state meets it.
(add-hook 'post-command-hook (lambda () (redisplay t)) -90)
(switch-to-buffer (get-buffer-create "cursor"))
(insert "one two\nthree four\nfive six\n")
(goto-char (point-min))
(evil-normal-state)
