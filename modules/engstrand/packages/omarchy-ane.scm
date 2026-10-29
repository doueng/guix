;; Apple Neural Engine support from omarchy-ane: the DRM accel driver and the
;; T8103 device-tree nodes it binds to.  Both take the kernel they belong to.
(define-module (engstrand packages omarchy-ane)
  #:use-module (gnu packages bootloaders)
  #:use-module (guix build-system copy)
  #:use-module (guix build-system linux-module)
  #:use-module (guix gexp)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:)
  #:export (omarchy-ane-module
            dtbs-with-ane))

(define omarchy-ane-version "0.5.6")

(define omarchy-ane-source
  (origin
    (method git-fetch)
    (uri (git-reference
          (url "https://github.com/joshuaswarren/omarchy-ane")
          (commit (string-append "v" omarchy-ane-version))))
    (file-name (git-file-name "omarchy-ane" omarchy-ane-version))
    (sha256
     (base32 "1snwi6plrvcsys7ml1rh066bwnnv7f95wvmfa8r1fv9gspx9gkbw"))))

(define (omarchy-ane-module linux)
  "ane.ko, the M1-family ANE driver, built for LINUX."
  (package
    (name "omarchy-ane-module")
    (version omarchy-ane-version)
    (source omarchy-ane-source)
    (build-system linux-module-build-system)
    (arguments
     (list #:linux linux
           #:tests? #f
           #:source-directory "ane"
           ;; The Makefile reads the version from git, which a store
           ;; checkout lacks; it becomes /sys/module/ane/version.
           #:make-flags #~(list (string-append "ANE_VERSION=" #$version))))
    (supported-systems '("aarch64-linux"))
    (home-page "https://github.com/joshuaswarren/omarchy-ane")
    (synopsis "Apple Neural Engine DRM accel driver for M1")
    (description "The @code{ane} kernel module exposes the M1 Neural Engine as
@file{/dev/accel/accel0}.")
    (license license:gpl2)))

(define (dtbs-with-ane linux)
  "LINUX's device trees with the ANE, its DARTs and power states added to
every T8103 board tree; Asahi's trees lack them."
  (package
    (name (string-append (package-name linux) "-dtbs-ane"))
    (version (string-append (package-version linux) "-ane" omarchy-ane-version))
    (source omarchy-ane-source)
    (build-system copy-build-system)
    (native-inputs (list dtc))
    (inputs (list linux))
    (arguments
     (list
      #:phases
      #~(modify-phases %standard-phases
          (replace 'install
            (lambda* (#:key inputs #:allow-other-keys)
              (let ((dtbs (string-append #$output "/lib/dtbs"))
                    (overlay "t8103-ane.dtbo"))
                (copy-recursively (search-input-directory inputs "lib/dtbs")
                                  dtbs)
                (invoke "dtc" "-@" "-I" "dts" "-O" "dtb" "-o" overlay
                        "packaging/dt/t8103-ane.dts")
                (for-each
                 (lambda (dtb)
                   (chmod dtb #o644)
                   (invoke "fdtoverlay" "-i" dtb "-o" dtb overlay))
                 (find-files (string-append dtbs "/apple")
                             "^t8103-.*\\.dtb$"))))))))
    (supported-systems '("aarch64-linux"))
    (home-page "https://github.com/joshuaswarren/omarchy-ane")
    (synopsis "Asahi device trees with the M1 Neural Engine")
    (description "Kernel device trees with omarchy-ane's T8103 ANE overlay
applied to each M1 board tree.")
    (license (list license:gpl2+ license:expat))))
