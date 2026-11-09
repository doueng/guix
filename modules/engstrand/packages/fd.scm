(define-module (engstrand packages fd)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define fd-version "10.5.2")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public fd
  (let ((version fd-version))
    (release-binary
     #:name "fd"
     #:version version
     #:uri (string-append "https://github.com/sharkdp/fd/releases/download/v"
                          version "/" "fd-v" version "-aarch64-unknown-linux-musl.tar.gz")
     #:hash "0ylhrwa9gbwsaxxz1w2a1iynrwa2smrnvw52xyc6svjq675m5sk3"
     #:install-plan (list (list (string-append "fd-v" version "-aarch64-unknown-linux-musl/fd") "bin/")
                          (list (string-append "fd-v" version "-aarch64-unknown-linux-musl/fd.1") "share/man/man1/")
                          (list (string-append "fd-v" version "-aarch64-unknown-linux-musl/autocomplete/fd.fish") "share/fish/vendor_completions.d/"))
     #:synopsis "Simple, fast alternative to find"
     #:home-page "https://github.com/sharkdp/fd"
     #:license license:expat)))
