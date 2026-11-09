(define-module (engstrand packages fish)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define fish-version "4.9.3")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public fish
  (let ((version fish-version))
    (release-binary
     #:name "fish"
     #:version version
     #:uri (string-append "https://github.com/fish-shell/fish-shell/releases/download/"
                          version "/" "fish-" version "-linux-aarch64.tar.xz")
     #:hash "1z29z8zvrmbvwrj5sf36hkxd3x3zm4d62km80fvn933j240ryd2p"
     #:install-plan (list (list "fish" "bin/"))
     #:synopsis "Friendly interactive shell"
     #:home-page "https://fishshell.com/"
     #:license license:gpl2)))
