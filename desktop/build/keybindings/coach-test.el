;;; coach-test.el -*- lexical-binding: t; -*-
(require 'ert)
(load-file "desktop/configs/doom/key-coach.el")

(ert-deftest coach-hints-without-intercepting-commands ()
  (with-temp-buffer
    (let ((eng/key-coach--last-hint 0.0)
          (eng/key-coach--counts (make-hash-table :test #'eq)))
      (dotimes (i 4)
        (should-not (eng/key-coach-observe 'evil-forward-char (+ 20 (* i 0.1)) t)))
      (should (equal "Use w, e, f, t, or $ instead of repeated l."
                     (eng/key-coach-observe 'evil-forward-char 20.4 t)))
      (should (= 1 (gethash 'evil-forward-char eng/key-coach--counts)))
      (should-not (eng/key-coach-observe 'evil-forward-char 20.5 t))
      (eng/key-coach-report)
      (with-current-buffer "*Key coach*"
        (should (string-match-p "evil-forward-char  1" (buffer-string)))))))

(ert-deftest coach-resets-for-counts-mode-changes-and-pauses ()
  (with-temp-buffer
    (let ((eng/key-coach--last-hint 0.0)
          (eng/key-coach--counts (make-hash-table :test #'eq)))
      (dotimes (i 4) (eng/key-coach-observe 'evil-next-line (+ 20 (* i 0.1)) t))
      (should-not (eng/key-coach-observe 'evil-next-line 20.4 nil))
      (should-not (eng/key-coach-observe 'evil-next-line 20.5 t))
      (should-not (eng/key-coach-observe 'evil-next-line 25 t))
      (should-not (eng/key-coach-observe 'save-buffer 25.1 t))
      (dotimes (i 4) (should-not (eng/key-coach-observe 'evil-next-line (+ 26 (* i 0.1)) t)))
      (should (equal "Use a count, C-d, /search, or s to jump down."
                     (eng/key-coach-observe 'evil-next-line 26.4 t))))))

(ert-deftest coach-mode-is-idempotent ()
  (unwind-protect
      (progn
        (eng/key-coach-mode 1)
        (eng/key-coach-mode 1)
        (should (= 1 (cl-count #'eng/key-coach--post-command post-command-hook)))
        (eng/key-coach-mode -1)
        (should-not (memq #'eng/key-coach--post-command post-command-hook)))
    (eng/key-coach-mode -1)))
