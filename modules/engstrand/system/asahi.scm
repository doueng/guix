(define-module (engstrand system asahi)
  #:use-module (asahi guix config)
  #:use-module (asahi guix initrd)
  #:use-module ((asahi guix services sound) #:prefix asahi:)
  #:use-module (asahi guix services substitutes)
  #:use-module (asahi guix services udev)
  #:use-module (asahi guix systems base)
  #:use-module (asahi guix systems desktop)
  #:use-module (engstrand packages flatpak)
  #:use-module (engstrand system bootloader)
  #:use-module (engstrand system builder-keys)
  #:use-module (gnu)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages ncurses)
  #:use-module (gnu packages package-management)
  #:use-module (gnu packages ssh)
  #:use-module (gnu packages version-control)
  #:use-module (gnu packages vim)
  #:use-module (gnu services base)
  #:use-module (gnu services dbus)
  #:use-module (gnu services desktop)
  #:use-module (gnu services linux)
  #:use-module (gnu services networking)
  #:use-module (gnu services sound)
  #:use-module (gnu services ssh)
  #:use-module (gnu services sysctl)
  #:use-module (guix gexp)
  #:use-module (srfi srfi-1)
  #:export (make-base-os))

(define %offload-key-activation
  (simple-service 'familiar-offload-key activation-service-type
    (with-imported-modules '((guix build utils))
      #~(begin
         (use-modules (guix build utils))
         (let ((key "/etc/guix/offload/id_ed25519"))
           (unless (file-exists? key)
             (mkdir-p "/etc/guix/offload")
             (chmod "/etc/guix/offload" #o755)
             (system* #$(file-append openssh "/bin/ssh-keygen")
                      "-q" "-t" "ed25519" "-N" "" "-C" "asahi-guix offload"
                      "-f" key)
             (chmod key #o600)
             (chmod (string-append key ".pub") #o644)))))))

(define %builder-signing-key (builder-key-file "builder-signing.pub"))
(define %builder-host-key (builder-key-string "host-key.pub"))
(define %builder? (and %builder-signing-key %builder-host-key #t))

(define %builder-machines
  (if %builder?
      (list #~(build-machine
               (name #$%builder-host)
               (systems '("aarch64-linux"))
               (port #$%builder-ssh-port)
               (user "offload")
               (host-key #$%builder-host-key)
               (private-key "/etc/guix/offload/id_ed25519")
               (parallel-builds 1)
               (overload-threshold 0.8)))
      '()))

(define %local-max-jobs "0")

(define %earlyoom-kills-log "/var/log/earlyoom-kills.log")

(define %earlyoom-kill-hook
  (computed-file "earlyoom-kill-hook"
    #~(begin
        (call-with-output-file #$output
          (lambda (port)
            (format port "#!~a
umask 022
name=${EARLYOOM_NAME//[^[:graph:]]/_}
printf '%(%Y-%m-%dT%H:%M:%S%z)T pid=%s uid=%s name=%s\\n' -1 \"${EARLYOOM_PID}\" \"${EARLYOOM_UID}\" \"$name\" >> ~a
"
                    #$(file-append bash-minimal "/bin/bash")
                    #$%earlyoom-kills-log)))
        (chmod #$output #o555))))

(define %earlyoom-kills-log-activation
  (simple-service 'familiar-earlyoom-kills-log activation-service-type
    #~(let ((log #$%earlyoom-kills-log))
        (close-port (open-file log "a"))
        (chmod log #o644))))

(define* (make-base-os #:key root-uuid esp-uuid channels)
  (unless (and (string? root-uuid) (string? esp-uuid) (pair? channels))
    (error "Supply root/ESP UUIDs and pinned channels"))
  (operating-system
    (inherit asahi-base-os)
    (kernel asahi-linux-keyd)
    (kernel-loadable-modules (list asahi-linux-keyd-ane))
    (host-name "asahi-guix")
    (timezone "Europe/London")
    (locale "en_US.utf8")
    (keyboard-layout (keyboard-layout "us"))
    (bootloader
     (bootloader-configuration
      (inherit (operating-system-bootloader asahi-base-os))
      (bootloader m1n1-u-boot-grub-bootloader-os-prepare)))
    (initrd-modules (cons* "uas" asahi-initrd-modules))
    (users
     (cons* (user-account
             (name "root")
             (group "root")
             (uid 0)
             (home-directory "/root")
             (password #f))
            %base-user-accounts))
    (file-systems
     (cons* (file-system
              (device (uuid root-uuid))
              (mount-point "/")
              (needed-for-boot? #t)
              (type "btrfs")
              ;; Early boot passes options verbatim to mount(2), so atime
              ;; must be a flag; btrfs rejects "noatime" as EINVAL.
              (flags '(no-atime))
              (options "compress=zstd:1"))
            (file-system
              (device (uuid esp-uuid 'fat))
              (mount-point "/boot/efi")
              (needed-for-boot? #t)
              (type "vfat")
              (options "umask=0077"))
            %base-file-systems))
    (packages
     (cons* flatpak/stable-exports ncurses git neovim brightnessctl alsa-utils
            (operating-system-packages asahi-base-os)))
    (services
     (append
      (list %udev-backlight-service
            %offload-key-activation
            %earlyoom-kills-log-activation
            (udev-rules-service 'ane-accel
                                (udev-rule "70-ane-accel.rules"
                                           "SUBSYSTEM==\"accel\", GROUP=\"video\", MODE=\"0660\"\n"))
            %asahi-sddm-service
            (service kernel-module-loader-service-type '("asahi" "appledrm"))
            (service zram-device-service-type
                     (zram-device-configuration
                      (size "8G")
                      ;; The Asahi kernel builds only the LZO zram backend.
                      (compression-algorithm 'lzo-rle)
                      (priority 100)))
            (simple-service 'familiar-zram-sysctl sysctl-service-type
                            '(("vm.swappiness" . "100")
                              ("vm.page-cluster" . "0")))
            (service earlyoom-service-type
                     (earlyoom-configuration
                      (minimum-available-memory 10)
                      (minimum-free-swap 40)
                      (prefer-regexp
                       "^(bb|ld-linux-aarch6|claude|java|chromium|headless_shell)$")
                      (avoid-regexp
                       "^(Hyprland|start-hyprland|Xwayland|X|\\.sddm.*|\\.?emacs.*|kanata|herdr|shepherd|pipewire.*|wireplumber|elogind|dbus-daemon|guix-daemon)$")
                      (memory-report-interval 60)
                      (send-notification-command %earlyoom-kill-hook)))
            (service btrfs-scrub-service-type
                     (btrfs-scrub-configuration
                      (schedule "0 12 * * *")))
            (service asahi:alsa-service-type)
            (service speakersafetyd-service-type)
            (service rtkit-service-type)
            (service elogind-service-type)
            (service dbus-root-service-type)
            (service polkit-service-type)
            polkit-wheel-service
            (service udisks-service-type)
            (service upower-service-type)
            (service ntp-service-type)
            (service x11-socket-directory-service-type))
      (modify-services (operating-system-user-services asahi-base-os)
        (delete openssh-service-type)
        (delete asahi-substitutes-service-type)
        (guix-service-type
         config => (guix-configuration
                     (inherit config)
                     (channels channels)
                     (authorized-keys (append %asahi-substitute-keys
                                              (if %builder? (list %builder-signing-key) '())
                                              (guix-configuration-authorized-keys config)))
                     (substitute-urls (append (if %builder? (list %builder-publish-url) '())
                                              '("https://bordeaux.guix.gnu.org")
                                              %asahi-substitute-urls
                                              '("https://ci.guix.gnu.org")))
                     (build-machines (append %builder-machines
                                             (guix-configuration-build-machines config)))
                     (extra-options (list (string-append "--max-jobs=" %local-max-jobs)
                                          "--cores=4")))))))))
