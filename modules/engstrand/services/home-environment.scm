(define-module (engstrand services home-environment)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:export (%home-environment-service))

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
