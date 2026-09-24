(define-module (engstrand packages babashka)
  #:use-module (gnu packages base)
  #:use-module (gnu packages elf)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define babashka-version "1.13.223")

;; The collection package pulls a large Go dependency tree and currently
;; fails in goresctrl's aarch64 tests.  GitHub publishes the same CLI as a
;; signed release binary, which is the appropriate small native package here.
;; Guix's pinned channel does not provide Babashka yet.  Use the upstream
;; native aarch64 release so the .bb scripts and #!/usr/bin/env bb helpers
;; work without a Clojure/JVM dependency tree.
(define-public babashka
  (package
    (name "babashka")
    (version babashka-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/babashka/babashka/releases/download/v"
                           version "/babashka-" version "-linux-aarch64-static.tar.gz"))
       (sha256
        (base32 "0qasmgb9zmvjz9ib5dxx62ak0hyxh8f0q2b548pk9w2vs18n0b05"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("bb" "bin/bb"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'patch-interpreter-and-rpath
            (lambda _
              (invoke #$(file-append patchelf "/bin/patchelf")
                      "--set-interpreter"
                      #$(file-append glibc "/lib/ld-linux-aarch64.so.1")
                      "--set-rpath" #$(file-append glibc "/lib")
                      (string-append #$output "/bin/bb")))))))
    (synopsis "Native Clojure scripting runtime")
    (description "Babashka, a fast native Clojure scripting runtime.")
    (home-page "https://babashka.org/")
    (license license:epl1.0)))
