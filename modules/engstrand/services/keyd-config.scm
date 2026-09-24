(define-module (engstrand services keyd-config)
  #:use-module (engstrand home files)
  #:use-module (gnu services)
  #:use-module (gnu services base)
  #:export (%keyd-config-service))

(define %keyd-config-service
  (simple-service 'familiar-keyd-config etc-service-type
    `(("keyd/default.conf" ,%desktop-keyd-config))))
