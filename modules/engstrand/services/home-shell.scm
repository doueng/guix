(define-module (engstrand services home-shell)
  #:use-module (gnu home services shells)
  #:use-module (gnu services)
  #:export (%home-shell-service))

(define %home-shell-service (service home-bash-service-type))
