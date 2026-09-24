(define-module (engstrand packages pi-coding-agent)
  #:use-module (gnu packages base)
  #:use-module (gnu packages elf)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define pi-version "0.87.1")

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
        (base32 "129vpk5n1s3km1vc2888g6bgdfhdg0y4wzc5lhkhnicihjglljrn"))))
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
                     (loader #$(file-append glibc "/lib/ld-linux-aarch64.so.1"))
                     (rpath #$(file-append glibc "/lib"))
                     (patchelf-bin #$(file-append patchelf "/bin/patchelf"))
                     (real (string-append output "/share/pi/pi"))
                     (bin-dir (string-append output "/bin"))
                     (wrapper (string-append bin-dir "/pi")))
                (for-each
                 (lambda (file)
                   (when (and (elf-file? file)
                              (string-suffix? "/pi/pi" file))
                     ;; patchelf --set-rpath corrupts this large Bun binary.
                     ;; Supply its glibc directory through the wrapper instead.
                     (invoke patchelf-bin
                             "--set-interpreter" loader file)))
                 (find-files output))
                (mkdir-p bin-dir)
                (call-with-output-file wrapper
                  (lambda (port)
                    (format port
                            "#!/bin/sh\nexport PI_SKIP_VERSION_CHECK=1\nexport LD_LIBRARY_PATH=~a${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}\nexec ~a \"$@\"\n"
                            rpath
                            real)))
                (chmod wrapper #o555)
                (patch-shebang wrapper)))))))
    (synopsis "Minimal terminal coding harness")
    (description "Pi coding agent from the earendil-works/pi release binaries.")
    (home-page "https://github.com/earendil-works/pi")
    (license license:expat)))
