(define-module (engstrand features desktop)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages bluetui)
  #:use-module (engstrand packages ghostty)
  #:use-module (engstrand packages noctalia)
  #:use-module (engstrand services bluetooth)
  #:use-module (engstrand services home-mime)
  #:use-module (asahi guix systems desktop)
  #:use-module (gnu packages)
  #:export (feature-familiar-desktop %desktop-home-packages))

(define %desktop-home-packages
  (append (list bluetui noctalia ghostty)
          (map specification->package
               '("thunar"
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

(define (feature-familiar-desktop)
  (feature-package-set
   'familiar-desktop
   #:home-packages %desktop-home-packages
   #:system-packages %desktop-system-packages
   #:system-services (list %bluetooth-service)
   #:home-services (append %asahi-desktop-home-services
                           (list %home-mime-service))))
