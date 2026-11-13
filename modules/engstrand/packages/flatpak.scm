;; Flatpak names its own binary in the Exec line of every exported desktop entry
;; and in exported wrapper scripts, by default its /gnu/store path, which a
;; guix gc after a flatpak upgrade removes.  Name the system profile's instead.
;; FLATPAK_BINARY would override it per process, but sudo drops it for system
;; installs.
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
