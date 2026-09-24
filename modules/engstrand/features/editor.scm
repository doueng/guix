(define-module (engstrand features editor)
  #:use-module (engstrand features packages)
  #:use-module (gnu packages)
  #:export (feature-familiar-editor %editor-home-packages))

(define %editor-home-packages
  (map specification->package
       '("emacs-no-x"
         "emacs-fish-mode"
         "parinfer-rust-emacs")))

(define (feature-familiar-editor)
  (feature-package-set
   'familiar-editor
   #:home-packages %editor-home-packages))
