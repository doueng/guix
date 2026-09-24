(define-module (engstrand services home-files)
  #:use-module (engstrand home files)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:export (%home-files-service))

(define %home-files-service
  (simple-service 'familiar-files home-files-service-type
    %desktop-home-files))
