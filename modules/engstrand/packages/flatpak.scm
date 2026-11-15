;; Flatpak writes its own /gnu/store path into exported desktop entries and
;; wrappers, which gc breaks after an upgrade; use the system profile's path.
;; FLATPAK_BINARY won't do: sudo drops it for system installs.
(define-module (engstrand packages flatpak)
  #:use-module (gnu packages package-management)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module (guix utils)
  #:export (flatpak/stable-exports))

(define flatpak/stable-exports
  (package
    (inherit flatpak)
    (arguments
     (substitute-keyword-arguments (package-arguments flatpak)
       ((#:phases phases)
        #~(modify-phases #$phases
            (add-after 'unpack 'export-stable-flatpak-path
              (lambda _
                (substitute* "common/flatpak-dir.c"
                  (("FLATPAK_BINDIR \"/flatpak\"")
                   "\"/run/current-system/profile/bin/flatpak\""))))))))))
