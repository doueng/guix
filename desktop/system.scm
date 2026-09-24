(use-modules (engstrand desktop)
             (guix channels)
             (ice-9 regex)
             (json)
             (rnrs io ports))

(define %root-uuid
  "c4f25409-b1a5-4ef0-8ac9-8e75f011668c")
(define %esp-uuid
  "5CDF-1DF4")

(make-familiar-os
 #:root-uuid %root-uuid
 #:esp-uuid %esp-uuid
 #:channels (primitive-load
             (string-append (dirname (current-filename)) "/../channels.scm")))
