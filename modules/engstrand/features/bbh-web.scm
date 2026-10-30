(define-module (engstrand features bbh-web)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-bbh-web))

;; The bbh checkout is mutable and the service follows it, as the shell does.
;; The command publishes itself with `tailscale serve', which the operator
;; setting in tailscaled lets this user do without root.
(define %bbh-web-service
  (simple-service 'familiar-bbh-web home-shepherd-service-type
    (list (shepherd-service
           (provision '(bbh-web))
           (documentation "The website for bbh sessions, published to the tailnet.")
           (respawn? #t)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.local/bin/bbh") "web")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/bbh-web.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-bbh-web)
  (feature-package-set
   'familiar-bbh-web
   #:home-services (list %bbh-web-service)))
