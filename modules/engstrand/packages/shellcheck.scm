(define-module (engstrand packages shellcheck)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define shellcheck-version "0.11.0")

;; Guix lags upstream, so this is upstream's aarch64 release; updates bumps it.
(define-public shellcheck
  (let ((version shellcheck-version))
    (release-binary
     #:name "shellcheck"
     #:version version
     #:uri (string-append "https://github.com/koalaman/shellcheck/releases/download/v"
                          version "/" "shellcheck-v" version ".linux.aarch64.tar.xz")
     #:hash "1225q7ksgsk1w8ik1f4xnrpahmxin43479pw7jqrwsyvsb0k3cqj"
     #:install-plan (list (list (string-append "shellcheck-v" version "/shellcheck") "bin/"))
     #:synopsis "Static analysis tool for shell scripts"
     #:home-page "https://www.shellcheck.net/"
     #:license license:gpl3+)))
