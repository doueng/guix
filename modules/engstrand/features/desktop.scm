(define-module (engstrand features desktop)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages bluetui)
  #:use-module (engstrand packages ghostty)
  #:use-module (asahi guix systems desktop)
  #:use-module (gnu home services xdg)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:use-module (gnu services desktop)
  #:export (feature-familiar-desktop))

(define %desktop-home-packages
  (append (list bluetui ghostty)
          (map specification->package
               '("thunar"
                 "imv"
                 "xdg-utils"
                 "xdg-desktop-portal"
                 "xdg-desktop-portal-gtk"
                 "xdg-desktop-portal-hyprland"
                 "wl-clipboard"))))

(define %desktop-system-packages
  (append
   (map specification->package
        '("bluez"
          "hyprland"
          "polkit-gnome"
          "grim"
          "slurp"
          "libnotify"
          "font-jetbrains-mono"
          "font-google-noto-emoji"
          "adwaita-icon-theme"))))

(define %home-mime-service
  (service home-xdg-mime-applications-service-type
    (home-xdg-mime-applications-configuration
      (default '(("x-scheme-handler/http" . "com.google.Chrome.desktop")
                 ("x-scheme-handler/https" . "com.google.Chrome.desktop")
                 ("text/html" . "com.google.Chrome.desktop")
                 ("inode/directory" . "thunar.desktop")
                 ("image/jpeg" . "imv.desktop")
                 ("image/png" . "imv.desktop")
                 ("image/webp" . "imv.desktop"))))))

(define (feature-familiar-desktop)
  (feature-package-set
   'familiar-desktop
   #:home-packages %desktop-home-packages
   #:system-packages %desktop-system-packages
   #:system-services (list (service bluetooth-service-type
                                    (bluetooth-configuration (auto-enable? #t))))
   #:home-services (append %asahi-desktop-home-services
                           (list %home-mime-service))))
