(define-module (engstrand features system-design-prep)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-system-design-prep))

;; tailscaled keeps the HTTPS :8444 mapping in its state and fronts the app on
;; its own, so this service needs nothing from Tailscale.  Run `make serve' in
;; the checkout once to publish it again after `make unserve'.
(define %system-design-prep-service
  (simple-service 'familiar-system-design-prep home-shepherd-service-type
    (list (shepherd-service
           (provision '(system-design-prep))
           (documentation "The system design prep flashcard PWA, published to the tailnet.")
           (respawn? #t)
           ;; A crash loop, such as the port being taken, stops after a minute
           ;; instead of spinning; `herd enable system-design-prep' rearms it.
           (respawn-limit #~'(5 . 60))
           (respawn-delay 2)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.guix-home/profile/bin/python3")
                                "-m" "http.server" "8790" "--bind" "127.0.0.1"
                                "--directory"
                                (string-append home "/projects/system-design-prep/app"))
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 "PYTHONUNBUFFERED=1"
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/system-design-prep.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-system-design-prep)
  (feature-package-set
   'familiar-system-design-prep
   #:home-services (list %system-design-prep-service)))
