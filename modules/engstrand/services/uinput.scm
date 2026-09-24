(define-module (engstrand services uinput)
  #:use-module (gnu services)
  #:use-module (gnu services linux)
  #:export (%uinput-service))

(define %uinput-service
  (simple-service 'familiar-uinput kernel-module-loader-service-type '("uinput")))
