(define-module (engstrand features bbh-web)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-bbh-web))

;; The bbh checkout is mutable and the services follow it, as the shell does.

;; The super-agent controller: the store, the command socket, the dispatcher
;; that feeds bbh mailboxes, and the website.  It publishes the website with
;; `tailscale serve', which the operator setting in tailscaled lets this user
;; do without root.
(define %bbh-super-service
  (simple-service 'familiar-bbh-super home-shepherd-service-type
    (list (shepherd-service
           (provision '(bbh-super))
           (documentation "The bbh super-agent controller, its SQLite store and the website.")
           (respawn? #t)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.local/bin/bbh") "super" "serve")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/bbh-super.log"))))))
           (stop #~(make-kill-destructor))))))

;; The super-agent's surfaces: one terminal Emacs daemon holding a buffer per
;; Worker, the Router and the Inbox. The controller opens them through
;; emacsclient -s bbh-super, and `bbh inbox' attaches a terminal frame.
(define %bbh-emacs-service
  (simple-service 'familiar-bbh-emacs home-shepherd-service-type
    (list (shepherd-service
           (provision '(bbh-emacs))
           (documentation "The Emacs daemon bbh-super that shows the super-agent's sessions.")
           (respawn? #t)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.local/bin/emacs") "--fg-daemon=bbh-super")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/bbh-emacs.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-bbh-web)
  (feature-package-set
   'familiar-bbh-web
   #:home-services (list %bbh-super-service %bbh-emacs-service)))
