(define-module (engstrand packages latest)
  #:use-module (guix gexp)
  #:use-module (guix git-download)
  #:use-module (guix packages)
  #:use-module (guix utils)
  #:use-module (ice-9 match)
  #:use-module (srfi srfi-26)
  #:export (%latest-packages
            latest-package))

;; (latest NAME VERSION BASE32 OWNER/REPO MODULE VARIABLE); updates rewrites
;; VERSION and BASE32.  A line for a library, such as hcloud-go, brings the
;; newer release another line's package needs: every opted-in package uses
;; the opted-in releases of its inputs.  Other changes go in %tweaks.
(define %latest
  '(;; updates apply release/NAME adds lines here.
    (latest "hetznercloud-cli" "1.70.2" "01nwzb25w48jqlzvwihavqppg7x4vklcabzl5a101qzyp3irsdch" "hetznercloud/cli" (gnu packages admin) hetznercloud-cli)
    (latest "direnv" "2.38.2" "1l0nb0pv2700g8xx1g3b4ljqb7vxbg8svcvylc14kan48i1nwdfi" "direnv/direnv" (gnu packages shellutils) direnv)
    (latest "go-github-com-hetznercloud-hcloud-go-v2" "2.53.0" "05ah1hislqxpv2www5llwxjnd2qc5p34gqsmqmlz5x28jlgrrcn0" "hetznercloud/hcloud-go" (gnu packages golang-web) go-github-com-hetznercloud-hcloud-go-v2)
    (latest "xxd" "9.2.1316" "0fw922j34wsc0yd7lb85xiqnkdzr62mbdq2nbys3jahrhvrc4b3d" "vim/vim" (gnu packages vim) xxd)
    (latest "wtype" "0.5" "1klib837nkl9l10ndzc6cq5agydx4v62v7jm7yncns6z2gjzf1ym" "atx/wtype" (gnu packages freedesktop) wtype)
    (latest "nixfmt" "1.5.0" "0y8kwlfs03c79nxn76ill9zx52vhxpdnibz88lpaq0dzfd2sd2v0" "NixOS/nixfmt" (gnu packages haskell-apps) nixfmt)
    (latest "btop" "1.4.8" "1jsdy4k915hzyfqyks516wcb8w7mcj175pq3jw8yfavwlx1z3s1n" "aristocratos/btop" (gnu packages admin) btop)
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

(define (skip-go-test file test)
  "A phase that skips TEST in FILE, a Go test the build sandbox can't run."
  #~(lambda _
      (substitute* #$file
        (((string-append "func " #$test "\\(t \\*testing\\.T\\) \\{") all)
         (string-append all "\n\tt.Skip(\"not in the build sandbox\")")))))

(define %tweaks
  `(("direnv"
     . ,(lambda (p)
          (package
            (inherit p)
            (arguments
             (substitute-keyword-arguments (package-arguments p)
               ((#:phases phases)
                ;; 2.38 runs the hook through a shebang the sandbox lacks.
                #~(modify-phases #$phases
                    (add-before 'check 'skip-self-path-test
                      #$(skip-go-test
                         "src/github.com/direnv/direnv/internal/cmd/self_path_test.go"
                         "TestBashHookEscapesSelfPath")))))))))))

;; (NAME OLD-VERSION . NEW-PACKAGE) for each line.
(define %bumped
  (map (match-lambda
         (('latest name version hash _ module variable)
          (let* ((base (module-ref (resolve-interface module) variable))
                 (new (bump base version hash)))
            (cons* name (package-version base)
                   ((or (assoc-ref %tweaks name) identity) new)))))
       %latest))

;; Replace each opted-in package's old release, wherever it is an input, by
;; its new one, itself rewritten; the spec names the old version, so a new
;; release is never replaced by itself.
(define with-latest-inputs
  (package-input-rewriting/spec
   (map (match-lambda
          ((name old . new)
           (cons (string-append name "@" old)
                 (lambda (_) (with-latest-inputs new)))))
        %bumped)
   #:deep? #f))

(define %latest-packages
  (map (match-lambda
         ((name _ . new) (cons name (with-latest-inputs new))))
       %bumped))

(define (latest-package item)
  "Return the opted-in newer release of package ITEM, or ITEM."
  (or (and (package? item)
           (assoc-ref %latest-packages (package-name item)))
      item))
