(use-modules (engstrand system builder)
             (gnu machine)
             (gnu machine hetzner)
             (gnu machine ssh))

(define bootstrap? (getenv "BUILDER_BOOTSTRAP"))
;; Unattended: guile-ssh cannot use a passphrase-protected key, and there is no agent.
(define ssh-key (or (getenv "BUILDER_SSH_KEY")
                    (string-append (getenv "HOME") "/.config/guix-builder/deploy_ed25519")))

(list
 (machine
  (operating-system (make-builder-os #:bootstrap? bootstrap?))
  (environment (if bootstrap? hetzner-environment-type managed-host-environment-type))
  (configuration
   (if bootstrap?
       (hetzner-configuration
        (server-type %builder-server-type)
        (location (or (getenv "BUILDER_LOCATION") "hel1"))
        (ssh-key ssh-key)
        (build-locally? #f))
       (machine-ssh-configuration
        (host-name %builder-host)
        (port (or (and=> (getenv "BUILDER_SSH_PORT") string->number) %builder-ssh-port))
        (system "aarch64-linux")
        (user "root")
        (identity ssh-key)
        (build-locally? #f)
        (host-key (builder-key-string "host-key.pub")))))))
