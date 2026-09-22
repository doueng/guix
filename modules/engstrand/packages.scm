(define-module (engstrand packages)
  #:use-module (gnu packages audio)
  #:use-module (gnu packages base)
  #:use-module (gnu packages calendar)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages crypto)
  #:use-module (gnu packages cpp)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages elf)
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
  #:use-module (gnu packages ncurses)
  #:use-module (gnu packages pkg-config)
  #:use-module (gnu packages polkit)
  #:use-module (gnu packages pulseaudio)
  #:use-module (gnu packages regex)
  #:use-module (gnu packages xml)
  #:use-module (gnu packages xdisorg)
  #:use-module (gnu packages stb)
  #:use-module (gnu packages zig)
  #:use-module (guix build-system cargo)
  #:use-module (guix build-system copy)
  #:use-module (guix build-system gnu)
  #:use-module (guix build-system go)
  #:use-module (guix build-system meson)
  #:use-module (guix download)
  #:use-module (engstrand herdr-tiny-crates)
  #:use-module (guix git-download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module (guix utils)
  #:use-module ((guix licenses) #:prefix license:)
  #:export (babashka noctalia github-cli pi-coding-agent herdr herdr-sesh
            herdr-tiny-fingers jjui ghostty))

(define babashka-version "1.13.223")
(define noctalia-version "5.1.0")
(define github-cli-version "2.83.2")
(define pi-version "0.85.1")
(define herdr-version "0.9.0")
(define jjui-version "0.10.10")
(define herdr-sesh-version "0.7.0")
(define herdr-tiny-fingers-version "0.1.0")

