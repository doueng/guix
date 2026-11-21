;; m1n1, U-Boot and the Guix boot logo for the Apple Silicon boot chain.
;; Copied unchanged from the asahi-guix channel
;; (https://codeberg.org/asahi-guix/channel, b9132769, packages/bootloader.scm
;; and packages/rust.scm), GPL-3.0-or-later, so the store paths match what
;; boots today.  Bump a version by editing its version and hash.
(define-module (engstrand asahi boot)
  #:use-module ((guix licenses) #:prefix license:)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bootloaders)
  #:use-module (gnu packages imagemagick)
  #:use-module (gnu packages inkscape)
  #:use-module (gnu packages rust)
  #:use-module (gnu packages tls)
  #:use-module (guix build-system cargo)
  #:use-module (guix build-system copy)
  #:use-module (guix gexp)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module (guix platform)
  #:use-module (guix utils))

(define-public rust-sysroot-for-aarch64-unknown-none-softfloat
  ;; Precompiled core and alloc for the bare-metal target m1n1 uses, built
  ;; from the same sources as rust itself.  This is what rustup's
  ;; rust-std component and Fedora's rust-std-static package provide.
  (package
    (inherit rust-sysroot-for-wasm32-unknown-unknown)
    (name "rust-sysroot-for-aarch64-unknown-none-softfloat")
    (arguments
     (substitute-keyword-arguments
         (package-arguments rust-sysroot-for-wasm32-unknown-unknown)
       ((#:phases phases)
        #~(modify-phases #$phases
            (replace 'configure
              (lambda* (#:key inputs outputs #:allow-other-keys)
                (call-with-output-file "config.toml"
                  (lambda (port)
                    (display (string-append "
[llvm]
[build]
cargo = \"" (search-input-file inputs "/bin/cargo") "\"
rustc = \"" (search-input-file inputs "/bin/rustc") "\"
docs = false
python = \"" (which "python") "\"
vendor = true
submodules = false
target = [\"aarch64-unknown-none-softfloat\"]
[install]
prefix = \"" (assoc-ref outputs "out") "\"
sysconfdir = \"etc\"
[rust]
debug = false
jemalloc = false
channel = \"stable\"
[target." #$(platform-rust-target
                (lookup-platform-by-system (%current-system))) "]
llvm-config = \"" (search-input-file inputs "/bin/llvm-config") "\"
linker = \"" (which "gcc") "\"
cc = \"" (which "gcc") "\"
cxx = \"" (which "g++") "\"
ar = \"" (which "ar") "\"
[target.aarch64-unknown-none-softfloat]
cc = \"" (which "gcc") "\"
ar = \"" (which "ar") "\"
[dist]
") port)))))))))
    (supported-systems (list "aarch64-linux"))))

(define-public asahi-bootlogo
  (package
    (name "asahi-bootlogo")
    (version "0.0.1")
    (source (local-file "files/bootlogo.svg"))
    (build-system copy-build-system)
    (arguments
     (list
      #:phases
      #~(modify-phases %standard-phases
          (replace 'install
            (lambda* (#:key inputs #:allow-other-keys)
              (let ((source (assoc-ref inputs "source"))
                    (target (string-append #$output "/share/asahi-m1n1")))
                (mkdir-p target)
                (for-each (lambda (width)
                            (invoke "convert"
                                    "-background" "none"
                                    "-resize" (format #f "~ax~a!" width width)
                                    source
                                    (format #f "~a/bootlogo_~a.png" target width)))
                          (list 48 128 256))))))))
    (home-page "https://guix.gnu.org/")
    (native-inputs (list imagemagick inkscape))
    (synopsis "Asahi Guix boot logo pacakge")
    (description "The package providing the Asahi Guix boot logo.")
    (license license:expat)))

(define-public asahi-m1n1
  (package
    (name "asahi-m1n1")
    (version "2.0.0")
    (source
     (origin
       (method git-fetch)
       (uri (git-reference
             (url "https://github.com/AsahiLinux/m1n1")
             (commit (string-append "v" version))))
       (file-name (git-file-name name version))
       (sha256
        (base32 "1n8ds6v5h1nhlm0i0p68yz04q3g5m1js65i613xvcx9cadmi3s47"))))
    (build-system cargo-build-system)
    (supported-systems (list "aarch64-linux"))
    (arguments
     (list
      #:install-source? #f
      #:tests? #f
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'unpack 'prepare-rust-source
            (lambda _
              ;; Use the vendored fatfs checkout instead of its git source,
              ;; so that Cargo can resolve offline.
              (substitute* "rust/Cargo.toml"
                (("fatfs = \\{ git = [^,]+, rev = [^,]+, ")
                 (string-append "fatfs = { path = "
                                "\"../guix-vendor/rust-fatfs-"
                                "0.4.0.4eccb50-checkout\",")))))
          (add-after 'unpack 'bootlogo
            (lambda* (#:key inputs #:allow-other-keys)
              (let ((bootlogo (assoc-ref inputs "asahi-bootlogo")))
                (when bootlogo
                  (let ((source (string-append bootlogo "/share/asahi-m1n1")))
                    (chdir "data")
                    (for-each
                     (lambda (width)
                       (let ((source (format #f "~a/bootlogo_~a.png" source width))
                             (target (format #f "bootlogo_~a.png" width)))
                         (when (file-exists? source)
                           (delete-file target)
                           (copy-file source target))))
                     (list 48 128 256))
                    ;; m1n1 >= 1.9 uses ImageMagick 7's "magick"; Guix
                    ;; currently ships ImageMagick 6, where the command is
                    ;; called "convert".
                    (substitute* "makelogo.sh"
                      (("magick ") "convert "))
                    (invoke "sh" "makelogo.sh")
                    (chdir ".."))))))
          (add-after 'patch-cargo-checksums 'configure-cargo
            (lambda _
              ;; Guix's vendored crates carry no checksums, so regenerate
              ;; m1n1's lock file against them.  The Makefile needs it.
              (delete-file "rust/Cargo.lock")
              (invoke "cargo" "generate-lockfile" "--offline"
                      "--manifest-path" "rust/Cargo.toml")
              ;; Use the precompiled core and alloc for the target.  With
              ;; --target, RUSTFLAGS does not apply to build scripts, so
              ;; they still use the host sysroot.
              (setenv "RUSTFLAGS"
                      (string-append
                       "--sysroot "
                       #$(this-package-native-input
                          "rust-sysroot-for-aarch64-unknown-none-softfloat")))))
          (replace 'build
            (lambda _
              (invoke "make" "-j" (number->string (parallel-job-count))
                      "RELEASE=1")))
          (replace 'install
            (lambda* (#:key outputs #:allow-other-keys)
              (let ((dir (string-append (assoc-ref outputs "out") "/libexec/")))
                (mkdir-p dir)
                (copy-file "build/m1n1.bin" (string-append dir "m1n1.bin"))))))))
    (native-inputs
     (append (list asahi-bootlogo
                   imagemagick
                   rust-sysroot-for-aarch64-unknown-none-softfloat)
             (cargo-inputs 'm1n1 #:module '(engstrand asahi crates))))
    (home-page "https://github.com/AsahiLinux/m1n1")
    (synopsis "Boot loader and experimentation playground for Apple Silicon")
    (description "m1n1 is the bootloader developed by the Asahi Linux project to bridge
the Apple (XNU) boot ecosystem to the Linux boot ecosystem.")
    (license (list license:gpl2+       ; m1n1 itself
                   license:bsd-2       ; libfdt
                   license:expat       ; minlzma
                   license:zlib        ; tinf
                   license:bsd-3       ; arm-trusted-firmware
                   license:cc0         ; dlmalloc, PDCLib
                   license:silofl1.1)))) ; Source Code Pro

(define-public asahi-u-boot
  (let ((base (make-u-boot-package "apple_m1" "aarch64-linux-gnu")))
    (package/inherit base
      (version "2026.07-3")
      (source
       (origin
         (method git-fetch)
         (uri (git-reference
               (url "https://github.com/AsahiLinux/u-boot")
               (commit (string-append "asahi-v" version))))
         (file-name (git-file-name (package-name base) version))
         (sha256
          (base32 "1zv46w972qsvxjvjd74l6m6gbfqycgb5mhrazxqdshcld4k67lgf"))))
      (arguments
       (substitute-keyword-arguments (package-arguments base)
         ((#:phases phases '%standard-phases)
          #~(modify-phases #$phases
              (delete 'disable-tools-libcrypto)))))
      (native-inputs
       (modify-inputs (package-native-inputs base)
         (prepend libressl))))))
