(define-module (engstrand packages tree-sitter)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define tree-sitter-version "0.27.2")

(define-public tree-sitter
  (let ((version tree-sitter-version))
    (release-binary
     #:name "tree-sitter"
     #:version version
     #:uri (string-append "https://github.com/tree-sitter/tree-sitter/releases/download/v"
                          version "/" "tree-sitter-linux-arm64.gz")
     #:hash "0cwdv4b1b6zra33jxmdnjcxb0jcsxfv06nm4l9c21pbvzb2cbz1l"
     #:file "tree-sitter"
     #:install-plan (list (list "tree-sitter" "bin/"))
     #:dynamic '("bin/tree-sitter")
     #:synopsis "Tree-sitter parser generator CLI"
     #:home-page "https://tree-sitter.github.io/"
     #:license license:expat)))
