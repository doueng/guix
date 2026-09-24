(define-module (engstrand packages herdr)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define herdr-version "0.9.0")

(define-public herdr
  (package
    (name "herdr")
    (version herdr-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/herdrdev/herdr/releases/download/v"
                           version "/herdr-linux-aarch64"))
       (file-name "herdr")
       (sha256
        (base32 "1lph2n8h5515kq06ypgny8cx7zr12qzi2rskil9pnhp7nw7v53cw"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("herdr" "bin/herdr"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'make-executable
            (lambda* (#:key outputs #:allow-other-keys)
              (chmod (string-append (assoc-ref outputs "out") "/bin/herdr") #o555))))))
    (synopsis "Terminal workspace multiplexer")
    (description "Herdr terminal multiplexer from the herdrdev/herdr release binaries.")
    (home-page "https://github.com/herdrdev/herdr")
    (license license:asl2.0)))
