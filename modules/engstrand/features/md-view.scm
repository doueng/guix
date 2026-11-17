(define-module (engstrand features md-view)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-md-view))

(define %md-view-service
  (simple-service 'familiar-md-view home-shepherd-service-type
    (list (shepherd-service
           (provision '(md-view))
           (documentation "Serve live-reloading Markdown and Org pages on 127.0.0.1:7341.")
           (respawn? #t)
           (respawn-limit #~'(5 . 60))
           (respawn-delay 2)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.guix-home/profile/bin/bb")
                                (string-append home "/guix/desktop/bin/md-view") "serve")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/md-view.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-md-view)
  (feature-package-set
   'familiar-md-view
   #:home-services (list %md-view-service)))
