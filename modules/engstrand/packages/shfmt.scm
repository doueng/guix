(define-module (engstrand packages shfmt)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define shfmt-version "3.14.1")

(define-public shfmt
  (let ((version shfmt-version))
    (release-binary
     #:name "shfmt"
     #:version version
     #:uri (string-append "https://github.com/mvdan/sh/releases/download/v"
                          version "/" "shfmt_v" version "_linux_arm64")
     #:hash "12b41jx3mbah0izav8k370w1m4ifccad1gddyx4aiz4imsfv0baz"
     #:file "shfmt"
     #:install-plan (list (list "shfmt" "bin/"))
     #:synopsis "Shell script formatter"
     #:home-page "https://github.com/mvdan/sh"
     #:license license:bsd-3)))
