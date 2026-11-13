(define-module (engstrand features second-brain)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-second-brain))

(define %second-brain-push-service
  (simple-service 'familiar-second-brain-push home-shepherd-service-type
    (list (shepherd-service
           (provision '(second-brain-push))
           (documentation "Push Chrome's bookmarks to the builder's second brain when they change.")
           (modules (cons '(shepherd service timer) %default-modules))
           (start #~(let ((home (getenv "HOME")))
                      (make-timer-constructor
                       (cron-string->calendar-event "41 * * * *")
                       (command (list (string-append home "/.local/bin/second-brain-push") "chrome")
                                #:environment-variables
                                (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                      ":/run/current-system/profile/bin")
                                       (filter (lambda (variable)
                                                 (not (string-prefix? "PATH=" variable)))
                                               (default-environment-variables))))
                       #:log-file (string-append home "/.local/state/second-brain-push.log")
                       #:max-duration 300
                       #:wait-for-termination? #t)))
           (stop #~(make-timer-destructor))
           (actions (list shepherd-trigger-action))))))

(define (feature-familiar-second-brain)
  (feature-package-set
   'familiar-second-brain
   #:home-services (list %second-brain-push-service)))
