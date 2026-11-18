;; Rex, the terminal session server the rexmux client talks to (T194, T207).
;; Upstream publishes preview builds only and no license yet, so this is for
;; private use on this machine. It is a Go binary linked against glibc.
(define-module (engstrand packages rex)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define rex-commit "202b1b3fc0219883112258a8d0f9f95e2d47daa9")

(define-public rex
  (release-binary
   #:name "rex"
   #:version "0.1.0-preview.21"
   #:uri (string-append "https://blob.superlogical.com/download/rex/preview/"
                        rex-commit "/rex-linux-arm64.tar.gz")
   #:hash "1xf3djq30p875rkr2blqc32ggisizlirvfssh432sri6ba8rlrkc"
   #:install-plan '(("rex" "bin/rex"))
   #:dynamic '("bin/rex")
   #:synopsis "Rex terminal session server and CLI"
   #:home-page "https://rex.superlogical.com"
   #:license (license:non-copyleft "https://rex.superlogical.com"
                                   "No license published; private use only.")))
