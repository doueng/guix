(define-module (engstrand features remote-access)
  #:use-module (engstrand features packages)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:use-module (gnu services ssh)
  #:export (feature-familiar-remote-access))

;; Moshi uses ordinary OpenSSH over the Tailscale tunnel (not Tailscale SSH).
;; mosh-server is optional in the app, but makes roaming between networks work.
(define (feature-familiar-remote-access)
  (feature-package-set
   'familiar-remote-access
   #:system-packages (list (specification->package "mosh"))
   #:system-services
   (list (service openssh-service-type
                  (openssh-configuration
                   (permit-root-login #f)
                   (password-authentication? #f)
                   (allow-empty-passwords? #f)
                   (extra-content "AllowUsers engstrand\n"))))))
