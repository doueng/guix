;;; The feed reader (~/projects/langchain/reader) as a system service on the
;;; remote builder, published on the tailnet at
;;; https://guix-builder.tailbf68ad.ts.net/ (docs/remote-builder-plan.md).
;;;
;;; The reader is a Python Cloudflare Worker run by `wrangler dev' (node and
;;; Cloudflare's workerd from the checkout's node_modules) plus the suggester,
;;; a Python service behind its Find button.  Its npm and PyPI dependencies are
;;; not in Guix, so this is not a package: `make reader-push' copies the
;;; checkout, with its prebuilt public/js/main.js, to %reader-home/app, and
;;; Guix supplies node, the glibc loader workerd needs, python and uv.  The
;;; Durable Object state (sources, headlines, suggestions) lives in
;;; %reader-home/state, outside the code, so a push never touches it.
(define-module (engstrand system reader)
  #:use-module (engstrand features tailscale)
  #:use-module (gnu packages admin)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages gcc)
  #:use-module (gnu packages node)
  #:use-module (gnu packages python)
  #:use-module (gnu packages build-tools)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (gnu system accounts)
  #:use-module (gnu system shadow)
  #:use-module (guix gexp)
  #:export (%reader-services))

(define %reader-home "/var/lib/reader")
(define %reader-port 8787)
(define %suggester-port 8788)
(define %app (string-append %reader-home "/app"))

;; Cloudflare links workerd against /lib/ld-linux-aarch64.so.1, which Guix
;; System lacks; run it through Guix's loader, as scripts/workerd does on the
;; laptop.  The builder is aarch64 like the laptop, so the pushed
;; node_modules hold the right binary.
(define %workerd
  (program-file "reader-workerd"
                #~(apply execl #$(file-append glibc "/lib/ld-linux-aarch64.so.1")
                         "ld-linux-aarch64.so.1"
                         "--library-path" #$(file-append glibc "/lib")
                         #$(string-append %app "/node_modules/@cloudflare/workerd-linux-arm64/bin/workerd")
                         (cdr (command-line)))))

;; The suggester's venv sits outside the code and is synced at every start
;; from the pushed uv.lock; its base python is this store item, which the
;; system closure keeps alive through guix gc.  The Jev key comes from
;; %reader-home/jev-key, which `make reader-push' writes.
(define %suggester
  (program-file "reader-suggester"
    #~(begin
        (use-modules (ice-9 textual-ports))
        (let* ((home #$%reader-home)
               (venv (string-append home "/venv"))
               (key (string-append home "/jev-key")))
          (setenv "UV_PYTHON" #$(file-append python-3.12 "/bin/python3"))
          (setenv "UV_PYTHON_DOWNLOADS" "never")
          (setenv "UV_PROJECT_ENVIRONMENT" venv)
          (setenv "UV_CACHE_DIR" (string-append home "/.cache/uv"))
          (unless (zero? (system* #$(file-append uv "/bin/uv") "sync" "--quiet"
                                  "--frozen" "--no-dev"
                                  "--project" #$(string-append %app "/suggester")))
            (exit 1))
          ;; manylinux wheels (orjson) need libgcc_s; load it in this python
          ;; only, as suggester/dev/env.sh does.
          (call-with-output-file
              (string-append venv "/lib/python3.12/site-packages/guix-libs.pth")
            (lambda (port)
              (format port "import ctypes; ctypes.CDLL('~a/lib/libgcc_s.so.1', ctypes.RTLD_GLOBAL)~%"
                      #$gcc:lib)))
          (when (file-exists? key)
            (setenv "TYPESAFE_API_KEY"
                    (string-trim-both (call-with-input-file key get-string-all))))
          (execl (string-append venv "/bin/suggester") "suggester"
                 "--reader" #$(format #f "http://127.0.0.1:~a" %reader-port)
                 "serve" "--port" #$(number->string %suggester-port))))))

(define %environment
  #~(list (string-append "HOME=" #$%reader-home)
          (string-append "PATH=" #$(file-append node "/bin")
                         ":" #$(file-append coreutils "/bin")
                         ":" #$(file-append bash-minimal "/bin"))
          (string-append "MINIFLARE_WORKERD_PATH=" #$%workerd)
          "WRANGLER_SEND_METRICS=false"
          "SSL_CERT_DIR=/etc/ssl/certs"
          "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt"
          "LANG=C.UTF-8"))

(define %accounts
  (list (user-group (name "reader") (system? #t))
        (user-account
         (name "reader")
         (group "reader")
         (system? #t)
         (comment "The feed reader")
         (home-directory %reader-home)
         (create-home-directory? #f)
         (shell (file-append shadow "/sbin/nologin")))))

(define %activation
  #~(begin
      (use-modules (guix build utils))
      (let ((user (getpwnam "reader")))
        (for-each (lambda (dir)
                    (mkdir-p dir)
                    (chown dir (passwd:uid user) (passwd:gid user)))
                  (map (lambda (dir) (string-append #$%reader-home dir))
                       '("" "/app" "/state" "/.cache"))))))

;; Loopback only: tailscaled fronts it over HTTPS and keeps that mapping in
;; its state, so neither waits for the other (T74's pattern).  workerd fetches
;; its Python runtime from Cloudflare at every start, and `networking' is up
;; before DNS answers at boot, so start waits up to a minute for DNS.  A crash loop,
;; such as a bad push, stops after a minute; `herd enable reader' rearms it.
(define (reader-shepherd-service provision documentation command log)
  (shepherd-service
   (provision (list provision))
   (requirement '(networking))
   (documentation documentation)
   (respawn? #t)
   (respawn-limit #~'(5 . 60))
   (respawn-delay 2)
   (start #~(lambda args
              (let loop ((tries 60))
                (unless (or (<= tries 1)
                            (false-if-exception
                             (getaddrinfo "pyodide-capnp-bin.edgeworker.net" "https")))
                  (sleep 1)
                  (loop (- tries 1))))
              (apply (make-forkexec-constructor
                      (list #$@command)
                      #:user "reader"
                      #:group "reader"
                      #:directory #$%app
                      #:environment-variables #$%environment
                      #:log-file #$log)
                     args)))
   ;; Kills the process group, so workerd goes with wrangler.
   (stop #~(make-kill-destructor))))

(define %shepherd-services
  (list
   (reader-shepherd-service
    'reader "The feed reader (wrangler dev) on loopback."
    (list (file-append node "/bin/node")
          (string-append %app "/node_modules/wrangler/bin/wrangler.js")
          "dev" "--ip" "127.0.0.1" "--port" (number->string %reader-port)
          "--persist-to" (string-append %reader-home "/state"))
    "/var/log/reader.log")
   (shepherd-service
    (inherit (reader-shepherd-service
              'reader-suggester "The reader's blog suggester behind its Find button."
              (list %suggester)
              "/var/log/reader-suggester.log"))
    (requirement '(networking reader)))
   (tailscale-one-shot 'reader-serve
                       "Publish the reader on the tailnet over HTTPS (tailscale serve)."
                       "serve" "--bg" "--https=443"
                       (format #f "http://127.0.0.1:~a" %reader-port))))

(define %reader-services
  (list (simple-service 'reader-accounts account-service-type %accounts)
        (simple-service 'reader-activation activation-service-type
                        (with-imported-modules '((guix build utils)) %activation))
        (simple-service 'reader-shepherd shepherd-root-service-type
                        %shepherd-services)))
