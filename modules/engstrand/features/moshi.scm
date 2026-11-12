(define-module (engstrand features moshi)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-moshi))

;; The upstream installer puts the user-managed binary in ~/.local/bin.
;; Moshi's `service install` assumes systemd, so use Guix Home's Shepherd.
;; Its server discovery sends GET to every loopback listener.  Ports the
;; kernel hands out for port 0, 32768 and up, are bbh's nREPL servers and
;; other private sockets, never a server to preview, so discovery skips them.
(define %moshi-hook-service
  (simple-service 'familiar-moshi-hook home-shepherd-service-type
    (list (shepherd-service
           (provision '(moshi-hook))
           (documentation "Moshi agent hooks and phone gateway.")
           (start #~(lambda ()
                       (use-modules (guix build utils))
                       (let* ((home (getenv "HOME"))
                              (state (string-append home "/.local/state/moshi"))
                              (moshi-hook (string-append home "/.local/bin/moshi-hook")))
                         (mkdir-p state)
                         (false-if-exception
                          (system* moshi-hook "set" "scan-ports" "1-32767"))
                         ((make-forkexec-constructor
                           (list moshi-hook "serve")
                           #:log-file (string-append state "/shepherd.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-moshi)
  (feature-package-set
   'familiar-moshi
   #:home-services (list %moshi-hook-service)))