;; The collection package pulls a large Go dependency tree and currently
;; fails in goresctrl's aarch64 tests.  GitHub publishes the same CLI as a
;; signed release binary, which is the appropriate small native package here.
;; Guix's pinned channel does not provide Babashka yet.  Use the upstream
;; native aarch64 release so the .bb scripts and #!/usr/bin/env bb helpers
;; work without a Clojure/JVM dependency tree.
(define-public babashka
  (package
    (name "babashka")
    (version babashka-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/babashka/babashka/releases/download/v"
                           version "/babashka-" version "-linux-aarch64-static.tar.gz"))
       (sha256
        (base32 "0qasmgb9zmvjz9ib5dxx62ak0hyxh8f0q2b548pk9w2vs18n0b05"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("bb" "bin/bb"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'patch-interpreter-and-rpath
            (lambda _
              (invoke #$(file-append patchelf "/bin/patchelf")
                      "--set-interpreter"
                      #$(file-append glibc "/lib/ld-linux-aarch64.so.1")
                      "--set-rpath" #$(file-append glibc "/lib")
                      (string-append #$output "/bin/bb")))))))
    (synopsis "Native Clojure scripting runtime")
    (description "Babashka, a fast native Clojure scripting runtime.")
    (home-page "https://babashka.org/")
    (license license:epl1.0)))

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

(define-public github-cli
  (package
    (name "github-cli")
    (version github-cli-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/cli/cli/releases/download/v"
                           version "/gh_" version "_linux_arm64.tar.gz"))
       (sha256
        (base32 "13m3fnx0zqiz40vcivsd07nma8q65b4xvjlnd7ij91gizjhc185i"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list #:install-plan
           #~'(("bin/gh" "bin/gh")
               ("share" "share"))))
    (synopsis "GitHub's official command-line tool")
    (description "GitHub's official command-line tool.")
    (home-page "https://cli.github.com/")
    (license license:expat)))

(define-public pi-coding-agent
  (package
    (name "pi-coding-agent")
    (version pi-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/earendil-works/pi/releases/download/v"
                           version "/pi-linux-arm64.tar.gz"))
       (sha256
        (base32 "1m74x8qcb34h6x0dwi7vx6r7ghv2p6034pw10aqz7r2yi2p20b84"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("." "share/pi"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'patch-and-wrap
            (lambda _
              (let* ((output #$output)
                     (loader #$(file-append glibc "/lib/ld-linux-aarch64.so.1"))
                     (rpath #$(file-append glibc "/lib"))
                     (patchelf-bin #$(file-append patchelf "/bin/patchelf"))
                     (real (string-append output "/share/pi/pi"))
                     (bin-dir (string-append output "/bin"))
                     (wrapper (string-append bin-dir "/pi")))
                (for-each
                 (lambda (file)
                   (when (and (elf-file? file)
                              (string-suffix? "/pi/pi" file))
                     ;; patchelf --set-rpath corrupts this large Bun binary.
                     ;; Supply its glibc directory through the wrapper instead.
                     (invoke patchelf-bin
                             "--set-interpreter" loader file)))
                 (find-files output))
                (mkdir-p bin-dir)
                (call-with-output-file wrapper
                  (lambda (port)
                    (format port
                            "#!/bin/sh\nexport PI_SKIP_VERSION_CHECK=1\nexport LD_LIBRARY_PATH=~a${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\nexec ~a \"$@\"\n"
                            rpath
                            real)))
                (chmod wrapper #o555)
                (patch-shebang wrapper)))))))
    (synopsis "Minimal terminal coding harness")
    (description "Pi coding agent from the earendil-works/pi release binaries.")
    (home-page "https://github.com/earendil-works/pi")
    (license license:expat)))

(define-public herdr
  (package
    (name "herdr")
    (version herdr-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/herdrdev/herdr/releases/download/v"
                           version "/herdr-linux-aarch64"))
       (file-name "herdr")
       (sha256
        (base32 "1lph2n8h5515kq06ypgny8cx7zr12qzi2rskil9pnhp7nw7v53cw"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("herdr" "bin/herdr"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'make-executable
            (lambda* (#:key outputs #:allow-other-keys)
              (chmod (string-append (assoc-ref outputs "out") "/bin/herdr") #o555))))))
    (synopsis "Terminal workspace multiplexer")
    (description "Herdr terminal multiplexer from the herdrdev/herdr release binaries.")
    (home-page "https://github.com/herdrdev/herdr")
    (license license:asl2.0)))
(define %herdr-module-dir
  (dirname (canonicalize-path
            (search-path %load-path "engstrand/packages.scm"))))
(define %repo-dir
  (dirname (dirname %herdr-module-dir)))
(define %herdr-sesh-vendor
  (local-file (string-append %repo-dir "/desktop/shared/herdr/sesh/vendor.tar.gz")))
(define %herdr-sesh-manifest
  (local-file (string-append %herdr-module-dir "/herdr-sesh-plugin.toml")))
(define %herdr-tiny-fingers-manifest
  (local-file (string-append %herdr-module-dir "/herdr-tiny-fingers-plugin.toml")))

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
    (inputs herdr-tiny-fingers-crate-inputs)
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

(define-public jjui
  (package
    (name "jjui")
    (version jjui-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/idursun/jjui/releases/download/v"
                           version "/jjui-" version "-linux-arm64.zip"))
       (sha256
        (base32 "0p7g2b3sdi43r0k48a386v8qh66i48mkrrzais9d0bfkfc27fxqy"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (native-inputs (list unzip))
    (arguments
     (list #:install-plan
           #~'(("jjui-0.10.10-linux-arm64" "bin/jjui"))))
    (synopsis "Terminal user interface for Jujutsu")
    (description "A terminal user interface for the Jujutsu version control system.")
    (home-page "https://github.com/idursun/jjui")
    (license license:expat)))

(define breakpad-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/breakpad-b99f444ba5f6b98cac261cbb391d8766b34a5918.tar.gz")
    (sha256 (base32 "1nbadlml3r982bz1wyp17w33hngzkb07f47nrrk0g68s7na9ijkc"))))
(define dearbindings-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/DearBindings_v0.17_ImGui_v1.92.5-docking.tar.gz")
    (sha256 (base32 "18xsf0zisr9q8xqa8g3kh4qkqb9rrikkvqiyhmwzc9h9w00cbzlb"))))
(define fontconfig-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/fontconfig-2.14.2.tar.gz")
    (sha256 (base32 "0mcarq6v9k7k9a8is23vq9as0niv0hbagwdabknaq6472n9dv8iv"))))
(define freetype-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/freetype-1220b81f6ecfb3fd222f76cf9106fecfa6554ab07ec7fdc4124b9bb063ae2adf969d.tar.gz")
    (sha256 (base32 "035r5bypzapa1x7za7lpvpkz58fxynz4anqzbk8705hmspsh2wj2"))))
(define gettext-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/gettext-0.24.tar.gz")
    (sha256 (base32 "1dqq2ln01mfwr4gblvy0cyvarbqnv09ml5sdhksdlw1xb4ym0669"))))
(define ghostty-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/ghostty-themes-release-20260216-151611-fc73ce3.tgz")
    (sha256 (base32 "1zd81af7hjnyfq3dypl3xg9bd5mkh2r01m89jsv4m08cdaw0n80l"))))
(define glslang-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/glslang-12201278a1a05c0ce0b6eb6026c65cd3e9247aa041b1c260324bf29cee559dd23ba1.tar.gz")
    (sha256 (base32 "1dcpm70fhxk07vk37f5l0hb9gxfv6pjgbqskk8dfbcwwa2xyv8hl"))))
(define gobject-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/gobject-2025-11-08-23-1.tar.zst")
    (sha256 (base32 "0j0csvsyvp0193mpkdp25s14kargppmdyslbhi5qw788y0347gfr"))))
(define gtk4-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/gtk4-layer-shell-1.1.0.tar.gz")
    (sha256 (base32 "12396gx723ybgq1xp9i02257hsmzqhb5z9b39xdyypha4s0l4a4q"))))
(define harfbuzz-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/harfbuzz-11.0.0.tar.xz")
    (sha256 (base32 "16rb7aazy36pj3xrjy149dd90j9yv7q5jnqx5kz2air1zsx52qzi"))))
(define highway-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/highway-66486a10623fa0d72fe91260f96c892e41aceb06.tar.gz")
    (sha256 (base32 "04m21b46h6c4x099r9qb720ql9llpzz8yq3k94i8zq7l7s4zim47"))))
(define jetbrainsmono-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/JetBrainsMono-2.304.tar.gz")
    (sha256 (base32 "1i2w213919avi0apgbw720wqy0z46a89bwv3b65hkbc2icg6jyn5"))))
(define libpng-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/libpng-1220aa013f0c83da3fb64ea6d327f9173fa008d10e28bc9349eac3463457723b1c66.tar.gz")
    (sha256 (base32 "0fm0y7543w2gx5sz3zg9i46x1am51c77a554r0zqwpphdjs9bk7y"))))
(define libxev-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/libxev-34fa50878aec6e5fa8f532867001ab3c36fae23e.tar.gz")
    (sha256 (base32 "1mvx91wn7499xfx76fxijq4x66x1g5yk4cpr52hii9g4jrmyl0v0"))))
