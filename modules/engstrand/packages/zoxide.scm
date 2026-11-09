(define-module (engstrand packages zoxide)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define zoxide-version "0.10.0")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public zoxide
  (let ((version zoxide-version))
    (release-binary
     #:name "zoxide"
     #:version version
     #:uri (string-append "https://github.com/ajeetdsouza/zoxide/releases/download/v"
                          version "/" "zoxide-" version "-aarch64-unknown-linux-musl.tar.gz")
     #:hash "0hgn7q1wjva1rlxa9glkwi1hwjfqvhffmvby8vp3vmlqc9fnrwgi"
     #:install-plan (list (list "zoxide" "bin/")
                          (list "man/man1" "share/man/man1")
                          (list "completions/zoxide.fish" "share/fish/vendor_completions.d/"))
     #:synopsis "Smarter cd command"
     #:home-page "https://github.com/ajeetdsouza/zoxide"
     #:license license:expat)))
