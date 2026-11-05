;;; clipboard.el --- Share the kill ring with the Wayland clipboard -*- lexical-binding: t; -*-

(setq select-enable-clipboard t
      select-enable-primary t
      save-interprogram-paste-before-kill t
      kill-do-not-save-duplicates t
      yank-pop-change-selection t)

(setq savehist-additional-variables '(search-ring regexp-search-ring kill-ring))
(savehist-mode 1)
(add-hook 'savehist-save-hook
          (lambda ()
            (setq kill-ring
                  (mapcar #'substring-no-properties
                          (cl-remove-if-not #'stringp kill-ring)))))

(defun eng/wayland-display ()
  "The Wayland display whose clipboard Emacs shares.
Daemons run by the home shepherd, such as bbh-super, start beside Hyprland
rather than under it and so lack WAYLAND_DISPLAY. Fall back to the compositor
socket in XDG_RUNTIME_DIR, which also follows a compositor restart."
  (let ((dir (or (getenv "XDG_RUNTIME_DIR")
                 (format "/run/user/%d" (user-uid))))
        (display (getenv "WAYLAND_DISPLAY")))
    (if (and display (file-exists-p (expand-file-name display dir)))
        display
      (car (directory-files dir nil "\\`wayland-[0-9]+\\'")))))

(defmacro eng/with-wayland-display (&rest body)
  "Run BODY with WAYLAND_DISPLAY set for its subprocesses."
  `(let ((process-environment
          (let ((display (eng/wayland-display)))
            (if display
                (cons (concat "WAYLAND_DISPLAY=" display) process-environment)
              process-environment))))
     ,@body))

(defun eng/system-clipboard-copy (text &optional _push)
  "Copy TEXT to the Wayland clipboard."
  (when (and (stringp text) (not (string-empty-p text)))
    (with-temp-buffer
      (insert text)
      (eng/with-wayland-display
       (call-process-region (point-min) (point-max) "wl-copy" nil 0 nil
                            "--type" "text/plain")))))

(defun eng/system-clipboard-paste ()
  "Read text from the Wayland clipboard."
  (with-temp-buffer
    (when (zerop (eng/with-wayland-display
                  (call-process "wl-paste" nil t nil "--no-newline")))
      (buffer-string))))

(setq interprogram-cut-function #'eng/system-clipboard-copy
      interprogram-paste-function #'eng/system-clipboard-paste)