(define libxml2-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/libxml2-2.11.5.tar.gz")
    (sha256 (base32 "05b2kbccbkb5pkizwx2s170lcqvaj7iqjr5injsl5sry5sg0aa3c"))))
(define nerdfontssymbolsonly-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/NerdFontsSymbolsOnly-3.4.0.tar.gz")
    (sha256 (base32 "010d7gkv359qg555d89i4hhgb56c8f69kw5jsx4f5gflaswx2r0i"))))
(define oniguruma-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/oniguruma-1220c15e72eadd0d9085a8af134904d9a0f5dfcbed5f606ad60edc60ebeccd9706bb.tar.gz")
    (sha256 (base32 "187jk4fxdkzc0wrcx4kdy4v6p1snwmv8r97i1d68yi3q5qha26h0"))))
(define pixels-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/pixels-12207ff340169c7d40c570b4b6a97db614fe47e0d83b5801a932dcd44917424c8806.tar.gz")
    (sha256 (base32 "06pi3f3lhyxfzczhwrc2b4n0jhhzydbz96qlpw12a24is0b3ps2m"))))
(define plasma-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/plasma_wayland_protocols-12207e0851c12acdeee0991e893e0132fc87bb763969a585dc16ecca33e88334c566.tar.gz")
    (sha256 (base32 "0hgl1p173pxs50z1p6mjjzcqssn44aq0ip166k56p3nd98hvln2w"))))
(define sentry-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/sentry-1220446be831adcca918167647c06c7b825849fa3fba5f22da394667974537a9c77e.tar.gz")
    (sha256 (base32 "1pqqqcin8nw398rvn187dfqlab4vikdssiry14qqs6nnr1y4kiia"))))
(define spirv-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/spirv_cross-1220fb3b5586e8be67bc3feb34cbe749cf42a60d628d2953632c2f8141302748c8da.tar.gz")
    (sha256 (base32 "1qspcsx56v0mddarb6f05i748wsl2ln3d8863ydsczsyqk7nyaxm"))))
