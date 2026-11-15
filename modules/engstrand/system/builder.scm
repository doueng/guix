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

(define %builder-server-type "cax21")
(define %prebuild-home "/var/lib/prebuild")

(define (authorized users+files)
  (filter-map (lambda (entry)
                (let ((key (builder-key-file (cadr entry))))
                  (and key (list (car entry) key))))
              users+files))

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
        #~(job "0 5 * * *" "guix gc --free-space=20G")))

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
                        (port-number (if bootstrap? 22 %builder-ssh-port))
                        (permit-root-login 'prohibit-password)
                        (password-authentication? #f)
                        (extra-content "ClientAliveInterval 30\nClientAliveCountMax 4\n")
                        (authorized-keys
                         (append (authorized '(("root" "deploy.pub")
                                               ("prebuild" "deploy.pub")
                                               ("offload" "offload.pub")))
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
               %reader-services
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
                            (extra-options '("--max-jobs=1" "--cores=4"
                                             "--gc-keep-outputs"
                                             "--timeout=21600"
                                             "--max-silent-time=3600")))))))))))
