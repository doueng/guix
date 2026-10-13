;;; nixpkgs binaries from cache.nixos.org as Guix packages.
;;;
;;; 'make nixpkgs-import' copies the closures in nixpkgs/lock.sexp into
;;; /gnu/store, replacing the equal-length /nix/store prefix and keeping each
;;; hash.  This module wraps those store items in packages.
(define-module (engstrand nixpkgs)
  #:use-module (gnu packages bash)
  #:use-module (gnu services)
  #:use-module (gnu services base)
  #:use-module (gnu packages fontutils)
  #:use-module (guix build-system trivial)
  #:use-module (guix gexp)
  #:use-module (guix monads)
  #:use-module (guix packages)
  #:use-module (guix store)
  #:use-module (ice-9 match)
  #:use-module (srfi srfi-1)
  #:use-module (srfi srfi-9)
  #:export (nix->gnu
            read-nixpkgs-lock
            lock-ref
            lock-list
            lock-items
            nixpkgs-output
            nixpkgs-locale-archive
            nixpkgs-system-services
            nixpkgs-package))

(define (nix->gnu path)
  (string-append "/gnu/store/" (basename path)))

(define* (read-nixpkgs-lock
          #:optional (file (search-path %load-path "engstrand/nixpkgs/lock.sexp")))
  (match (call-with-input-file file read)
    (('nixpkgs-lock fields ...) fields)))

(define (lock-ref fields key)
  (match (assq key fields)
    ((_ value) value)
    (#f #f)))

(define (lock-list fields key)
  (match (assq key fields)
    ((_ . values) values)
    (#f '())))

(define (lock-items lock)
  (map (match-lambda (('item fields ...) fields))
       (lock-list lock 'items)))

(define %lock (delay (read-nixpkgs-lock)))

(define (lock-package attr)
  (or (find (match-lambda
              (('package fields ...) (equal? attr (lock-ref fields 'attr))))
            (lock-list (force %lock) 'packages))
      (error "nixpkgs attribute missing from the lock; add it to modules/engstrand/nixpkgs/packages.sexp and run 'make nixpkgs-lock'" attr)))

(define (package-paths attr)
  (match (lock-package attr)
    (('package fields ...) (lock-list fields 'outputs))))

(define-record-type <nixpkgs-item>
  (nixpkgs-item path)
  nixpkgs-item?
  (path nixpkgs-item-path))

(define-gexp-compiler (nixpkgs-item-compiler (item <nixpkgs-item>)
                                             system target)
  (let ((path (nix->gnu (nixpkgs-item-path item))))
    (mlet %store-monad ((valid? ((store-lift valid-path?) path)))
      (if valid?
          (return path)
          (error (string-append path " is not in the store; run 'make nixpkgs-import'"))))))

(define* (nixpkgs-output attr #:optional (output "out"))
  "Return the imported OUTPUT of ATTR as a file-like object."
  (match (assoc output (package-paths attr))
    ((_ path) (nixpkgs-item path))
    (#f (error "nixpkgs output not locked" attr output))))

(define (nixpkgs-locale-archive)
  "Locales for nixpkgs programs, for LOCALE_ARCHIVE.  Guix's glibc ignores
that variable."
  (file-append (nixpkgs-output "glibcLocalesUtf8") "/lib/locale/locale-archive"))

(define %fonts.conf
  (mixed-text-file "nixpkgs-fonts.conf"
                   "<?xml version=\"1.0\"?>
<!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\">
<fontconfig>
  <dir prefix=\"xdg\">fonts</dir>
  <dir>~/.guix-home/profile/share/fonts</dir>
  <dir>~/.guix-profile/share/fonts</dir>
  <dir>/run/current-system/profile/share/fonts</dir>
  <cachedir prefix=\"xdg\">fontconfig</cachedir>
  <include ignore_missing=\"yes\">" fontconfig "/etc/fonts/conf.d</include>
</fontconfig>
"))

(define (nixpkgs-system-services)
  "Graphics and fonts for nixpkgs GUI programs, in the default locations
nixpkgs libglvnd and fontconfig search.  Guix programs read neither path, and
unlike environment variables nothing reaches the shells a terminal starts."
  (list (simple-service 'nixpkgs-opengl-driver special-files-service-type
                        `(("/run/opengl-driver" ,(nixpkgs-output "mesa"))))
        (simple-service 'nixpkgs-fonts etc-service-type
                        `(("fonts" ,(file-union "nixpkgs-fonts"
                                                `(("fonts.conf" ,%fonts.conf))))))))

(define* (nixpkgs-package attr #:key (environment '()))
  "Return a package whose output is the union of ATTR's installable outputs,
as recorded in the lock.  When ENVIRONMENT, a list of (NAME VALUE) pairs, is
not empty, each program in bin/ becomes a wrapper that sets it, and desktop,
D-Bus and systemd service files point at the wrappers."
  (package
    (name (string-append "nixpkgs-" attr))
    (version (match (lock-package attr)
               (('package fields ...) (lock-ref fields 'version))))
    (source #f)
    (supported-systems (list (lock-ref (force %lock) 'system)))
    (build-system trivial-build-system)
    (arguments
     (list #:modules '((guix build union) (guix build utils))
           #:builder
           #~(begin
               (use-modules (guix build union) (guix build utils)
                            (ice-9 ftw) (ice-9 regex) (srfi srfi-1))
               (define items
                 (list #$@(map (match-lambda ((_ path) (nixpkgs-item path)))
                               (package-paths attr))))
               (define environment
                 (list #$@(map (match-lambda
                                 ((name value) #~(cons #$name #$value)))
                               environment)))
               (define bin (string-append #$output "/bin"))
               (union-build #$output items #:create-all-directories? #t)
               (unless (null? environment)
                 (for-each
                  (lambda (name)
                    (let* ((link (string-append bin "/" name))
                           (target (readlink link)))
                      (delete-file link)
                      (call-with-output-file link
                        (lambda (port)
                          (format port "#!~a~%" #$(file-append bash-minimal "/bin/sh"))
                          (for-each (lambda (pair)
                                      (format port "export ~a=~s~%" (car pair) (cdr pair)))
                                    environment)
                          (format port "exec ~s \"$@\"~%" target)))
                      (chmod link #o555)))
                  (scandir bin (lambda (name) (not (member name '("." ".."))))))
                 (for-each
                  (lambda (file)
                    (let ((target (readlink file)))
                      (when (any (lambda (item) (string-prefix? item target)) items)
                        (delete-file file)
                        (copy-file target file)
                        (chmod file #o644)
                        (substitute* file
                          (((string-append "(" (string-join (map regexp-quote items) "|") ")/bin/"))
                           (string-append bin "/"))))))
                  (filter symbolic-link?
                          (append-map find-files
                                      (filter file-exists?
                                              (map (lambda (dir) (string-append #$output dir))
                                                   '("/share/applications"
                                                     "/share/dbus-1/services"
                                                     "/share/systemd/user"))))))))))
    (synopsis "nixpkgs binary from cache.nixos.org")
    (description (string-append "The nixpkgs " (lock-ref (force %lock) 'revision)
                                " build of " attr
                                ", with /nix/store rewritten to /gnu/store."))
    (home-page "https://search.nixos.org/packages")
    (license #f)))
