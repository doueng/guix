(define-module (engstrand packages assets)
  #:use-module (guix gexp)
  #:export (package-asset %repo-dir))

(define %module-file
  (canonicalize-path (search-path %load-path "engstrand/packages/assets.scm")))
(define %repo-dir
  (dirname (dirname (dirname (dirname %module-file)))))

(define* (package-asset relative #:optional (name (basename relative))
                        #:key (recursive? #f))
  (local-file (string-append %repo-dir "/" relative) name
              #:recursive? recursive?))
