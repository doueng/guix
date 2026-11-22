(define-module (engstrand features video)
  #:use-module (engstrand asahi video)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu packages video)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-video))

;; Hardware video decode on the AVD and the camera denoiser (T213).  The
;; decoder firmware makes apple_avd probe at boot; nothing decodes on it
;; until a player asks for VA-API: mpv --hwdec=vaapi, or Chrome with the
;; features in docs/aurora-silicon-adopted.html.

(define %avd-firmware-service
  (simple-service 'familiar-avd-firmware firmware-service-type
                  (list asahi-avd-firmware)))

;; libva looks in Mesa's lib/dri unless told otherwise, and Mesa has no VA-API
;; driver for this GPU.
(define %libva-drivers-service
  (simple-service 'familiar-libva-drivers home-environment-variables-service-type
                  '(("LIBVA_DRIVERS_PATH" . "$HOME/.guix-home/profile/lib/dri"))))

;; The flatpak Chrome has its own libva and sees neither the Home profile nor
;; the store.  The driver needs only libva.so.2 and libc.so.6, which Chrome
;; has already loaded, so it runs from the store as it is.
(define %chrome-libva-service
  (simple-service 'familiar-chrome-libva home-activation-service-type
    #~(let ((flatpak "/run/current-system/profile/bin/flatpak"))
        (when (file-exists? flatpak)
          (system* flatpak "override" "--user"
                   (string-append "--filesystem=" #$libva-v4l2-request-avd ":ro")
                   (string-append "--env=LIBVA_DRIVERS_PATH="
                                  #$libva-v4l2-request-avd "/lib/dri")
                   "com.google.Chrome")))))

;; Adds a denoised node beside the apple-isp camera; the camera is touched only
;; while an app streams from it.  Settings: ~/.config/aurora-camera-denoise/.
(define %camera-denoise-service
  (simple-service 'familiar-camera-denoise home-shepherd-service-type
    (list (shepherd-service
           (provision '(camera-denoise))
           (requirement '(pipewire wireplumber))
           (documentation "Denoise the built-in camera through PipeWire.")
           (respawn? #t)
           (respawn-limit #~'(5 . 60))
           (respawn-delay 5)
           (start #~(make-forkexec-constructor
                     (list #$(file-append camera-denoise
                                          "/bin/aurora-camera-denoise"))
                     #:log-file (string-append %user-log-dir
                                               "/camera-denoise.log")))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-video)
  (feature-package-set
   'familiar-video
   ;; mpv propagates lua 5.2, which clashes with the profile's lua; run it
   ;; with guix shell mpv -- mpv --hwdec=vaapi FILE.
   #:home-packages (list libva-v4l2-request-avd libva-utils)
   #:system-services (list %avd-firmware-service)
   #:home-services (list %libva-drivers-service
                         %chrome-libva-service
                         %camera-denoise-service)))
