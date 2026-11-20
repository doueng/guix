(define-module (engstrand features terminal-mux)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages ghostty)
  #:use-module (engstrand packages rex)
  #:use-module (gnu home services)
  #:use-module (gnu services)
  #:use-module (guix gexp)
  #:export (feature-familiar-terminal-mux))

;; Which terminal multiplexer the desktop uses (T207): 'herdr, Herdr in
;; Ghostty; 'rex, the rexmux window on a Rex server; or 'ghostty-rex, Ghostty
;; drawing a Rex session through the bb ghostty-rex client. All stay
;; installed; the choice is the one word in ~/.config/engstrand/mux that
;; Hyprland, mux-terminal, workspace-or-herdr-tab and notify.clj read
;; (desktop/lib/mux.clj). Both Rex clients start the local Rex server
;; themselves, with the window's environment, as Herdr starts its own.
;; ghostty-rex adds rexg-render, its C half, built on libghostty-vt.
(define (feature-familiar-terminal-mux mux)
  (unless (memq mux '(herdr rex ghostty-rex))
    (error "terminal mux must be 'herdr, 'rex or 'ghostty-rex:" mux))
  (feature-package-set
   'familiar-terminal-mux
   #:home-packages (if (eq? mux 'ghostty-rex) (list rex rexg-render) (list rex))
   #:home-services
   (list (simple-service 'familiar-terminal-mux-choice
                         home-xdg-configuration-files-service-type
                         (list (list "engstrand/mux"
                                     (plain-file "mux" (string-append (symbol->string mux) "\n"))))))))
