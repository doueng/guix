(define-module (engstrand services home-files)
  #:use-module (engstrand packages assets)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:export (%home-files-service))

(define %home-files-service
  (simple-service 'familiar-files home-files-service-type
    (list (list ".config/ghostty/themes/catppuccin-mocha"
                (package-asset "desktop/build/ghostty/themes/catppuccin-mocha")))))
