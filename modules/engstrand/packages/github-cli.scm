(define-module (engstrand packages github-cli)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define github-cli-version "2.83.2")

(define-public github-cli
  (package
    (name "github-cli")
    (version github-cli-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/cli/cli/releases/download/v"
                           version "/gh_" version "_linux_arm64.tar.gz"))
       (sha256
        (base32 "13m3fnx0zqiz40vcivsd07nma8q65b4xvjlnd7ij91gizjhc185i"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list #:install-plan
           #~'(("bin/gh" "bin/gh")
               ("share" "share"))))
    (synopsis "GitHub's official command-line tool")
    (description "GitHub's official command-line tool.")
    (home-page "https://cli.github.com/")
    (license license:expat)))
