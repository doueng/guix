(define-module (engstrand packages tailscale)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define-public tailscale
  (package
    (name "tailscale")
    (version "1.102.4")
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://pkgs.tailscale.com/stable/tailscale_"
                           version "_arm64.tgz"))
       (sha256
        (base32 "0gscdi7kd598kk77iq0lpajfvbcrwbznfc8hl2pbn550jajydlcx"))))
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
