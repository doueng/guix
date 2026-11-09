;; guix deploy file for the remote builder (docs/remote-builder-plan.md).
;; make builder-create sets BUILDER_BOOTSTRAP to create the Hetzner server and
;; admit public ssh; make builder-deploy reconfigures it over the tailnet.
(use-modules (engstrand system builder)
             (gnu machine)
             (gnu machine hetzner)
             (gnu machine ssh))

(define bootstrap? (getenv "BUILDER_BOOTSTRAP"))
(define ssh-key (string-append (getenv "HOME") "/.ssh/guix-builder"))

(list
 (machine
  (operating-system (make-builder-os #:bootstrap? bootstrap?))
  (environment (if bootstrap? hetzner-environment-type managed-host-environment-type))
  (configuration
   (if bootstrap?
       (hetzner-configuration
        (server-type %builder-server-type)
        (location "hel1")
        (ssh-key ssh-key)
        ;; The server builds its own system, not the laptop.
        (build-locally? #f))
       (machine-ssh-configuration
        (host-name %builder-host)
        (system "aarch64-linux")
        (user "root")
        (identity ssh-key)
        (build-locally? #f)
        ;; Phase 2 records it with ssh-keyscan.
        (host-key (builder-key-string "host-key.pub")))))))
