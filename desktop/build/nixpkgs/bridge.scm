;; Usage: guix repl -L modules -- desktop/build/nixpkgs/bridge.scm COMMAND ...
;;   lock PACKAGES LOCK [REVISION]  evaluate PACKAGES (a list of attribute names)
;;                                  and write LOCK; REVISION defaults to the
;;                                  locked one, then to nixpkgs-unstable
;;   versions PACKAGES [REVISION]   print REVISION, which defaults to the latest
;;                                  nixpkgs-unstable, then ATTR VERSION CHANGELOG
;;                                  lines separated by tabs
;;   import LOCK SECRET-KEY         import the locked closures into the store
;;   verify LOCK                    check imported items against the signed
;;                                  cache.nixos.org NAR hashes
;;   key SECRET-KEY                 generate a signing key pair
(use-modules (engstrand nixpkgs)
             (engstrand nixpkgs bridge)
             (ice-9 match)
             (ice-9 popen)
             (ice-9 textual-ports))

(define (cache-directory)
  (string-append (or (getenv "XDG_CACHE_HOME")
                     (string-append (getenv "HOME") "/.cache"))
                 "/nixpkgs-bridge/nars"))

(define (latest-revision)
  (let* ((port (open-pipe* OPEN_READ "curl" "-fsSL"
                           "https://channels.nixos.org/nixpkgs-unstable/git-revision"))
         (revision (string-trim-both (get-string-all port))))
    (close-pipe port)
    revision))

(match (command-line)
  ((_ "lock" packages lock . revision)
   (write-lock lock
               (call-with-input-file packages read)
               (match revision
                 ((revision) revision)
                 (()
                  (if (file-exists? lock)
                      (lock-ref (read-nixpkgs-lock lock) 'revision)
                      (latest-revision))))
               "aarch64-linux"))
  ((_ "versions" packages . revision)
   (let ((revision (match revision ((revision) revision) (() (latest-revision)))))
     (format #t "~a~%" revision)
     (for-each (match-lambda
                 ((attr version changelog)
                  (format #t "~a\t~a\t~a~%" attr version changelog)))
               (package-versions (call-with-input-file packages read)
                                 revision "aarch64-linux"))))
  ((_ "import" lock secret-key)
   (import-items (lock-items (read-nixpkgs-lock lock)) secret-key (cache-directory)))
  ((_ "verify" lock)
   (match (verify-items (lock-items (read-nixpkgs-lock lock))
                        (string-append (cache-directory) "/verify"))
     (() (format #t "all locked items match cache.nixos.org~%"))
     (bad (for-each (lambda (path) (format #t "MISMATCH ~a~%" path)) bad)
          (exit 1))))
  ((_ "key" secret-key)
   (format #t "wrote ~a~%" (generate-signing-key secret-key)))
  (_
   (format (current-error-port) "usage: bridge.scm lock|versions|import|verify|key ...~%")
   (exit 1)))
