(define-module (engstrand packages herdr-tiny-fingers)
  #:use-module (engstrand packages assets)
  #:use-module (guix build-system cargo)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define herdr-tiny-fingers-version "0.1.0")

(define %herdr-tiny-fingers-manifest
  (package-asset "desktop/configs/herdr/tiny-fingers/manifest.toml"
                 "tiny-fingers.toml"))

(define-public herdr-tiny-fingers
  (package
    (name "herdr-tiny-fingers")
    (version herdr-tiny-fingers-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append
             "https://github.com/hotchpotch/herdr-tiny-fingers/archive/"
             "2270f872d22297806a92f72ddb76b10f5983fce0.tar.gz"))
       (file-name (string-append name "-" version ".tar.gz"))
       (sha256
        (base32
         "099bz2aw419rkbnliwvzpj48156j35igf7p4jbdrmc230j8p8iim"))))
    (build-system cargo-build-system)
    (inputs (cargo-inputs 'herdr-tiny-fingers
                          #:module '(engstrand packages rust-crates)))
    (arguments
     (list
      #:tests? #f
      #:install-source? #f
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'install 'install-plugin
            (lambda* (#:key outputs #:allow-other-keys)
              (let ((manifest (string-append (assoc-ref outputs "out")
                                             "/herdr-plugin.toml")))
                (copy-file #$(file-append %herdr-tiny-fingers-manifest "") manifest)))))))
    (synopsis "tmux-fingers style copy hints for Herdr")
    (description "Visible-screen copy hints plugin for Herdr.")
    (home-page "https://github.com/hotchpotch/herdr-tiny-fingers")
    (license license:expat)))
