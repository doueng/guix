(define-module (engstrand packages jiti)
  #:use-module (gnu packages lisp-check)
  #:use-module (gnu packages lisp-xyz)
  #:use-module (gnu packages python)
  #:use-module (gnu packages python-build)
  #:use-module (gnu packages python-xyz)
  #:use-module (guix build-system asdf)
  #:use-module (guix download)
  #:use-module (guix gexp)
  #:use-module (guix packages))

(define-public jiti
  (let ((commit "a9f46a604c07d8d1e1687a2797cb6e8edfecf818"))
    (package
      (name "jiti")
      (version (string-append "0.1.0-0." (substring commit 0 7)))
      (source
       (origin
         (method url-fetch)
         (uri (string-append "https://codeload.github.com/ghuntley/jiti/tar.gz/"
                             commit))
         (file-name (string-append "jiti-" commit ".tar.gz"))
         (sha256
          (base32 "0ngnd1cwg6h7jwq6z8iryynkc658ddlwb9amz7iqjd1fdgd40rri"))))
      (build-system asdf-build-system/sbcl)
      (inputs
       (list sbcl-dexador sbcl-yason sbcl-babel
             python python-prompt-toolkit python-pygments python-rich))
      (native-inputs (list sbcl-fiveam sbcl-check-it))
      (arguments
       (list
        #:asd-systems ''("image-agent/terminal")
        #:phases
        #~(modify-phases %standard-phases
            (replace 'check
              (lambda* (#:key tests? #:allow-other-keys)
                (when tests?
                  (invoke "sbcl" "--noinform" "--script" "scripts/test.lisp"
                          "test"))))
            (add-after 'create-asdf-configuration 'build-programs
              (lambda* (#:key outputs #:allow-other-keys)
                (let ((out (assoc-ref outputs "out")))
                  (build-program
                   (string-append out "/libexec/jiti-cli") outputs
                   #:dependencies '("image-agent/cli")
                   #:entry-program
                   '((handler-case (image-agent/cli:main arguments)
                       (error (c)
                         (format *error-output* "REPL startup failed: ~a~%" c)
                         (sb-ext:exit :code 2))))
                   #:compress? #t)
                  (build-program
                   (string-append out "/libexec/jiti-terminal") outputs
                   #:dependencies '("image-agent/terminal")
                   ;; The first executable already retained these ASD files.
                   #:dependency-prefixes '()
                   #:entry-program '((image-agent/terminal:main arguments))
                   #:compress? #t))))
            (add-after 'build-programs 'install-launcher
              (lambda* (#:key outputs #:allow-other-keys)
                (let* ((out (assoc-ref outputs "out"))
                       (scripts (string-append out "/share/jiti/scripts"))
                       (bin (string-append out "/bin")))
                  (for-each (lambda (file) (install-file file scripts))
                            '("scripts/repl.py" "scripts/terminal_ui.py"
                              "scripts/local_openai.py"))
                  ;; Saved Lisp executables replace the development SBCL loaders.
                  (substitute* (string-append scripts "/repl.py")
                    (("\\['sbcl', '--noinform', '--script', str\\(loader\\), \\*arguments\\]")
                     (string-append "['" out "/libexec/jiti-cli', *arguments]"))
                    (("os.execvp\\('sbcl',")
                     (string-append "os.execvp('" out "/libexec/jiti-cli',")))
                  (substitute* (string-append scripts "/terminal_ui.py")
                    (("\\['sbcl', '--noinform', '--script', str\\(loader\\), \\*self.arguments\\]")
                     (string-append "['" out "/libexec/jiti-terminal', *self.arguments]")))
                  (mkdir-p bin)
                  (call-with-output-file (string-append bin "/jiti")
                    (lambda (port)
                      (format port "#!~a~%exec ~a ~a \"$@\"~%"
                              (which "sh") (which "python3")
                              (string-append scripts "/repl.py"))))
                  (chmod (string-append bin "/jiti") #o555)
                  (wrap-program (string-append bin "/jiti")
                    `("GUIX_PYTHONPATH" prefix (,(getenv "GUIX_PYTHONPATH"))))
                  ;; Retain the command documented by upstream.
                  (symlink "jiti" (string-append bin "/image-repl")))))
            (add-after 'install-launcher 'check-launcher
              (lambda* (#:key tests? outputs #:allow-other-keys)
                (when tests?
                  (invoke (string-append (assoc-ref outputs "out") "/bin/jiti")
                          "--help")
                  (invoke "python3" "-c"
                          "import prompt_toolkit, pygments, rich")))))))
      (synopsis "Grow a running Common Lisp application through chat")
      (description
       "Jiti is a cooperative kernel for developing a persistent Common Lisp
application through chat or manual Lisp forms.  It provides a terminal interface,
managed previews, revision history, recovery and live repair of paused calls.")
      (home-page "https://github.com/ghuntley/jiti")
      ;; Upstream does not currently declare a license.
      (license #f))))
