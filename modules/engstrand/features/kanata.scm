(define-module (engstrand features kanata)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages assets)
  #:use-module (gnu packages rust-apps)
  #:use-module (gnu services)
  #:use-module (gnu services base)
  #:use-module (gnu services linux)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-kanata))

;; hyprkan sends layer changes here; moshi-hook already polls 7070. The build
;; has no cmd feature, so clients can switch layers and reload but cannot run
;; commands.
(define %kanata-address "127.0.0.1:7333")

(define %kanata-service
  (simple-service 'familiar-kanata shepherd-root-service-type
    (list (shepherd-service
            (provision '(kanata))
            (requirement '(udev kernel-module-loader))
            (documentation "Personal keyboard layers.")
            ;; The /etc path lets a reload pick up the switched configuration.
            (start #~(make-forkexec-constructor
                       (list #$(file-append kanata "/bin/kanata")
                             "--cfg" "/etc/kanata/kanata.kbd"
                             "--port" #$%kanata-address)
                       #:log-file "/var/log/kanata.log"))
            (stop #~(make-kill-destructor))))))

(define (feature-familiar-kanata)
  (feature-package-set
   'familiar-kanata
   #:system-packages (list kanata)
   #:system-services
   (list %kanata-service
         (simple-service 'familiar-uinput kernel-module-loader-service-type
                         '("uinput"))
         (simple-service 'familiar-kanata-config etc-service-type
                         `(("kanata/kanata.kbd"
                            ,(package-asset "desktop/kanata.kbd")))))))
