(define-module (engstrand features packages)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:use-module (rde features base)
  #:export (feature-package-set))

(define* (feature-package-set name
                              #:key
                              (home-packages '())
                              (system-packages '())
                              (home-services '())
                              (system-services '()))
  "Build a named rde feature from package sets and ordinary Guix services."
  (feature-custom-services
   #:feature-name-prefix name
   #:home-services
   (append home-services
           (if (null? home-packages)
               '()
               (list (simple-service
                      (string->symbol (string-append (symbol->string name)
                                                     "-home-packages"))
                      home-profile-service-type
                      home-packages))))
   #:system-services
   (append system-services
           (if (null? system-packages)
               '()
               (list (simple-service
                      (string->symbol (string-append (symbol->string name)
                                                     "-system-packages"))
                      profile-service-type
                      system-packages))))))
