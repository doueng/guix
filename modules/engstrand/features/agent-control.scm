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
;;; equivalents stay out: the keyd socket accepts command() bindings that run
;;; as root, and reconfigure builds whatever the writable repo describes.

(define %ydotool-socket "/run/ydotoold.socket")

;; ydotoold's uinput device advertises media keys, so keyd's [ids] * grabs it
;; and injected chords pass through the same layers as the built-in keyboard.
(define %ydotoold-service
  (simple-service 'familiar-ydotoold shepherd-root-service-type
    (list (shepherd-service
           (provision '(ydotoold))
           (requirement '(udev kernel-module-loader))
           (documentation "Inject input upstream of keyd for the user.")
           (start #~(let ((user (getpwnam "engstrand")))
                      (make-forkexec-constructor
                       (list #$(file-append ydotool "/bin/ydotoold")
                             (string-append "--socket-path=" #$%ydotool-socket)
                             (string-append "--socket-own="
                                            (number->string (passwd:uid user)) ":"
                                            (number->string (passwd:gid user))))
                       #:log-file "/var/log/ydotoold.log")))
           (stop #~(make-kill-destructor))))))

(define %agent-sudoers
  (simple-service 'familiar-agent-sudoers sudoers-service-type
    (list (string-append
           "engstrand ALL=(root) NOPASSWD: "
           "/run/current-system/profile/bin/herd status, "
           "/run/current-system/profile/bin/herd status *, "
           "/run/current-system/profile/bin/keyd monitor, "
           "/run/current-system/profile/bin/keyd monitor -t, "
           "/run/current-system/profile/bin/keyd reload"))))

(define (feature-familiar-agent-control)
  (feature-package-set
   'familiar-agent-control
   #:home-packages (list ydotool)
   #:home-services
   (list (simple-service 'familiar-ydotool-socket
                         home-environment-variables-service-type
                         `(("YDOTOOL_SOCKET" . ,%ydotool-socket))))
   #:system-services (list %ydotoold-service %agent-sudoers)))
