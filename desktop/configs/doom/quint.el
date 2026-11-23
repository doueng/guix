;;; quint.el --- Quint specifications: a major mode and its language server -*- lexical-binding: t; -*-

;; quint-mode highlights and indents .qnt files; lsp-mode runs
;; quint-language-server (modules/engstrand/packages/quint.scm) for
;; diagnostics, hover, go-to-definition, completion and rename. Upstream's
;; quint-mode.el is a generic mode without indentation, and lsp-quint.el would
;; fetch the server from npm, so neither is used.

(defgroup quint nil
  "Quint specifications."
  :group 'languages)

(defcustom quint-indent-offset 2
  "Indentation inside a Quint bracket."
  :type 'integer)

(defconst quint--declarations
  '("module" "import" "export" "from" "as" "const" "var" "def" "val" "pure"
    "action" "temporal" "run" "nondet" "invariant" "observation" "assume" "type"))

(defconst quint--keywords
  '("if" "else" "match" "all" "any" "and" "or" "not" "iff" "implies"))

(defconst quint--constants '("true" "false" "Bool" "Int" "Nat"))

(defconst quint--builtin-types '("int" "bool" "str"))

(defvar quint-mode-syntax-table
  (let ((table (make-syntax-table)))
    (modify-syntax-entry ?/ ". 124b" table)
    (modify-syntax-entry ?* ". 23" table)
    (modify-syntax-entry ?\n "> b" table)
    (modify-syntax-entry ?_ "_" table)
    (modify-syntax-entry ?' "." table)
    (modify-syntax-entry ?\" "\"" table)
    table)
  "Syntax table for `quint-mode': // and /* */ comments, \"strings\".")

(defvar quint-font-lock-keywords
  `((,(regexp-opt quint--declarations 'symbols) . font-lock-keyword-face)
    (,(regexp-opt quint--keywords 'symbols) . font-lock-keyword-face)
    (,(regexp-opt quint--constants 'symbols) . font-lock-constant-face)
    (,(regexp-opt quint--builtin-types 'symbols) . font-lock-type-face)
    (,(rx symbol-start (or "def" "val" "action" "temporal" "run") (+ space)
          (group (+ (or word (syntax symbol)))))
     1 font-lock-function-name-face)
    (,(rx symbol-start (or "var" "const" "nondet") (+ space) (group (+ (or word (syntax symbol)))))
     1 font-lock-variable-name-face)
    ;; Types, sum-type constructors and modules are capitalized.
    (,(rx symbol-start (group upper (* (or word (syntax symbol))))) 1 font-lock-type-face)
    ;; x' = e assigns the next value of x.
    (,(rx (group (+ (or word (syntax symbol)))) "'") 1 font-lock-variable-name-face)
    (,(rx symbol-start (+ digit) symbol-end) . font-lock-constant-face))
  "Highlighting for `quint-mode'.")

(defconst quint--operator-line (rx (or (seq (or "and" "or" "implies" "iff") symbol-end) ".")))

(defun quint--open-bracket (pos)
  "The innermost open bracket at POS, or nil."
  (save-excursion (nth 1 (syntax-ppss pos))))

(defun quint--bracket-column (open closing)
  "Where a line inside the bracket at OPEN goes. Text after the bracket on
its line sets the column, as in { ...s,, unless that line ends in an
operator; else one step past that line's indentation. A CLOSING bracket goes under the bracket or that line."
  (save-excursion
    (goto-char open)
    (let ((bracket (current-column)))
      (forward-char)
      (skip-chars-forward " \t")
      (if (or (eolp) (looking-at-p "//")
              ;; A line that ends in an operator hangs its continuation.
              (save-excursion (end-of-line) (skip-chars-backward " \t")
                              (looking-back (rx (or "=" "=>" (seq symbol-start (or "and" "or" "implies" "iff"))))
                                            (line-beginning-position))))
          (+ (current-indentation) (if closing 0 quint-indent-offset))
        (if closing bracket (current-column))))))

(defun quint--previous-code-line ()
  "Point at the indentation of the previous line with code, or nil."
  (save-excursion
    (beginning-of-line)
    (let (found)
      (while (and (not found) (zerop (forward-line -1)))
        (back-to-indentation)
        (unless (or (eolp) (looking-at-p "//"))
          (setq found (point))))
      found)))

(defun quint-indent-line ()
  "Indent the line by its bracket, then by what it continues: a .then
chain or an and, or, implies or iff line goes one step past the line it
continues, as does the line after one that ends in = or =>."
  (interactive)
  (let* ((bol (line-beginning-position))
         (open (quint--open-bracket bol))
         (text (save-excursion (back-to-indentation) (point)))
         (closing (save-excursion (goto-char text) (looking-at-p "[]})]")))
         (operator (save-excursion (goto-char text) (looking-at-p quint--operator-line)))
         (prev (quint--previous-code-line))
         (same (and prev (eq (quint--open-bracket (save-excursion (goto-char prev) (line-beginning-position))) open)))
         (prev-indent (and prev (save-excursion (goto-char prev) (current-indentation))))
         (prev-operator (and prev (save-excursion (goto-char prev) (looking-at-p quint--operator-line))))
         (prev-opens (and prev (save-excursion (goto-char prev) (end-of-line)
                                               (skip-chars-backward " \t")
                                               (looking-back "=>?" (line-beginning-position)))))
         (column (cond ((and same operator) (if prev-operator prev-indent (+ prev-indent quint-indent-offset)))
                       ((and same prev-opens (not closing)) (+ prev-indent quint-indent-offset))
                       (open (quint--bracket-column open closing))
                       (t 0))))
    (if (<= (current-column) (current-indentation))
        (indent-line-to column)
      (save-excursion (indent-line-to column)))))

;;;###autoload
(define-derived-mode quint-mode prog-mode "Quint"
  "Major mode for Quint specifications."
  :syntax-table quint-mode-syntax-table
  (setq-local comment-start "// "
              comment-start-skip "//+ *\\|/\\*+ *"
              comment-end ""
              indent-line-function #'quint-indent-line
              indent-tabs-mode nil
              electric-indent-chars (append "{}()[]" electric-indent-chars)
              font-lock-defaults '(quint-font-lock-keywords)))

;;;###autoload
(add-to-list 'auto-mode-alist '("\\.qnt\\'" . quint-mode))

(with-eval-after-load 'lsp-mode
  (add-to-list 'lsp-language-id-configuration '(quint-mode . "quint"))
  (lsp-register-client
   (make-lsp-client :new-connection (lsp-stdio-connection '("quint-language-server" "--stdio"))
                    :activation-fn (lsp-activate-on "quint")
                    :major-modes '(quint-mode)
                    :server-id 'quint-language-server)))

(when (fboundp 'lsp!)
  (add-hook 'quint-mode-local-vars-hook #'lsp! 'append))

(provide 'quint)
;;; quint.el ends here
