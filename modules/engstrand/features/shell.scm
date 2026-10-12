(define-module (engstrand features shell)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shells)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:export (feature-familiar-shell))

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
   #:home-services (list (service home-bash-service-type))))
