;; System services for Apple Silicon.  Copied, with names shortened, from the
;; asahi-guix channel (https://codeberg.org/asahi-guix/channel, b9132769:
;; services/firmware.scm, udev.scm, systems/desktop.scm, build/modules.scm),
;; GPL-3.0-or-later.
(define-module (engstrand asahi services)
  #:use-module (gnu packages cpio)
  #:use-module (gnu packages fonts)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages xorg)
  #:use-module (gnu services)
  #:use-module (gnu services base)
  #:use-module (gnu services sddm)
  #:use-module (gnu services xorg)
  #:use-module (guix gexp)
  #:use-module (guix modules)
  #:use-module (ice-9 match)
  #:export (import-build-module?
            asahi-firmware-service-type
            console-font-terminus
            %udev-backlight-service
            %udev-mtd-by-name-service
            %asahi-sddm-service))

(define (import-build-module? module)
  "True for MODULE when build-side code may import it: Guix and engstrand
modules, minus those that need guile-git, guile-json or gcrypt."
  (and (match module
         (((or 'engstrand 'gnu 'guix) _ ...) #t)
         (_ #f))
       (not (member module '((guix store deduplication)
                             (guix config)
                             (guix git)
                             (guix git-authenticate)
                             (guix git-download)
                             (guix channels)
                             (guix swh))))))

;; Unpacks the firmware the Asahi installer left in /boot/efi/vendorfw into
;; /run/asahi-guix/firmware and points the kernel's firmware path there.
(define asahi-firmware-service-type
  (service-type
   (name 'asahi-firmware)
   (extensions
    (list (service-extension
           activation-service-type
           (const
            (with-imported-modules (source-module-closure
                                    '((engstrand build asahi-firmware))
                                    #:select? import-build-module?)
              #~(begin
                  (use-modules (engstrand build asahi-firmware))
                  (setup-firmware
                   (firmware
                    (cpio #$(file-append cpio "/bin/cpio"))
                    (blkid #$(file-append util-linux "/sbin/blkid"))))
                  #t))))
          (service-extension profile-service-type (const (list util-linux)))))
   (description "Load the Apple Silicon firmware from the ESP at boot.")
   (default-value #f)))

(define (console-font-terminus config)
  (map (lambda (tty)
         (cons tty (file-append font-terminus "/share/consolefonts/ter-132n")))
       (map car config)))

(define %udev-backlight-service
  (udev-rules-service
   'backlight
   (udev-rule
    "90-backlight.rules"
    (string-append
     "ACTION==\"add\", SUBSYSTEM==\"backlight\", "
     "RUN+=\"/run/current-system/profile/bin/chgrp video /sys/class/backlight/%k/brightness\""
     "\n"
     "ACTION==\"add\", SUBSYSTEM==\"backlight\", "
     "RUN+=\"/run/current-system/profile/bin/chmod g+w /sys/class/backlight/%k/brightness\""))))

;; /dev/mtd/by-name links, as in eudev and systemd upstream (eudev PR 267).
(define %udev-mtd-by-name-service
  (udev-rules-service
   'mtd-by-name
   (udev-rule
    "60-persistent-storage-mtd.rules"
    "ACTION==\"remove\", GOTO=\"persistent_storage_mtd_end\"
SUBSYSTEM!=\"mtd\", GOTO=\"persistent_storage_mtd_end\"
KERNEL!=\"mtd[0-9]*\", GOTO=\"persistent_storage_mtd_end\"
KERNEL==\"mtd[0-9]*ro\", GOTO=\"persistent_storage_mtd_end\"

ATTR{name}!=\"\", SYMLINK+=\"mtd/by-name/$attr{name}\"

LABEL=\"persistent_storage_mtd_end\"")))

(define %asahi-desktop-xorg-touchpads "
  Section \"InputClass\"
    Driver \"libinput\"
    Identifier \"Touchpads\"
    MatchDevicePath \"/dev/input/event*\"
    MatchIsTouchpad \"on\"
    Option \"DisableWhileTyping\" \"on\"
    Option \"MiddleEmulation\" \"on\"
    Option \"ScrollMethod\" \"twofinger\"
    Option \"TappingDrag\" \"on\"
    Option \"Tapping\" \"on\"
  EndSection\n")

(define %asahi-desktop-xorg-keyboards "
  Section \"InputClass\"
    Driver \"libinput\"
    Identifier \"Keyboards\"
    MatchDevicePath \"/dev/input/event*\"
    MatchIsKeyboard \"on\"
  EndSection\n")

(define %asahi-desktop-xorg-modesetting "
  Section \"OutputClass\"
    Driver \"modesetting\"
    Identifier \"appledrm\"
    MatchDriver \"apple\"
    Option \"PrimaryGPU\" \"true\"
  EndSection\n")

(define %asahi-sddm-service
  (service sddm-service-type
           (sddm-configuration
            (xorg-configuration
             (xorg-configuration
              (server xorg-server)
              (extra-config (list %asahi-desktop-xorg-keyboards
                                  %asahi-desktop-xorg-touchpads
                                  %asahi-desktop-xorg-modesetting)))))))
