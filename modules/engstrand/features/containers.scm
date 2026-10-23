(define-module (engstrand features containers)
  #:use-module (engstrand features packages)
  #:use-module (gnu packages containers)
  #:use-module (gnu services)
  #:use-module (guix gexp)
  #:use-module (gnu services containers)
  #:use-module (gnu system accounts)
  #:export (feature-familiar-containers))

;; Image layers chown files to system uids such as 42, and apt drops to
;; uid 65534, so rootless Podman needs a subordinate id range for the user.
(define %rootless-podman-service
  (service rootless-podman-service-type
           (rootless-podman-configuration
            (subgids (list (subid-range (name "engstrand"))))
            (subuids (list (subid-range (name "engstrand")))))))

;; netavark writes nftables inet tables, which the Asahi kernel lacks, and
;; Guix builds it without the iptables driver. Rootless networking does its
;; own NAT, so container networks work without firewall rules.
(define %containers-conf
  (simple-service 'familiar-containers-conf etc-service-type
    (list `("containers/containers.conf"
            ,(plain-file "containers.conf" "[network]\nfirewall_driver = \"none\"\n")))))

(define (feature-familiar-containers)
  (feature-package-set
   'familiar-containers
   #:home-packages (list podman-compose)
   #:system-services (list %rootless-podman-service %containers-conf)))
