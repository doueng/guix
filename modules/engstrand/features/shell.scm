(define-module (engstrand features shell)
  #:use-module (engstrand features packages)
  #:use-module (engstrand nixpkgs)
  #:use-module (gnu home services shells)
  #:use-module (gnu packages)
  #:use-module (gnu services)
  #:export (feature-familiar-shell))

(define %shell-home-packages
  (append
   ;; git and neovim also stay in the system profile for root.
   (map nixpkgs-package
        '("fish" "fzf" "zoxide" "ripgrep" "fd" "git" "neovim"))
   (map specification->package
        '("direnv"
          "jq"
          "bat"
          "btop"))))

(define (feature-familiar-shell)
  (feature-package-set
   'familiar-shell
   #:home-packages %shell-home-packages
   #:home-services (list (service home-bash-service-type))))
