(define-module (engstrand packages bombadil)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define bombadil-version "0.7.10")

;; Not in the home profile: `bb test:bombadil' builds it on demand (T174).
(define-public bombadil
  (let ((version bombadil-version))
    (release-binary
     #:name "bombadil"
     #:version version
     #:uri (string-append "https://github.com/antithesishq/bombadil/releases/download/v"
                          version "/bombadil-aarch64-linux")
     #:hash "0wwya04ib6rkkv28ca8v3bspg0k729rk2yz3zq6akaw7qach1vk7"
     #:file "bombadil"
     #:install-plan '(("bombadil" "bin/"))
     #:synopsis "Property-based testing for web and terminal UIs"
     #:home-page "https://github.com/antithesishq/bombadil"
     #:license license:expat)))
