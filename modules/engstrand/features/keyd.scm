(define-module (engstrand features keyd)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages assets)
  #:use-module (gnu packages)
  #:use-module (gnu packages linux)
  #:use-module (gnu services)
  #:use-module (gnu services base)
  #:use-module (gnu services linux)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-keyd))

(define %keyd-service
  (simple-service 'familiar-keyd shepherd-root-service-type
    (list (shepherd-service
            (provision '(keyd))
            (requirement '(udev kernel-module-loader))
            (documentation "Personal keyboard layers.")
            (start #~(make-forkexec-constructor
                       (list #$(file-append keyd "/bin/keyd"))
                       #:log-file "/var/log/keyd.log"))
            (stop #~(make-kill-destructor))))))

(define (feature-familiar-keyd)
  (feature-package-set
   'familiar-keyd
   #:system-packages (list (specification->package "keyd"))
   #:system-services
   (list %keyd-service
         (simple-service 'familiar-uinput kernel-module-loader-service-type
                         '("uinput"))
         (simple-service 'familiar-keyd-config etc-service-type
                         `(("keyd/default.conf"
                            ,(package-asset "desktop/keyd.conf")))))))
