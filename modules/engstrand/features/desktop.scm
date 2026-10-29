(define-module (engstrand features desktop)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages bluetui)
  #:use-module (engstrand packages omarchy-mlx)
  #:use-module (engstrand nixpkgs)
  #:use-module (asahi guix systems desktop)
  #:use-module (gnu home services xdg)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:use-module (gnu services desktop)
  #:export (feature-familiar-desktop))

(define %desktop-home-packages
  (append (list bluetui
                ;; desktop/bin/dictate: speech to text on the Neural Engine.
                omarchy-mlx-parakeet
                ;; Launchers may not inherit Home's LOCALE_ARCHIVE.
                (nixpkgs-package "ghostty"
                                 #:environment
                                 `(("LOCALE_ARCHIVE" ,(nixpkgs-locale-archive)))))
          (map specification->package
               '("thunar"
                 "imv"
                 "xdg-utils"
                 "xdg-desktop-portal"
                 "xdg-desktop-portal-gtk"
                 "xdg-desktop-portal-hyprland"
                 "wl-clipboard"
                 "wtype"))))

(define %desktop-system-packages
  (append
   (map specification->package
        '("bluez"
          "hyprland"
          "polkit-gnome"
          "grim"
          "slurp"
          "libnotify"
          "font-dejavu"
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

;; Flatpak resolves Chrome's xdg-download grant from user-dirs.dirs; without
;; it the sandbox writes ~/Downloads to a tmpfs that vanishes on exit.
;; Directories pointed at $HOME are disabled, so activation skips them.
(define %home-user-dirs-service
  (service home-xdg-user-directories-service-type
    (home-xdg-user-directories-configuration
      (documents "$HOME")
      (music "$HOME")
      (projects "$HOME")
      (publicshare "$HOME")
      (templates "$HOME")
      (videos "$HOME"))))

(define (feature-familiar-desktop)
  (feature-package-set
   'familiar-desktop
   #:home-packages %desktop-home-packages
   #:system-packages %desktop-system-packages
   #:system-services (cons (service bluetooth-service-type
                                    (bluetooth-configuration (auto-enable? #t)))
                           (nixpkgs-system-services))
   #:home-services (append %asahi-desktop-home-services
                           (list %home-mime-service
                                 %home-user-dirs-service))))
