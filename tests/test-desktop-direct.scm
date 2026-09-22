(use-modules (engstrand desktop-files)
             (guix gexp)
             (srfi srfi-1))

(define (check condition message)
  (unless condition (error message)))

(for-each
 (lambda (entry)
   (check (and (string? (car entry))
               (file-exists? (local-file-file (cadr entry))))
          "Missing direct Home source"))
 %desktop-home-files)

(for-each
 (lambda (entry)
   (check (and (string? (car entry))
               (string? (cdr entry))
               (file-exists? (cdr entry)))
          "Missing editable checkout source"))
 %desktop-direct-home-links)

(check (file-exists? (local-file-file %desktop-keyd-config))
       "Missing keyd configuration")

(for-each
 (lambda (entry)
   (check (assoc entry %desktop-direct-home-links)
          (string-append "Missing exact Home destination: " entry)))
 '(".config/hypr/hyprland.conf"
   ".config/nvim/init.lua"
   ".local/bin/custom-launcher"))

(for-each
 (lambda (entry)
   (check (assoc entry %desktop-home-files)
          (string-append "Missing exact generated Home destination: " entry)))
 '(".config/ghostty/themes/catppuccin-mocha"))

(display "PASS: direct Scheme desktop sources\n")
