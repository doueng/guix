(define-module (engstrand packages herdr)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand packages babashka)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define herdr-version "0.9.3")

(define-public herdr
  (package
    (name "herdr")
    (version herdr-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/herdrdev/herdr/releases/download/v"
                           version "/herdr-linux-aarch64"))
       (file-name "herdr")
       (sha256
        (base32 "1h7mw4qy4dwqamg3q2pqy2hvr8daqbvn9pk057li52374lzamrsd"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("herdr" "bin/herdr"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'make-executable
            (lambda* (#:key outputs #:allow-other-keys)
              (chmod (string-append (assoc-ref outputs "out") "/bin/herdr") #o555))))))
    (synopsis "Terminal workspace multiplexer")
    (description "Herdr terminal multiplexer from the herdrdev/herdr release binaries.")
    (home-page "https://github.com/herdrdev/herdr")
    (license license:asl2.0)))

(define* (babashka-herdr-plugin #:key name version directory script
                                synopsis description home-page)
  "Package the Babashka Herdr plugin in desktop/build/herdr/DIRECTORY, whose
entry point SCRIPT becomes bin/NAME after its tests.bb passes."
  (let ((bin (string-append "bin/" name)))
    (package
      (name name)
      (version version)
      (source (package-asset (string-append "desktop/build/herdr/" directory)
                             (string-append directory "-source")
                             #:recursive? #t))
      (build-system copy-build-system)
      (arguments
       (list
        #:install-plan #~'((#$script #$bin)
                           ("manifest.toml" "herdr-plugin.toml"))
        #:phases
        #~(modify-phases %standard-phases
            (add-before 'install 'test-script
              (lambda _ (invoke #$(file-append babashka "/bin/bb") "tests.bb")))
            (add-after 'install 'set-interpreter
              (lambda* (#:key outputs #:allow-other-keys)
                (let ((script (string-append (assoc-ref outputs "out")
                                             #$(string-append "/" bin))))
                  (substitute* script
                    (("^#!/usr/bin/env bb")
                     (string-append "#!" #$(file-append babashka "/bin/bb"))))
                  (chmod script #o555)))))))
      (synopsis synopsis)
      (description description)
      (home-page home-page)
      (license license:expat))))

(define-public herdr-sesh
  (babashka-herdr-plugin
   #:name "herdr-sesh"
   #:version "0.8.0"
   #:directory "sesh"
   #:script "sesh.bb"
   #:synopsis "Babashka workspace picker for Herdr"
   #:description "Sesh-style Herdr workspace picker with zoxide and project tabs."
   #:home-page "https://github.com/fullerzz/herdr-plugin-sesh"))

(define-public herdr-tiny-fingers
  (babashka-herdr-plugin
   #:name "herdr-tiny-fingers"
   #:version "0.3.0"
   #:directory "tiny-fingers"
   #:script "finger.bb"
   #:synopsis "Babashka visible-screen copy hints for Herdr"
   #:description "Frame-free, position-preserving copy hints for Herdr panes."
   #:home-page "https://github.com/hotchpotch/herdr-tiny-fingers"))
