(define-module (engstrand features tailscale)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages tailscale)
  #:use-module (gnu packages base)
  #:use-module (gnu packages dns)
  #:use-module (gnu packages linux)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-tailscale %tailscale-service tailscale-one-shot))

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
                             "--state=/var/lib/tailscale.state"
                             ;; HTTPS certificates live here.  Without it serve
                             ;; fails every TLS handshake, because tailscaled
                             ;; derives this only from a state file inside a
                             ;; directory named tailscale.
                             "--statedir=/var/lib/tailscale")
                       ;; The Asahi kernel has nf_tables without its inet
                       ;; families, so use legacy iptables for the firewall.
                       ;; Without resolvconf on PATH, tailscaled overwrites
                       ;; /etc/resolv.conf directly; openresolv then refuses
                       ;; NetworkManager's DNS for every new network.  Tailscale
                       ;; SSH finds the login shell with getent; without it the
                       ;; session is a bare /bin/sh with a Debian PATH.
                       #:environment-variables
                       (cons* "TS_DEBUG_FIREWALL_MODE=iptables"
                              (string-append "PATH=" #$(file-append iptables "/sbin")
                                             ":" #$(file-append openresolv "/sbin")
                                             ":" #$(file-append glibc "/bin")
                                             ":" (or (getenv "PATH") ""))
                              (filter (lambda (variable)
                                        (not (string-prefix? "PATH=" variable)))
                                      (default-environment-variables)))
                       #:log-file "/var/log/tailscaled.log")))
           (stop #~(make-kill-destructor))))))

(define (tailscale-one-shot name documentation . arguments)
  "Return a one-shot Shepherd service NAME that runs `tailscale ARGUMENTS...'
once tailscaled answers, for settings such as `set --ssh' or `serve' that
tailscaled keeps in its state and that this restates at boot and deploy."
  (shepherd-service
   (provision (list name))
   (requirement '(tailscaled))
   (one-shot? #t)
   (documentation documentation)
   (start #~(lambda _
              ;; tailscaled takes a moment to open its socket.
              (let loop ((tries 30))
                (cond ((zero? (system* #$(file-append tailscale "/bin/tailscale")
                               #$@arguments))
                       #t)
                      ((> tries 1) (sleep 1) (loop (- tries 1)))
                      (else #f)))))))

(define (feature-familiar-tailscale)
  (feature-package-set
   'familiar-tailscale
   #:system-packages (list tailscale)
   #:system-services (list %tailscale-service)))
