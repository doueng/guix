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
                 "GHOSTTY_SURFACE_ID" "TERM_PROGRAM" "TERM_PROGRAM_VERSION" "HL_INITIAL_WORKSPACE_TOKEN"
                 "REX_BLOCK" "REX_SESSION" "REX_SERVER" "REX_BIN_DIR" "BBH_SUPER_TOKEN" "WF_RUN"
                 "COLORTERM" "EMACSLOADPATH"))
    (should (session-env-test--denied-p var)))
  (dolist (var '("PATH" "SHELL" "GUIX_LOCPATH" "EDITOR"))
    (should-not (session-env-test--denied-p var))))

(ert-deftest doom-env-saves-path-without-store-or-shim-directories ()
  (should (equal '("PATH=/home/me/.local/bin:/run/current-system/profile/bin" "SHELL=/bin/fish")
                 (eng/env-path-without-store
                  '("PATH=/home/me/guix/desktop/build/bbh/shims:/home/me/.local/bin:/gnu/store/abc-ghostty-1.4/bin:/run/current-system/profile/bin"
                    "SHELL=/bin/fish")))))

(ert-deftest reloading-the-env-file-drops-what-it-no-longer-sets ()
  (let* ((file (make-temp-file "doom-env" nil ".el"
                               ";; -*- lexical-binding: t; -*-\n(setq process-environment (append '(\"PATH=/new/bin\" \"SHELL=/bin/fish\") (default-value 'process-environment)) exec-path (split-string (getenv \"PATH\") \":\" t) shell-file-name (getenv \"SHELL\"))"))
         (old-initial (get 'process-environment 'initial-value))
         (process-environment '("PATH=/old/bin" "REX_BLOCK=gone" "HYPRLAND_INSTANCE_SIGNATURE=live" "HOME=/h"))
         (exec-path '("/old/bin" "/added/by/config"))
         (shell-file-name "/bin/bash"))
    (unwind-protect
        (progn
          (put 'process-environment 'initial-value '("HOME=/h"))
          (should (equal file (eng/reload-env-file file)))
          (should (equal "/new/bin" (getenv "PATH")))
          (should-not (getenv "REX_BLOCK"))
          (should (equal "live" (getenv "HYPRLAND_INSTANCE_SIGNATURE")))
          (should (equal "/h" (getenv "HOME")))
          (should (equal '("/new/bin" "/added/by/config") exec-path))
          (should (equal "/bin/bash" shell-file-name)))
      (put 'process-environment 'initial-value old-initial)
      (delete-file file))))

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
