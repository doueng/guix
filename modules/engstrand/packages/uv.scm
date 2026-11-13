(define-module (engstrand packages uv)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define uv-version "0.13.7")

(define-public uv
  (let ((version uv-version))
    (release-binary
     #:name "uv"
     #:version version
     #:uri (string-append "https://github.com/astral-sh/uv/releases/download/"
                          version "/" "uv-aarch64-unknown-linux-musl.tar.gz")
     #:hash "0ify4dlzpydd8rp0pgm3y52baa081128qhdn1cjyih64l1vc0gid"
     #:install-plan (list (list "uv-aarch64-unknown-linux-musl/uv" "bin/")
                          (list "uv-aarch64-unknown-linux-musl/uvx" "bin/"))
     #:synopsis "Python package and project manager"
     #:home-page "https://github.com/astral-sh/uv"
     #:license license:expat)))
