(define-module (engstrand packages plannotator)
  #:use-module (gnu packages base)
  #:use-module (gnu packages elf)
  #:use-module (guix build-system copy)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

;; bbh's Inbox bridge is an internal Plannotator contract, so bump this pin
;; together with a `bb test:inbox' run in desktop/build/bbh.
(define-public plannotator
  (package
    (name "plannotator")
    (version "0.28.38")
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/backnotprop/plannotator/releases/download/v"
                           version "/plannotator-linux-arm64"))
       (sha256
        (base32 "0hs9340qf8g6k2ay0l97lyjl72c8p8yvb41ipbdqxmglp8778rrd"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (arguments
     (list
      #:install-plan #~'(("plannotator-linux-arm64" "bin/plannotator"))
      #:validate-runpath? #f
      #:phases
      #~(modify-phases %standard-phases
          (delete 'strip)
          (add-after 'install 'patch-interpreter
            (lambda _
              (let ((real (string-append #$output "/bin/plannotator")))
                (chmod real #o755)
                ;; A compiled Bun binary respawns itself, so running it through
                ;; the loader breaks `inbox --background'.  Only the interpreter
                ;; changes, because a new RPATH can corrupt the embedded data.
                (invoke #$(file-append patchelf "/bin/patchelf")
                        "--set-interpreter"
                        #$(file-append glibc "/lib/ld-linux-aarch64.so.1")
                        real)
                (invoke real "--version")))))))
    (synopsis "Local inbox and review surface for coding agents")
    (description "Plannotator lets a person annotate agent plans and code
reviews.  Its Inbox is one local window where agents leave messages, questions
and files, and where replies go back to the session that asked.  This package
installs the upstream native ARM64 Linux release.")
    (home-page "https://plannotator.ai")
    (license (list license:expat license:asl2.0))))
