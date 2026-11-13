;; make builder-create sets BUILDER_BOOTSTRAP to create the Hetzner server and
;; admit public ssh; make builder-deploy reconfigures it over the tailnet.
(use-modules (engstrand system builder)
             (gnu machine)
             (gnu machine hetzner)
             (gnu machine ssh))

(define bootstrap? (getenv "BUILDER_BOOTSTRAP"))
;; Unattended: guile-ssh cannot use a passphrase-protected key, and there is
;; no agent.  deploy.pub also authorizes ~/.ssh/guix-builder for you.
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
        ;; BUILDER_LOCATION=fsn1 or nbg1 when hel1 has no capacity.
        (location (or (getenv "BUILDER_LOCATION") "hel1"))
        (ssh-key ssh-key)
        ;; The server builds its own system, not the laptop.
        (build-locally? #f))
       (machine-ssh-configuration
        (host-name %builder-host)
        ;; BUILDER_SSH_PORT=22 for the first deploy after builder-create, whose
        ;; sshd still listens on 22.
        (port (or (and=> (getenv "BUILDER_SSH_PORT") string->number) %builder-ssh-port))
        (system "aarch64-linux")
        (user "root")
        (identity ssh-key)
        (build-locally? #f)
        ;; Recorded with ssh-keyscan in phase 2 of the plan.
        (host-key (builder-key-string "host-key.pub")))))))
