(use-modules (engstrand desktop-files)
             (guix gexp)
             (srfi srfi-1))

(define (check condition message)
  (unless condition (error message)))

(for-each
 (lambda (entry)
   (check (and (string? (car entry))
               (file-exists? (local-file-file (cadr entry))))
          "Missing direct Home source"))
 %desktop-home-files)

(for-each
 (lambda (entry)
   (check (and (string? (car entry))
               (string? (cdr entry))
               (file-exists? (cdr entry)))
          "Missing editable checkout source"))
 %desktop-direct-home-links)

(check (file-exists? (local-file-file %desktop-keyd-config))
       "Missing keyd configuration")
(display "PASS: direct Scheme desktop sources\n")