(define utfcpp-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/utfcpp-1220d4d18426ca72fc2b7e56ce47273149815501d0d2395c2a98c726b31ba931e641.tar.gz")
    (sha256 (base32 "1ksrdf7dy4csazhddi64xahks8jzf4r8phgkjg9hfxp722iniipz"))))
(define uucode-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/uucode-0.2.0-ZZjBPqZVVABQepOqZHR7vV_NcaN-wats0IB6o-Exj6m9.tar.gz")
    (sha256 (base32 "15az8qzp0rg5qj8ma0dam9j8jbf4wwb7wxsiq3iymmlb9w7yxayh"))))
(define vaxis-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/vaxis-7dbb9fd3122e4ffad262dd7c151d80d863b68558.tar.gz")
    (sha256 (base32 "1xlf12dlzda0z4d3svq0qibvfgqzkrv4igg6qqg58nwwr0mk6wif"))))
(define wayland-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/wayland-9cb3d7aa9dc995ffafdbdef7ab86a949d0fb0e7d.tar.gz")
    (sha256 (base32 "03f574n5w0y6glr7lf8xjd71844qh8kxxb1s3zjpfxj3ivb92hga"))))
(define wayland2-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/wayland-protocols-258d8f88f2c8c25a830c6316f87d23ce1a0f12d9.tar.gz")
    (sha256 (base32 "1y1h0pmql53x6ixbsycgkzxlxsxqs9fkps754c7ycx8vx3fwmvaw"))))
(define wuffs-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/wuffs-122037b39d577ec2db3fd7b2130e7b69ef6cc1807d68607a7c232c958315d381b5cd.tar.gz")
    (sha256 (base32 "04qwpr8c4xjla4skwb1fpvkjc0c611qhbhz9xp3c9rlnpq5d4k4y"))))
(define z2d-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/z2d-0.10.0-j5P_Hu-6FgBsZNgwphIqh17jDnj8_yPtD8yzjO6PpHRQ.tar.gz")
    (sha256 (base32 "1xwpcw2awxf2r1kz27m0j4pzpi5g92gd1i2mzqvhkvnmxyi1vwk9"))))
(define zf-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/zf-3c52637b7e937c5ae61fd679717da3e276765b23.tar.gz")
    (sha256 (base32 "0s25gjvp7rns1l52jvgbd7aakndlvfs5xh9b4wk9wkphia95s09v"))))
(define zig-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/zig_js-04db83c617da1956ac5adc1cb9ba1e434c1cb6fd.tar.gz")
    (sha256 (base32 "18vkzib7xgvk4g1xk18070w3yfg9kjnqc0q9p029blqmc3jih82c"))))
(define zig2-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/zig_objc-f356ed02833f0f1b8e84d50bed9e807bf7cdc0ae.tar.gz")
    (sha256 (base32 "1k4fq05brsm799qpkxbwcq1dgs5jyc4hkcrcfb6nyd95frrsz16x"))))
(define zig3-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/zig_wayland-1b5c038ec10da20ed3a15b0b2a6db1c21383e8ea.tar.gz")
    (sha256 (base32 "0khjg5q1z1d4sgnyhfjqzb8c6wizx79p3gz343sjgmfhbrrnn52g"))))
(define zlib-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/zlib-1220fed0c74e1019b3ee29edae2051788b080cd96e90d56836eea857b0b966742efb.tar.gz")
    (sha256 (base32 "0p6h2i9ajdp46lckdpibfqy4vz5nh5r22bqq96mp41k0ydiqis0p"))))
(define v1925-origin
  (origin
    (method url-fetch)
    (uri "https://github.com/ocornut/imgui/archive/refs/tags/v1.92.5-docking.tar.gz")
    (sha256 (base32 "1jzr65gpx4mqfcdbnf2rm2kd20jmj9whwdb7x1df3wvmih7c45n8"))))

(define zigimg-origin
  (origin
    (method url-fetch)
    (uri "https://github.com/ivanstepanovftw/zigimg/archive/d7b7ab0ba0899643831ef042bd73289510b39906.tar.gz")
    (sha256 (base32 "0ly53dd3pj8hl3kkf3h8x4dw79yb7riwj9qc9da18mdkl9mxf7ic"))))
