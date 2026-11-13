(define-module (engstrand packages dasel)
  #:use-module (engstrand packages release-binary)
  #:use-module ((guix licenses) #:prefix license:))

(define dasel-version "3.11.2")

(define-public dasel
  (let ((version dasel-version))
    (release-binary
     #:name "dasel"
     #:version version
     #:uri (string-append "https://github.com/TomWright/dasel/releases/download/v"
                          version "/" "dasel_linux_arm64")
     #:hash "1imwgigg6y5yqs93iz64gf6iglhcf4m67hyffdayxn1cg4594cyz"
     #:file "dasel"
     #:install-plan (list (list "dasel" "bin/"))
     #:synopsis "Query and modify JSON, YAML, TOML and XML"
     #:home-page "https://github.com/TomWright/dasel"
     #:license license:expat)))
