(define-module (engstrand features chrome-extensions)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:use-module (ice-9 format)
  #:export (feature-familiar-chrome-extensions))

;; Chrome extensions under development, by the name `ext-reload NAME' takes:
;; the project directory under $HOME, the unpacked directory in it, and the
;; build command run there.  See desktop/bin/ext-reload.
(define %dev-extensions
  '(("vimium-cljs" "projects/vimium-cljs" "ext" ("./build.sh"))
    ("cookie-autoaccept" "projects/cookie-autoaccept" "ext" ("npm" "run" "build"))))

(define (extensions-edn extensions)
  (string-append
   ";; Written by Guix: modules/engstrand/features/chrome-extensions.scm\n{"
   (string-join
    (map (lambda (e)
           (format #f "~s {:dir ~s :ext ~s :build [~{~s~^ ~}]}"
                   (car e) (string-append "~/" (cadr e)) (caddr e) (cadddr e)))
         extensions)
    "\n ")
   "}\n"))

(define %registry-service
  (simple-service 'familiar-ext-reload-registry home-xdg-configuration-files-service-type
    `(("ext-reload/extensions.edn" ,(plain-file "extensions.edn" (extensions-edn %dev-extensions))))))

;; Chrome reads unpacked extensions inside its Flatpak sandbox.  Grant each
;; project directory rather than its ext/: a build that deletes and recreates
;; ext/ would leave the sandbox's bind mount on the deleted directory.  Chrome
;; sees a new grant after its next start.
(define %flatpak-grants-service
  (simple-service 'familiar-ext-reload-flatpak-grants home-activation-service-type
    #~(let ((flatpak "/run/current-system/profile/bin/flatpak"))
        (when (file-exists? flatpak)
          (for-each (lambda (dir)
                      (system* flatpak "override" "--user"
                               (string-append "--filesystem=~/" dir ":ro")
                               "com.google.Chrome"))
                    '#$(map cadr %dev-extensions))))))

;; The hub ext-reload talks to; Chrome's helper extension holds a WebSocket to it.
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
                                (string-append home "/.local/bin/ext-reload") "serve")
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
   #:home-services (list %registry-service %flatpak-grants-service %hub-service)))
