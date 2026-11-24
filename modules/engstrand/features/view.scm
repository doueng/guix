(define-module (engstrand features view)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-view))

(define %view-service
  (simple-service 'familiar-view home-shepherd-service-type
    (list (shepherd-service
           (provision '(view))
           (documentation "Serve live-reloading Markdown, Org and HTML pages on 127.0.0.1:7341.")
           (respawn? #t)
           (respawn-limit #~'(5 . 60))
           (respawn-delay 2)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.guix-home/profile/bin/bb")
                                (string-append home "/guix/desktop/bin/view") "serve")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/view.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-view)
  (feature-package-set
   'familiar-view
   #:home-services (list %view-service)))
