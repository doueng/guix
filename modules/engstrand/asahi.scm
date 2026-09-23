(define-module (engstrand asahi)
  #:use-module (asahi guix initrd)
  #:use-module (asahi guix services udev)
  #:use-module (asahi guix systems desktop)
  #:use-module (asahi guix systems sway)
  #:use-module (engstrand bootloader)
  #:use-module (gnu)
  #:use-module (gnu home)
  #:use-module (gnu packages ncurses)
  #:use-module (gnu packages package-management)
  #:use-module (gnu packages version-control)
  #:use-module (gnu packages vim)
  #:use-module (gnu services base)
  #:use-module (gnu services guix)
  #:use-module (gnu services ssh)
  #:export (make-ssd-os))

(define* (make-ssd-os #:key root-uuid esp-uuid channels)
  (unless (and (string? root-uuid) (string? esp-uuid) (pair? channels))
    (error "Supply root/ESP filesystem UUIDs and pinned channels"))
  (when (string-ci=? esp-uuid "5CDF-1DF4")
    (error "Refusing the existing NixOS ESP"))
  (operating-system
    (inherit asahi-sway-os)
    (kernel asahi-linux-keyd)
    (host-name "asahi-guix")
    (timezone "Europe/Amsterdam")
    (locale "en_US.utf8")
    (keyboard-layout (keyboard-layout "us"))
    (bootloader
     (bootloader-configuration
      (inherit (operating-system-bootloader asahi-sway-os))
      (bootloader m1n1-u-boot-grub-bootloader-os-prepare)))
    (initrd-modules (cons* "uas" asahi-initrd-modules))
    (users
     (cons* (user-account
            (name "engstrand")
            (comment "Engstrand")
            (group "users")
            (home-directory "/home/engstrand")
            ;; #f preserves a passwd(1)-managed password across reconfigure.
            ;; Set an initial password after a fresh installation.
            (password #f)
            (supplementary-groups '("wheel" "netdev" "audio" "video")))
            (user-account
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
              (type "ext4"))
            (file-system
              (device (uuid esp-uuid 'fat32))
              (mount-point "/boot/efi")
              (needed-for-boot? #t)
              (type "vfat")
              (options "umask=0077"))
            %base-file-systems))
    (packages
     (cons* flatpak ncurses git neovim
            (operating-system-packages asahi-sway-os)))
    (services
     (cons* %udev-backlight-service
            (modify-services (operating-system-user-services asahi-sway-os)
       (delete openssh-service-type)
       (guix-home-service-type
        homes => `(("engstrand" ,(home-environment
                                  (services %asahi-desktop-home-services)))))
       (guix-service-type
        config => (guix-configuration
                    (inherit config)
                    (channels channels)
                    (extra-options '("--max-jobs=1" "--cores=4")))))))))
