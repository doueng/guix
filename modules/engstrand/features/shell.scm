(define-module (engstrand features shell)
  #:use-module (engstrand features packages)
  #:use-module (engstrand services home-shell)
  #:use-module (gnu packages)
  #:export (feature-familiar-shell %shell-home-packages))

(define %shell-home-packages
  (map specification->package
       '("fish"
         "fzf"
         "zoxide"
         "direnv"
         "ripgrep"
         "fd"
         "jq"
         "bat"
         "btop")))

(define (feature-familiar-shell)
  (feature-package-set
   'familiar-shell
   #:home-packages %shell-home-packages
   #:home-services (list %home-shell-service)))
