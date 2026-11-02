;; Hex, local voice dictation (anomalyco/hex), from its prebuilt Linux release.
;; The wrapper mirrors upstream nix/package.nix: the libraries Hex links or
;; dlopens, PipeWire's ALSA plugin, and wtype and wl-clipboard for paste.
(define-module (engstrand packages hex)
  #:use-module (gnu packages audio)
  #:use-module (gnu packages base)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages elf)
  #:use-module (gnu packages fontutils)
  #:use-module (gnu packages freedesktop)
  #:use-module (gnu packages gcc)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages maths)
  #:use-module (gnu packages tls)
  #:use-module (gnu packages video)
  #:use-module (gnu packages vulkan)
  #:use-module (gnu packages xdisorg)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define-public hex
  (package
    (name "hex")
    (version "3.2.0")
    (source
     (origin
       (method url-fetch)
       ;; Release files are addressed by their SHA-256.
       (uri "https://anoma.ly/release/hex/files/8aa94907194aa74e4ecac0a780606e65482f4c2f208440cd6699ac67b790411b/hex")
       (file-name (string-append "hex-" version))
       (sha256
        (base32 "06s1j2vngb4rcv6l11105x62yj35drh819y0r974x9sa343lkaca"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (inputs (list alsa-lib curl fontconfig (list gcc "lib") glibc
                  libva libxkbcommon openblas openssl pipewire vulkan-loader
                  wayland wl-clipboard wtype))
    (arguments
     (list
      ;; Libraries come from the wrapper's LD_LIBRARY_PATH, not a RUNPATH.
      #:validate-runpath? #f
      #:install-plan #~'((#$(string-append "hex-" (package-version this-package)) "bin/hex"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'wrap
            (lambda* (#:key inputs #:allow-other-keys)
              (let ((hex (string-append #$output "/bin/hex"))
                    (lib (lambda (name) (string-append (assoc-ref inputs name) "/lib"))))
                (chmod hex #o755)
                (invoke #$(file-append patchelf "/bin/patchelf") "--set-interpreter"
                        (search-input-file inputs "/lib/ld-linux-aarch64.so.1") hex)
                (wrap-program hex
                  `("LD_LIBRARY_PATH" prefix
                    ,(cons #$(file-append (gexp-input gcc "lib") "/lib")
                           (map lib '("alsa-lib" "fontconfig-minimal" "libva" "libxkbcommon"
                                      "openblas" "openssl" "pipewire" "vulkan-loader"
                                      "wayland"))))
                  `("ALSA_PLUGIN_DIR" = (,(string-append (assoc-ref inputs "pipewire")
                                                          "/lib/alsa-lib")))
                  ;; The Asahi driver from nixpkgs mesa (engstrand nixpkgs).
                  `("VK_DRIVER_FILES" = ("/run/opengl-driver/share/vulkan/icd.d/asahi_icd.aarch64.json"))
                  `("PATH" prefix
                    ,(map (lambda (name) (string-append (assoc-ref inputs name) "/bin"))
                          '("curl" "wl-clipboard" "wtype"))))))))))
    (synopsis "Local voice dictation")
    (description "Hex records while a shortcut is held, transcribes on the device
and pastes the text at the cursor.")
    (home-page "https://github.com/anomalyco/hex")
    (license license:expat)))
