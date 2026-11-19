(define-module (engstrand features terminal-mux)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages rex)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:use-module (guix gexp)
  #:export (feature-familiar-terminal-mux))

;; Which terminal multiplexer the desktop uses (T207): 'herdr, Herdr in
;; Ghostty, or 'rex, the rexmux window on a Rex server. Both stay installed;
;; the choice is the one word in ~/.config/engstrand/mux that Hyprland,
;; mux-terminal, workspace-or-herdr-tab and notify.clj read
;; (desktop/lib/mux.clj). rexmux starts the local Rex server itself, with the
;; window's environment, as Herdr starts its own.
(define (feature-familiar-terminal-mux mux)
  (unless (memq mux '(herdr rex))
    (error "terminal mux must be 'herdr or 'rex:" mux))
  (feature-package-set
   'familiar-terminal-mux
   #:home-packages (list rex)
   #:home-services
   (list (simple-service 'familiar-terminal-mux-choice
                         home-xdg-configuration-files-service-type
                         (list (list "engstrand/mux"
                                     (plain-file "mux" (string-append (symbol->string mux) "\n"))))))))
