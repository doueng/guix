;;; Its data lives in %brain-home/data, outside the store.  The laptop pushes
;;; Chrome's bookmarks with a key that can run only the importer.
(define-module (engstrand system second-brain)
  #:use-module (engstrand features tailscale)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand packages babashka)
  #:use-module (engstrand packages second-brain)
  #:use-module (engstrand packages tailscale)
  #:use-module (engstrand system builder-keys)
  #:use-module (gnu packages base)
  #:use-module (gnu packages bash)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages sqlite)
  #:use-module (gnu services)
  #:use-module (gnu services mcron)
  #:use-module (gnu services shepherd)
  #:use-module (gnu system accounts)
  #:use-module (gnu system shadow)
  #:use-module (guix gexp)
  #:export (%brain-authorized-key
            %brain-services))

(define %brain-home "/var/lib/second-brain")
(define %brain-port 8790)

(define (glue subcommand)
  (program-file (string-append "second-brain-" subcommand)
    #~(begin
        (setenv "BRAIN_HOME" #$%brain-home)
        (setenv "HOME" #$%brain-home)
        (setenv "LANG" "C.UTF-8")
        (setenv "PATH" (string-join
                        (list #$(file-append second-brain "/bin")
                              #$(file-append tailscale "/bin")
                              #$(file-append sqlite "/bin")
                              #$(file-append util-linux "/bin")
                              #$(file-append coreutils "/bin"))
                        ":"))
        (execl #$(file-append babashka "/bin/bb") "bb"
               #$(package-asset "desktop/build/second-brain/builder.bb")
               #$subcommand))))

;; sshd runs the receiver whatever the laptop asks for, through the user's
;; shell, with no pty, forwarding or agent.
(define %brain-authorized-key
  (let ((key (builder-key-string "second-brain.pub")))
    (and key
         (mixed-text-file "second-brain.pub"
                          "command=\"" (glue "receive") "\",restrict " key "\n"))))

(define %accounts
  (list (user-group (name "second-brain") (system? #t))
        (user-account
         (name "second-brain")
         (group "second-brain")
         (system? #t)
         (comment "The second brain")
         (home-directory %brain-home)
         (create-home-directory? #f)
         ;; A shell, so sshd can run the forced command; the key admits nothing else.
         (shell (file-append bash-minimal "/bin/bash")))))

;; Saves and fetched pages are private: only the service user reads them.
(define %activation
  #~(begin
      (use-modules (guix build utils))
      (let ((user (getpwnam "second-brain")))
        (for-each (lambda (dir)
                    (mkdir-p dir)
                    (chown dir (passwd:uid user) (passwd:gid user))
                    (chmod dir #o700))
                  (map (lambda (dir) (string-append #$%brain-home dir))
                       '("" "/data" "/inbox" "/backups" "/logs"))))))

;; 21:30 London, well before the 03:00 prebuild and 05:00 gc.
(define %jobs
  (list #~(job "30 21 * * *"
               #$(program-file "second-brain-nightly-logged"
                   #~(begin
                       (let ((log (open-file #$(string-append %brain-home "/logs/nightly.log") "a")))
                         (dup2 (fileno log) 1)
                         (dup2 (fileno log) 2))
                       (execl #$(glue "nightly") "second-brain-nightly")))
               #:user "second-brain")))

;; Loopback only; tailscale serve fronts it over HTTPS.  The page reads the
;; owner from tailscaled, so it requires it.  A crash loop stops after a
;; minute; `herd enable second-brain' rearms it.
(define %shepherd-services
  (list
   (shepherd-service
    (provision '(second-brain))
    (requirement '(networking tailscaled))
    (documentation "The second brain's page on loopback.")
    (respawn? #t)
    (respawn-limit #~'(5 . 60))
    (respawn-delay 2)
    (start #~(make-forkexec-constructor
              (list #$(glue "serve"))
              #:user "second-brain"
              #:group "second-brain"
              #:directory #$%brain-home
              #:log-file "/var/log/second-brain.log"))
    (stop #~(make-kill-destructor)))
   (tailscale-one-shot 'second-brain-serve
                       "Publish the second brain on the tailnet over HTTPS (tailscale serve)."
                       "serve" "--bg" "--https=8443"
                       (format #f "http://127.0.0.1:~a" %brain-port))))

(define %brain-services
  (list (simple-service 'second-brain-accounts account-service-type %accounts)
        (simple-service 'second-brain-activation activation-service-type
                        (with-imported-modules '((guix build utils)) %activation))
        (simple-service 'second-brain-shepherd shepherd-root-service-type
                        %shepherd-services)
        (simple-service 'second-brain-jobs mcron-service-type %jobs)))
