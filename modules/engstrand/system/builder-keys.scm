;;; Public keys and address of the remote builder (docs/remote-builder-plan.md).
;;; Both the builder OS and the laptop's daemon read them.  The key files are
;;; committed in modules/engstrand/builder/; each key is #f when its file is
;;; missing, as in a clone without them.
(define-module (engstrand system builder-keys)
  #:use-module (engstrand packages assets)
  #:use-module (guix gexp)
  #:use-module (ice-9 textual-ports)
  #:export (%builder-host
            %builder-publish-url
            %builder-ssh-port
            builder-key-file
            builder-key-string))

;; desktop/lib/builder.clj holds the same host and port for the scripts.
(define %builder-host "guix-builder.tailbf68ad.ts.net")
(define %builder-publish-url (string-append "http://" %builder-host ":8080"))
;; sshd for machines (offload, deploy, prebuild).  Port 22 on the tailnet
;; address is Tailscale SSH, for people.
(define %builder-ssh-port 2222)

(define (builder-key-path name)
  (string-append %repo-dir "/modules/engstrand/builder/" name))

(define (builder-key-file name)
  "Return modules/engstrand/builder/NAME as a file-like object, or #f."
  (let ((file (builder-key-path name)))
    (and (file-exists? file) (local-file file name))))

(define (builder-key-string name)
  "Return the trimmed contents of modules/engstrand/builder/NAME, or #f."
  (let ((file (builder-key-path name)))
    (and (file-exists? file)
         (string-trim-both (call-with-input-file file get-string-all)))))
