(define-module (engstrand features agent-control)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services)
  #:use-module (gnu packages xdisorg)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:use-module (rde system services admin)
  #:export (feature-familiar-agent-control))

;;; Everything an agent may do on the desktop without a password.  Root
;;; equivalents stay out: reconfigure builds whatever the writable repo
;;; describes.

(define %ydotool-socket "/run/ydotoold.socket")

;; ydotoold's uinput device advertises keys and pointer motion, so kanata
;; grabs it and injected chords pass through the same layers as the built-in
;; keyboard.
(define %ydotoold-service
  (simple-service 'familiar-ydotoold shepherd-root-service-type
    (list (shepherd-service
           (provision '(ydotoold))
           (requirement '(udev kernel-module-loader))
           (documentation "Inject input upstream of kanata for the user.")
           (start #~(let ((user (getpwnam "engstrand")))
                      (make-forkexec-constructor
                       (list #$(file-append ydotool "/bin/ydotoold")
                             (string-append "--socket-path=" #$%ydotool-socket)
                             (string-append "--socket-own="
                                            (number->string (passwd:uid user)) ":"
                                            (number->string (passwd:gid user))))
                       #:log-file "/var/log/ydotoold.log")))
           (stop #~(make-kill-destructor))))))

(define %herd "/run/current-system/profile/bin/herd")

;; A restart only applies what a human already switched to.  Services that end
;; the session, stop builds, guard the speakers or carry ssh stay out;
;; NetworkManager and iwd count, because restarting them restarts ssh-daemon.
(define %restartable-services
  '("bluetooth" "kanata" "ntpd" "tailscaled" "upower-daemon" "ydotoold"))

(define %agent-sudoers
  (simple-service 'familiar-agent-sudoers sudoers-service-type
    (list (string-append
           "engstrand ALL=(root) NOPASSWD: "
           (string-join
            (append (list (string-append %herd " status")
                          (string-append %herd " status *"))
                    (map (lambda (service)
                           (string-append %herd " restart " service))
                         %restartable-services))
            ", ")))))

(define (feature-familiar-agent-control)
  (feature-package-set
   'familiar-agent-control
   #:home-packages (list ydotool)
   #:home-services
   (list (simple-service 'familiar-ydotool-socket
                         home-environment-variables-service-type
                         `(("YDOTOOL_SOCKET" . ,%ydotool-socket))))
   #:system-services (list %ydotoold-service %agent-sudoers)))
