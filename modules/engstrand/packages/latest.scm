;; Newer releases of Guix packages, opted in one line each.  `updates' reports
;; how far Guix trails upstream (source release), opts a package in with
;; `updates apply release/NAME' and bumps it with source latest; the builder
;; builds it in the prebuild before you switch.
(define-module (engstrand packages latest)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module (ice-9 match)
  #:use-module (srfi srfi-26)
  #:export (%latest-packages
            latest-package))

;; (latest NAME VERSION BASE32 OWNER/REPO MODULE VARIABLE); updates rewrites
;; VERSION and BASE32.  A release that needs new inputs gets a hand-written
;; variant below instead.
(define %latest
  '(;; updates apply release/NAME adds lines here.
    (latest "neovim" "0.12.6" "0h7y3szk0jrw80l5ib0nninzmqxypfkjjmd51hsn1y0cg2k9py0y" "neovim/neovim" (gnu packages vim) neovim)
    ))

(define (replace-version text old new)
  (match (string-contains text old)
    (#f text)
    (i (string-append (substring text 0 i) new
                      (replace-version (substring text (+ i (string-length old))) old new)))))

(define (bump-uri uri old new)
  (match uri
    ((? string?) (replace-version uri old new))
    ((? list?) (map (cut bump-uri <> old new) uri))
    ((? git-reference?)
     (let ((commit (git-reference-commit uri)))
       (unless (string-contains commit old)
         (error "pins a commit, not a release tag; write a variant by hand:" commit))
       (git-reference (inherit uri) (commit (replace-version commit old new)))))))

(define (bump base version hash)
  (let* ((old (package-version base))
         (source (package-source base))
         (file-name (origin-file-name source)))
    (package
      (inherit base)
      (version version)
      (source (origin
                (inherit source)
                (uri (bump-uri (origin-uri source) old version))
                (file-name (and file-name (replace-version file-name old version)))
                (sha256 (base32 hash)))))))

(define %latest-packages
  (map (match-lambda
         (('latest name version hash _ module variable)
          (cons name (bump (module-ref (resolve-interface module) variable)
                           version hash))))
       %latest))

(define (latest-package item)
  "Return the opted-in newer release of package ITEM, or ITEM."
  (or (and (package? item)
           (assoc-ref %latest-packages (package-name item)))
      item))
