;; Build environment for rexmux: Dusk's GPUI dependencies, Guix's Rust 1.96
;; (libghostty-vt needs it) and the repo's Zig 0.17, which builds libghostty-vt.
;; Load with -L modules from the repository root.
(use-modules (gnu) (gnu packages rust) (guix profiles))

(concatenate-manifests
 (list (specifications->manifest
        '("gcc-toolchain" "pkg-config" "cmake" "make" "wayland" "libxkbcommon"
          "fontconfig" "freetype" "vulkan-loader" "mesa" "openssl" "zlib" "dbus"
          "nss-certs" "git" "coreutils"))
       (packages->manifest
        (list rust-1.96 (list rust-1.96 "cargo")
              (@@ (engstrand packages ghostty) zig-0.17)))))
