(define-module (engstrand packages fzf)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define fzf-version "0.75.0")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public fzf
  (let ((version fzf-version))
    (release-binary
     #:name "fzf"
     #:version version
     #:uri (string-append "https://github.com/junegunn/fzf/releases/download/v"
                          version "/" "fzf-" version "-linux_arm64.tar.gz")
     #:hash "0nadxcx87c5biqsbaxxbnvmdki5a151bjjq1gqw20mkavfx1f1xx"
     #:install-plan (list (list "fzf" "bin/"))
     #:synopsis "Command-line fuzzy finder"
     #:home-page "https://github.com/junegunn/fzf"
     #:license license:expat)))
