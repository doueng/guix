(define-module (engstrand features keyd)
  #:use-module (engstrand features packages)
  #:use-module (engstrand services keyd)
  #:use-module (engstrand services keyd-config)
  #:use-module (engstrand services uinput)
  #:use-module (gnu packages)
  #:export (feature-familiar-keyd))

(define %keyd-system-packages
  (list
   (specification->package "keyd")))

(define (feature-familiar-keyd)
  (feature-package-set
   'familiar-keyd
   #:system-packages %keyd-system-packages
   #:system-services (list %keyd-service
                           %uinput-service
                           %keyd-config-service)))