(define uucode-git-origin
  (origin
    (method git-fetch)
    (uri (git-reference
          (url "https://github.com/jacobsandlund/uucode")
          (commit "5f05f8f83a75caea201f12cc8ea32a2d82ea9732")))
    (file-name "uucode-git-checkout")
    (sha256 (base32 "1zrdyhnqs0v46qasxb2kwd7694j8r8z6w4zlnfp42x8j6kwy2wxh"))))

(define zig_objc_f356ed02833f0f1b8e84d50bed9e807bf7cdc0ae_tar_gz-origin
  (origin
    (method url-fetch)
    (uri "https://deps.files.ghostty.org/zig_objc-f356ed02833f0f1b8e84d50bed9e807bf7cdc0ae.tar.gz")
    (sha256 (base32 "1k4fq05brsm799qpkxbwcq1dgs5jyc4hkcrcfb6nyd95frrsz16x"))))

(define %ghostty-dep-origins
  (list breakpad-origin dearbindings-origin fontconfig-origin freetype-origin gettext-origin ghostty-origin glslang-origin gobject-origin gtk4-origin harfbuzz-origin highway-origin jetbrainsmono-origin libpng-origin libxev-origin libxml2-origin nerdfontssymbolsonly-origin oniguruma-origin pixels-origin plasma-origin sentry-origin spirv-origin utfcpp-origin uucode-origin vaxis-origin wayland-origin wayland2-origin wuffs-origin z2d-origin zf-origin zig-origin zig2-origin zig3-origin zlib-origin v1925-origin
        zigimg-origin uucode-git-origin))
