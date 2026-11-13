(define-module (engstrand packages ty)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define ty-version "0.0.100")

(define-public ty
  (let ((version ty-version))
    (release-binary
     #:name "ty"
     #:version version
     #:uri (string-append "https://github.com/astral-sh/ty/releases/download/"
                          version "/" "ty-aarch64-unknown-linux-musl.tar.gz")
     #:hash "19mzf5hhj2fzdsy054kmlny747jj36b6c0938glqvk0ng9cnm489"
     #:install-plan (list (list "ty-aarch64-unknown-linux-musl/ty" "bin/"))
     #:synopsis "Python type checker and language server"
     #:home-page "https://github.com/astral-sh/ty"
     #:license license:expat)))
