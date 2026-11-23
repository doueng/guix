;;; session-env.el --- The Hyprland session's variables in a daemon -*- lexical-binding: t; -*-

(defun eng/adopt-session-env-h (frame)
  "Take the Wayland session's variables from the client that made FRAME.
The home shepherd starts daemons such as bbh-super outside Hyprland, and
subprocesses see only `process-environment', not the frame's, so shells and
make in Emacs could not reach hyprctl (T216). Run as the frame is made, before
the client's --eval."
  (dolist (var '("HYPRLAND_INSTANCE_SIGNATURE" "WAYLAND_DISPLAY"))
    (when-let* ((env (frame-parameter frame 'environment))
                (value (getenv-internal var env)))
      (setenv var value))))

(add-hook 'after-make-frame-functions #'eng/adopt-session-env-h)

(defun eng/reload-env-file (file)
  "Load the Doom envvar FILE as this Emacs would at startup, and return FILE.
Start again from the environment the daemon inherited, which Doom recorded, so a
variable the old file set and the new one leaves out goes too. Keep the session
variables frames brought in, the shell config.el picked and what it added to
`exec-path'. make refresh runs this in each daemon through emacsclient after doom
env rewrites FILE (T218)."
  (let* ((path (split-string (or (getenv "PATH") "") path-separator t))
         (added (seq-remove (lambda (d) (member d path)) exec-path))
         (shell shell-file-name)
         (session (delq nil (mapcar (lambda (var) (when-let* ((v (getenv var))) (cons var v)))
                                    '("HYPRLAND_INSTANCE_SIGNATURE" "WAYLAND_DISPLAY")))))
    (setq process-environment (copy-sequence (or (get 'process-environment 'initial-value)
                                                 process-environment)))
    (load file nil 'nomessage)
    (setq exec-path (delete-dups (append exec-path added))
          shell-file-name shell)
    (pcase-dolist (`(,var . ,value) session) (setenv var value))
    file))
