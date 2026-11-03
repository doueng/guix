(define-module (engstrand features reader)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-reader))

;; The reader checkout is mutable and the service follows it. `make serve'
;; publishes port 8787 on the tailnet and runs the reader with its suggester,
;; so one service owns both. Stopping kills the whole process group.
(define %reader-service
  (simple-service 'familiar-reader home-shepherd-service-type
    (list (shepherd-service
           (provision '(reader))
           (documentation "The feed reader and its suggester, published to the tailnet.")
           (respawn? #t)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          ;; `kill 0' takes the group down whenever make exits,
                          ;; so a respawn never meets a stale wrangler on 8787.
                          (list (string-append home "/.guix-home/profile/bin/bash")
                                "-c" "make -C \"$0\" serve; kill 0"
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
