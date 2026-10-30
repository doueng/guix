(define-module (engstrand features plannotator)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages plannotator)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:export (feature-familiar-plannotator))

;; Connecting would edit Pi's and Claude Code's config, which this repo owns,
;; and the package is pinned, so update notices only mislead.
(define %plannotator-environment
  '(("PLANNOTATOR_INBOX_CONNECT" . "0")
    ("PLANNOTATOR_UPDATE_CHECK" . "0")))

(define (feature-familiar-plannotator)
  (feature-package-set
   'familiar-plannotator
   #:home-packages (list plannotator)
   #:home-services
   (list (simple-service 'familiar-plannotator-environment
                         home-environment-variables-service-type
                         %plannotator-environment))))
