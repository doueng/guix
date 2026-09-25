(define-module (engstrand services home-mime)
  #:use-module (gnu home services xdg)
  #:use-module (gnu services)
  #:export (%home-mime-service))

(define %home-mime-service
  (service home-xdg-mime-applications-service-type
    (home-xdg-mime-applications-configuration
      (default '(("x-scheme-handler/http" . "com.google.Chrome.desktop")
                 ("x-scheme-handler/https" . "com.google.Chrome.desktop")
                 ("text/html" . "com.google.Chrome.desktop")
                 ("inode/directory" . "thunar.desktop")
                 ("image/jpeg" . "imv.desktop")
                 ("image/png" . "imv.desktop")
                 ("image/webp" . "imv.desktop"))))))
