(define-module (engstrand packages jujutsu)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define jujutsu-version "0.47.1")

(define-public jujutsu
  (let ((version jujutsu-version))
    (release-binary
     #:name "jujutsu"
     #:version version
     #:uri (string-append "https://github.com/jj-vcs/jj/releases/download/v"
                          version "/" "jj-v" version "-aarch64-unknown-linux-musl.tar.gz")
     #:hash "1k8vzg0n24558aixjm6glp7ary24ms3hppyym7hap3fyfqahwc0g"
     #:install-plan (list (list "jj" "bin/"))
     #:synopsis "Git-compatible version control system"
     #:home-page "https://github.com/jj-vcs/jj"
     #:license license:asl2.0)))
