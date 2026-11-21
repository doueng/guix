(define-module (engstrand packages bombadil)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define bombadil-version "0.8.0")

(define-public bombadil
  (let ((version bombadil-version))
    (release-binary
     #:name "bombadil"
     #:version version
     #:uri (string-append "https://github.com/antithesishq/bombadil/releases/download/v"
                          version "/bombadil-aarch64-linux")
     #:hash "1ls6s8q2lfvjbvywcllkg6d7nq8hyfx200j82xqfr5ljc1my5ica"
     #:file "bombadil"
     #:install-plan '(("bombadil" "bin/"))
     #:synopsis "Property-based testing for web and terminal UIs"
     #:home-page "https://github.com/antithesishq/bombadil"
     #:license license:expat)))
