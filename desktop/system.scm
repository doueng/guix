(use-modules (engstrand desktop)
             (guix channels)
             (ice-9 regex)
             (json)
             (rnrs io ports))

(define %base-directory
  (or (getenv "GUIX_BASE") "/etc/guix-ssd"))

(define (read-json path)
  (call-with-input-file path
    (lambda (port)
      (json-string->scm (get-string-all port)))))

(define (field object name)
  (let ((entry (assoc name object)))
    (and entry (cdr entry))))

(define %devices
  (read-json (string-append %base-directory "/devices.json")))
(define %root-uuid
  (field (field %devices "root") "uuid"))
(define %esp-uuid
  (field (field %devices "esp") "uuid"))
(define %esp-partuuid
  (field (field %devices "esp") "partuuid"))

(unless (equal? (field %devices "schema") 2)
  (error "The installation identity record must use schema 2"))
(unless (and (string? %root-uuid)
             (string-match "^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"
                          %root-uuid)
             (string? %esp-uuid)
             (string-match "^[0-9a-fA-F]{4}-[0-9a-fA-F]{4}$" %esp-uuid)
             (string? %esp-partuuid))
  (error "The installation identity record has invalid root/ESP UUIDs"))
(when (or (string-ci=? %esp-uuid "5CDF-1DF4")
          (string-ci=? %esp-partuuid
                       "ea8adc5b-ec2d-4df1-913b-f0f05ff85367"))
  (error "Refusing the protected NixOS ESP"))

(make-familiar-os
 #:root-uuid %root-uuid
 #:esp-uuid %esp-uuid
 #:channels (primitive-load
             (string-append (dirname (current-filename)) "/../channels.scm")))
