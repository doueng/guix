;; Video on Apple Silicon, userspace only: the open firmware for the Apple
;; Video Decoder (AVD), the VA-API driver that drives it through the kernel's
;; apple_avd V4L2 stateless decoder, and Aurora Silicon's camera denoiser.
;; The decoder parts are opt-in at use: nothing decodes on the AVD until a
;; player asks VA-API for it (see the comment on libva-v4l2-request-avd).
(define-module (engstrand asahi video)
  #:use-module ((guix licenses) #:prefix license:)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages llvm)
  #:use-module (gnu packages build-tools)
  #:use-module (gnu packages pkg-config)
  #:use-module (gnu packages python)
  #:use-module (gnu packages video)
  #:use-module (gnu packages xdisorg)
  #:use-module (guix build-system gnu)
  #:use-module (guix build-system meson)
  #:use-module (guix gexp)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module (guix utils))

;; Asahi's own release, not Aurora's fork: Aurora's avd-fw speaks the queue
;; ABI of Aurora's kernel driver, and lacks the config block the asahi-7.2.15
;; apple_avd driver checks for ("Too old or incompatible firmware without
;; config").  The M1 (T8103) loads apple/avd-fw-v2-t0.bin.
(define-public asahi-avd-firmware
  (package
    (name "asahi-avd-firmware")
    (version "0.2")
    (source (origin
              (method git-fetch)
              (uri (git-reference
                    (url "https://github.com/AsahiLinux/avd-fw")
                    (commit (string-append "v" version))))
              (file-name (git-file-name name version))
              (sha256
               (base32 "09y9ix2pz9ycz6jmbnw9zdqc9mgrb5j5mvk30pvxn16vyfgjzgw9"))))
    (build-system gnu-build-system)
    (arguments
     (list
      #:tests? #f                       ;no tests in this release
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'unpack 'set-version
            ;; version.sh asks git, and the checkout has no .git.
            (lambda _
              (substitute* "meson.build"
                (("run_command\\('version.sh', 'get-vcs'.*\\)\\.stdout\\(\\)\\.strip\\(\\)")
                 (string-append "'" #$version "'")))))
          (replace 'configure
            (lambda _
              ;; -O2 without -g, as Asahi's Makefile built it.  H.264 and
              ;; HEVC are off by default for distributions' patent lawyers;
              ;; the M1's decoder has no AV1, so without them it does VP9 only.
              (invoke "meson" "setup" "--cross-file=llvm.ini"
                      (string-append "--prefix=" #$output)
                      "-Doptimization=2" "-Ddebug=false"
                      "-Dvideo-codecs=all" "build")))
          (replace 'build
            (lambda _ (invoke "meson" "compile" "-C" "build")))
          (replace 'install
            (lambda _ (invoke "meson" "install" "-C" "build"))))))
    (native-inputs (list clang lld llvm meson ninja))
    (home-page "https://github.com/AsahiLinux/avd-fw")
    (synopsis "Open firmware for the Apple Silicon video decoder")
    (description "This package provides the open source Cortex-M3 firmware
that the Linux @code{apple_avd} driver loads into the Apple Video Decoder of
Apple Silicon Macs, as @file{lib/firmware/apple/avd-fw-*.bin}.")
    (license license:expat)))

;; The fork Aurora Silicon packages (the nixos-aurora pin, 1.3.r11): megi's
;; libva-v4l2-request with sofus13's AVD support and Chromium and robustness
;; fixes.  libva picks its driver from the render node's DRM driver, asahi,
;; so the asahi_drv_video.so link makes it the default without
;; LIBVA_DRIVER_NAME.  Players still use it only when asked: mpv with
;; --hwdec=vaapi, Chrome with its VA-API features.
(define-public libva-v4l2-request-avd
  (package
    (name "libva-v4l2-request-avd")
    (version "1.3.r11")
    (source (origin
              (method git-fetch)
              (uri (git-reference
                    (url "https://github.com/aurora-silicon/libva-v4l2_request")
                    (commit "4f60c51f7841dd74bb61c94a4ad31e630bbdab01")))
              (file-name (git-file-name name version))
              (sha256
               (base32 "1417nl1fqypidv06636xa5j1gwckcw6p2nd0cr6zgzng2fg9gkgq"))))
    (build-system meson-build-system)
    (arguments
     (list
      #:configure-flags
      #~(list (string-append "-Ddriverdir=" #$output "/lib/dri")
              ;; Fail the build if the kernel headers lack a codec the M1
              ;; decodes, rather than quietly leaving it out.
              "-Dcodec_h264=enabled" "-Dcodec_hevc=enabled"
              "-Dcodec_vp9=enabled")
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'unpack 'skip-harness-self-tests
            ;; Two self-tests of the test harness, not of the driver, fail
            ;; in the build container: the ThreadSanitizer probe needs a
            ;; libtsan GCC lacks here, and the fuzz runner's 1 s CPU limit
            ;; races its own wall clock.  The TSan pass itself still runs.
            (lambda _
              (substitute* "tests/meson.build"
                (("^test\\('(concurrent-tsan-probe|fuzz-campaign-runner)'.*") ""))))
          (add-before 'check 'set-cc
            ;; The ThreadSanitizer pass compiles with $CC, else cc.
            (lambda _ (setenv "CC" #$(cc-for-target))))
          (add-after 'install 'link-asahi-driver-name
            (lambda _
              (with-directory-excursion (string-append #$output "/lib/dri")
                (symlink "v4l2_request_drv_video.so"
                         "asahi_drv_video.so")))))))
    (native-inputs (list pkg-config python))
    ;; The stateless codec UAPI of the running kernel (7.2).
    (inputs (list libdrm libva linux-libre-headers-7.2))
    (home-page "https://github.com/aurora-silicon/libva-v4l2_request")
    (synopsis "VA-API driver for the Apple Silicon video decoder")
    (description "This package provides a VA-API driver for V4L2 stateless
video decoders using the media request API, with the fixes needed for the
Apple Video Decoder (AVD) of Apple Silicon Macs.  It decodes H.264, HEVC and
VP9 on an M1.")
    (license license:gpl3+)))

(define-public camera-denoise
  (let ((commit "6ae2f1d8a64df50a205d101e28d0c3c5dc0de4b7")
        (revision "0"))
    (package
      (name "camera-denoise")
      (version (git-version "0.1.0" revision commit))
      (source (origin
                (method git-fetch)
                (uri (git-reference
                      (url "https://github.com/aurora-silicon/camera-denoise")
                      (commit commit)))
                (file-name (git-file-name name version))
                (sha256
                 (base32 "05qkr7b7rw9i5m9w4jba0i124q6c970kdp8nl01wa3xxh4r5h827"))))
      (build-system meson-build-system)
      (arguments
       (list
        #:configure-flags
        #~(list "-Ddaemon=enabled"
                ;; The Vulkan backend is opt-in and costs more energy.
                "-Dgpu=disabled"
                (string-append "-Dsystemduserunitdir=" #$output
                               "/lib/systemd/user"))))
      (inputs (list pipewire))
      (native-inputs (list pkg-config))
      (home-page "https://github.com/aurora-silicon/camera-denoise")
      (synopsis "PipeWire temporal denoiser for the Apple Silicon camera")
      (description "This package provides a PipeWire filter that adds a
denoised camera node next to each built-in camera of an Apple Silicon Mac
(the apple-isp driver).  Each pixel keeps a short history; still pixels are
averaged over about 15 frames and moving ones restart, so nothing trails.  The
original camera stays available.")
      (license license:expat))))
