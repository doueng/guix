;;; quint-mode-test.el --- quint-mode highlights and indents the repo's specs -*- lexical-binding: t; -*-
;; emacs --batch -l desktop/build/doom/quint-mode-test.el

(require 'ert)
(require 'cl-lib)
(defvar quint-mode-test--root (expand-file-name "../../../" (file-name-directory load-file-name)))
(load (expand-file-name "desktop/configs/doom/quint.el" quint-mode-test--root) nil t)

(defun quint-mode-test--reindented (file)
  "The lines of FILE that `indent-region' in quint-mode changes."
  (with-temp-buffer
    (insert-file-contents (expand-file-name file quint-mode-test--root))
    (quint-mode)
    (let ((before (split-string (buffer-string) "\n")))
      (indent-region (point-min) (point-max))
      (cl-loop for b in before for a in (split-string (buffer-string) "\n") for n from 1
               unless (string= a b) collect (list n b a)))))

(ert-deftest quint-mode-opens-qnt-files ()
  (should (eq 'quint-mode (cdr (assoc "x.qnt" auto-mode-alist #'string-match-p)))))

(ert-deftest quint-mode-keeps-the-indentation-of-specs-written-by-hand ()
  (should-not (quint-mode-test--reindented "desktop/build/bbh/verify/quint/workflow.qnt"))
  (should-not (quint-mode-test--reindented "desktop/build/bbh/verify/quint/effects.qnt")))

(ert-deftest quint-mode-indents-brackets-chains-and-continuations ()
  (with-temp-buffer
    (quint-mode)
    (insert "module m {\nval v = and {\na,\nb,\n}\nrun t =\ninit\n.then(a)\n.then(b)\ndef f(x: int): bool =\nx > 1\nand x < 9\nval r = { ...s,\nf: 1 }\n}\n")
    (indent-region (point-min) (point-max))
    (should (equal (buffer-string)
                   "module m {\n  val v = and {\n    a,\n    b,\n  }\n  run t =\n    init\n      .then(a)\n      .then(b)\n  def f(x: int): bool =\n    x > 1\n      and x < 9\n  val r = { ...s,\n            f: 1 }\n}\n"))))

(ert-deftest quint-mode-highlights-declarations-types-and-comments ()
  (with-temp-buffer
    (insert "module m {\n  // note\n  type K = A | B\n  var x: int\n  action step = x' = 1\n}\n")
    (quint-mode)
    (font-lock-ensure)
    (cl-flet ((face (s) (goto-char (point-min)) (search-forward s) (get-text-property (match-beginning 0) 'face)))
      (should (eq 'font-lock-keyword-face (face "module")))
      (should (memq (face "note") '(font-lock-comment-face)))
      (should (eq 'font-lock-type-face (face "A |")))
      (should (eq 'font-lock-variable-name-face (face "x:")))
      (should (eq 'font-lock-type-face (face "int")))
      (should (eq 'font-lock-function-name-face (face "step")))
      (should (eq 'font-lock-constant-face (face "1\n"))))))

(ert-run-tests-batch-and-exit)
