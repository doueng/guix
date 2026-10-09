;;; envrc-startup.el --- Envrc activation regression -*- lexical-binding: t; -*-

(require 'ert)
(require 'envrc)

(ert-deftest eng/envrc-global-activation-survives-process-buffer-deletion ()
  (let* ((directory (make-temp-file "envrc-startup-" t))
         (default-directory (file-name-as-directory directory))
         (buffer (generate-new-buffer "envrc-startup-test"))
         (was-enabled envrc-global-mode))
    (unwind-protect
        (progn
          (with-temp-file (expand-file-name ".envrc" directory)
            (insert "sleep 0.2\nexport ENVRC_STARTUP_TEST=ready\n"))
          (should (= 0 (call-process envrc-direnv-executable nil nil nil "allow" directory)))
          (envrc-global-mode -1)
          (with-current-buffer buffer
            (setq default-directory (file-name-as-directory directory))
            (let ((envrc-async t))
              (envrc-mode 1)
              (envrc-reload)))
          (should (seq-some
                   (lambda (candidate)
                     (string-prefix-p " *envrc-temp-" (buffer-name candidate)))
                   (buffer-list)))
          (let ((envrc-async nil))
            (envrc-global-mode 1))
          (with-current-buffer buffer
            (should envrc-mode)
            (should (equal "ready" (getenv "ENVRC_STARTUP_TEST"))))
          (should (equal "ready"
                         (with-temp-buffer
                           (setq default-directory (file-name-as-directory directory))
                           (fundamental-mode)
                           (getenv "ENVRC_STARTUP_TEST"))))
          (envrc-global-mode -1)
          (with-current-buffer buffer
            (should-not (getenv "ENVRC_STARTUP_TEST"))))
      (when (buffer-live-p buffer)
        (kill-buffer buffer))
      (delete-directory directory t)
      (envrc-global-mode (if was-enabled 1 -1)))))
