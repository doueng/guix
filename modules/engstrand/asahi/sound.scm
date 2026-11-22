;; Sound for Apple Silicon: the Asahi UCM profiles, PipeWire with LV2, the
;; speaker DSP (asahi-audio, triforce) and the ALSA service.  Copied from the
;; asahi-guix channel (https://codeberg.org/asahi-guix/channel, b9132769,
;; packages/linux.scm, packages/audio.scm and services/sound.scm),
;; GPL-3.0-or-later.
(define-module (engstrand asahi sound)
  #:use-module ((gnu packages linux) #:prefix linux:)
  #:use-module ((guix licenses) #:prefix license:)
  #:use-module (gnu packages audio)
  #:use-module (gnu services)
  #:use-module (gnu system pam)
  #:use-module (guix build-system cargo)
  #:use-module (guix build-system copy)
  #:use-module (guix build-system gnu)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module (guix records))

;; local-file with a relative name resolves against the working directory
;; when the module is loaded with -L, so name the patches by absolute path.
(define %asahi-files
  (string-append (dirname (canonicalize-path
                           (search-path %load-path "engstrand/asahi/sound.scm")))
                 "/files"))

(define-public asahi-alsa-ucm-conf
  (package
    (name "asahi-alsa-ucm-conf")
    (version "10")
    (source (origin
              (method git-fetch)
              (uri (git-reference
                     (url "https://github.com/AsahiLinux/alsa-ucm-conf-asahi")
                     (commit (string-append "v" version))))
              (file-name (git-file-name name version))
              (sha256
               (base32
                "15zj0hc6d0iwyjnda01y7x6v5h8zl6walfl3ic7519niln4lf3nq"))))
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan
      #~'(("ucm2" "share/alsa/ucm2"))
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'install 'add-alsa-ucm-conf
            (lambda* (#:key inputs outputs #:allow-other-keys)
              (let ((out (assoc-ref outputs "out"))
                    (alsa-ucm-conf (assoc-ref inputs "alsa-ucm-conf")))
                (for-each (lambda (dir)
                            (let ((path (format #f "/share/alsa/~a" dir)))
                              (copy-recursively
                               (string-append alsa-ucm-conf path)
                               (string-append out path)
                               #:follow-symlinks? #t)))
                          '("ucm" "ucm2"))))))))
    (native-inputs
     `(("alsa-ucm-conf" ,linux:alsa-ucm-conf)))
    (home-page "https://github.com/AsahiLinux/alsa-ucm-conf-asahi")
    (synopsis "The Advanced Linux Sound Architecture Use Case Manager")
    (description
     "This package contains Advanced Linux Sound Architecture Use Case Manager
configuration of audio input/output names and routing for specific audio
hardware.")
    (license license:bsd-3)))

(define-public asahi-pipewire
  (package
    (inherit linux:pipewire)
    (name "asahi-pipewire")
    (inputs (modify-inputs (package-inputs linux:pipewire)
              (prepend lilv)))))

(define-public asahi-audio
  (package
    (name "asahi-audio")
    (version "5.0")
    (source (origin
              (method git-fetch)
              (uri (git-reference
                    (url "https://github.com/AsahiLinux/asahi-audio")
                    (commit (string-append "v" version))))
              (file-name (git-file-name name version))
              (sha256
               (base32 "12mfl28s70zv37d44l0zqzbbasg2y9l57a0k5zvdsh5nggidmbm5"))
              ;; Aurora Silicon's J313 true-peak limiter (MIT); see the patch.
              (patches
               (list (local-file (string-append %asahi-files
                                                "/asahi-audio-j313-limiter.patch"))))))
    (build-system gnu-build-system)
    (arguments
     (list
      #:make-flags
      #~(list (string-append "PREFIX=" (assoc-ref %outputs "out")))
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'unpack 'patch-source
            (lambda* (#:key inputs outputs #:allow-other-keys)
              (let ((out (assoc-ref outputs "out")))
                (substitute* "conf/wireplumber.conf"
                  (("/usr/share/asahi-audio")
                   (string-append out "/share/asahi-audio"))
                  (("device/asahi-lock-volume.lua")
                   (string-append out "/share/wireplumber/scripts/device"
                                  "/asahi-lock-volume.lua")))
                (substitute* (find-files "firs" "\\.json$")
                  (("/usr/share/asahi-audio")
                   (string-append out "/share/asahi-audio"))))))
          (delete 'configure)
          (delete 'check))))
    (home-page "https://github.com/AsahiLinux/asahi-audio")
    (synopsis "Linux audio configuration for Apple Silicon Macs")
    (description "Linux userspace audio configuration for Apple Silicon Macs.")
    (license license:expat)))

(define-public triforce-lv2
  (package
    (name "triforce-lv2")
    (version "0.4.0")
    (source
     (origin
       (method url-fetch)
       (uri (crate-uri "triforce-lv2" version))
       (file-name (string-append name "-" version ".tar.gz"))
       (sha256
        (base32 "1rbbd4b7vkr6jg42frfgaj0wm467rzbcvn96wychr3als7gby9b3"))))
    (build-system cargo-build-system)
    (arguments
     (list
      #:install-source? #f
      #:phases
      #~(modify-phases %standard-phases
          (replace 'install
            (lambda* (#:key outputs #:allow-other-keys)
              (setenv "LIBDIR" (string-append (assoc-ref outputs "out") "/lib"))
              (invoke "make" "install"))))))
    (inputs (cargo-inputs 'triforce-lv2
                          #:module '(engstrand asahi crates)))
    (home-page "https://github.com/chadmed/triforce")
    (synopsis "Beamformer for Apple Silicon laptops")
    (description
     "This package provides a minimum variance, distortion-less response
adaptive beamformer for the microphone array found in Apple Silicon
laptops.")
    (license license:gpl2+)))

(define-record-type* <alsa-configuration>
  alsa-configuration make-alsa-configuration alsa-configuration?
  (alsa-ucm-conf alsa-configuration-alsa-ucm-conf
                 (default asahi-alsa-ucm-conf))
  (alsa-plugins alsa-configuration-alsa-plugins
                (default linux:alsa-plugins))
  (pipewire alsa-configuration-pipewire
            (default asahi-pipewire))
  (extra-options alsa-configuration-extra-options
                 (default "")))

(define (alsa-config-ucm2 config)
  (let ((alsa-ucm-conf (alsa-configuration-alsa-ucm-conf config)))
    #~(string-append #$alsa-ucm-conf "/share/alsa/ucm2")))

(define (alsa-environment-variables config)
  `(("ALSA_CONFIG_UCM2" . ,(alsa-config-ucm2 config))))

(define (alsa-pipewire-config-file config)
  (let ((pipewire (alsa-configuration-pipewire config)))
    #~(string-append #$pipewire "/share/alsa/alsa.conf.d/50-pipewire.conf")))

(define (alsa-pipewire-default-config-file config)
  (let ((pipewire (alsa-configuration-pipewire config)))
    #~(string-append #$pipewire "/share/alsa/alsa.conf.d/99-pipewire-default.conf")))

(define (alsa-etc-service config)
  `(("alsa/conf.d/50-pipewire.conf" ,(alsa-pipewire-config-file config))
    ("alsa/conf.d/50-pipewire-default.conf" ,(alsa-pipewire-default-config-file config))))

(define (alsa-profile-entries config)
  (list (alsa-configuration-alsa-plugins config)
        (alsa-configuration-alsa-ucm-conf config)
        (alsa-configuration-pipewire config)))

(define-public alsa-service-type
  (service-type
   (name 'alsa)
   (extensions
    (list (service-extension etc-service-type
                             alsa-etc-service)
          (service-extension profile-service-type
                             alsa-profile-entries)
          (service-extension session-environment-service-type
                             alsa-environment-variables)))
   (default-value (alsa-configuration))
   (description "Configure low-level Linux sound support, ALSA.")))
