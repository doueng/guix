(define-module (engstrand packages jjui)
  #:use-module (gnu packages compression)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define jjui-version "0.10.10")

(define-public jjui
  (package
    (name "jjui")
    (version jjui-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/idursun/jjui/releases/download/v"
                           version "/jjui-" version "-linux-arm64.zip"))
       (sha256
        (base32 "0p7g2b3sdi43r0k48a386v8qh66i48mkrrzais9d0bfkfc27fxqy"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (native-inputs (list unzip))
    (arguments
     (list #:install-plan
           #~'(("jjui-0.10.10-linux-arm64" "bin/jjui"))))
    (synopsis "Terminal user interface for Jujutsu")
    (description "A terminal user interface for the Jujutsu version control system.")
    (home-page "https://github.com/idursun/jjui")
    (license license:expat)))
