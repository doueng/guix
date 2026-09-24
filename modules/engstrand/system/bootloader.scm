(define-module (engstrand system bootloader)
  #:use-module (asahi guix build modules)
  #:use-module (asahi guix packages bootloader)
  #:use-module (asahi guix packages linux)
  #:use-module (gnu bootloader)
  #:use-module (gnu bootloader grub)
  #:use-module (gnu bootloader m1n1)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages guile)
  #:use-module (gnu packages linux)
  #:use-module (guix gexp)
  #:use-module (guix modules)
  #:use-module (guix packages)
  #:export (asahi-u-boot-os-prepare
            m1n1-u-boot-grub-bootloader-os-prepare))

(define-public asahi-linux-keyd
  (customize-linux
   #:name "asahi-linux-keyd"
   #:linux asahi-linux
   #:configs '("CONFIG_INPUT_UINPUT=m")
   #:extra-version "keyd"))

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

;; m1n1-u-boot-grub-installer is not exported by (gnu bootloader m1n1).
(define m1n1-u-boot-grub-installer
  (with-extensions (list coreutils bash-minimal gzip guile-sqlite3)
    (with-imported-modules (source-module-closure
                            '((asahi guix build bootloader m1n1))
                            #:select? import-asahi-module?)
      #~(lambda (bootloader efi-dir mount-point)
          (use-modules (asahi guix build bootloader m1n1))
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
   #:packages (list asahi-linux-keyd asahi-m1n1 asahi-u-boot-os-prepare)))
