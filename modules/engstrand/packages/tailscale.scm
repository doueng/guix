(define-module (engstrand packages tailscale)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define-public tailscale
  (package
    (name "tailscale")
    (version "1.104.5")
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://pkgs.tailscale.com/stable/tailscale_"
                           version "_arm64.tgz"))
       (sha256
        (base32 "0v1v9630hjbs16hw3y06w0shbsij5b73ghmj0kjs28shwbkb0079"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan
      #~'(("tailscale" "bin/tailscale")
          ("tailscaled" "bin/tailscaled"))))
    (synopsis "Mesh VPN based on WireGuard")
    (description
     "Tailscale creates a secure mesh VPN using WireGuard, NAT traversal, and
an identity-based access control system.")
    (home-page "https://tailscale.com/")
    (license license:bsd-3)))
