(define-module (engstrand system bootloader)
  #:use-module (engstrand asahi boot)
  #:use-module (engstrand asahi kernel)
  #:use-module (engstrand asahi services)
  #:use-module (engstrand packages omarchy-ane)
  #:use-module (gnu bootloader)
  #:use-module (gnu bootloader grub)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages guile)
  #:use-module (gnu packages linux)
  #:use-module (guix gexp)
  #:use-module (guix modules)
  #:use-module (guix packages))

(define-public linux-asahi-ane (omarchy-ane-module linux-asahi))

(define-public asahi-dtbs-ane (dtbs-with-ane linux-asahi))

(define %bootloader-module-dir
  (dirname (canonicalize-path
            (search-path %load-path "engstrand/system/bootloader.scm"))))

(define-public asahi-u-boot-os-prepare
  (package
    (inherit asahi-u-boot)
    (name "asahi-u-boot-os-prepare")
    (source
     (origin
       (inherit (package-source asahi-u-boot))
       (patches
        (append (origin-patches (package-source asahi-u-boot))
                (list (local-file (string-append %bootloader-module-dir
                                                 "/u-boot-xhci-dwc3-os-prepare.patch")))))))))

(define m1n1-u-boot-grub-installer
  (with-extensions (list coreutils bash-minimal gzip guile-sqlite3)
    (with-imported-modules (source-module-closure
                            '((engstrand build m1n1))
                            #:select? import-build-module?)
      #~(lambda (bootloader efi-dir mount-point)
          (use-modules (engstrand build m1n1))
          (setenv "PATH" (string-join
                          (filter string?
                                  (list (getenv "PATH")
                                        (string-append #$bash-minimal "/bin")
                                        (string-append #$coreutils "/bin")
                                        (string-append #$gzip "/bin")))
                          ":"))
          (install-m1n1-u-boot-grub bootloader efi-dir mount-point)))))

(define-public m1n1-u-boot-grub-bootloader-os-prepare
  (efi-bootloader-chain
   grub-efi-removable-bootloader
   #:installer m1n1-u-boot-grub-installer
   #:packages (list asahi-dtbs-ane asahi-m1n1 asahi-u-boot-os-prepare)))
