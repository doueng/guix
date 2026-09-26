(define-module (engstrand packages herdr-sesh)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand packages babashka)
  #:use-module (guix build-system copy)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define %sesh-source
  (package-asset "desktop/build/herdr/sesh" "sesh-source" #:recursive? #t))

(define-public herdr-sesh
  (package
    (name "herdr-sesh")
    (version "0.8.0")
    (source %sesh-source)
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan #~'(("sesh.bb" "bin/herdr-sesh")
                         ("manifest.toml" "herdr-plugin.toml"))
      #:phases
      #~(modify-phases %standard-phases
          (add-before 'install 'test-script
            (lambda _ (invoke #$(file-append babashka "/bin/bb") "tests.bb")))
          (add-after 'install 'set-interpreter
            (lambda* (#:key outputs #:allow-other-keys)
              (let ((script (string-append (assoc-ref outputs "out")
                                           "/bin/herdr-sesh")))
                (substitute* script
                  (("^#!/usr/bin/env bb")
                   (string-append "#!" #$(file-append babashka "/bin/bb"))))
                (chmod script #o555)))))))
    (synopsis "Babashka workspace picker for Herdr")
    (description "Sesh-style Herdr workspace picker with zoxide and project tabs.")
    (home-page "https://github.com/fullerzz/herdr-plugin-sesh")
    (license license:expat)))
