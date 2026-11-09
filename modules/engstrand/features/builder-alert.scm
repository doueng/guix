(define-module (engstrand features builder-alert)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-builder-alert))

;; The remote builder's nightly prebuild runs at 03:00 with nobody watching.
;; Every hour `builder-prebuild alert --notify' reads its status over ssh and
;; sends a desktop notification when it failed, hangs or stopped running, once
;; per problem.  The checkout is mutable and the timer follows it, like the
;; reader; an unreachable builder, as when the laptop is offline, stays quiet.
(define %builder-alert-service
  (simple-service 'familiar-builder-alert home-shepherd-service-type
    (list (shepherd-service
           (provision '(builder-alert))
           (documentation "Notify when the builder's nightly prebuild failed or stopped.")
           (modules (cons '(shepherd service timer) %default-modules))
           (start #~(let ((home (getenv "HOME")))
                      (make-timer-constructor
                       (cron-string->calendar-event "23 * * * *")
                       (command (list (string-append home "/.local/bin/builder-prebuild") "alert" "--notify")
                                #:environment-variables
                                (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                      ":/run/current-system/profile/bin")
                                       (filter (lambda (variable)
                                                 (not (string-prefix? "PATH=" variable)))
                                               (default-environment-variables))))
                       #:log-file (string-append home "/.local/state/builder-alert.log")
                       #:max-duration 120
                       #:wait-for-termination? #t)))
           (stop #~(make-timer-destructor))
           (actions (list shepherd-trigger-action))))))

(define (feature-familiar-builder-alert)
  (feature-package-set
   'familiar-builder-alert
   #:home-services (list %builder-alert-service)))
