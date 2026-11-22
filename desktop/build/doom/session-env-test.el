;;; session-env-test.el --- doom env and daemon frames keep Hyprland current -*- lexical-binding: t; -*-
;; emacs --batch -l desktop/build/doom/session-env-test.el

(require 'ert)
(defvar doom-env-deny nil)
(let ((dir (expand-file-name "../../configs/doom/" (file-name-directory load-file-name))))
  (load (expand-file-name "cli.el" dir) nil t)
  (load (expand-file-name "session-env.el" dir) nil t))

(defun session-env-test--denied-p (var)
  (seq-find (lambda (re) (string-match-p re var)) doom-env-deny))

(ert-deftest doom-env-leaves-out-session-variables ()
  (dolist (var '("HYPRLAND_INSTANCE_SIGNATURE" "HYPRLAND_CMD" "HERDR_PANE_ID"
                 "GHOSTTY_SURFACE_ID" "TERM_PROGRAM" "HL_INITIAL_WORKSPACE_TOKEN"))
    (should (session-env-test--denied-p var)))
  (dolist (var '("PATH" "SHELL" "GUIX_LOCPATH" "EDITOR"))
    (should-not (session-env-test--denied-p var))))

(ert-deftest a-client-frame-replaces-a-stale-signature ()
  (let ((process-environment (cons "HYPRLAND_INSTANCE_SIGNATURE=stale" process-environment))
        (old (frame-parameter nil 'environment)))
    (unwind-protect
        (progn
          (set-frame-parameter nil 'environment '("HYPRLAND_INSTANCE_SIGNATURE=live"))
          (run-hook-with-args 'after-make-frame-functions (selected-frame))
          (should (equal "live" (string-trim (shell-command-to-string "echo $HYPRLAND_INSTANCE_SIGNATURE")))))
      (set-frame-parameter nil 'environment old))))

(ert-deftest a-frame-without-a-client-changes-nothing ()
  (let ((process-environment (cons "HYPRLAND_INSTANCE_SIGNATURE=kept" process-environment))
        (old (frame-parameter nil 'environment)))
    (unwind-protect
        (progn
          (set-frame-parameter nil 'environment nil)
          (run-hook-with-args 'after-make-frame-functions (selected-frame))
          (should (equal "kept" (getenv "HYPRLAND_INSTANCE_SIGNATURE"))))
      (set-frame-parameter nil 'environment old))))

(ert-run-tests-batch-and-exit)
