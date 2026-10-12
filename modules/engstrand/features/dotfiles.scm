(define-module (engstrand features dotfiles)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:export (feature-familiar-dotfiles))

(define %home-environment-service
  (simple-service 'familiar-environment home-environment-variables-service-type
    '(("EDITOR" . "nvim") ("VISUAL" . "nvim")
      ("CC" . "gcc")
      ("DOOMDIR" . "/home/engstrand/.config/doom")
      ("COLORTERM" . "truecolor")
      ("XCURSOR_THEME" . "Adwaita")
      ("XCURSOR_SIZE" . "20")
      ("GTK_THEME" . "Adwaita:dark")
      ;; Let launchers and xdg-open discover Flatpak's Chrome entry.
      ("XDG_DATA_DIRS" . "$XDG_DATA_DIRS:$HOME/.local/share/flatpak/exports/share:/var/lib/flatpak/exports/share"))))

(define (feature-familiar-dotfiles)
  (feature-package-set
   'familiar-dotfiles
   #:home-packages (list (specification->package "stow"))
   #:home-services (list %home-environment-service)))
