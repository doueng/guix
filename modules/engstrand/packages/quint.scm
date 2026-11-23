;; Quint, the specification language on TLA+'s logic, with the two tools it
;; would otherwise download into ~/.quint on first use: its Rust evaluator
;; (quint run and test) and Apalache (quint verify, on a Guix JDK).
(define-module (engstrand packages quint)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages elf)
  #:use-module (gnu packages gcc)
  #:use-module (gnu packages java)
  #:use-module (gnu packages node)
  #:use-module (engstrand packages quint-language-server)
  #:use-module (guix base32)
  #:use-module (guix build-system copy)
  #:use-module (guix build-system trivial)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

;; quint 0.34.0 looks for exactly these versions under $QUINT_HOME.
(define quint-evaluator-version "v0.8.0")
(define quint-apalache-version "0.62.4")

(define quint-evaluator-source
  (origin
    (method url-fetch)
    (uri (string-append "https://github.com/quint-co/quint/releases/download/evaluator%2F"
                        quint-evaluator-version
                        "/quint_evaluator-aarch64-unknown-linux-gnu.tar.gz"))
    (sha256 (base32 "1vr6y407rsgm48id6fq1rg5ijj10nx2x9balrj8h86n5dznfv95v"))))

(define-public apalache
  (package
    (name "apalache")
    (version quint-apalache-version)
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/apalache-mc/apalache/releases/download/v"
                           version "/apalache-" version ".tgz"))
       (sha256 (base32 "1x1fmb3jq56q1030r1bvrdjdqbnww4r06az0q95c3yijp8xvb0dp"))))
    (build-system copy-build-system)
    (inputs (list bash-minimal openjdk))
    (arguments
     (list
      #:install-plan #~'(("lib" "share/apalache/lib")
                         ("bin" "share/apalache/bin"))
      #:phases
      #~(modify-phases %standard-phases
          (add-after 'install 'wrap
            (lambda _
              (let ((real (string-append #$output "/share/apalache/bin/apalache-mc")))
                (patch-shebang real)
                (substitute* real
                  (("exec java ")
                   (string-append "exec " #$(this-package-input "openjdk") "/bin/java ")))
                (mkdir-p (string-append #$output "/bin"))
                (symlink real (string-append #$output "/bin/apalache-mc"))))))))
    (synopsis "Symbolic model checker for TLA+ and Quint")
    (description "Apalache checks TLA+ and Quint specifications with the Z3 SMT
solver, by bounded model checking and inductive invariants.  This package
installs the upstream JVM release and runs it on Guix's OpenJDK.")
    (home-page "https://apalache-mc.org")
    (license license:asl2.0)))

(define-public quint
  (package
    (name "quint")
    (version "0.34.0")
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://github.com/quint-co/quint/releases/download/v"
                           version "/quint-linux-arm64"))
       (sha256 (base32 "0qgxrpvripd5c4nsji6rw47zd9ca2g7gy8mkcr2y9a8jv65v0xx0"))))
    (build-system copy-build-system)
    (supported-systems '("aarch64-linux"))
    (native-inputs (list tar gzip))
    (inputs (list openjdk))
    ;; quint verify --backend tlc runs java -cp apalache.jar; apalache-mc on PATH too.
    (propagated-inputs (list apalache))
    (arguments
     (list
      #:install-plan #~'(("quint-linux-arm64" "libexec/quint/quint"))
      #:validate-runpath? #f
      #:phases
      #~(modify-phases %standard-phases
          (replace 'unpack
            (lambda* (#:key source #:allow-other-keys)
              (copy-file source "quint-linux-arm64")))
          (delete 'strip)
          (add-after 'install 'assemble
            (lambda _
              (let* ((ld #$(file-append glibc "/lib/ld-linux-aarch64.so.1"))
                     (real (string-append #$output "/libexec/quint/quint"))
                     (home (string-append #$output "/share/quint/home"))
                     (eval-dir (string-append home "/rust-evaluator-" #$quint-evaluator-version))
                     (evaluator (string-append eval-dir "/quint_evaluator"))
                     (dist (string-append home "/apalache-dist-" #$quint-apalache-version))
                     (lib (string-append #$output "/lib/quint"))
                     (bin (string-append #$output "/bin"))
                     (wrapper (string-append bin "/quint")))
                (chmod real #o755)
                ;; Changing this single-file JS executable's RPATH corrupts its
                ;; embedded payload, as with Bun and Pi: set the interpreter only.
                (invoke #$(file-append patchelf "/bin/patchelf") "--set-interpreter" ld real)
                (mkdir-p eval-dir)
                (invoke "tar" "xzf" #$quint-evaluator-source "-C" eval-dir)
                (invoke #$(file-append patchelf "/bin/patchelf") "--set-interpreter" ld
                        "--set-rpath" #$(file-append glibc "/lib") evaluator)
                ;; quint verify runs $QUINT_HOME/apalache-dist-V/apalache/bin/apalache-mc.
                (mkdir-p dist)
                (symlink #$(file-append (this-package-input "apalache") "/share/apalache")
                         (string-append dist "/apalache"))
                (mkdir-p lib)
                (symlink (string-append #$gcc:lib "/lib/libgcc_s.so.1")
                         (string-append lib "/libgcc_s.so.1"))
                (mkdir-p bin)
                (call-with-output-file wrapper
                  (lambda (port)
                    (format port "#!/bin/sh
export QUINT_HOME=\"${QUINT_HOME:-~a}\"
export PATH=\"${PATH:+$PATH:}~a/bin\"
export LD_LIBRARY_PATH=~a${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
exec ~a \"$@\"
" home #$(this-package-input "openjdk") lib real)))
                (chmod wrapper #o555)
                (patch-shebang wrapper)
                (invoke wrapper "--version")))))))
    (synopsis "Executable specification language on the logic of TLA+")
    (description "Quint is a specification language with a typed, programmer
friendly syntax over the Temporal Logic of Actions.  Its tools type-check,
simulate (quint run), test (quint test) and model-check specifications through
Apalache (quint verify).  This package installs the upstream ARM64 executable
with the Rust evaluator and Apalache in place, so nothing is downloaded at run
time.")
    (home-page "https://quint-lang.org")
    (license license:asl2.0)))

(define-public quint-language-server
  (package
    (name "quint-language-server")
    (version quint-language-server-version)
    (source #f)
    (build-system trivial-build-system)
    (native-inputs (list coreutils gzip tar))
    (inputs (list bash-minimal node))
    (arguments
     (list
      #:modules '((guix build utils))
      #:builder
      #~(begin
          (use-modules (guix build utils))
          (let ((lib (string-append #$output "/lib"))
                (wrapper (string-append #$output "/bin/quint-language-server")))
            (setenv "PATH" (string-append #$(this-package-native-input "gzip") "/bin:"
                                           #$(this-package-native-input "coreutils") "/bin"))
            ;; Each npm tarball unpacks to its lock path, as npm would.
            (for-each (lambda (entry)
                        (let ((dir (string-append lib "/" (car entry))))
                          (mkdir-p dir)
                          (invoke #$(file-append (this-package-native-input "tar") "/bin/tar")
                                  "xzf" (cdr entry) "-C" dir "--strip-components=1")))
                      (list #$@(map (lambda (module)
                                      (let ((path (car module)) (url (cadr module)) (hash (caddr module)))
                                        #~(cons #$path
                                                #$(origin
                                                    (method url-fetch)
                                                    (uri url)
                                                    (hash (content-hash (nix-base32-string->bytevector hash)
                                                                        sha512))))))
                                    quint-language-server-modules)))
            (invoke "chmod" "-R" "u+rwX,go+rX" lib)
            (mkdir-p (dirname wrapper))
            (call-with-output-file wrapper
              (lambda (port)
                (format port "#!~a~%exec ~a ~a \"$@\"~%"
                        #$(file-append bash-minimal "/bin/sh")
                        #$(file-append node "/bin/node")
                        (string-append lib "/node_modules/@informalsystems/quint-language-server/out/src/server.js"))))
            (chmod wrapper #o555)))))
    (synopsis "Language server for Quint")
    (description "The Quint language server gives an editor diagnostics from the
parser and type checker, hover with types and docs, go-to-definition,
completion and renaming for Quint specifications.  This package installs the
npm release with its dependencies, each pinned by the hash npm records.")
    (home-page "https://github.com/informalsystems/quint")
    (license license:asl2.0)))
