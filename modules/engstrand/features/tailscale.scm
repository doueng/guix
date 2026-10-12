(define-module (engstrand features tailscale)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages tailscale)
  #:use-module (gnu packages linux)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-tailscale))

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
                       ;; The Asahi kernel has nf_tables without its inet
                       ;; families, so use legacy iptables for the firewall.
                       #:environment-variables
                       (cons* "TS_DEBUG_FIREWALL_MODE=iptables"
                              (string-append "PATH=" #$(file-append iptables "/sbin")
                                             ":" (or (getenv "PATH") ""))
                              (filter (lambda (variable)
                                        (not (string-prefix? "PATH=" variable)))
                                      (default-environment-variables)))
                       #:log-file "/var/log/tailscaled.log")))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-tailscale)
  (feature-package-set
   'familiar-tailscale
   #:system-packages (list tailscale)
   #:system-services (list %tailscale-service)))
