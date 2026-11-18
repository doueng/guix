(define-module (engstrand config)
  #:use-module (engstrand features agent-control)
  #:use-module (engstrand features bbh-web)
  #:use-module (engstrand features builder-alert)
  #:use-module (engstrand features chrome-extensions)
  #:use-module (engstrand features md-view)
  #:use-module (engstrand features desktop)
  #:use-module (engstrand features development)
  #:use-module (engstrand features dotfiles)
  #:use-module (engstrand features editor)
  #:use-module (engstrand features herdr)
  #:use-module (engstrand features kanata)
  #:use-module (engstrand features remote-access)
  #:use-module (engstrand features research-lab)
  #:use-module (engstrand features second-brain)
  #:use-module (engstrand features shell)
  #:use-module (engstrand features system-design-prep)
  #:use-module (engstrand features tailscale)
  #:use-module (engstrand features terminal-mux)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand system asahi)
  #:use-module (gnu)
  #:use-module (gnu packages admin)
  #:use-module (guix channels)
  #:use-module (rde features)
  #:use-module (rde features base)
  #:export (%familiar-config))

;; The terminal multiplexer: 'herdr, 'rex or 'ghostty-rex (T207). make mux
;; MUX=rex or MUX=ghostty-rex switches, make mux MUX=herdr rolls back; see
;; desktop/build/ghostty-rex/README.md.
(define %terminal-mux 'ghostty-rex)

(define %root-uuid "c4f25409-b1a5-4ef0-8ac9-8e75f011668c")
(define %esp-uuid "5CDF-1DF4")

(define %familiar-config
  (let* ((base (make-base-os #:root-uuid %root-uuid
                             #:esp-uuid %esp-uuid
                             #:channels (primitive-load
                                         (string-append %repo-dir "/channels.scm"))))
         (features
          (list
           (feature-user-info
            #:user-name "engstrand"
            #:full-name "Engstrand"
            #:email ""
            #:user-groups '("wheel" "netdev" "audio" "video"))
           (feature
            (name 'familiar-shepherd)
            (values `((shepherd . ,shepherd-1.0))))
           (feature-familiar-desktop)
           (feature-familiar-shell)
           (feature-familiar-editor)
           (feature-familiar-development)
           ;; This precedes dotfiles because Herdr activation consumes its live config.
           (feature-familiar-herdr)
           (feature-familiar-terminal-mux %terminal-mux)
           (feature-familiar-bbh-web)
           (feature-familiar-research-lab)
           (feature-familiar-builder-alert)
           (feature-familiar-second-brain)
           (feature-familiar-system-design-prep)
           (feature-familiar-chrome-extensions)
           (feature-familiar-md-view)
           (feature-familiar-dotfiles)
           (feature-familiar-kanata)
           (feature-familiar-agent-control)
           (feature-familiar-tailscale)
           (feature-familiar-remote-access)
           (feature-custom-services
            #:feature-name-prefix 'asahi-machine
            #:system-services (operating-system-user-services base)))))
    (rde-config
     (initial-os base)
     (features features)
     (integrate-he-in-os? #t))))
