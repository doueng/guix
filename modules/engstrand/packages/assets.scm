(define-module (engstrand packages assets)
  #:use-module (guix gexp)
  #:export (package-asset))

(define %module-file
  (canonicalize-path (search-path %load-path "engstrand/packages/assets.scm")))
(define %repo-dir
  (dirname (dirname (dirname (dirname %module-file)))))

(define (package-asset relative)
  (local-file (string-append %repo-dir "/" relative)))
