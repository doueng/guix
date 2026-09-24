(define-module (engstrand packages noctalia)
  #:use-module (gnu packages audio)
  #:use-module (gnu packages calendar)
  #:use-module (gnu packages crypto)
  #:use-module (gnu packages cpp)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages fontutils)
  #:use-module (gnu packages freedesktop)
  #:use-module (gnu packages gl)
  #:use-module (gnu packages glib)
  #:use-module (gnu packages gnome)
  #:use-module (gnu packages gtk)
  #:use-module (gnu packages image)
  #:use-module (gnu packages jemalloc)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages markup)
  #:use-module (gnu packages maths)
  #:use-module (gnu packages multiprecision)
  #:use-module (gnu packages pkg-config)
  #:use-module (gnu packages polkit)
  #:use-module (gnu packages pulseaudio)
  #:use-module (gnu packages stb)
  #:use-module (gnu packages xml)
  #:use-module (gnu packages xdisorg)
  #:use-module (guix build-system meson)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define noctalia-version "5.1.0")

(define-public noctalia
  (package
    (name "noctalia")
    (version noctalia-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/noctalia-dev/noctalia/releases/download/v"
                           version "/noctalia-v" version ".tar.gz"))
       (sha256
        (base32 "05h83s88i039jjh5fl86gkr685kjzf1m2y0abwnajar7xv99nnn8"))))
    (build-system meson-build-system)
    (native-inputs (list pkg-config))
    (inputs
     (list cairo curl fontconfig freetype glib harfbuzz jemalloc libical libjxl
           libepoxy libqalculate libsecret libsndfile libsodium libwebp
           libxkbcommon libxml2 librsvg linux-pam md4c nlohmann-json pango
           pipewire polkit sdbus-c++ gmp mpfr
           stb tomlplusplus wayland wayland-protocols wireplumber))
    (arguments
     (list
      #:configure-flags #~'("-Dtests=disabled" "-Djemalloc=enabled"
                             "-Dc_args=-I." "-Dcpp_args=-I.")
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'unpack 'namespace-stb-headers
            (lambda _
              (mkdir "stb")
              (copy-file #$(file-append stb "/stb_image_resize2.h")
                         "stb/stb_image_resize2.h")
              (copy-file #$(file-append stb "/stb_image_write.h")
                         "stb/stb_image_write.h")
              (setenv "C_INCLUDE_PATH"
                      (string-append (getcwd) ":"
                                     #$(file-append gmp "/include") ":"
                                     #$(file-append mpfr "/include") ":"
                                     (or (getenv "C_INCLUDE_PATH") "")))
              (setenv "CPLUS_INCLUDE_PATH"
                      (string-append (getcwd) ":"
                                     #$(file-append gmp "/include") ":"
                                     #$(file-append mpfr "/include") ":"
                                     (or (getenv "CPLUS_INCLUDE_PATH") ""))))))))
    (supported-systems '("aarch64-linux"))
    (synopsis "Wayland desktop shell")
    (description "Noctalia is a configurable Wayland desktop shell with bars,
notifications, a launcher, wallpaper management, lock screen and settings UI.")
    (home-page "https://github.com/noctalia-dev/noctalia")
    (license license:expat)))
