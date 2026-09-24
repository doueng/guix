(define-module (engstrand features herdr)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages herdr)
  #:use-module (engstrand packages herdr-sesh)
  #:use-module (engstrand packages herdr-tiny-fingers)
  #:use-module (engstrand packages jjui)
  #:use-module (engstrand services herdr-plugins)
  #:use-module (gnu packages)
  #:export (feature-familiar-herdr %herdr-home-packages))

(define %herdr-home-packages
  (cons* herdr herdr-sesh herdr-tiny-fingers jjui
         (list
          (specification->package "jujutsu"))))

(define (feature-familiar-herdr)
  (feature-package-set
   'familiar-herdr
   #:home-packages %herdr-home-packages
   #:home-services (list %familiar-herdr-plugins)))
