(define-module (engstrand features reader)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-reader))

;; The reader checkout is mutable and the service follows it.  It runs the
;; reader on loopback port 8787 with its suggester, the recipe of `make serve'
;; without its `tailscale serve' lines.  tailscaled keeps the serve mapping in
;; its state and fronts the reader over HTTPS on its own, so this service needs
;; nothing from Tailscale and the order they start in does not matter.  Run
;; `make serve' in the checkout once to publish it again after `make unserve'.
;; Stopping kills the whole process group.
(define %reader-service
  (simple-service 'familiar-reader home-shepherd-service-type
    (list (shepherd-service
           (provision '(reader))
           (documentation "The feed reader and its suggester, published to the tailnet.")
           (respawn? #t)
           ;; A crash loop, such as a broken checkout, stops after a minute
           ;; instead of spinning; `herd enable reader' rearms it.
           (respawn-limit #~'(5 . 60))
           (respawn-delay 2)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          ;; `kill 0' takes the group down whenever npm exits,
                          ;; so a respawn never meets a stale wrangler on 8787.
                          (list (string-append home "/.guix-home/profile/bin/bash")
                                "-c" (string-append
                                       "cd \"$0\" && { make suggester & "
                                       "npm run dev -- --port 8787; }; kill 0")
                                (string-append home "/projects/langchain/reader"))
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.config/guix/current/bin"
                                                ":" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/reader.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-reader)
  (feature-package-set
   'familiar-reader
   #:home-services (list %reader-service)))
