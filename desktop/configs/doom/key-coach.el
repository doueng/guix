;;; key-coach.el --- Non-blocking motion hints -*- lexical-binding: t; -*-

(defvar eng/key-coach-threshold 5)
(defvar eng/key-coach-interval 1.0)
(defvar eng/key-coach-cooldown 10.0)
(defvar eng/key-coach-hints
  '((evil-next-line . "Use a count, C-d, /search, or s to jump down.")
    (evil-previous-line . "Use a count, C-u, /search, or S to jump up.")
    (evil-next-visual-line . "Use a count, C-d, /search, or s to jump down.")
    (evil-previous-visual-line . "Use a count, C-u, /search, or S to jump up.")
    (evil-forward-char . "Use w, e, f, t, or $ instead of repeated l.")
    (evil-backward-char . "Use b, F, T, or ^ instead of repeated h.")))
(defvar-local eng/key-coach--run nil)
(defvar eng/key-coach--last-hint 0.0)
(defvar eng/key-coach--counts (make-hash-table :test #'eq))

(defun eng/key-coach-observe (command now eligible)
  "Observe COMMAND at NOW when ELIGIBLE. Return a hint without blocking input."
  (let ((hint (and eligible (alist-get command eng/key-coach-hints))))
    (if (not hint)
        (setq eng/key-coach--run nil)
      (let ((count (if (and (eq command (nth 0 eng/key-coach--run))
                            (< (- now (or (nth 1 eng/key-coach--run) 0))
                               eng/key-coach-interval))
                       (1+ (nth 2 eng/key-coach--run))
                     1)))
        (setq eng/key-coach--run (list command now count))
        (when (and (>= count eng/key-coach-threshold)
                   (>= (- now eng/key-coach--last-hint) eng/key-coach-cooldown))
          (setq eng/key-coach--last-hint now)
          (puthash command (1+ (gethash command eng/key-coach--counts 0))
                   eng/key-coach--counts)
          hint)))))

(defun eng/key-coach--post-command ()
  (when-let* ((hint (eng/key-coach-observe
                   this-command (float-time)
                   (and (eq (bound-and-true-p evil-state) 'normal)
                        buffer-file-name (not buffer-read-only)
                        (not current-prefix-arg)
                        (not executing-kbd-macro)
                        (not defining-kbd-macro)
                        (not (minibufferp))))))
    (message "Key coach. %s" hint)))

(defun eng/key-coach-report ()
  "Show command counts for hints in this session. No text or paths are stored."
  (interactive)
  (with-help-window "*Key coach*"
    (princ "Motion hints in this Emacs session\n\n")
    (if (zerop (hash-table-count eng/key-coach--counts))
        (princ "No hints yet.\n")
      (maphash (lambda (command count) (princ (format "%s  %d\n" command count)))
               eng/key-coach--counts))))

(define-minor-mode eng/key-coach-mode
  "Suggest better Evil motions without blocking keys or recording typed text."
  :global t
  (if eng/key-coach-mode
      (add-hook 'post-command-hook #'eng/key-coach--post-command)
    (remove-hook 'post-command-hook #'eng/key-coach--post-command))
  (dolist (buffer (buffer-list))
    (with-current-buffer buffer (setq eng/key-coach--run nil))))

(provide 'key-coach)
