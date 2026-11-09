;; Ghostty, built from its release tarball on the remote builder.  Ghostty
;; 1.4 needs Zig 0.17, newer than Guix's, so zig-0.17 below is upstream's
;; static release binary, used only to build.  Upstream's PACKAGING.md is the
;; recipe: fetch the build.zig.zon dependencies into one directory with zig
;; fetch, then zig build --system on it, linking Guix's GTK, fonts and image
;; libraries.  updates bumps neither; a new release means new versions,
;; hashes and %ghostty-zig-deps (from its build.zig.zon.json).
(define-module (engstrand packages ghostty)
  #:use-module (gnu packages base)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages elf)
  #:use-module (gnu packages fontutils)
  #:use-module (gnu packages freedesktop)
  #:use-module (gnu packages gettext)
  #:use-module (gnu packages gl)
  #:use-module (gnu packages glib)
  #:use-module (gnu packages gnome)
  #:use-module (gnu packages gtk)
  #:use-module (gnu packages image)
  #:use-module (gnu packages ncurses)
  #:use-module (gnu packages pkg-config)
  #:use-module (gnu packages regex)
  #:use-module (guix build-system copy)
  #:use-module (guix build-system gnu)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module (ice-9 match)
  #:use-module ((guix licenses) #:prefix license:))

;; The compiler finds the native dynamic linker and glibc through the ELF
;; header of /usr/bin/env, which a build container lacks, and being static
;; it can't fall back on its own; it would then target musl.  The same
;; length /tmp/zig-env, which the ghostty build links to Guix's env, takes
;; its place in the binary and in the standard library the build runner
;; compiles.
(define zig-0.17
  (package
    (name "zig")
    (version "0.17.0")
    (source (origin
              (method url-fetch)
              (uri (string-append "https://ziglang.org/download/" version
                                  "/zig-aarch64-linux-" version ".tar.xz"))
              (sha256 (base32 "1y2g4djkyl6dbimf362pvqfibb933rw34f1af1bvvqsa3mk133cy"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("." "lib/zig/"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-before 'install 'use-tmp-env
            (lambda _
              (setenv "LC_ALL" "C")
              (invoke "sed" "-i" "s|/usr/bin/env|/tmp/zig-env|g"
                      "zig" "lib/std/zig/system.zig")))
          (add-after 'install 'link-bin
            (lambda _
              (mkdir-p (string-append #$output "/bin"))
              (symlink "../lib/zig/zig" (string-append #$output "/bin/zig")))))))
    (synopsis "Zig compiler, upstream's static release, for building Ghostty")
    (description "Upstream's prebuilt Zig, patched to detect the native libc
through /tmp/zig-env instead of /usr/bin/env.")
    (home-page "https://ziglang.org")
    (license license:expat)))

;; (NAME ZIG-HASH URL BASE32) for every package in build.zig.zon.json.
(define %ghostty-zig-deps
  '(
    (aro "aro-0.0.0-JSD1QtuBNwCASyBtNF3pqTl_W3oAJQGEVyFAtrBSE_Pa"
     "https://github.com/Vexu/arocc/archive/d0c8c4d9c55daa7ef6e40cf0f630a5b5e900989b.tar.gz"
     "161xa6a3vqb7k3phzfryaq8rc17qvlr7hip9b5s4b2yhd2xm8ga2")
    (bindings "N-V-__8AANT61wB--nJ95Gj_ctmzAtcjloZ__hRqNw5lC1Kr"
     "https://deps.files.ghostty.org/DearBindings_v0.17_ImGui_v1.92.5-docking.tar.gz"
     "18xsf0zisr9q8xqa8g3kh4qkqb9rrikkvqiyhmwzc9h9w00cbzlb")
    (breakpad "N-V-__8AALw2uwF_03u4JRkZwRLc3Y9hakkYV7NKRR9-RIZJ"
     "https://deps.files.ghostty.org/breakpad-b99f444ba5f6b98cac261cbb391d8766b34a5918.tar.gz"
     "1nbadlml3r982bz1wyp17w33hngzkb07f47nrrk0g68s7na9ijkc")
    (fontconfig "N-V-__8AAOgqbADacob-q2_DMQlmgaG4xKHRuW-6PJ4oJzMZ"
     "https://deps.files.ghostty.org/N-V-__8AAOgqbADacob-q2_DMQlmgaG4xKHRuW-6PJ4oJzMZ.tar.xz"
     "1qrm7nsd07n963v0fnpnjjh197klydqqhv367w1qrxyd7155aysg")
    (freetype "N-V-__8AAKLKpwC4H27Ps_0iL3bPkQb-z6ZVSrB-x_3EEkub"
     "https://deps.files.ghostty.org/freetype-1220b81f6ecfb3fd222f76cf9106fecfa6554ab07ec7fdc4124b9bb063ae2adf969d.tar.gz"
     "035r5bypzapa1x7za7lpvpkz58fxynz4anqzbk8705hmspsh2wj2")
    (gettext "N-V-__8AADcZkgn4cMhTUpIz6mShCKyqqB-NBtf_S2bHaTC-"
     "https://deps.files.ghostty.org/gettext-0.24.tar.gz"
     "1dqq2ln01mfwr4gblvy0cyvarbqnv09ml5sdhksdlw1xb4ym0669")
    (glslang "N-V-__8AABzkUgISeKGgXAzgtutgJsZc0-kkeqBBscJgMkvy"
     "https://deps.files.ghostty.org/glslang-12201278a1a05c0ce0b6eb6026c65cd3e9247aa041b1c260324bf29cee559dd23ba1.tar.gz"
     "1dcpm70fhxk07vk37f5l0hb9gxfv6pjgbqskk8dfbcwwa2xyv8hl")
    (gobject "gobject-0.3.2-Skun7LuIogBbEh-6WrLeEzMaRCnOMB3eOf__RaPYSMCZ"
     "https://deps.files.ghostty.org/gobject-2026-10-02-38-1.tar.zst"
     "191b6rmwd1kh5zqcdbs6cn99cgx2hl3wf54f6k2rxlmpggysf3s0")
    (gtk4_layer_shell "N-V-__8AALiNBAA-_0gprYr92CjrMj1I5bqNu0TSJOnjFNSr"
     "https://deps.files.ghostty.org/gtk4-layer-shell-1.1.0.tar.gz"
     "12396gx723ybgq1xp9i02257hsmzqhb5z9b39xdyypha4s0l4a4q")
    (harfbuzz "N-V-__8AAG02ugUcWec-Ndp-i7JTsJ0dgF8nnJRUInkGLG7G"
     "https://deps.files.ghostty.org/harfbuzz-11.0.0.tar.xz"
     "16rb7aazy36pj3xrjy149dd90j9yv7q5jnqx5kz2air1zsx52qzi")
    (highway "N-V-__8AAGmZhABbsPJLfbqrh6JTHsXhY6qCaLAQyx25e0XE"
     "https://deps.files.ghostty.org/highway-66486a10623fa0d72fe91260f96c892e41aceb06.tar.gz"
     "04m21b46h6c4x099r9qb720ql9llpzz8yq3k94i8zq7l7s4zim47")
    (imgui "N-V-__8AAEbOfQBnvcFcCX2W5z7tDaN8vaNZGamEQtNOe0UI"
     "https://deps.files.ghostty.org/N-V-__8AAEbOfQBnvcFcCX2W5z7tDaN8vaNZGamEQtNOe0UI.tar.gz"
     "1jzr65gpx4mqfcdbnf2rm2kd20jmj9whwdb7x1df3wvmih7c45n8")
    (iterm2_themes "N-V-__8AAJ3vBQAq1fLMh5J8uWLkeDLS6WAdR2x5KcrmEyz6"
     "https://deps.files.ghostty.org/ghostty-themes-release-20261019-151001-86a776d.tgz"
     "0nshi3i2y58rdc4i1hh3z8wnrynvxcd52gkfz34lab1p2vii69db")
    (jetbrains_mono "N-V-__8AAIC5lwAVPJJzxnCAahSvZTIlG-HhtOvnM1uh-66x"
     "https://deps.files.ghostty.org/JetBrainsMono-2.304.tar.gz"
     "1i2w213919avi0apgbw720wqy0z46a89bwv3b65hkbc2icg6jyn5")
    (libpng "N-V-__8AAJrvXQCqAT8Mg9o_tk6m0yf5Fz-gCNEOKLyTSerD"
     "https://deps.files.ghostty.org/libpng-1220aa013f0c83da3fb64ea6d327f9173fa008d10e28bc9349eac3463457723b1c66.tar.gz"
     "0fm0y7543w2gx5sz3zg9i46x1am51c77a554r0zqwpphdjs9bk7y")
    (libxev "libxev-0.0.0-86vtc8QQFACz0sVc7U-VDiDIgH-XfBuEY5JpABLjHNpn"
     "https://deps.files.ghostty.org/libxev-819ffa180ca20e31c7eded74e2e1aceb1eab557f.tar.gz"
     "0diz1ib7rxgb3mzr9mcx0mlv83x6x2xyl3d9dv11z0c943q3m6a2")
    (libxml2 "N-V-__8AAG3RoQEyRC2Vw7Qoro5SYBf62IHn3HjqtNVY6aWK"
     "https://deps.files.ghostty.org/libxml2-2.11.5.tar.gz"
     "05b2kbccbkb5pkizwx2s170lcqvaj7iqjr5injsl5sry5sg0aa3c")
    (nerd_fonts_symbols_only "N-V-__8AAMVLTABmYkLqhZPLXnMl-KyN38R8UVYqGrxqO26s"
     "https://deps.files.ghostty.org/NerdFontsSymbolsOnly-3.4.0.tar.gz"
     "010d7gkv359qg555d89i4hhgb56c8f69kw5jsx4f5gflaswx2r0i")
    (oniguruma "N-V-__8AAHjwMQDBXnLq3Q2QhaivE0kE2aD138vtX2Bq1g7c"
     "https://deps.files.ghostty.org/oniguruma-1220c15e72eadd0d9085a8af134904d9a0f5dfcbed5f606ad60edc60ebeccd9706bb.tar.gz"
     "187jk4fxdkzc0wrcx4kdy4v6p1snwmv8r97i1d68yi3q5qha26h0")
    (pixels "N-V-__8AADYiAAB_80AWnH1AxXC0tql9thT-R-DYO1gBqTLc"
     "https://deps.files.ghostty.org/pixels-12207ff340169c7d40c570b4b6a97db614fe47e0d83b5801a932dcd44917424c8806.tar.gz"
     "06pi3f3lhyxfzczhwrc2b4n0jhhzydbz96qlpw12a24is0b3ps2m")
    (plasma_wayland_protocols "N-V-__8AAKYZBAB-CFHBKs3u4JkeiT4BMvyHu3Y5aaWF3Bbs"
     "https://deps.files.ghostty.org/plasma_wayland_protocols-12207e0851c12acdeee0991e893e0132fc87bb763969a585dc16ecca33e88334c566.tar.gz"
     "0hgl1p173pxs50z1p6mjjzcqssn44aq0ip166k56p3nd98hvln2w")
    (sentry "N-V-__8AAPlZGwBEa-gxrcypGBZ2R8Bse4JYSfo_ul8i2jlG"
     "https://deps.files.ghostty.org/sentry-1220446be831adcca918167647c06c7b825849fa3fba5f22da394667974537a9c77e.tar.gz"
     "1pqqqcin8nw398rvn187dfqlab4vikdssiry14qqs6nnr1y4kiia")
    (spirv_cross "N-V-__8AANb6pwD7O1WG6L5nvD_rNMvnSc9Cpg1ijSlTYywv"
     "https://deps.files.ghostty.org/spirv_cross-1220fb3b5586e8be67bc3feb34cbe749cf42a60d628d2953632c2f8141302748c8da.tar.gz"
     "1qspcsx56v0mddarb6f05i748wsl2ln3d8863ydsczsyqk7nyaxm")
    (translate_c "translate_c-2.0.0-Q_BUWltOBwBYABF84EUL208pIFeUIwbBs29FmVBaafN5"
     "https://deps.files.ghostty.org/translate_c-02ff0c523fb92a38939b04d0145c4a9602a74d56.tar.gz"
     "14c5spdb8z6vi0pi4nvpn539ihypcw761sjqkr95jiz1rhxl6i73")
    (uucode "uucode-0.2.0-ZZjBPoGFVgCiflJNFc6Io8xzC67B6PA5bUkk4LT0kCxg"
     "https://deps.files.ghostty.org/uucode-ea62149739404a73c202b48a33bf6dd2af4bd9b0.tar.gz"
     "1gxcdq2qckx8fzz45jxrgs48zdnlnq99rjw4s183918pl9ql0z7k")
    (vaxis "vaxis-0.6.0-BWNV_MafDAAEzkVUvrW9NFUqnnMUoeHqrs2mecnD6D7w"
     "https://deps.files.ghostty.org/vaxis-6fd944a27fb3d6f596e981076381a3131f2448b4.tar.gz"
     "0sp16yfa84k2kla0kavnhdsl54flmmxfzia9jhn9bp537nc0rm9r")
    (wayland "N-V-__8AAKrHGAAs2shYq8UkE6bGcR1QJtLTyOE_lcosMn6t"
     "https://deps.files.ghostty.org/wayland-9cb3d7aa9dc995ffafdbdef7ab86a949d0fb0e7d.tar.gz"
     "03f574n5w0y6glr7lf8xjd71844qh8kxxb1s3zjpfxj3ivb92hga")
    (wayland_protocols "N-V-__8AAKw-DAAaV8bOAAGqA0-oD7o-HNIlPFYKRXSPT03S"
     "https://deps.files.ghostty.org/wayland-protocols-258d8f88f2c8c25a830c6316f87d23ce1a0f12d9.tar.gz"
     "1y1h0pmql53x6ixbsycgkzxlxsxqs9fkps754c7ycx8vx3fwmvaw")
    (wayland_protocols "N-V-__8AAFdWDwA0ktbNUi9pFBHCRN4weXIgIfCrVjfGxqgA"
     "https://deps.files.ghostty.org/N-V-__8AAFdWDwA0ktbNUi9pFBHCRN4weXIgIfCrVjfGxqgA.tar.gz"
     "08rjsdh8w0lpj2pr83rziw0yxh4szfsw9p5fg8jkh47lnm5g2bfx")
    (wuffs "N-V-__8AAP5JWgCGP_AD0teWpa4krRvE9VPZzvviGdbmN4jI"
     "https://deps.files.ghostty.org/wuffs-7411f488fe2e2c205c3d3b3d28638b7356522930.tar.gz"
     "074sf6lv4x13c4s5wb24lq4nij057cp5pr32dhfnb23rdws7v1qp")
    (z2d "z2d-0.13.0-j5P_HgsRFABzZDH54gI35P0OSrg7HFyJwS2bXX5uBdU4"
     "https://deps.files.ghostty.org/z2d-40af3e84a4e15a9f31d126b3f281f23ce6a3dae5.tar.gz"
     "0x1zmm9npj8w1pj9kipfrkc90d7gnpm7dk7jxs0vgf4vg3ia9b7x")
    (zf "zf-0.11.0-OIRy8XCRAAB6FiMwIrtD6FqlagnFwpI6YyOu7bXeYH5J"
     "https://deps.files.ghostty.org/zf-87153bb86b75d21fbac897b738f316d449f5d0c9.tar.gz"
     "02m5imdhfxq90man8fkkip47817xfmqzmg81apyvgspv6p6ln5sn")
    (zig_js "zig_js-0.0.0-rjCAV7-GAADvMTBL7lPMuvDk7xgS9PCMIZWiOUXLZSlj"
     "https://deps.files.ghostty.org/zig_js-3c23860e47fdcdc5af805efb7fd0bdac5fd3e9bc.tar.gz"
     "0fsbgnjjkh274vnxgxshrhdb0sb8mlm279b9jkjz80a34j54izx2")
    (zig_objc "zig_objc-0.0.0-Ir_Sp_4tAQDXtPi51nW_jD9LOEzBvU9lofwO9Pzi6IB4"
     "https://deps.files.ghostty.org/zig_objc-3352c12a868f8787ab71bf2ca6eab9fcfb635c52.tar.gz"
     "0y7i8ldwj6n8n6f5d4rgglq9zbgdrsf90i074qdwacb005ydc9ss")
    (zig_wayland "wayland-0.7.0-dev-lQa1klz9AQB87V0pMKMdm8JvyOx32XR9FxcWnw9wh6XZ"
     "https://deps.files.ghostty.org/zig_wayland-27dda01712ddf3cb46ebd93380d88b01a7c828f8.tar.gz"
     "1927314algmxbd63xrf548bm9lb48fqg5k6h16rgxv6wq3bniln7")
    (zigimg "zigimg-0.1.0-8_eo2ri_FwBqF5UYiggLz4IGjHJO_n9-B6gT9Ayqg8G4"
     "https://github.com/sergot/zigimg/archive/c701c9f99779d7ddf594dcc6da8f858fd277d61f.tar.gz"
     "0rpaizw768inlwmldjnld58cdwvhxjd3kz8vh2rr2cp0cbrcc5n7")
    (zlib "N-V-__8AAB0eQwD-0MdOEBmz7intriBReIsIDNlukNVoNu6o"
     "https://deps.files.ghostty.org/zlib-1220fed0c74e1019b3ee29edae2051788b080cd96e90d56836eea857b0b966742efb.tar.gz"
     "0p6h2i9ajdp46lckdpibfqy4vz5nh5r22bqq96mp41k0ydiqis0p")))

(define (zig-dep-origin entry)
  (match entry
    ((name hash url sha)
     (origin
       (method url-fetch)
       (uri url)
       ;; zig fetch tells the archive type by its extension.
       (file-name (string-append hash (cond ((string-suffix? ".tar.xz" url) ".tar.xz")
                                            ((string-suffix? ".tar.zst" url) ".tar.zst")
                                            (else ".tar.gz"))))
       (sha256 (base32 sha))))))

;; Build-side setup for zig: a private cache, the /tmp/zig-env link and a
;; libc file naming Guix's glibc and gcc's crt files.
(define zig-environment-definition
  #~(define (zig-environment env libc)
      (symlink env "/tmp/zig-env")
      (setenv "ZIG_GLOBAL_CACHE_DIR" "/tmp/zig-cache")
      (setenv "ZIG_LOCAL_CACHE_DIR" "/tmp/zig-local-cache")
      (let* ((port (open-pipe* OPEN_READ "gcc" "-print-file-name=crtbegin.o"))
             (crtbegin (read-line port)))
        (close-pipe port)
        (call-with-output-file "/tmp/zig-libc"
          (lambda (out)
            (format out "include_dir=~a/include~%sys_include_dir=~a/include~%crt_dir=~a/lib~%msvc_lib_dir=~%kernel32_lib_dir=~%cc_dir=~a~%darwin_sdk_dir=~%"
                    libc libc libc (dirname crtbegin)))))
      (setenv "ZIG_LIBC" "/tmp/zig-libc")))

;; Every dependency unpacked the way zig build --system wants it: zig fetch
;; checks the hash and leaves p/HASH.tar.gz, unpacked here into the output.
(define ghostty-zig-deps
  (package
    (name "ghostty-zig-deps")
    (version "1.4.0")
    (source #f)
    (build-system gnu-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:tests? #f
      #:modules '((guix build gnu-build-system) (guix build utils)
                  (ice-9 ftw) (ice-9 match) (ice-9 popen) (ice-9 rdelim)
                  (srfi srfi-26))
      #:phases
      #~(let ()
          #$zig-environment-definition
          (modify-phases %standard-phases
          (replace 'unpack (lambda _ #t))
          (delete 'configure)
          (delete 'install)
          (replace 'build
            (lambda* (#:key inputs #:allow-other-keys)
              (zig-environment (search-input-file inputs "/bin/env")
                               (assoc-ref inputs "libc"))
              (mkdir-p #$output)
              (mkdir-p "/tmp/zig-fetch")
              (with-directory-excursion "/tmp/zig-fetch"
                (for-each
                 (match-lambda
                   ((hash file)
                    (let* ((port (open-pipe* OPEN_READ "zig" "fetch" file))
                           (got (read-line port)))
                      (unless (zero? (status:exit-val (close-pipe port)))
                        (error "zig fetch failed:" file))
                      (unless (equal? got hash)
                        (error "zig hash mismatch:" file got hash))
                      (invoke "tar" "-xzf"
                              (string-append "/tmp/zig-cache/p/" hash ".tar.gz")
                              "-C" #$output)
                      ;; The archive keeps its single top directory, which
                      ;; zig strips when it unpacks a package itself.
                      (let* ((dir (string-append #$output "/" hash))
                             (entries (scandir dir (negate (cut member <> '("." ".."))))))
                        (match entries
                          ((top)
                           (let ((top (string-append dir "/" top))
                                 (tmp (string-append dir ".top")))
                             (when (eq? 'directory (stat:type (lstat top)))
                               (rename-file top tmp)
                               (rmdir dir)
                               (rename-file tmp dir))))
                          (_ #t))))))
                 '#$(map (match-lambda
                           ((name hash url sha)
                            #~(#$hash #$(zig-dep-origin (list name hash url sha)))))
                         %ghostty-zig-deps)))))))))
    (native-inputs (list zig-0.17 coreutils))
    (synopsis "Zig packages Ghostty builds with")
    (description "The build.zig.zon dependencies of Ghostty, as zig fetch
unpacks them.")
    (home-page "https://ghostty.org")
    (license license:expat)))

(define-public ghostty
  (package
    (name "ghostty")
    (version "1.4.0")
    (source (origin
              (method url-fetch)
              (uri (string-append "https://release.files.ghostty.org/" version
                                  "/ghostty-" version ".tar.gz"))
              (sha256 (base32 "01ghw0daf940nc75dd9qys3lq40pq3c1bvxzsxgqs9x8qg38l66b"))))
    (build-system gnu-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:tests? #f
      #:modules '((guix build gnu-build-system) (guix build utils)
                  (ice-9 popen) (ice-9 rdelim))
      #:phases
      #~(let ()
          #$zig-environment-definition
          (modify-phases %standard-phases
          (delete 'configure)
          (add-before 'build 'zig-environment
            (lambda* (#:key inputs #:allow-other-keys)
              (zig-environment (search-input-file inputs "/bin/env")
                               (assoc-ref inputs "libc"))
              ;; Guix's bzip2 ships no bzip2.pc, which the freetype link asks for.
              (let ((bzip2 (dirname (dirname (search-input-file inputs "/lib/libbz2.so")))))
                (mkdir-p "/tmp/pkgconfig")
                (call-with-output-file "/tmp/pkgconfig/bzip2.pc"
                  (lambda (port)
                    (format port "Name: bzip2~%Description: bzip2~%Version: 1.0.8~%Libs: -L~a/lib -lbz2~%Cflags: -I~a/include~%"
                            bzip2 bzip2)))
                (setenv "PKG_CONFIG_PATH"
                        (string-append "/tmp/pkgconfig:" (getenv "PKG_CONFIG_PATH"))))))
          (replace 'build
            (lambda* (#:key parallel-build? #:allow-other-keys)
              (invoke "zig" "build"
                      "--prefix" #$output
                      "--system" #$ghostty-zig-deps
                      "-Doptimize=ReleaseFast" "-Dcpu=baseline"
                      "-Demit-docs=false"
                      #$(string-append "-Dversion-string=" version)
                      (string-append "-Dinstalled-exe-path=" #$output "/bin/ghostty")
                      (string-append "-j" (number->string
                                             (if parallel-build? (parallel-job-count) 1))))))
          (delete 'install)))))
    (native-inputs (list zig-0.17 coreutils gettext-minimal ncurses pkg-config
                         (list glib "bin")))
    (inputs (list bzip2 fontconfig freetype gtk gtk4-layer-shell harfbuzz
                  libadwaita libpng mesa oniguruma wayland zlib))
    (synopsis "GPU-accelerated terminal emulator")
    (description "Ghostty is a terminal emulator with platform-native UI and
GPU acceleration, here its GTK build.")
    (home-page "https://ghostty.org")
    (license license:expat)))
