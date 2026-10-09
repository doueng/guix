(define-module (engstrand system asahi)
  #:use-module (asahi guix config)
  #:use-module (asahi guix initrd)
  #:use-module ((asahi guix services sound) #:prefix asahi:)
  #:use-module (asahi guix services substitutes)
  #:use-module (asahi guix services udev)
  #:use-module (asahi guix systems base)
  #:use-module (asahi guix systems desktop)
  #:use-module (engstrand system bootloader)
  #:use-module (gnu)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages ncurses)
  #:use-module (gnu packages package-management)
  #:use-module (gnu packages version-control)
  #:use-module (gnu packages vim)
  #:use-module (gnu services base)
  #:use-module (gnu services dbus)
  #:use-module (gnu services desktop)
  #:use-module (gnu services guix)
  #:use-module (gnu services linux)
  #:use-module (gnu services networking)
  #:use-module (gnu services sound)
  #:use-module (gnu services ssh)
  #:use-module (gnu services sysctl)
  #:export (make-base-os))

(define* (make-base-os #:key root-uuid esp-uuid channels)
  (unless (and (string? root-uuid) (string? esp-uuid) (pair? channels))
    (error "Supply root/ESP UUIDs and pinned channels"))
  (operating-system
    (inherit asahi-base-os)
    (kernel asahi-linux-keyd)
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
     (cons* flatpak ncurses git neovim brightnessctl alsa-utils
            (operating-system-packages asahi-base-os)))
    (services
     (append
      (list %udev-backlight-service
            ;; Keep the Asahi display-manager Xorg configuration without the
            ;; Sway example packages, guest Home, or generic %desktop-services.
            %asahi-sddm-service
            (service kernel-module-loader-service-type '("asahi" "appledrm"))
            ;; Compressed swap in RAM; without swap, memory pressure ends in OOM kills.
            (service zram-device-service-type
                     (zram-device-configuration
                      (size "8G")
                      ;; The Asahi kernel builds only the LZO zram backend.
                      (compression-algorithm 'lzo-rle)
                      (priority 100)))
            ;; zram swap has no seek cost, so swap earlier and read single pages.
            (simple-service 'familiar-zram-sysctl sysctl-service-type
                            '(("vm.swappiness" . "100")
                              ("vm.page-cluster" . "0")))
            ;; On this hardware, the ALSA UCM and speaker protection are not
            ;; optional.  PipeWire and D-Bus for the user are in Guix Home.
            (service asahi:alsa-service-type)
            (service speakersafetyd-service-type)
            (service rtkit-service-type)
            ;; Minimal session/authorization services intentionally replace
            ;; Guix's broader %desktop-services.  Add others only when needed.
            ;; SDDM requires elogind; keep D-Bus, polkit, and media/power APIs.
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
        ;; It prepends its URL to the daemon's list; the list below owns the order.
        (delete asahi-substitutes-service-type)
        (guix-service-type
         config => (guix-configuration
                     (inherit config)
                     (channels channels)
                     (authorized-keys (append %asahi-substitute-keys
                                              (guix-configuration-authorized-keys config)))
                     ;; Prefer the general cache; Asahi and CI are signed fallbacks.
                     (substitute-urls (append '("https://bordeaux.guix.gnu.org")
                                              %asahi-substitute-urls
                                              '("https://ci.guix.gnu.org")))
                     (extra-options '("--max-jobs=1" "--cores=4")))))))))
