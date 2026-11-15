(define-module (engstrand features shell)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages fd)
  #:use-module (engstrand packages fish)
  #:use-module (engstrand packages fzf)
  #:use-module (engstrand packages zoxide)
  #:use-module (gnu home services shells)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:export (feature-familiar-shell))

(define %shell-home-packages
  (append
   (list fish fzf zoxide fd)
   (map specification->package
        '("ripgrep"
          "git"
          "neovim"
          "direnv"
          "jq"
          "bat"
          "btop"))))

(define (feature-familiar-shell)
  (feature-package-set
   'familiar-shell
   #:home-packages %shell-home-packages
   #:home-services (list (service home-bash-service-type))))
