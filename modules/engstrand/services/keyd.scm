(define-module (engstrand services keyd)
  #:use-module (gnu packages linux)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (%keyd-service))

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
