;; PipeWire and WirePlumber as Home services, with the Asahi speaker DSP
;; (asahi-audio) and LV2 plugins.  Copied from the asahi-guix channel
;; (https://codeberg.org/asahi-guix/channel, b9132769, home/services/sound.scm),
;; GPL-3.0-or-later.
(define-module (engstrand asahi home-sound)
  #:use-module ((gnu packages audio) #:prefix audio:)
  #:use-module ((gnu packages linux) #:prefix linux:)
  #:use-module (engstrand asahi sound)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu home services xdg)
  #:use-module (gnu home services)
  #:use-module (gnu packages music)
  #:use-module (gnu services configuration)
  #:use-module (guix gexp)
  #:use-module (guix records)
  #:use-module (ice-9 match)
  #:use-module (srfi srfi-1)
  #:export (home-pipewire-configuration
            home-pipewire-service-type))


;;;
;;; PipeWire support.
;;;

(define-configuration/no-serialization home-pipewire-configuration
  (alsa-ucm-conf
   (file-like asahi-alsa-ucm-conf)
   "The Alsa Use Case Manager to use.")
  (asahi-audio
   (file-like asahi-audio)
   "The Asahi Linux Audio package to use.")
  (bankstown
   (file-like audio:bankstown-lv2)
   "The Bass enhancer package to use.")
  (lsp-plugins
   (file-like lsp-plugins)
   "The Lsp Plugins package to use.")
  (pipewire
   (file-like asahi-pipewire)
   "The PipeWire package to use.")
  (triforce
   (file-like triforce-lv2)
   "The Triforce beamformer package to use.")
  (wireplumber
   (file-like linux:wireplumber)
   "The WirePlumber package to use.")
  (enable-pulseaudio?
   (boolean #t)
   "When true, enable PipeWire's PulseAudio emulation support, allowing
PulseAudio clients to use PipeWire transparently.")
  (pipewire-debug
   (string "I")
   "The value of the @env{PIPEWIRE_DEBUG} environment variable for the
PipeWire and PulseAudio shepherd services.")
  (wireplumber-debug
   (string "I")
   "The value of the @env{WIREPLUMBER_DEBUG} environment variable for the
WirePlumber shepherd service.")
  (wireplumber-pipewire-debug
   (string "")
   "When non-empty, the value of the @env{PIPEWIRE_DEBUG} environment
variable for the WirePlumber shepherd service.  The SPA Bluetooth monitor
and the HFP/HSP backend run inside WirePlumber and log through the PipeWire
log, so this is required to trace Bluetooth profile and SCO negotiation,
e.g. @code{\"2,spa.bluez5*:5\"}."))

(define (alsa-config-ucm2 config)
  #~(string-append #$(home-pipewire-configuration-alsa-ucm-conf config)
                   "/share/alsa/ucm2"))

(define (lv2-path config)
  (let ((lsp-plugins (home-pipewire-configuration-lsp-plugins config)))
    #~(string-append #$(home-pipewire-configuration-bankstown config) "/lib/lv2:"
                     #$(home-pipewire-configuration-triforce config) "/lib/lv2:"
                     #$lsp-plugins:lv2 "/lib/lv2")))

(define (pipewire-module-dir config)
  #~(string-append #$(home-pipewire-configuration-pipewire config)
                   "/lib/pipewire-0.3"))

(define (spa-plugin-dir config)
  #~(string-append #$(home-pipewire-configuration-pipewire config)
                   "/lib/spa-0.2"))

(define (wireplumber-module-dir config)
  #~(string-append #$(home-pipewire-configuration-wireplumber config)
                   "/lib/wireplumber-0.5"))

(define (home-pipewire-shepherd-service config)
  (shepherd-service
   (documentation "PipeWire media processing.")
   (modules '((shepherd support)))
   (provision '(pipewire))
   (requirement '(dbus))
   (start #~(make-forkexec-constructor
             (list #$(file-append
                      (home-pipewire-configuration-pipewire config)
                      "/bin/pipewire"))
             #:environment-variables
             (list (string-append "ALSA_CONFIG_UCM2="
                                  #$(alsa-config-ucm2 config))
                   (string-append "LV2_PATH="
                                  #$(lv2-path config))
                   (string-append "PIPEWIRE_DEBUG="
                                  #$(home-pipewire-configuration-pipewire-debug config))
                   (string-append "PIPEWIRE_MODULE_DIR="
                                  #$(pipewire-module-dir config))
                   (string-append "SPA_PLUGIN_DIR="
                                  #$(spa-plugin-dir config))
                   (string-append
                    "XDG_RUNTIME_DIR="
                    (or (getenv "XDG_RUNTIME_DIR")
                        (format #f "/run/user/~a" (getuid)))))
             #:log-file (string-append %user-log-dir "/pipewire.log")))
   (stop #~(make-kill-destructor))))

(define (home-pipewire-pulseaudio-shepherd-service config)
  (shepherd-service
   (documentation "Drop-in PulseAudio replacement service for PipeWire.")
   (modules '((shepherd support)))
   (provision '(pipewire-pulseaudio))
   (requirement '(pipewire))
   (start #~(make-forkexec-constructor
             (list #$(file-append
                      (home-pipewire-configuration-pipewire config)
                      "/bin/pipewire-pulse"))
             #:environment-variables
             (list (string-append "ALSA_CONFIG_UCM2="
                                  #$(alsa-config-ucm2 config))
                   (string-append "LV2_PATH="
                                  #$(lv2-path config))
                   (string-append "PIPEWIRE_DEBUG="
                                  #$(home-pipewire-configuration-pipewire-debug config))
                   (string-append "PIPEWIRE_MODULE_DIR="
                                  #$(pipewire-module-dir config))
                   (string-append "SPA_PLUGIN_DIR="
                                  #$(spa-plugin-dir config))
                   (string-append
                    "XDG_RUNTIME_DIR="
                    (or (getenv "XDG_RUNTIME_DIR")
                        (format #f "/run/user/~a" (getuid)))))
             #:log-file (string-append %user-log-dir "/pipewire-pulseaudio.log")))
   (stop #~(make-kill-destructor))))

(define (home-wireplumber-shepherd-service config)
  (shepherd-service
   (documentation "WirePlumber session management for PipeWire.")
   (modules '((shepherd support)))
   (provision '(wireplumber))
   (requirement '(pipewire))
   (start #~(make-forkexec-constructor
             (list #$(file-append
                      (home-pipewire-configuration-wireplumber config)
                      "/bin/wireplumber"))
             #:environment-variables
             (append
              (list (string-append "ALSA_CONFIG_UCM2="
                                   #$(alsa-config-ucm2 config))
                    (string-append "LV2_PATH="
                                   #$(lv2-path config))
                    (string-append "PIPEWIRE_MODULE_DIR="
                                   #$(pipewire-module-dir config))
                    (string-append "SPA_PLUGIN_DIR="
                                   #$(spa-plugin-dir config))
                    (string-append "WIREPLUMBER_MODULE_DIR="
                                   #$(wireplumber-module-dir config))
                    (string-append "WIREPLUMBER_DEBUG="
                                   #$(home-pipewire-configuration-wireplumber-debug config))
                    (string-append
                     "XDG_RUNTIME_DIR="
                     (or (getenv "XDG_RUNTIME_DIR")
                         (format #f "/run/user/~a" (getuid)))))
              #$(let ((pw-debug (home-pipewire-configuration-wireplumber-pipewire-debug config)))
                  (if (string=? pw-debug "")
                      #~'()
                      #~(list (string-append "PIPEWIRE_DEBUG=" #$pw-debug)))))
             #:log-file (string-append %user-log-dir "/wireplumber.log")))
   (stop #~(make-kill-destructor))))

(define (home-pipewire-shepherd-services config)
  (cons* (home-pipewire-shepherd-service config)
         (home-wireplumber-shepherd-service config)
         (if (home-pipewire-configuration-enable-pulseaudio? config)
             (list (home-pipewire-pulseaudio-shepherd-service config))
             '())))

(define (home-pipewire-asoundrc config)
  (match-record config <home-pipewire-configuration> (pipewire)
    (mixed-text-file
     "asoundrc"
     "<" pipewire "/share/alsa/alsa.conf.d/50-pipewire.conf>\n"
     "<" pipewire "/share/alsa/alsa.conf.d/99-pipewire-default.conf>\n"
     "pcm_type.pipewire {\n"
     "  lib \"" pipewire "/lib/alsa-lib/libasound_module_pcm_pipewire.so\"\n"
     "}\n"
     "ctl_type.pipewire {\n"
     "  lib \"" pipewire "/lib/alsa-lib/libasound_module_ctl_pipewire.so\"\n"
     "}\n")))

(define (home-pipewire-conf-dir config)
  (let ((asahi-audio (home-pipewire-configuration-asahi-audio config))
        (pipewire (home-pipewire-configuration-pipewire config)))
    (directory-union "pipewire-config"
                     (list (file-append pipewire "/share/pipewire")
                           (file-append asahi-audio "/share/pipewire")))))

(define (home-wireplumber-conf-dir config)
  (let ((asahi-audio (home-pipewire-configuration-asahi-audio config))
        (wireplumber (home-pipewire-configuration-wireplumber config)))
    (directory-union "wireplumber-config"
                     (list (file-append wireplumber "/share/wireplumber")
                           (file-append asahi-audio "/share/wireplumber")))))

(define home-pipewire-disable-pulseaudio-auto-start
  (plain-file "client.conf" "autospawn = no"))

(define (home-pipewire-xdg-configuration config)
  (cons* `("alsa/asoundrc" ,(home-pipewire-asoundrc config))
         `("pipewire" ,(home-pipewire-conf-dir config))
         `("wireplumber" ,(home-wireplumber-conf-dir config))
         (if (home-pipewire-configuration-enable-pulseaudio? config)
             `(("pulse/client.conf"
                ,home-pipewire-disable-pulseaudio-auto-start))
             '())))

(define (home-pipewire-profile-entries config)
  (let ((lsp-plugins (home-pipewire-configuration-lsp-plugins config)))
    (list (list lsp-plugins "lv2")
          (home-pipewire-configuration-pipewire config)
          (home-pipewire-configuration-wireplumber config))))

(define (home-pipewire-environment-variables config)
  `(("ALSA_CONFIG_UCM2" . ,(alsa-config-ucm2 config))))

(define home-pipewire-service-type
  (service-type
   (name 'pipewire)
   (extensions
    (list (service-extension home-environment-variables-service-type
                             home-pipewire-environment-variables)
          (service-extension home-profile-service-type
                             home-pipewire-profile-entries)
          (service-extension home-shepherd-service-type
                             home-pipewire-shepherd-services)
          (service-extension home-xdg-configuration-files-service-type
                             home-pipewire-xdg-configuration)))
   (description
    "Start essential PipeWire services.")
   (default-value (home-pipewire-configuration))))
