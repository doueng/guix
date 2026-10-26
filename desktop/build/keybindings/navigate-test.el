;;; navigate-test.el -*- lexical-binding: t; -*-
(require 'ert)
(require 'cl-lib)
(load-file "desktop/configs/doom/key-navigate.el")

(ert-deftest navigate-moves-inside-emacs-then-hands-off-at-the-edge ()
  (delete-other-windows)
  (let ((left (selected-window))
        (calls nil))
    (split-window-right)
    (cl-letf (((symbol-function 'call-process)
               (lambda (&rest args) (push args calls) 0)))
      (eng/navigate "right")
      (should-not (eq left (selected-window)))
      (should-not calls)
      (eng/navigate "right")
      (should (equal (list (expand-file-name "~/.local/bin/keyleader") nil 0 nil "nav" "right" "from Emacs edge")
                     (car calls)))
      (eng/navigate "left")
      (should (eq left (selected-window))))))

(ert-deftest keylog-command-records-the-binding-and-runs-the-command ()
  (let* ((file (make-temp-file "keylog"))
         (process-environment (cons (concat "KEYLOG_FILE=" file) process-environment))
         (ran nil))
    (unwind-protect
        (progn
          (funcall (eng/keylog-command "Space w s" "Split\tbelow" (lambda () (interactive) (setq ran t))))
          (should ran)
          (should (string-match-p "\\`[0-9T:-]+\temacs\tSpace w s\tSplit below\taction\t2\n\\'"
                                  (with-temp-buffer (insert-file-contents file) (buffer-string)))))
      (delete-file file))))
