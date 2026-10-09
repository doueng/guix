(define-module (engstrand packages pi-coding-agent)
  #:use-module (gnu packages base)
  #:use-module (gnu packages elf)
  #:use-module (gnu packages gcc)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define pi-version "1.0.0")

(define-public pi-coding-agent
  (package
    (name "pi-coding-agent")
    (version pi-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/earendil-works/pi/releases/download/v"
                           version "/pi-linux-arm64.tar.gz"))
       (sha256
        (base32 "06aq0k370xdr2fncsj0bjc52mwl1nv3mznsy7zfc2hqahgd3y2xn"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("." "share/pi"))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'patch-and-wrap
            (lambda _
              (let* ((output #$output)
                     (real (string-append output "/share/pi/pi"))
                     ;; Pi's children inherit LD_LIBRARY_PATH, so it must not
                     ;; shadow their libstdc++. Expose only libgcc_s, which glibc
                     ;; dlopens when QuickJS worker threads exit.
                     (library-path (string-append output "/lib/pi"))
                     (bin-dir (string-append output "/bin"))
                     (wrapper (string-append bin-dir "/pi")))
                ;; patchelf --set-rpath and --add-needed corrupt this Bun binary.
                (invoke #$(file-append patchelf "/bin/patchelf") "--set-interpreter"
                        #$(file-append glibc "/lib/ld-linux-aarch64.so.1") real)
                (mkdir-p library-path)
                (symlink (string-append #$gcc:lib "/lib/libgcc_s.so.1")
                         (string-append library-path "/libgcc_s.so.1"))
                (mkdir-p bin-dir)
                (call-with-output-file wrapper
                  (lambda (port)
                    (format port
                            "#!/bin/sh\nexport PI_SKIP_VERSION_CHECK=1\nexport LD_LIBRARY_PATH=~a${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\nexec ~a \"$@\"\n"
                            library-path
                            real)))
                (chmod wrapper #o555)
                (patch-shebang wrapper)))))))
    (synopsis "Minimal terminal coding harness")
    (description "Pi coding agent from the earendil-works/pi release binaries.")
    (home-page "https://github.com/earendil-works/pi")
    (license license:expat)))
