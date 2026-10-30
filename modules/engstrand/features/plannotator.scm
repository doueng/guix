(define-module (engstrand features plannotator)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages plannotator)
  #:use-module (gnu home services)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-plannotator))

;; Connecting would edit Pi's and Claude Code's config, which this repo owns,
;; and the package is pinned, so update notices only mislead.
(define %plannotator-environment
  '(("PLANNOTATOR_INBOX_CONNECT" . "0")
    ("PLANNOTATOR_UPDATE_CHECK" . "0")))

;; bbh looks here.  Unset, Plannotator would follow XDG_DATA_HOME, which an
;; environment without Guix Home lacks, so the two could miss each other.
(define %plannotator-data-dir "/.plannotator")

(define %plannotator-inbox-service
  (simple-service 'familiar-plannotator-inbox home-shepherd-service-type
    (list (shepherd-service
           (provision '(plannotator-inbox))
           (documentation "The Plannotator Inbox, which bbh sessions post to.")
           (respawn? #t)
           (start #~(make-forkexec-constructor
                     (list #$(file-append plannotator "/bin/plannotator")
                           "inbox" "--no-open")
                     #:environment-variables
                     (append (list (string-append "PLANNOTATOR_DATA_DIR="
                                                  (getenv "HOME") #$%plannotator-data-dir))
                             '#$(map (lambda (pair)
                                       (string-append (car pair) "=" (cdr pair)))
                                     %plannotator-environment)
                             (default-environment-variables))
                     #:log-file (string-append (getenv "HOME")
                                               "/.local/state/plannotator-inbox.log")))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-plannotator)
  (feature-package-set
   'familiar-plannotator
   #:home-packages (list plannotator)
   #:home-services
   (list %plannotator-inbox-service
         (simple-service 'familiar-plannotator-environment
                         home-environment-variables-service-type
                         (acons "PLANNOTATOR_DATA_DIR"
                                (string-append "$HOME" %plannotator-data-dir)
                                %plannotator-environment)))))
