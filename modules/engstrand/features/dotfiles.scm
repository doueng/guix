(define-module (engstrand features dotfiles)
  #:use-module (engstrand features packages)
  #:use-module (engstrand services home-environment)
  #:use-module (gnu packages)
  #:export (feature-familiar-dotfiles))

(define (feature-familiar-dotfiles)
  (feature-package-set
   'familiar-dotfiles
   #:home-packages (list (specification->package "stow"))
   #:home-services (list %home-environment-service)))
