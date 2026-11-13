(define-module (engstrand packages typst)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define typst-version "0.15.1")

(define-public typst
  (let ((version typst-version))
    (release-binary
     #:name "typst"
     #:version version
     #:uri (string-append "https://github.com/typst/typst/releases/download/v"
                          version "/" "typst-aarch64-unknown-linux-musl.tar.xz")
     #:hash "1vnw4c6a34z38nkq4wddrca1pvwfgzrw4sm62bm60vlh7m5dga2s"
     #:install-plan (list (list "typst-aarch64-unknown-linux-musl/typst" "bin/"))
     #:synopsis "Markup-based typesetting system"
     #:home-page "https://typst.app/"
     #:license license:asl2.0)))
