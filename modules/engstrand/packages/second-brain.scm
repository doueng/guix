;;; The second brain (~/projects/second-brain, T147): a bookmark store with a
;;; phone page, written in babashka over the sqlite3 command.  The checkout
;;; has no remote, so the source is its files, minus the gitignored data/ and
;;; VCS directories; SECOND_BRAIN_DIR names another checkout.  Without a
;;; checkout the package has no source, so loading this module never fails.
;;; It runs on the builder as modules/engstrand/system/second-brain.scm.
(define-module (engstrand packages second-brain)
  #:use-module (engstrand packages babashka)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages compression)
  #:use-module (gnu packages sqlite)
  #:use-module (guix build-system copy)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module ((guix licenses) #:prefix license:))

(define %checkout
  (or (getenv "SECOND_BRAIN_DIR")
      (string-append (or (getenv "HOME") "") "/projects/second-brain")))

;; Only what the app needs, so personal data in data/ never enters the store.
(define (source-file? file stat)
  (let ((rel (if (string-prefix? %checkout file)
                 (string-drop file (string-length %checkout))
                 file)))
    (or (string=? rel "")
        (member rel '("/bb.edn" "/README.md"))
        (string-prefix? "/src" rel)
        (string-prefix? "/test" rel)
        (string-prefix? "/tools" rel))))

(define-public second-brain
  (package
    (name "second-brain")
    (version "0.1")
    (source (and (file-exists? (string-append %checkout "/bb.edn"))
                 (local-file %checkout "second-brain-checkout"
                             #:recursive? #t
                             #:select? source-file?)))
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan #~'(("bb.edn" "share/second-brain/")
                         ("README.md" "share/second-brain/")
                         ("src" "share/second-brain/src")
                         ("tools" "share/second-brain/tools"))
      #:phases
      #~(modify-phases %standard-phases
          (add-before 'install 'run-tests
            (lambda* (#:key tests? #:allow-other-keys)
              (when tests?
                (setenv "HOME" (getcwd))
                (invoke #$(file-append babashka "/bin/bb") "test"))))
          ;; bin/second-brain TASK ARGS... runs a bb.edn task; data goes to
          ;; BRAIN_DB (default data/brain.db under the current directory).
          (add-after 'install 'wrap
            (lambda _
              (let ((bin (string-append #$output "/bin/second-brain")))
                (mkdir-p (dirname bin))
                (call-with-output-file bin
                  (lambda (port)
                    (format port "#!~a/bin/bash
export PATH=~a/bin:~a/bin:~a/bin${PATH:+:$PATH}
exec ~a/bin/bb --config ~a/share/second-brain/bb.edn \"$@\"~%"
                            #$bash-minimal #$sqlite #$unzip #$coreutils
                            #$babashka #$output)))
                (chmod bin #o555)))))))
    (native-inputs (list sqlite))
    (inputs (list babashka bash-minimal coreutils sqlite unzip))
    (supported-systems '("aarch64-linux"))
    (synopsis "Personal bookmark store with search for the phone")
    (description "Imports Chrome, Safari and X saves into one SQLite store, fetches
the pages, and serves a search page on loopback.")
    (home-page "file:///home/engstrand/projects/second-brain")
    (license #f)))
