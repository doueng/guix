;; guix repl -L modules -- scheme.scm PATH...
;; Compile every .scm file under each PATH with warnings, writing objects to a
;; temporary directory. Exit 1 on any read error or warning. Loading alone misses
;; unbound variables inside procedure bodies; compiling catches them.
(use-modules (system base compile) (ice-9 ftw) (srfi srfi-1))

;; (gnu) rebinds core `delete' for the modify-services clause; that is intended.
;; Auto-compile notes, such as a source newer than its cached .go, are not about
;; the code, so keep only warnings among Guile's ;;; lines.
(define (benign? line)
  (or (string-null? line)
      (string-contains line "overrides core binding `delete'")
      (and (string-prefix? ";;;" line) (not (string-contains line ": warning: ")))))

(define (scheme-files path)
  (if (eq? 'directory (stat:type (stat path)))
      (append-map (lambda (name) (scheme-files (string-append path "/" name)))
                  (scandir path (lambda (n) (not (member n '("." ".."))))))
      (if (string-suffix? ".scm" path) (list path) '())))

(define out-dir
  (string-append (or (getenv "TMPDIR") "/tmp") "/guix-lint-" (number->string (getpid))))

;; channels.scm and manifests are scripts that Guix evaluates with these
;; modules in scope rather than modules of their own.
(define (script-env)
  (let ((m (make-fresh-user-module)))
    (for-each (lambda (name) (module-use! m (resolve-interface name)))
              '((guix channels) (guix profiles) (gnu)))
    m))

(define (module-file? file)
  (let ((form (call-with-input-file file read)))
    (and (pair? form) (memq (car form) '(define-module use-modules)))))

(define (check file)
  (let* ((port (open-output-string))
         (ok? (catch #t
                (lambda ()
                  (parameterize ((current-warning-port port))
                    (compile-file file
                                  #:output-file (string-append out-dir "/" (basename file) ".go")
                                  #:env (if (module-file? file) (make-fresh-user-module) (script-env))
                                  #:opts '(#:warnings (unbound-variable arity-mismatch format))))
                  #t)
                (lambda (key . args)
                  (format port "~a: ~a: ~s~%" file key args)
                  #f)))
         (lines (remove benign? (string-split (get-output-string port) #\newline))))
    (for-each (lambda (l) (display l (current-error-port)) (newline (current-error-port))) lines)
    (and ok? (null? lines))))

(define files (sort (append-map scheme-files (cdr (command-line))) string<?))
(define failed (remove check files))
(format #t "scheme: compiled ~a file(s), ~a with problems~%" (length files) (length failed))
(exit (if (null? failed) 0 1))
