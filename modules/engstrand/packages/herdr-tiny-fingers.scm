(define-module (engstrand packages herdr-tiny-fingers)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand packages babashka)
  #:use-module (guix build-system copy)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define %tiny-fingers-source
  (package-asset "desktop/build/herdr/tiny-fingers" "tiny-fingers-source"
                 #:recursive? #t))

(define-public herdr-tiny-fingers
  (package
    (name "herdr-tiny-fingers")
    (version "0.3.0")
    (source %tiny-fingers-source)
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan #~'(("finger.bb" "bin/herdr-tiny-fingers")
                         ("manifest.toml" "herdr-plugin.toml"))
      #:phases
      #~(modify-phases %standard-phases
          (add-before 'install 'test-script
            (lambda _ (invoke #$(file-append babashka "/bin/bb") "tests.bb")))
          (add-after 'install 'set-interpreter
            (lambda* (#:key outputs #:allow-other-keys)
              (let ((script (string-append (assoc-ref outputs "out")
                                           "/bin/herdr-tiny-fingers")))
                (substitute* script
                  (("^#!/usr/bin/env bb")
                   (string-append "#!" #$(file-append babashka "/bin/bb"))))
                (chmod script #o555)))))))
    (synopsis "Babashka visible-screen copy hints for Herdr")
    (description "Frame-free, position-preserving copy hints for Herdr panes.")
    (home-page "https://github.com/hotchpotch/herdr-tiny-fingers")
    (license license:expat)))
