;;; Copy nixpkgs closures from cache.nixos.org into /gnu/store.
;;;
;;; /nix/store and /gnu/store have the same length, so replacing one with the
;;; other in a NAR keeps every string length and offset; the hash part of
;;; each store path is kept.  Imports are signed with a key that the daemon
;;; must trust (see signing-key.pub).
(define-module (engstrand nixpkgs bridge)
  #:use-module (engstrand nixpkgs)
  #:use-module (gcrypt hash)
  #:use-module (gcrypt pk-crypto)
  #:use-module (guix base16)
  #:use-module (guix base32)
  #:use-module (guix base64)
  #:use-module (guix pki)
  #:use-module (guix serialization)
  #:use-module (guix store)
  #:use-module (guix build utils)
  #:use-module ((guix build syscalls) #:select (with-file-lock))
  #:use-module (ice-9 binary-ports)
  #:use-module (ice-9 iconv)
  #:use-module (ice-9 match)
  #:use-module (ice-9 popen)
  #:use-module (ice-9 pretty-print)
  #:use-module (ice-9 textual-ports)
  #:use-module (ice-9 threads)
  #:use-module (json)
  #:use-module (rnrs bytevectors)
  #:use-module (srfi srfi-1)
  #:use-module (srfi srfi-26)
  #:use-module (srfi srfi-71)
  #:export (write-lock
            package-versions
            import-items
            verify-items
            generate-signing-key))

(define %cache "https://cache.nixos.org")
(define %cache-key-name "cache.nixos.org-1")
(define %cache-key "6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY=")
(define %jobs 8)

(define (hash-part path)
  (string-take (basename path) 32))

(define (command-output program . args)
  (let* ((port (apply open-pipe* OPEN_READ program args))
         (text (get-string-all port)))
    (unless (zero? (status:exit-val (close-pipe port)))
      (error "command failed" (cons program args)))
    text))

