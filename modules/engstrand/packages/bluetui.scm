(define-module (engstrand packages bluetui)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define bluetui-version "0.8.1")

(define-public bluetui
  (package
    (name "bluetui")
    (version bluetui-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/pythops/bluetui/releases/download/v"
                           version "/bluetui-aarch64-linux-musl"))
       (file-name "bluetui")
       (sha256
        (base32 "0kr8y6hs09gn3p20cxm62lg609xxnhmwc9k9byh78lmbypdv39b6"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("bluetui" "bin/bluetui"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'make-executable
            (lambda* (#:key outputs #:allow-other-keys)
              (chmod (string-append (assoc-ref outputs "out") "/bin/bluetui") #o555))))))
    (synopsis "Terminal user interface for managing Bluetooth devices")
    (description "Bluetui manages BlueZ adapters and Bluetooth devices from a terminal.")
    (home-page "https://github.com/pythops/bluetui")
    (license license:gpl3)))
