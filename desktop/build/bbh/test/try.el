;;; try.el --- run real bbh prompts through agent-shell headlessly -*- lexical-binding: t; -*-

;; bb try MODEL PROMPT... prints the agent-shell buffer after each prompt.

(dolist (pkg '("agent-shell" "acp" "shell-maker" "map"))
  (add-to-list 'load-path (expand-file-name pkg (getenv "BBH_ELISP_BUILD"))))
(defmacro after! (feature &rest body)
  `(with-eval-after-load ',feature ,@body))
(load (getenv "BBH_DOOM_AGENT_SHELL") nil t)
(require 'agent-shell)

(defun try--wait (pred secs)
  (let ((end (+ (float-time) secs)))
    (while (and (not (funcall pred)) (< (float-time) end))
      (accept-process-output nil 0.2))
    (unless (funcall pred)
      (error "Timed out waiting for agent-shell"))
    t))

(let* ((prompts (split-string (getenv "BBH_TRY_PROMPTS") "\n---\n"))
       (buf (agent-shell-start :config (eng/bbh-agent-config)))
       (mark 1))
  (with-current-buffer buf
    (try--wait (lambda () (and (map-nested-elt agent-shell--state '(:session :id))
                               (string-match-p "Ready" (buffer-string))))
               60)
    (accept-process-output nil 0.5)
    (dolist (p prompts)
      (goto-char (point-max))
      (insert p)
      (shell-maker-submit)
      (try--wait (lambda () (not (shell-maker-busy))) (string-to-number (or (getenv "BBH_TRY_SECONDS") "600")))
      (princ (buffer-substring-no-properties mark (point-max)))
      (setq mark (point-max)))
    (princ (format "\n[session %s]\n" (map-nested-elt agent-shell--state '(:session :id))))))
