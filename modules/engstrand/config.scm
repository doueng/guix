(define-module (engstrand config)
  #:use-module (engstrand features desktop)
  #:use-module (engstrand features development)
  #:use-module (engstrand features dotfiles)
  #:use-module (engstrand features editor)
  #:use-module (engstrand features herdr)
  #:use-module (engstrand features keyd)
  #:use-module (engstrand features moshi)
  #:use-module (engstrand features remote-access)
  #:use-module (engstrand features shell)
  #:use-module (engstrand features tailscale)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand system asahi)
  #:use-module (gnu)
  #:use-module (gnu packages admin)
  #:use-module (guix channels)
  #:use-module (rde features)
  #:use-module (rde features base)
  #:export (%familiar-config))

(define %root-uuid "c4f25409-b1a5-4ef0-8ac9-8e75f011668c")
(define %esp-uuid "5CDF-1DF4")

(define %familiar-config
  (let* ((base (make-base-os #:root-uuid %root-uuid
                             #:esp-uuid %esp-uuid
                             #:channels (primitive-load
                                         (string-append %repo-dir "/channels.scm"))))
         ;; User identity is contributed by feature-user-info, not this
         ;; machine-level operating-system.
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
           (feature-familiar-moshi)
           (feature-familiar-dotfiles)
           (feature-familiar-keyd)
           (feature-familiar-tailscale)
           (feature-familiar-remote-access)
           ;; rde rebuilds the OS service list from feature services. Preserve
           ;; every user service supplied by the Asahi machine configuration.
           (feature-custom-services
            #:feature-name-prefix 'asahi-machine
            #:system-services (operating-system-user-services base)))))
    (rde-config
     (initial-os base)
     (features features)
     (integrate-he-in-os? #t))))
