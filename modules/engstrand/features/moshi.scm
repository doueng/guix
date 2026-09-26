(define-module (engstrand features moshi)
  #:use-module (engstrand features packages)
  #:use-module (engstrand services moshi-hook)
  #:export (feature-familiar-moshi))

(define (feature-familiar-moshi)
  (feature-package-set
   'familiar-moshi
   #:home-services (list %moshi-hook-service)))
