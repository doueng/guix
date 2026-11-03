(define-module (engstrand features research-lab)
  #:use-module (engstrand features packages)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu services)
  #:use-module (gnu services shepherd)
  #:use-module (guix gexp)
  #:export (feature-familiar-research-lab))

;; The research lab checkout is mutable and the timer follows it, like the
;; reader. Every two hours it runs one `lab tick' from the checkout's .venv,
;; which `dev/env.sh' builds. `lab' reads seeds/, data/ and .env relative to
;; the working directory, so .env's LAB_SUBJECT picks the subject. A tick that
;; crashed resumes from its checkpoint at the next firing. Waiting for each tick
;; keeps two from sharing a dataroom, and the cap stops a hung one.
(define %research-lab-service
  (simple-service 'familiar-research-lab home-shepherd-service-type
    (list (shepherd-service
           (provision '(research-lab))
           (documentation "One research lab tick every two hours.")
           (modules '((shepherd service timer)))
           (start #~(let ((home (getenv "HOME")))
                      (make-timer-constructor
                       (cron-string->calendar-event "17 */2 * * *")
                       (let ((repo (string-append home "/projects/langchain/research_lab")))
                         (command (list (string-append repo "/.venv/bin/lab") "tick")
                                  #:directory repo))
                       #:log-file (string-append home "/.local/state/research-lab.log")
                       #:max-duration (* 30 60)
                       #:wait-for-termination? #t)))
           (stop #~(make-timer-destructor))
           (actions (list shepherd-trigger-action))))))

(define (feature-familiar-research-lab)
  (feature-package-set
   'familiar-research-lab
   #:home-services (list %research-lab-service)))
