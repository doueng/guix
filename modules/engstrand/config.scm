(define-module (engstrand config)
  #:use-module (engstrand features desktop)
  #:use-module (engstrand features development)
  #:use-module (engstrand features dotfiles)
  #:use-module (engstrand features editor)
  #:use-module (engstrand features herdr)
  #:use-module (engstrand features keyd)
  #:use-module (engstrand features shell)
  #:use-module (engstrand system asahi)
  #:use-module (gnu)
  #:use-module (gnu packages admin)
  #:use-module (guix packages)
  #:use-module (rde features)
  #:use-module (rde features base)
  #:use-module (srfi srfi-1)
  #:export (make-familiar-config %familiar-home-packages))

;; Keep the preview manifest in sync with the explicit package features.
(define %familiar-home-packages
  (append %desktop-home-packages
          %shell-home-packages
          %editor-home-packages
          %development-home-packages
          %herdr-home-packages))

(define* (make-familiar-config #:key root-uuid esp-uuid channels)
  (let* ((base (make-base-os #:root-uuid root-uuid
                             #:esp-uuid esp-uuid
                             #:channels channels))
         ;; User identity is contributed by feature-user-info, not this
         ;; machine-level operating-system.
         (initial-os
          (operating-system
            (inherit base)
            (packages
             (remove (lambda (package)
                       (member (package-name package) '("sway" "foot")))
                     (operating-system-packages base)))))
         (features
          (list
           (feature-user-info
            #:user-name "engstrand"
            #:full-name "Engstrand"
            #:email ""
            #:user-groups '("wheel" "netdev" "audio" "video"))
           ;; Supply the package value rde needs without adopting its patched
           ;; Shepherd or adding a Home Shepherd service during this migration.
           (feature
            (name 'familiar-shepherd)
            (values `((shepherd . ,shepherd-1.0))))
           (feature-familiar-desktop)
           (feature-familiar-shell)
           (feature-familiar-editor)
           (feature-familiar-development)
           ;; This precedes dotfiles because Herdr activation consumes its live config.
           (feature-familiar-herdr)
           (feature-familiar-dotfiles)
           (feature-familiar-keyd)
           ;; rde rebuilds the OS service list from feature services. Preserve
           ;; every user service supplied by the Asahi machine configuration.
           (feature-custom-services
            #:feature-name-prefix 'asahi-machine
            #:system-services (operating-system-user-services base)))))
    (rde-config
     (initial-os initial-os)
     (features features)
     (integrate-he-in-os? #t))))
