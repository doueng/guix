(define-module (engstrand services home-links)
  #:use-module (engstrand home files)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:use-module (guix gexp)
  #:export (%familiar-direct-home-links))

;; Live files are deliberately not home-files-service entries: that service
;; always adds a store indirection. Install direct links during activation,
;; and track ownership so stale links can be removed without touching files
;; the user has replaced. Conflicting live-link destinations are moved to a
;; backup under ~/.local/state/guix-home before being replaced.
(define %familiar-direct-home-links
  (simple-service 'familiar-direct-home-links home-activation-service-type
    #~(begin
        (use-modules (guix build utils) (ice-9 ftw) (ice-9 rdelim)
                     (srfi srfi-1) (srfi srfi-13))
        ;; Keep waybar here only to retire legacy store-backed tree links.
        (define roots
          '(".config/fish" ".config/nvim" ".config/doom"
            ".config/hypr" ".config/waybar" ".pi/agent"
            ".local/share/catppuccin-mocha/wallpapers"
            ".local/share/herdr/tiny-fingers"))
        (define links '#$%desktop-direct-home-links)
        (define home (getenv "HOME"))
        (define state (string-append home "/.local/state/guix-home"))
        (define manifest (string-append state "/live-links"))
        (define backup-directory
          (string-append state "/backups/" (number->string (current-time))
                         "-" (number->string (getpid))))
        (define (symlink-target path)
          (catch 'system-error
            (lambda ()
              (and (eq? 'symlink (stat:type (lstat path)))
                   (readlink path)))
            (lambda _ #f)))
        (define (path-exists? path)
          (catch 'system-error
            (lambda () (lstat path) #t)
            (lambda _ #f)))
        (define (backup-conflict relative)
          (let* ((target (string-append home "/" relative))
                 (backup (string-append backup-directory "/" relative)))
            (when (path-exists? target)
              (when (path-exists? backup)
                (error "Home backup destination already exists" backup))
              (mkdir-p (dirname backup))
              (format #t "Backing up conflicting Home path ~a to ~a~%"
                      target backup)
              (rename-file target backup))))
        (define (ensure-parent relative)
          (let loop ((parts (drop-right (string-split relative #\/) 1))
                     (prefix ""))
            (unless (null? parts)
              (let* ((next (if (string-null? prefix) (car parts)
                               (string-append prefix "/" (car parts))))
                     (target (string-append home "/" next)))
                (when (and (path-exists? target)
                           (not (eq? 'directory (stat:type (lstat target)))))
                  (backup-conflict next))
                (mkdir-p target)
                (loop (cdr parts) next)))))
        (define (legacy-link? target)
          (and target
               (string-prefix? "/gnu/store/" target)
               (string-suffix? "-familiar-live-file" target)))
        (define previous
          (if (path-exists? manifest)
              (call-with-input-file manifest
                (lambda (port)
                  (let loop ((line (read-line port)) (result '()))
                    (if (eof-object? line)
                        (reverse result)
                        (let ((fields (string-split line #\tab)))
                          (unless (= (length fields) 2)
                            (error "Invalid live-link ownership record" manifest))
                          (loop (read-line port) (cons fields result)))))))
              '()))
        (define current-paths (map car links))
        ;; Retire only known links left by older Home generations before
        ;; checking child paths; those children otherwise resolve through the
        ;; old store-backed tree symlink during preflight.
        (for-each
         (lambda (relative)
           (let* ((target (string-append home "/" relative))
                  (old (symlink-target target)))
             (when (legacy-link? old) (backup-conflict relative))))
         '(".config/hypr/hyprland.lua"))
        (for-each
         (lambda (root)
           (let* ((target (string-append home "/" root))
                  (old (symlink-target target)))
             (when (legacy-link? old) (backup-conflict root))))
         roots)
        (for-each
         (lambda (owned)
           (unless (member (car owned) current-paths)
             (let* ((target (string-append home "/" (car owned)))
                    (old (symlink-target target)))
               (when (equal? old (cadr owned))
                 (format #t "Removing stale managed Home link ~a~%" target)
                 (delete-file target)))))
         previous)
        (for-each
         (lambda (link)
           (let* ((relative (car link))
                  (source (cdr link))
                  (target (string-append home "/" relative))
                  (old (symlink-target target)))
             (ensure-parent relative)
             (when (and (path-exists? target)
                        (not (equal? old source)))
               (backup-conflict relative))
             (unless (path-exists? target) (symlink source target))))
         links)
        (mkdir-p state)
        (let ((temporary (string-append manifest ".new")))
          (call-with-output-file temporary
            (lambda (port)
              (for-each (lambda (link)
                          (format port "~a\t~a\n" (car link) (cdr link)))
                        links)))
          (rename-file temporary manifest)))))
