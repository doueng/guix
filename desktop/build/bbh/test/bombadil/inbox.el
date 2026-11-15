;;; inbox.el --- the bbh Inbox for Bombadil to drive -*- lexical-binding: t; -*-

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map" "org" "goto-chg" "evil"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(require 'cl-lib)
(require 'evil)
(evil-mode 1)
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)

;; babashka cannot start under Emacs's address-space cap, so bbh lifts it.
(setq eng/bbh-inbox-bbh (list "prlimit" "--as=unlimited:unlimited" (getenv "BBH_BOMBADIL_BBH"))
      confirm-kill-emacs (lambda (&rest _) nil)
      inhibit-startup-screen t
      ring-bell-function #'ignore)

(setq evil-esc-delay 0)

;; Reload waits for idleness and Bombadil types every ~120 ms; 0.5 s would never fire.
(setq eng/bbh-inbox-reload-delay 0.05)

(when-let* ((root (getenv "BBH_BOMBADIL_ROOT")))
  (let ((log (expand-file-name "messages.log" root)))
    (advice-add 'message :after
                (lambda (format-string &rest args)
                  (when format-string
                    (let ((inhibit-message t))
                      (write-region (concat (format-time-string "%T.%3N ") (apply #'format format-string args) "\n")
                                    nil log t 'silent)))))))

;; Counted in the mode line: the next key clears the echo area before Bombadil may look.
(defvar bbh-bombadil--refusals 0)
(advice-add 'message :after
            (lambda (format-string &rest args)
              (when (and format-string
                         (string-match-p "\\`bbh inbox: \\(stale\\|no-such-ask\\|changed\\)" (apply #'format format-string args)))
                (setq bbh-bombadil--refusals (1+ bbh-bombadil--refusals))
                (force-mode-line-update t))))
(setq global-mode-string '(:eval (format " refused:%d" bbh-bombadil--refusals)))

(eng/bbh-inbox)
