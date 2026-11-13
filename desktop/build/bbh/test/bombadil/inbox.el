;;; inbox.el --- the bbh Inbox for Bombadil to drive -*- lexical-binding: t; -*-

;; Run by `bb test:bombadil' as `emacs -Q -nw -l inbox.el' inside Bombadil's
;; PTY.  It loads evil and the bbh Emacs code the way tty-visit-test.el does
;; and opens the split Inbox on the fixture controller its environment
;; points at.  BBH_BOMBADIL_BBH is this checkout's bbh, so the Inbox never
;; runs the installed one, and the runner checked XDG_DATA_HOME is a temp dir.

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org" "goto-chg" "evil"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(require 'cl-lib)
(require 'evil)
(evil-mode 1)
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)

;; Emacs runs under a soft address-space cap that babashka cannot start
;; under, so bbh lifts it for itself.
(setq eng/bbh-inbox-bbh (list "prlimit" "--as=unlimited:unlimited" (getenv "BBH_BOMBADIL_BBH"))
      ;; Leaving Emacs ends the run, so the spec never sends C-x; refuse anyway.
      confirm-kill-emacs (lambda (&rest _) nil)
      inhibit-startup-screen t
      ring-bell-function #'ignore)

;; Doom's evil-escape is absent here; ESC alone leaves insert state at once.
(setq evil-esc-delay 0)

;; A pushed reload waits for 0.5 s of idleness, and Bombadil types every
;; ~120 ms, so it would never come.  A shorter wait keeps pushes racing keys.
(setq eng/bbh-inbox-reload-delay 0.05)

;; Every message goes to <root>/messages.log too, for triage of a trace.
(when-let* ((root (getenv "BBH_BOMBADIL_ROOT")))
  (let ((log (expand-file-name "messages.log" root)))
    (advice-add 'message :after
                (lambda (format-string &rest args)
                  (when format-string
                    (let ((inhibit-message t))
                      (write-region (concat (format-time-string "%T.%3N ") (apply #'format format-string args) "\n")
                                    nil log t 'silent)))))))

(eng/bbh-inbox)