(define (narinfo-fields text)
  (filter-map (lambda (line)
                (let ((i (string-index line #\:)))
                  (and i (cons (string-take line i)
                               (string-trim (string-drop line (+ i 1)))))))
              (string-split text #\newline)))

(define (nix-signature-valid? fields)
  "Check the cache.nixos.org Ed25519 signature over the narinfo fingerprint."
  (define (field key) (assoc-ref fields key))
  (define (hex bv) (bytevector->base16-string bv))
  (define (slice bv start)
    (let ((out (make-bytevector 32)))
      (bytevector-copy! bv start out 0 32)
      out))
  (define fingerprint
    (string-join (list "1" (field "StorePath") (field "NarHash") (field "NarSize")
                       (string-join (map (cut string-append "/nix/store/" <>)
                                         (string-tokenize (or (field "References") "")))
                                    ","))
                 ";"))
  (any (lambda (sig)
         (match (string-split sig #\:)
           (((? (cut string=? <> %cache-key-name)) b64)
            (let ((bv (base64-decode b64)))
              (verify (string->canonical-sexp
                       (format #f "(sig-val (eddsa (r #~a#) (s #~a#)))"
                               (hex (slice bv 0)) (hex (slice bv 32))))
                      (string->canonical-sexp
                       (format #f "(data (flags eddsa) (hash-algo sha512) (value #~a#))"
                               (hex (string->utf8 fingerprint))))
                      (string->canonical-sexp
                       (format #f "(public-key (ecc (curve Ed25519) (flags eddsa) (q #~a#)))"
                               (hex (base64-decode %cache-key)))))))
           (_ #f)))
       (filter-map (match-lambda (("Sig" . sig) sig) (_ #f)) fields)))

(define (fetch-narinfo path)
  (let* ((url (string-append %cache "/" (hash-part path) ".narinfo"))
         (fields (narinfo-fields
                  (catch #t
                    (lambda () (command-output "curl" "-fsSL" "--retry" "3" url))
                    (lambda _
                      (error "not on cache.nixos.org; Hydra did not build it for this system" path))))))
    (unless (nix-signature-valid? fields)
      (error "bad cache.nixos.org signature" path))
    `(item (path ,path)
           (url ,(assoc-ref fields "URL"))
           (compression ,(or (assoc-ref fields "Compression") "none"))
           (nar-hash ,(assoc-ref fields "NarHash"))
           (nar-size ,(string->number (assoc-ref fields "NarSize")))
           (references ,@(string-tokenize (or (assoc-ref fields "References") ""))))))

(define (closure roots)
  "Return the item records of the runtime closure of ROOTS."
  (let loop ((frontier (delete-duplicates roots)) (seen '()))
    (if (null? frontier)
        (map cdr (sort seen (lambda (a b) (string<? (car a) (car b)))))
        (let* ((items (n-par-map %jobs fetch-narinfo frontier))
               (seen (append (map cons frontier items) seen))
               (refs (append-map (match-lambda
                                   (('item fields ...)
                                    (map (cut string-append "/nix/store/" <>)
                                         (lock-list fields 'references))))
                                 items)))
          (loop (delete-duplicates
                 (remove (cut assoc <> seen) refs))
                seen)))))

(define (map-attrs body attrs revision system)
  "Evaluate the Nix expression BODY, in which d is the derivation of attribute
n, for each of ATTRS in nixpkgs REVISION; return the parsed JSON objects."
  (setenv "NIX_CONFIG" "experimental-features = nix-command flakes")
  (vector->list
   (json-string->scm
    (command-output "guix" "shell" "nix" "--" "nix" "eval"
                    "--store" (string-append (getenv "HOME") "/.cache/nixpkgs-bridge/nix-store")
                    "--json"
                    (string-append "github:NixOS/nixpkgs/" revision
                                   "#legacyPackages." system)
                    "--apply"
                    (string-append
                     "p: map (n: let d = p.${n}; in " body ") "
                     "(builtins.fromJSON ''" (scm->json-string (list->vector attrs)) "'')")))))

(define (evaluate attrs revision system)
  "Evaluate ATTRS in nixpkgs REVISION; return (ATTR VERSION ((OUTPUT PATH) ...))."
  (map (lambda (entry)
         (list (assoc-ref entry "attr")
               (assoc-ref entry "version")
               (map (lambda (o) (list (assoc-ref o "name") (assoc-ref o "path")))
                    (vector->list (assoc-ref entry "outputs")))))
       (map-attrs (string-append
                   "{ attr = n; version = d.version or \"0\"; "
                   "outputs = map (o: { name = o; path = d.${o}.outPath; }) "
                   "(d.meta.outputsToInstall or [ \"out\" ]); }")
                  attrs revision system)))

(define (package-versions attrs revision system)
  "Return (ATTR VERSION CHANGELOG) for ATTRS in nixpkgs REVISION.  CHANGELOG
falls back to the homepage and may be empty."
  (map (lambda (entry)
         (list (assoc-ref entry "attr")
               (assoc-ref entry "version")
               (assoc-ref entry "changelog")))
       (map-attrs (string-append
                   "{ attr = n; version = d.version or \"0\"; "
                   "changelog = toString (d.meta.changelog or d.meta.homepage or \"\"); }")
                  attrs revision system)))

(define (write-lock file attrs revision system)
  (let* ((packages (evaluate attrs revision system))
         (roots (append-map (match-lambda
                              ((_ _ outputs) (map second outputs)))
                            packages))
         (items (closure roots)))
    (call-with-output-file file
      (lambda (port)
        (display ";; Generated by 'make nixpkgs-lock' from packages.sexp.\n" port)
        (pretty-print
         `(nixpkgs-lock
           (revision ,revision)
           (system ,system)
           (packages
            ,@(map (match-lambda
                     ((attr version outputs)
                      `(package (attr ,attr) (version ,version) (outputs ,@outputs))))
                   packages))
           (items ,@items))
         port)))
    (format #t "locked ~a packages, ~a store items~%" (length packages) (length items))))

(define* (rewrite-port in out #:key (from "/nix/store/") (to "gnu"))
  "Copy IN to OUT, replacing the 3 letters after the slash in every FROM with
TO.  Return the SHA256 and size of the input."
  (let ((hash-port get-hash (open-sha256-port))
        (keep (- (string-length from) 1)))
    (let loop ((carry "") (size 0))
      (let ((chunk (get-bytevector-n in (* 1024 1024))))
        (if (eof-object? chunk)
            (begin
              (put-bytevector out (string->bytevector carry "ISO-8859-1"))
              (close-port hash-port)
              (values (get-hash) size))
            (let ((text (string-append carry (bytevector->string chunk "ISO-8859-1"))))
              (put-bytevector hash-port chunk)
              (let scan ((start 0))
                (match (string-contains text from start)
                  (#f #t)
                  (i (string-copy! text (+ i 1) to)
                     (scan (+ i (string-length from))))))
              (let ((cut-at (max 0 (- (string-length text) keep))))
                (put-bytevector out (string->bytevector (string-take text cut-at) "ISO-8859-1"))
                (loop (string-drop text cut-at)
                      (+ size (bytevector-length chunk))))))))))

(define (nar-file fields directory)
  (string-append directory "/" (basename (lock-ref fields 'path)) ".nar"))

(define (fetch-nar fields directory)
  "Download the NAR of item FIELDS, check it against the lock, and write the
rewritten NAR to DIRECTORY, unless an earlier run already did."
  (let* ((path (lock-ref fields 'path))
         (file (nar-file fields directory))
         (partial (string-append file ".part"))
         (decompress (match (lock-ref fields 'compression)
                       ("zstd" "zstd -dc") ("xz" "xz -dc") ("bzip2" "bzip2 -dc")
                       ("none" "cat"))))
    (unless (file-exists? file)
      (let* ((in (open-pipe* OPEN_READ "bash" "-o" "pipefail" "-c"
                             (string-append "curl -fsSL --retry 3 '" %cache "/"
                                            (lock-ref fields 'url) "' | " decompress)))
             (hash size (call-with-output-file partial (cut rewrite-port in <>)
                          #:binary #t)))
        (unless (zero? (status:exit-val (close-pipe in)))
          (error "download failed" path))
        (unless (and (= size (lock-ref fields 'nar-size))
                     (string=? (lock-ref fields 'nar-hash)
                               (string-append "sha256:" (bytevector->nix-base32-string hash))))
          (delete-file partial)
          (error "NAR does not match the lock" path))
        (rename-file partial file)))))

(define (topological items)
  "Order ITEMS so that every item follows its references."
  (let ((by-name (map (lambda (fields) (cons (basename (lock-ref fields 'path)) fields))
                      items)))
    (define (visit name order)
      (if (member name order)
          order
          (cons name
                (fold (lambda (ref order)
                        (if (or (string=? ref name) (not (assoc ref by-name)))
                            order
                            (visit ref order)))
                      order
                      (lock-list (assoc-ref by-name name) 'references)))))
    (map (cut assoc-ref by-name <>)
         (reverse (fold visit '() (map car by-name))))))

(define %export-magic #x4558494e)

(define (write-export-item fields nar-file key-pair port)
  "Write one item of a 'guix archive --export' stream to PORT, signed with
KEY-PAIR (public . secret)."
  (let ((hash-port get-hash (open-sha256-port)))
    (define (both proc . args)
      (apply proc (append args (list port)))
      (apply proc (append args (list hash-port))))
    (write-long-long 1 port)
    (call-with-input-file nar-file
      (lambda (in)
        (let loop ()
          (let ((chunk (get-bytevector-n in (* 1024 1024))))
            (unless (eof-object? chunk)
              (put-bytevector port chunk)
              (put-bytevector hash-port chunk)
              (loop)))))
      #:binary #t)
    (both write-int %export-magic)
    (both write-string (nix->gnu (lock-ref fields 'path)))
    (both write-string-list (map nix->gnu (lock-list fields 'references)))
    (both write-string "")
    (close-port hash-port)
    (match key-pair
      ((public . secret)
       (write-int 1 port)
       (write-string (canonical-sexp->string
                      (signature-sexp (bytevector->hash-data (get-hash)
                                                             #:key-type (key-type public))
                                      secret public))
                     port)))))

(define (read-key-pair secret-file)
  (cons (string->canonical-sexp
         (call-with-input-file (string-append (string-drop-right secret-file 4) ".pub")
           get-string-all))
        (string->canonical-sexp (call-with-input-file secret-file get-string-all))))

(define (import-items items secret-file cache-directory)
  "Import the ITEMS that are not yet valid into the store.  Concurrent runs
wait for each other, since they share CACHE-DIRECTORY."
  (mkdir-p cache-directory)
  (with-file-lock (string-append cache-directory "/lock")
    (import-missing items secret-file cache-directory)))

(define (import-missing items secret-file cache-directory)
  (let ((todo (with-store store
                (remove (lambda (fields)
                          (valid-path? store (nix->gnu (lock-ref fields 'path))))
                        items))))
    (format #t "importing ~a of ~a nixpkgs store items~%" (length todo) (length items))
    (unless (null? todo)
      (let ((key-pair (read-key-pair secret-file)))
        (unless (authorized-key? (car key-pair) (current-acl))
          (error (string-append "the daemon does not trust " secret-file
                                "; run 'make nixpkgs-authorize'")))
        (n-par-for-each %jobs (cut fetch-nar <> cache-directory) todo)
        (let ((out (open-pipe* OPEN_WRITE "guix" "archive" "--import")))
          (for-each (lambda (fields)
                      (write-export-item fields (nar-file fields cache-directory)
                                         key-pair out))
                    (topological todo))
          (write-long-long 0 out)
          (unless (zero? (status:exit-val (close-pipe out)))
            (error "guix archive --import failed")))
        (for-each (lambda (fields) (delete-file (nar-file fields cache-directory)))
                  todo)))))

(define (verify-items items directory)
  "Check that each imported item, with /gnu/store/ turned back into
/nix/store/, has the NAR hash that cache.nixos.org signed.  Return the paths
that differ."
  (mkdir-p directory)
  (filter-map
   (lambda (fields)
     (let* ((path (nix->gnu (lock-ref fields 'path)))
            (nar (string-append directory "/" (basename path) ".verify")))
       (call-with-output-file nar (cut write-file path <>) #:binary #t)
       (let ((hash-port get-hash (open-sha256-port)))
         (call-with-input-file nar
           (cut rewrite-port <> hash-port #:from "/gnu/store/" #:to "nix")
           #:binary #t)
         (close-port hash-port)
         (delete-file nar)
         (and (not (string=? (lock-ref fields 'nar-hash)
                             (string-append "sha256:"
                                            (bytevector->nix-base32-string (get-hash)))))
              path))))
   items))

(define (generate-signing-key secret-file)
  (let* ((pair (generate-key (string->canonical-sexp
                              "(genkey (ecdsa (curve Ed25519) (flags rfc6979)))")))
         (public-file (string-append (string-drop-right secret-file 4) ".pub")))
    (umask #o077)
    (mkdir-p (dirname secret-file))
    (call-with-output-file secret-file
      (cut display (canonical-sexp->string (find-sexp-token pair 'private-key)) <>))
    (call-with-output-file public-file
      (cut display (canonical-sexp->string (find-sexp-token pair 'public-key)) <>))
    public-file))
