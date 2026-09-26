(define-module (engstrand services tailscale)
  #:use-module (engstrand packages tailscale)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (%tailscale-service))

(define %tailscale-service
  (simple-service 'familiar-tailscale shepherd-root-service-type
    (list (shepherd-service
           (provision '(tailscaled))
           (requirement '(networking))
           (documentation "Run the Tailscale daemon.")
           (start #~(begin
                      (use-modules (guix build utils))
                      (mkdir-p "/var/run/tailscale")
                      (make-forkexec-constructor
                       (list #$(file-append tailscale "/bin/tailscaled")
                             "--state=/var/lib/tailscale.state")
                       #:log-file "/var/log/tailscaled.log")))
           (stop #~(make-kill-destructor))))))