(define %ghostty-phases
  #~(modify-phases %standard-phases
      (delete 'configure)
      (delete 'check)
      (delete 'install)
      (replace 'build
        (lambda* (#:key inputs #:allow-other-keys)
          (let* ((zcache (string-append (getcwd) "/.zig-cache-global"))
                 (fixes (string-append (getcwd) "/lib-fixes"))
                 (search (lambda (in)
                           (string-append (assoc-ref inputs in) "/lib")))
                 (pkgconfig '("gtk" "libadwaita" "libxkbcommon" "libpng" "zlib"
                              "bzip2" "expat" "fontconfig-minimal" "freetype" "harfbuzz"
                              "pixman" "libxml2" "oniguruma" "glib" "glib-out"
                              "gtk4-layer-shell"))
                 (inherited-pkgconfig (or (getenv "PKG_CONFIG_PATH") "")))
            (setenv "ZIG_GLOBAL_CACHE_DIR" zcache)
            (setenv "ZIG_LOCAL_CACHE_DIR"
                    (string-append (getcwd) "/.zig-cache-local"))
            (setenv "PKG_CONFIG_PATH"
                    (string-append
                     (string-join
                      (map (lambda (in)
                             (string-append (assoc-ref inputs in) "/lib/pkgconfig"))
                           pkgconfig)
                      ":")
                     ":" inherited-pkgconfig))
            (for-each
             (lambda (tarball)
               (invoke "zig" "fetch" "--global-cache-dir" zcache tarball))
             (list #$@%ghostty-dep-origins))
            (mkdir-p (string-append fixes "/lib"))
            (symlink (string-append (search "gtk") "/libgtk-4.so")
                     (string-append fixes "/lib/libgtk4.so"))
            (symlink (string-append (search "gtk") "/libgtk-4.so.0")
                     (string-append fixes "/lib/libgtk4.so.0"))
            (symlink (string-append (search "libadwaita") "/libadwaita-1.so")
                     (string-append fixes "/lib/liblibadwaita-1.so"))
            (mkdir-p (string-append fixes "/include"))
            (for-each
             (lambda (triple)
               (symlink (string-append (search (car triple)) (cadr triple))
                        (string-append fixes "/include/" (caddr triple))))
             '(("gtk" "/../include/gtk-4.0/gtk" "gtk")
               ("gtk" "/../include/gtk-4.0/gdk" "gdk")
               ("gtk" "/../include/gtk-4.0/gsk" "gsk")
               ("glib-out" "/../include/glib-2.0/glib" "glib")
               ("glib-out" "/glib-2.0/gio" "gio")
               ("glib-out" "/glib-2.0/gobject" "gobject")
               ("pango" "/../include/pango-1.0/pango" "pango")
               ("graphene" "/../include/graphene-1.0" "graphene-1.0")
               ("gdk-pixbuf" "/../include/gdk-pixbuf-2.0/gdk-pixbuf" "gdk-pixbuf")
               ("harfbuzz" "/../include/harfbuzz" "harfbuzz")
               ("gtk" "/../include/libadwaita-1/adwaita.h" "adwaita.h")
               ("glib-out" "/glib-2.0/include/glibconfig.h" "glibconfig.h")
               ("glib-out" "/glib-2.0/glib-object.h" "glib-object.h")
               ("cairo" "/../include/cairo/cairo.h" "cairo.h")
               ("cairo" "/../include/cairo/cairo-gobject.h" "cairo-gobject.h")))
            (invoke "zig" "build" "-Doptimize=ReleaseFast"
                    "--prefix" #$output
                    "--search-prefix" fixes
                    "--search-prefix" (search "gtk")
                    "--search-prefix" (search "libadwaita")
                    "--search-prefix" (search "gtk4-layer-shell")
                    "--search-prefix" (search "libxkbcommon")
                    "--search-prefix" (search "glib")
                    "--search-prefix" (search "fontconfig-minimal")
                    "--search-prefix" (search "freetype")
                    "--search-prefix" (search "harfbuzz")))))
      (add-after 'build 'wrap-binary
        (lambda* (#:key inputs #:allow-other-keys)
          (let* ((bin (string-append #$output "/bin"))
                 (real (string-append bin "/.ghostty-real"))
                 (wrapper (string-append bin "/ghostty"))
                 (xdg-dirs (string-join
                            (map (lambda (in)
                                   (string-append (assoc-ref inputs in) "/share"))
                                 '("gtk" "libadwaita" "glib" "gsettings-desktop-schemas"
                                   "shared-mime-info" "adwaita-icon-theme" "gdk-pixbuf"))
                            ":")))
            (rename-file (string-append bin "/ghostty") real)
            (call-with-output-file wrapper
              (lambda (port)
                (format port
                        "#!/bin/sh\nexport XDG_DATA_DIRS=~a:${XDG_DATA_DIRS:-}\nexec ~a \"$@\"\n"
                        xdg-dirs real)))
            (chmod wrapper #o555)
            (patch-shebang wrapper))))))
(define gettext/msgfmt (@ (gnu packages gettext) gettext-minimal))

(define-public ghostty
  (package
    (name "ghostty")
    (version "1.3.1")
    (source
     (origin
       (method git-fetch)
       (uri (git-reference
             (url "https://github.com/ghostty-org/ghostty")
             (commit (string-append "v" version))))
       (file-name (git-file-name name version))
       (sha256
        (base32 "0d064l17drqcf6bc27jmjxak0n2xqp2mpalakwp3j9mx8yclrmzr"))
       (snippet
        #~(begin
            (use-modules (guix build utils))
            (substitute* "src/apprt/gtk/build/blueprint.zig"
              (("^pub const c = @cImport[(][{]")
               "pub const c = struct {")
              (("[@]cInclude[(]\"adwaita[.]h\"[)];")
               "    pub const ADW_MAJOR_VERSION = 1;\n    pub const ADW_MINOR_VERSION = 8;\n    pub const ADW_MICRO_VERSION = 4;")
              (("^\\}\\);")
               "};"))))))
    (build-system gnu-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:tests? #f
      #:phases %ghostty-phases))
    (native-inputs
     `(("zig" ,zig-0.15)
       ("pkg-config" ,pkg-config)
       ("wayland" ,wayland)
       ("gettext" ,gettext/msgfmt)
       ("ncurses" ,ncurses)
       ("blueprint-compiler" ,blueprint-compiler)
       ("glib:bin" ,glib "bin")
       ("glib-out" ,glib)))
    (inputs
     (list gtk libadwaita libxkbcommon libpng zlib bzip2 expat fontconfig
           freetype harfbuzz pixman libxml2 oniguruma glib pango graphene cairo
           gtk4-layer-shell gsettings-desktop-schemas shared-mime-info adwaita-icon-theme
           gdk-pixbuf))
    (synopsis "Terminal emulator")
    (description "Ghostty terminal emulator from the ghostty-org/ghostty source at v1.3.1.")
    (home-page "https://ghostty.org")
    (license license:expat)))
