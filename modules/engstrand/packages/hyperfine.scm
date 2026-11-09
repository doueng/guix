(define-module (engstrand packages hyperfine)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define hyperfine-version "2.0.0")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public hyperfine
  (let ((version hyperfine-version))
    (release-binary
     #:name "hyperfine"
     #:version version
     #:uri (string-append "https://github.com/sharkdp/hyperfine/releases/download/v"
                          version "/" "hyperfine-v" version "-aarch64-unknown-linux-musl.tar.gz")
     #:hash "1qdjwpzy795rh9f95y9mp5yad6fwjv7pjffj4hk2svznp0n1qngh"
     #:install-plan (list (list (string-append "hyperfine-v" version "-aarch64-unknown-linux-musl/hyperfine") "bin/")
                          (list (string-append "hyperfine-v" version "-aarch64-unknown-linux-musl/hyperfine.1") "share/man/man1/"))
     #:synopsis "Command-line benchmarking tool"
     #:home-page "https://github.com/sharkdp/hyperfine"
     #:license license:expat)))
