;; Standalone Guix Home configuration for user-only updates.
;; The rde feature graph is shared with desktop/system.scm.
(use-modules (engstrand config)
             (guix channels)
             (rde features))

(define %root-uuid
  "c4f25409-b1a5-4ef0-8ac9-8e75f011668c")
(define %esp-uuid
  "5CDF-1DF4")

(rde-config-home-environment
 (make-familiar-config
  #:root-uuid %root-uuid
  #:esp-uuid %esp-uuid
  #:channels (primitive-load
              (string-append (dirname (current-filename)) "/../channels.scm"))))
