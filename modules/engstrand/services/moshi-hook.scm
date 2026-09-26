(define-module (engstrand services moshi-hook)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (%moshi-hook-service))

;; The upstream installer puts the user-managed binary in ~/.local/bin.
;; Moshi's `service install` assumes systemd, so use Guix Home's Shepherd.
(define %moshi-hook-service
  (simple-service 'familiar-moshi-hook home-shepherd-service-type
    (list (shepherd-service
           (provision '(moshi-hook))
           (documentation "Moshi agent hooks and phone gateway.")
           (start #~(lambda ()
                       (use-modules (guix build utils))
                       (let* ((home (getenv "HOME"))
                              (state (string-append home "/.local/state/moshi")))
                         (mkdir-p state)
                         ((make-forkexec-constructor
                           (list (string-append home "/.local/bin/moshi-hook")
                                 "serve")
                           #:log-file (string-append state "/shepherd.log"))))))
           (stop #~(make-kill-destructor))))))
