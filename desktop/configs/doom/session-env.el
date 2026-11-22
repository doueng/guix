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
