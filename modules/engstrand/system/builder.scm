;;; The remote aarch64 builder on Hetzner (docs/remote-builder-plan.md).
;;; It builds and publishes what the laptop's make pull, home-apply and switch
;;; need, reachable only over the tailnet.
(define-module (engstrand system builder)
  #:use-module (asahi guix config)
  #:use-module (engstrand features tailscale)
  #:use-module (engstrand system builder-keys)
  #:use-module (engstrand system reader)
  #:use-module (engstrand system second-brain)
  #:use-module (engstrand packages assets)
  #:use-module (engstrand packages babashka)
  #:use-module (engstrand packages tailscale)
  #:use-module (gnu)
  #:use-module (gnu machine hetzner)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages sqlite)
  #:use-module (gnu packages ssh)
  #:use-module (gnu packages version-control)
  #:use-module (gnu services)
  #:use-module (gnu services base)
  #:use-module (gnu services linux)
  #:use-module (gnu services mcron)
  #:use-module (gnu services networking)
  #:use-module (gnu services shepherd)
  #:use-module (gnu services ssh)
  #:use-module (guix gexp)
  #:use-module ((guix utils) #:select (string-replace-substring))
  #:use-module (ice-9 textual-ports)
  #:use-module (srfi srfi-1)
  #:re-export (%builder-host %builder-ssh-port builder-key-string)
  #:export (%builder-server-type
            make-builder-os))

;; CAX21: 4 vCPU, 8 GB, 80 GB, about 10.5 EUR/month (chosen 2026-11-09 over
;; the CAX31 at 21).  Rescale up with the server off; keep the disk to come back.
(define %builder-server-type "cax21")
(define %prebuild-home "/var/lib/prebuild")

(define (authorized users+files)
  (filter-map (lambda (entry)
                (let ((key (builder-key-file (cadr entry))))
                  (and key (list (car entry) key))))
              users+files))

;; git-shell runs only the commands in ~/git-shell-commands, so the laptop's
;; `ssh prebuild@builder prebuild REV' can do nothing else.  The script loads
;; desktop/lib/builder.clj, which -cp supplies from the store.
(define %prebuild-lib
  (file-union "prebuild-lib"
              `(("builder.clj" ,(package-asset "desktop/lib/builder.clj")))))

(define %prebuild-command
  (program-file "prebuild"
                #~(apply execl #$(file-append babashka "/bin/bb") "bb"
                         "-cp" #$%prebuild-lib
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
        #~(job "0 5 * * *" "guix gc --free-space=20G")))

;; Tailscale SSH for people: tailscaled answers port 22 on the tailnet address
;; and the tailnet policy's ssh rules decide who logs in as whom.  sshd stays
;; on 2222 for guix offload, deploy and prebuild, which need a real sshd.  The
;; setting persists in tailscaled's state; this restates it at boot and deploy.
(define %tailscale-ssh-service
  (simple-service 'tailscale-ssh shepherd-root-service-type
    (list (tailscale-one-shot 'tailscale-ssh
                              "Turn on Tailscale SSH (tailscale set --ssh)."
                              "set" "--ssh"))))

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
                     ;; Your login over Tailscale SSH; no password, so no sudo:
                     ;; log in as root for that.
                     (name "engstrand")
                     (group "users")
                     (comment "Douglas Engstrand"))
                    (user-account
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
      (packages (cons* git curl babashka sqlite tailscale (operating-system-packages base)))
      (services
       (cons* %tailscale-service
              %tailscale-ssh-service
              (service openssh-service-type
                       (openssh-configuration
                        (openssh openssh-sans-x)
                        ;; The inetd-style service listens on one port: 22 for the
                        ;; bootstrap deploy over public ssh, then 2222, since
                        ;; Tailscale SSH answers 22 on the tailnet address.
                        (port-number (if bootstrap? 22 %builder-ssh-port))
                        (permit-root-login 'prohibit-password)
                        (password-authentication? #f)
                        ;; Offload hangs on a dead connection otherwise.
                        (extra-content "ClientAliveInterval 30\nClientAliveCountMax 4\n")
                        (authorized-keys
                         (append (authorized '(("root" "deploy.pub")
                                               ("prebuild" "deploy.pub")
                                               ("offload" "offload.pub")))
                                 ;; Runs only the second brain's importer.
                                 (if %brain-authorized-key
                                     `(("second-brain" ,%brain-authorized-key))
                                     '())))))
              (service nftables-service-type
                       (nftables-configuration
                        (ruleset
                         (if bootstrap?
                             ;; In the filter chain itself: an accept in another base chain
                             ;; does not stop this chain's drop policy.
                             (plain-file "nftables-bootstrap.conf"
                                         (string-replace-substring
                                          (call-with-input-file
                                              (string-append %repo-dir "/modules/engstrand/builder/nftables.conf")
                                            (lambda (port) (get-string-all port)))
                                          "    udp dport 41641 accept\n"
                                          "    udp dport 41641 accept\n    tcp dport 22 accept\n"))
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
              (append
               ;; The feed reader, on the tailnet at https://guix-builder.tailbf68ad.ts.net/.
               %reader-services
               ;; The second brain, at https://guix-builder.tailbf68ad.ts.net:8443/.
               %brain-services
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
                                                 '("laptop-signing.pub"))
                                     (guix-configuration-authorized-keys config)))
                            ;; One build at a time: two Rust or kernel builds would not fit 8 GB.
                            (extra-options '("--max-jobs=1" "--cores=4"
                                             "--gc-keep-outputs"
                                             "--timeout=21600"
                                             "--max-silent-time=3600")))))))))))
