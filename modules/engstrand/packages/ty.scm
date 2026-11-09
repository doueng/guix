(define-module (engstrand packages ty)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define ty-version "0.0.99")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public ty
  (let ((version ty-version))
    (release-binary
     #:name "ty"
     #:version version
     #:uri (string-append "https://github.com/astral-sh/ty/releases/download/"
                          version "/" "ty-aarch64-unknown-linux-musl.tar.gz")
     #:hash "07hraal292mhml1qjqjqm0m3amasdzgcf3pk6zs18jckbr191sqs"
     #:install-plan (list (list "ty-aarch64-unknown-linux-musl/ty" "bin/"))
     #:synopsis "Python type checker and language server"
     #:home-page "https://github.com/astral-sh/ty"
     #:license license:expat)))
