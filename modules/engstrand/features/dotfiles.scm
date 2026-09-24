(define-module (engstrand features dotfiles)
  #:use-module (engstrand features packages)
  #:use-module (engstrand services home-environment)
  #:use-module (engstrand services home-files)
  #:use-module (engstrand services home-links)
  #:export (feature-familiar-dotfiles))

(define (feature-familiar-dotfiles)
  (feature-package-set
   'familiar-dotfiles
   ;; This feature deliberately retains mutable links into the checkout.
   #:home-services (list %familiar-direct-home-links
                         %home-environment-service
                         %home-files-service)))
