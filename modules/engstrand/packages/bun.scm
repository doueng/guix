(define-module (engstrand packages bun)
  #:use-module (gnu packages base)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages elf)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define-public bun
  (package
    (name "bun")
    (version "1.3.14")
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/oven-sh/bun/releases/download/bun-v"
                           version "/bun-linux-aarch64.zip"))
       (sha256
        (base32 "0fwsl5rijcv53j17rhw8ig8xia3zw656cvqdds1pa0rim1iznzx2"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (native-inputs (list unzip))
    (arguments
     (list
      #:install-plan #~'(("bun" "libexec/bun"))
      ;; The wrapper supplies glibc without rewriting Bun's RPATH.
      #:validate-runpath? #f
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'patch-and-wrap
            (lambda _
              (let* ((real (string-append #$output "/libexec/bun"))
                     (bin (string-append #$output "/bin"))
                     (wrapper (string-append bin "/bun")))
                ;; Changing Bun's RPATH can corrupt its embedded executable data.
                (invoke #$(file-append patchelf "/bin/patchelf")
                        "--set-interpreter"
                        #$(file-append glibc "/lib/ld-linux-aarch64.so.1")
                        real)
                (mkdir-p bin)
                (call-with-output-file wrapper
                  (lambda (port)
                    (format port
                            "#!/bin/sh\nexport LD_LIBRARY_PATH=~a${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\ncase \"$0\" in */bunx) exec ~a x \"$@\";; *) exec ~a \"$@\";; esac\n"
                            #$(file-append glibc "/lib") real real)))
                (chmod wrapper #o555)
                (patch-shebang wrapper)
                (symlink "bun" (string-append bin "/bunx"))
                (invoke wrapper "--version")))))))
    (synopsis "JavaScript and TypeScript runtime and toolkit")
    (description "Bun provides a JavaScript and TypeScript runtime, package
manager, test runner, and bundler.  This package installs the upstream native
ARM64 Linux release for the development environment.")
    (home-page "https://bun.sh")
    (license license:expat)))
