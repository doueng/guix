;;; emacs.el --- Clojure feature config -*- lexical-binding: t; -*-

;; Register Babashka files before clojure-mode loads. Keeping this inside
;; `after!' creates a cycle: opening a .bb file cannot load clojure-mode until
;; the association exists, but the association would not exist until the mode
;; had already loaded.
(add-to-list 'auto-mode-alist '("\\.bb\\'" . clojure-mode))

;; Corretto 25's Nix package currently ships jspawnhelper without its execute
;; bit. clojure-lsp runs `bb print-deps', whose Clojure subprocess may need to
;; spawn Git, so use Java's legacy fork launcher until the package is fixed.
(let ((fork-option "-Djdk.lang.Process.launchMechanism=FORK"))
  (unless (member fork-option (split-string (or (getenv "JAVA_TOOL_OPTIONS") "")))
    (setenv "JAVA_TOOL_OPTIONS"
            (string-join (delq nil (list (getenv "JAVA_TOOL_OPTIONS") fork-option)) " "))))

(defvar eng/bb--nix-macro-cache nil)

(defun eng/bb-nix-dsl-p ()
  "Return non-nil when the current buffer is a Babashka Nix DSL file."
  (and buffer-file-name
       (let ((name (file-name-nondirectory buffer-file-name)))
         (or (string= name "feature.bb")
             (string-suffix-p ".feature.bb" name)))
       (locate-dominating-file buffer-file-name "dendritic/bb/nix/macros.clj")))

(defun eng/bb--nix-macro-candidates ()
  "Return completion candidates exported by nix.macros."
  (let* ((root (locate-dominating-file default-directory "dendritic/bb/nix/macros.clj"))
         (source (and root (expand-file-name "dendritic/bb/nix/macros.clj" root)))
         (modified (and source (file-attribute-modification-time
                                (file-attributes source)))))
    (if (and eng/bb--nix-macro-cache
             (equal modified (car eng/bb--nix-macro-cache)))
        (cdr eng/bb--nix-macro-cache)
      (let (candidates)
        (when (and source (file-readable-p source))
          (with-temp-buffer
            (insert-file-contents source)
            (goto-char (point-min))
            (while (re-search-forward
                    "^(defmacro[[:space:]]+\\([^][()[:space:]]+\\)" nil t)
              (push (concat "n/" (match-string-no-properties 1)) candidates))))
        (setq eng/bb--nix-macro-cache
              (cons modified (sort candidates #'string-lessp)))
        (cdr eng/bb--nix-macro-cache)))))

(defun eng/bb-nix-dsl-completion-at-point ()
  "Complete n/* forms from the repository's nix.macros namespace."
  (when (eng/bb-nix-dsl-p)
    (when-let* ((bounds (bounds-of-thing-at-point 'symbol))
                (beg (car bounds))
                (end (cdr bounds))
                (prefix (buffer-substring-no-properties beg end))
                ((string-prefix-p "n/" prefix)))
      (list beg end (eng/bb--nix-macro-candidates)
            :exclusive 'no
            :annotation-function (lambda (_) " Nix DSL")))))

(defun eng/bb-enable-editing-support ()
  "Enable Babashka/Nix editing support in the current .bb buffer."
  (when (eng/bb-nix-dsl-p)
    (setq-local cider-preferred-build-tool 'babashka)
    (add-hook 'completion-at-point-functions
              #'eng/bb-nix-dsl-completion-at-point nil t)))

(after! clojure-mode
  (setq cider-babashka-command "bb")
  (add-hook 'clojure-mode-hook #'eng/bb-enable-editing-support))

(after! lsp-completion
  ;; lsp-completion rebuilds the buffer-local CAPF list after the major-mode
  ;; hook, so add the DSL completion source again once LSP completion is ready.
  (add-hook 'lsp-completion-mode-hook #'eng/bb-enable-editing-support))

(after! quickrun
  (quickrun-add-command
   "clojure"
   '((:command . "clojure")
     (:exec . ("%c %s"))
     (:description . "Run Clojure with the clojure CLI"))
   :mode '(clojure-mode clojure-ts-mode)
   :override t))

(defun eng/clojure-project-root ()
  "Return the current Clojure project root or `default-directory`."
  (let ((dir (or (and buffer-file-name (file-name-directory buffer-file-name))
                 default-directory)))
    (or (locate-dominating-file dir "deps.edn")
        (locate-dominating-file dir "bb.edn")
        (and (fboundp 'projectile-project-root)
             (ignore-errors (projectile-project-root)))
        dir)))

(defun eng/bb--compile (target)
  "Save the current DSL buffer and run Makefile TARGET asynchronously."
  (unless (eng/bb-nix-dsl-p)
    (user-error "This command is only available in Dendritic .bb files"))
  (when (buffer-modified-p)
    (save-buffer))
  (let ((default-directory (file-name-as-directory (eng/clojure-project-root))))
    (compilation-start
     (format "make %s" target)
     'compilation-mode
     (lambda (_) (format "*bb-nix:%s*" target)))))

(defun eng/bb-generate-nix ()
  "Regenerate tracked Nix output from the Babashka DSL."
  (interactive)
  (eng/bb--compile "bb-gen"))

(defun eng/bb-check-generated ()
  "Check that generated Nix output matches the Babashka DSL."
  (interactive)
  (eng/bb--compile "bb-check"))

(defun eng/bb-validate ()
  "Run strict semantic validation for the Babashka Nix DSL."
  (interactive)
  (eng/bb--compile "bb-validate-strict"))

(defun eng/bb-test ()
  "Run the Babashka Nix DSL test suite."
  (interactive)
  (eng/bb--compile "bb-test"))

(defun eng/clojure-start-repl ()
  "Start a Clojure REPL from the current project root."
  (interactive)
  (let ((default-directory (file-name-as-directory (eng/clojure-project-root))))
    (cond
     ((and buffer-file-name (string-match-p "\\.bb\\'" buffer-file-name)
           (fboundp 'cider-jack-in-clj))
      (let ((cider-preferred-build-tool 'babashka))
        (call-interactively #'cider-jack-in-clj)))
     ((fboundp 'cider-jack-in-clj)
      (call-interactively #'cider-jack-in-clj))
     ((fboundp 'cider-jack-in)
      (call-interactively #'cider-jack-in))
     (t
      (user-error "CIDER is not available")))))

