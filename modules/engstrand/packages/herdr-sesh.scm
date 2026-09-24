(define-module (engstrand packages herdr-sesh)
  #:use-module (engstrand packages assets)
  #:use-module (guix build-system go)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define herdr-sesh-version "0.7.0")

(define %herdr-sesh-vendor
  (package-asset "desktop/configs/herdr/sesh/vendor.tar.gz"))
(define %herdr-sesh-manifest
  (package-asset "desktop/configs/herdr/sesh/manifest.toml" "sesh.toml"))

(define-public herdr-sesh
  (package
    (name "herdr-sesh")
    (version herdr-sesh-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append
             "https://github.com/fullerzz/herdr-plugin-sesh/archive/refs/tags/v"
             version ".tar.gz"))
       (file-name (string-append name "-" version ".tar.gz"))
       (sha256
        (base32 "15wk7fpls5pc3m7lyvh9jc896192r2iw9v0kspq9c06450vibzyp"))))
    (build-system go-build-system)
    (arguments
     (list
      #:import-path "github.com/fullerzz/herdr-plugin-sesh/cmd/herdr-sesh"
      #:unpack-path "github.com/fullerzz/herdr-plugin-sesh"
      #:install-source? #f
      #:tests? #f
      #:phases
      #~(modify-phases %standard-phases
          (add-before 'build 'unpack-vendor
            (lambda* (#:key inputs #:allow-other-keys)
              (let ((source (string-append (getcwd) "/src/"
                                           "github.com/fullerzz/herdr-plugin-sesh")))
                (invoke "tar" "-xzf"
                        #$(file-append %herdr-sesh-vendor "")
                        "-C" source))))
          (add-after 'install 'install-plugin
            (lambda* (#:key outputs #:allow-other-keys)
              (let ((manifest (string-append (assoc-ref outputs "out")
                                             "/herdr-plugin.toml")))
                (copy-file #$(file-append %herdr-sesh-manifest "") manifest)))))))
    (synopsis "Sesh-style workspace picker for Herdr")
    (description "Sesh-style workspace picker and session manager for Herdr.")
    (home-page "https://github.com/fullerzz/herdr-plugin-sesh")
    (license license:expat)))
