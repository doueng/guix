;;; The remote aarch64 builder on Hetzner (docs/remote-builder-plan.md).
;;; It builds and publishes what the laptop's make pull, home-apply and switch
;;; need, reachable only over the tailnet.
(define-module (engstrand system builder)
  #:use-module (asahi guix config)
  #:use-module (engstrand features tailscale)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand packages babashka)
  #:use-module (gnu)
  #:use-module (gnu machine hetzner)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages ssh)
  #:use-module (gnu packages version-control)
  #:use-module (gnu services base)
  #:use-module (gnu services linux)
  #:use-module (gnu services mcron)
  #:use-module (gnu services networking)
  #:use-module (gnu services ssh)
  #:use-module (guix gexp)
  #:use-module (ice-9 textual-ports)
  #:use-module (srfi srfi-1)
  #:export (%builder-server-type
            %builder-host
            builder-key-file
            builder-key-string
            make-builder-os))

(define %builder-server-type "cax31")
(define %builder-host "guix-builder.tailbf68ad.ts.net")
(define %prebuild-home "/var/lib/prebuild")

(define (builder-key-file name)
  "Return modules/engstrand/builder/NAME as a file-like object, or #f until
phase 2 of the plan has put it there."
  (let ((file (string-append %repo-dir "/modules/engstrand/builder/" name)))
    (and (file-exists? file) (local-file file name))))

(define (builder-key-string name)
  "Return the first line of modules/engstrand/builder/NAME, or #f."
  (let ((file (string-append %repo-dir "/modules/engstrand/builder/" name)))
    (and (file-exists? file)
         (string-trim-both (call-with-input-file file get-string-all)))))

(define (authorized users+files)
  (filter-map (lambda (entry)
                (let ((key (builder-key-file (cadr entry))))
                  (and key (list (car entry) key))))
              users+files))

;; git-shell runs only the commands in ~/git-shell-commands, so the laptop's
;; `ssh prebuild@builder prebuild REV' can do nothing else.
(define %prebuild-command
  (program-file "prebuild"
                #~(apply execl #$(file-append babashka "/bin/bb") "bb"
                         #$(package-asset "desktop/bin/builder-prebuild")
                         (cdr (command-line)))))

(define %prebuild-activation
  (with-imported-modules '((guix build utils))
    #~(begin
        (use-modules (guix build utils))
        (let ((home #$%prebuild-home)
              (user (getpwnam "prebuild")))
          (for-each mkdir-p (map (lambda (dir) (string-append home "/" dir))
                                 '("git-shell-commands" "roots" "logs")))
          (unless (file-exists? (string-append home "/guix.git"))
            (invoke #$(file-append git "/bin/git") "init" "--bare" "-q"
                    (string-append home "/guix.git")))
          (let ((link (string-append home "/git-shell-commands/prebuild")))
            (false-if-exception (delete-file link))
            (symlink #$%prebuild-command link))
          ;; Not the command symlink: chown would follow it into the store.
          (for-each (lambda (file)
                      (unless (eq? 'symlink (stat:type (lstat file)))
                        (chown file (passwd:uid user) (passwd:gid user))))
                    (find-files home #:directories? #t))))))

(define %prebuild-jobs
  (list #~(job "0 3 * * *"
               #$(file-append %prebuild-command " nightly")
               #:user "prebuild")
        ;; Prebuild roots keep the last three revisions alive.
        #~(job "0 5 * * *" "guix gc --free-space=40G")))

(define* (make-builder-os #:key bootstrap?)
  "Return the builder's operating system.  BOOTSTRAP? also admits public ssh,
for the first deploy before Tailscale is up; the Cloud Firewall then limits it
to your address."
  (let ((base (make-hetzner-os %builder-server-type)))
    (operating-system
      (inherit base)
      (host-name "guix-builder")
      (timezone "Europe/London")
      (users (cons* (user-account
                     (name "offload")
                     (group "users")
                     (comment "guix offload from the laptop")
                     (home-directory "/home/offload"))
                    (user-account
                     (name "prebuild")
                     (group "users")
                     (comment "Prebuilds pushed from the laptop")
                     (home-directory %prebuild-home)
                     (shell (file-append git "/bin/git-shell")))
                    %base-user-accounts))
      (packages (cons* git curl babashka (operating-system-packages base)))
      (services
       (cons* %tailscale-service
              (service openssh-service-type
                       (openssh-configuration
                        (openssh openssh-sans-x)
                        (permit-root-login 'prohibit-password)
                        (password-authentication? #f)
                        ;; Offload hangs on a dead connection otherwise.
                        (extra-content "ClientAliveInterval 30\nClientAliveCountMax 4\n")
                        (authorized-keys
                         (authorized '(("root" "deploy.pub")
                                       ("prebuild" "deploy.pub")
                                       ("offload" "offload.pub"))))))
              (service nftables-service-type
                       (nftables-configuration
                        (ruleset
                         (if bootstrap?
                             (plain-file "nftables-bootstrap.conf"
                                         (string-append
                                          (call-with-input-file
                                              (string-append %repo-dir "/modules/engstrand/builder/nftables.conf")
                                            (lambda (port) (get-string-all port)))
                                          "table inet bootstrap {\n  chain input {\n    type filter hook input priority -1; policy accept;\n    tcp dport 22 accept\n  }\n}\n"))
                             (package-asset "modules/engstrand/builder/nftables.conf")))))
              (service guix-publish-service-type
                       (guix-publish-configuration
                        ;; nftables admits 8080 only from tailscale0, so this
                        ;; does not wait for the tailnet address.
                        (host "0.0.0.0")
                        (port 8080)
                        (compression '(("zstd" 9)))
                        (cache "/var/cache/guix/publish")
                        (cache-bypass-threshold (* 100 1024 1024))
                        (ttl (* 30 24 3600))
                        (negative-ttl 60)))
              (service zram-device-service-type
                       (zram-device-configuration
                        (size "8G")
                        (compression-algorithm 'zstd)))
              (simple-service 'prebuild-activation activation-service-type
                              %prebuild-activation)
              (simple-service 'prebuild-jobs mcron-service-type %prebuild-jobs)
              (modify-services (operating-system-user-services base)
                (delete openssh-service-type)
                (guix-service-type
                 config => (guix-configuration
                            (inherit config)
                            (substitute-urls (append '("https://bordeaux.guix.gnu.org")
                                                     %asahi-substitute-urls
                                                     '("https://ci.guix.gnu.org")))
                            (authorized-keys
                             (append %asahi-substitute-keys
                                     (filter-map builder-key-file
                                                 '("laptop-signing.pub" "nixpkgs-builder.pub"))
                                     (guix-configuration-authorized-keys config)))
                            (extra-options '("--max-jobs=2" "--cores=4"
                                             "--gc-keep-outputs"
                                             "--timeout=21600"
                                             "--max-silent-time=3600"))))))))))
