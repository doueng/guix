;; Print the folded system facts that activation will use, and fail on
;; file-system options that early boot would pass verbatim to mount(2).
;; Run with: guix repl -L modules -- tests/system-facts.scm
(use-modules (engstrand config)
             (gnu services)
             (gnu services base)
             (gnu services shepherd)
             (gnu services sysctl)
             (gnu system)
             (gnu system file-systems)
             (ice-9 match)
             (rde features)
             (srfi srfi-1))

(define os (rde-config-operating-system %familiar-config))

(define (folded type)
  (service-value (fold-services (operating-system-services os)
                                #:target-type type)))

;; mount(8) converts these words into flags; Guix's initrd does not.
(define %flag-words
  '(("ro" . read-only) ("bind" . bind-mount) ("nosuid" . no-suid)
    ("nodev" . no-dev) ("noexec" . no-exec) ("noatime" . no-atime)
    ("nodiratime" . no-diratime) ("strictatime" . strict-atime)
    ("lazytime" . lazy-time) ("remount" . remount)
    ("rw" . #f) ("relatime" . #f) ("defaults" . #f)))

(define (flag-words fs)
  (filter (lambda (word) (assoc word %flag-words))
          (string-split (or (file-system-options fs) "") #\,)))

(format #t "substitute-urls ~s~%"
        (guix-configuration-substitute-urls (folded guix-service-type)))
(format #t "sysctl ~s~%"
        (sysctl-configuration-settings (folded sysctl-service-type)))
(format #t "shepherd ~s~%"
        (sort (map (compose symbol->string shepherd-service-canonical-name)
                   (shepherd-configuration-services
                    (folded shepherd-root-service-type)))
              string<?))

(define errors
  (filter-map
   (lambda (fs)
     (match (flag-words fs)
       (() #f)
       (words
        (format #f "~a: options ~s are mount(8) flags; use (flags '~s)"
                (file-system-mount-point fs) words
                (filter-map (lambda (word) (assoc-ref %flag-words word)) words)))))
   (operating-system-file-systems os)))

(for-each (lambda (fs)
            (format #t "file-system ~a ~s ~s~%" (file-system-mount-point fs)
                    (file-system-flags fs) (file-system-options fs)))
          (operating-system-file-systems os))
(for-each (lambda (error) (format (current-error-port) "error: ~a~%" error)) errors)
(exit (null? errors))
