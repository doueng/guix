;; The Asahi Linux kernel, with its config in kernel.config next to this file.
;; Build recipe and initrd module list from the asahi-guix channel
;; (https://codeberg.org/asahi-guix/channel, b9132769), GPL-3.0-or-later.
(define-module (engstrand asahi kernel)
  #:use-module (gnu packages)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages cpio)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages llvm)
  #:use-module (gnu packages ncurses)
  #:use-module (gnu packages python)
  #:use-module (gnu packages rust)
  #:use-module (gnu packages rust-apps)
  #:use-module (guix gexp)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module (guix utils)
  #:use-module (ice-9 textual-ports)
  #:export (linux-asahi
            %asahi-initrd-modules))

;; Bump by changing these three and building on guix-builder; tags are listed
;; at https://github.com/AsahiLinux/linux/tags.
(define %version "7.2.15")
(define %revision "1")
(define %hash "0w1kb68ki0fcdg7h9x6qqmbgmw2n2yq8wj1hcicp5gb8dipkk9g3")

(define %kernel-config
  (call-with-input-file
      (search-path %load-path "engstrand/asahi/kernel.config")
    get-string-all))

(define %source
  (let ((tag (string-append "asahi-" %version "-" %revision)))
    (origin
      (method git-fetch)
      (uri (git-reference
            (url "https://github.com/AsahiLinux/linux")
            (commit tag)))
      (file-name (git-file-name "linux-source" tag))
      (sha256 (base32 %hash))
      ;; The second patch keeps bindgen's layout assertions, so the build fails
      ;; when a Rust binding's size, alignment or field offsets differ from C,
      ;; and makes the types bindgen gets wrong opaque.  See
      ;; docs/bindgen-schedstats.html.
      (patches
       (list (search-patch "linux-libre-fix-arm64-bin-sh.patch")
             (local-file "patches/linux-asahi-bindgen-layout.patch"))))))

(define linux-asahi
  (let ((base (customize-linux
               #:name "linux-asahi"
               #:linux (package/inherit linux-libre-arm64-generic
                         (version (string-append %version "-" %revision)))
               #:source %source
               #:defconfig "defconfig"
               #:configs %kernel-config
               #:extra-version "asahi")))
    (package
      (inherit base)
      (arguments
       (substitute-keyword-arguments (package-arguments base)
         ((#:phases phases '%standard-phases)
          #~(modify-phases #$phases
              (add-before 'configure 'configure-rust
                (lambda* (#:key inputs #:allow-other-keys)
                  (let ((clang (search-input-file inputs "bin/clang")))
                    (setenv "BINDGEN" (search-input-file inputs "bin/bindgen"))
                    (setenv "CC" clang)
                    (setenv "LIBCLANG_PATH"
                            (string-append (dirname (dirname clang)) "/lib"))
                    (setenv "RUSTC" (search-input-file inputs "bin/rustc"))
                    (setenv "RUST_LIB_SRC"
                            (search-input-directory
                             inputs "lib/rustlib/src/rust/library")))))))))
      (native-inputs
       (modify-inputs (package-native-inputs base)
         (prepend clang-19
                  cpio
                  ncurses
                  python
                  rust
                  `(,rust "rust-src")
                  `(,rust "tools")
                  rust-bindgen-cli
                  zstd)))
      (home-page "https://asahilinux.org")
      (synopsis "Linux for Apple Silicon Macs")
      (description "The Asahi Linux kernel for Apple Silicon Macs, configured
by the defconfig fragment kernel.config."))))

(define %asahi-initrd-modules
  ;; macsmc must load before nvme-apple, which needs its power domains.
  '("macsmc" "macsmc-power" "macsmc-hwmon" "macsmc-reboot" "macsmc-input"
    "nvme-apple"
    "pinctrl-apple-gpio"
    "apple-dart" "dwc3-apple" "gpio-macsmc" "i2c-pasemi-platform"
    "nvmem-apple-efuses" "pcie-apple" "phy-apple-atc" "tps6598x"
    "spi-apple" "spi-hid-apple" "spi-hid-apple-of"
    "apple_nvmem_spmi" "rtc-macsmc" "spmi-apple-controller"
    "apple-dockchannel" "apple-rtkit-helper" "dockchannel-hid"
    "dm-crypt" "hid-apple" "vfat" "nls_iso8859-1" "virtio-gpu"))
