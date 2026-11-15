(define-module (engstrand features chrome-extensions)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-chrome-extensions))

(define %chrome-dir "guix/desktop/build/chrome")

(define %flatpak-grants-service
  (simple-service 'familiar-ext-reload-flatpak-grants home-activation-service-type
    #~(let ((flatpak "/run/current-system/profile/bin/flatpak"))
        (when (file-exists? flatpak)
          (system* flatpak "override" "--user"
                   (string-append "--filesystem=~/" #$%chrome-dir ":ro")
                   "com.google.Chrome")))))

(define %hub-service
  (simple-service 'familiar-ext-reload home-shepherd-service-type
    (list (shepherd-service
           (provision '(ext-reload))
           (documentation "Hub between the ext-reload command and its Chrome helper extension.")
           (respawn? #t)
           (respawn-limit #~'(5 . 60))
           (respawn-delay 2)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.guix-home/profile/bin/bb")
                                (string-append home "/guix/desktop/bin/ext-reload") "serve")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/ext-reload.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-chrome-extensions)
  (feature-package-set
   'familiar-chrome-extensions
   #:home-services (list %flatpak-grants-service %hub-service)))
