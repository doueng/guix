(define-module (engstrand features tailscale)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages tailscale)
  #:use-module (engstrand services tailscale)
  #:export (feature-familiar-tailscale))

(define (feature-familiar-tailscale)
  (feature-package-set
   'familiar-tailscale
   #:system-packages (list tailscale)
   #:system-services (list %tailscale-service)))
