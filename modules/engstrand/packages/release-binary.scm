(define-module (engstrand packages release-binary)
  #:use-module (gnu packages base)
  #:use-module (gnu packages elf)
  #:use-module (gnu packages gcc)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:export (release-binary))

;; Upstream's prebuilt aarch64 release of a tool Guix lags on or lacks.  The
;; source is a tarball, a gzipped binary or a bare binary, which unpack
;; leaves as FILE.  INSTALL-PLAN maps paths in the unpacked tree to the
;; output.  Most releases are static; DYNAMIC lists output paths of glibc
;; binaries to point at Guix's loader and libraries.
(define* (release-binary #:key name version uri hash install-plan file
                         (dynamic '()) synopsis home-page license)
  (package
    (name name)
    (version version)
    (source (origin
              (method url-fetch)
              (uri uri)
              (sha256 (base32 hash))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'#$install-plan
      #:phases
      #~(modify-phases %standard-phases
          (replace 'unpack
            (lambda* (#:key source #:allow-other-keys)
              (cond ((string-contains source ".tar")
                     (invoke "tar" "xf" source))
                    ((string-suffix? ".gz" source)
                     (invoke "sh" "-c" (string-append "gzip -dc " source " > " #$file)))
                    (else (copy-file source #$file)))
              (when #$file (chmod #$file #o755))))
          (delete 'strip)
          (add-after 'install 'patch-interpreter
            (lambda _
              (for-each
               (lambda (binary)
                 (invoke #$(file-append patchelf "/bin/patchelf")
                         "--set-interpreter"
                         #$(file-append glibc "/lib/ld-linux-aarch64.so.1")
                         "--set-rpath"
                         (string-append #$(file-append glibc "/lib") ":"
                                        #$(file-append (gexp-input gcc "lib") "/lib"))
                         (string-append #$output "/" binary)))
               '#$dynamic))))))
    (synopsis synopsis)
    (description synopsis)
    (home-page home-page)
    (license license)))
