(define-module (engstrand desktop-files)
  #:use-module (guix build utils)
  #:use-module (guix gexp)
  #:use-module (srfi srfi-1)
  #:use-module (srfi srfi-13)
  #:export (desktop-file %desktop-home-files
            %desktop-direct-home-links %desktop-keyd-config))

(define %module-file
  (canonicalize-path (search-path %load-path "engstrand/desktop-files.scm")))
(define %repo
  (dirname (dirname (dirname %module-file))))

(define (repo-file relative)
  (string-append %repo "/" relative))

(define (relative-to root path)
  (substring path (+ 1 (string-length root))))

(define (source-files relative)
  (let* ((root (repo-file relative))
         (prefix (if (string-suffix? "/" root) root (string-append root "/"))))
    (map (lambda (path)
           (cons (relative-to prefix path) path))
         (sort (find-files root #:directories? #f) string<?))))

(define (tree-entries target-root source-root)
  (map (lambda (entry)
         (cons (if (string-null? target-root)
                   (car entry)
                   (string-append target-root "/" (car entry)))
               (cdr entry)))
       (source-files source-root)))

(define (file-entry target source)
  (cons target (repo-file source)))

(define %home-sources
  (append
   (tree-entries "" "desktop/home")
   (tree-entries ".config/fish" "desktop/shared/shell/fish")
   (tree-entries ".config/nvim" "desktop/shared/neovim")
   (tree-entries ".config/doom" "desktop/shared/doom")
   (list (file-entry ".config/git/config" "desktop/shared/git/config"))
   (list (file-entry ".config/jj/config.toml" "desktop/shared/jj/config.toml"))
   (list (file-entry ".config/herdr/config.toml" "desktop/shared/herdr/config.toml"))
   (list (file-entry ".config/herdr/sesh.toml" "desktop/shared/herdr/sesh.toml"))
   (tree-entries ".local/share/herdr/tiny-fingers"
                  "desktop/shared/herdr/tiny-fingers")
   (list (file-entry ".config/noctalia/config.toml"
                      "desktop/shared/noctalia/config.toml"))
   (list (file-entry ".config/ghostty/config" "desktop/shared/ghostty/config"))
   (list (file-entry ".config/ghostty/config.asahi"
                      "desktop/shared/ghostty/config.asahi"))
   (tree-entries ".config/ghostty/themes" "desktop/shared/ghostty/themes")
   (list (file-entry ".config/btop/themes/catppuccin-mocha.theme"
                      "desktop/shared/theme/btop.theme"))
   (tree-entries ".local/share/catppuccin-mocha/wallpapers"
                  "desktop/shared/theme/wallpapers")
   (list (file-entry ".pi/README.md" "desktop/shared/pi/README.md"))
   (tree-entries ".pi/agent" "desktop/shared/pi/assets/agent")
   (list (file-entry ".config/jjui/config.toml" "desktop/shared/jjui/config.toml"))
   (tree-entries ".local/bin" "desktop/bin")
   (tree-entries ".local/bin" "desktop/shared/herdr/bin")))

(define %desktop-direct-home-links
  (append
   (list
    (file-entry ".config/git/config" "desktop/shared/git/config")
    (file-entry ".config/jj/config.toml" "desktop/shared/jj/config.toml")
    (file-entry ".config/jjui/config.toml" "desktop/shared/jjui/config.toml")
    (file-entry ".config/herdr/config.toml" "desktop/shared/herdr/config.toml")
    (file-entry ".config/herdr/sesh.toml" "desktop/shared/herdr/sesh.toml")
    (file-entry ".config/noctalia/config.toml"
                 "desktop/shared/noctalia/config.toml")
    (file-entry ".config/ghostty/config" "desktop/shared/ghostty/config")
    (file-entry ".config/ghostty/config.asahi"
                 "desktop/shared/ghostty/config.asahi")
    (file-entry ".config/btop/themes/catppuccin-mocha.theme"
                 "desktop/shared/theme/btop.theme")
    (file-entry ".pi/README.md" "desktop/shared/pi/README.md"))
   (tree-entries ".config/fish" "desktop/shared/shell/fish")
   (tree-entries ".config/nvim" "desktop/shared/neovim")
   (tree-entries ".config/doom" "desktop/shared/doom")
   (tree-entries ".config/hypr" "desktop/home/.config/hypr")
   (tree-entries ".config/waybar" "desktop/home/.config/waybar")
   (tree-entries ".pi/agent" "desktop/shared/pi/assets/agent")
   (tree-entries ".local/share/catppuccin-mocha/wallpapers"
                  "desktop/shared/theme/wallpapers")
   (tree-entries ".local/share/herdr/tiny-fingers"
                  "desktop/shared/herdr/tiny-fingers")
   (tree-entries ".local/bin" "desktop/bin")
   (tree-entries ".local/bin" "desktop/shared/herdr/bin")))

(define %desktop-home-files
  (map (lambda (entry)
         (list (car entry) (local-file (cdr entry))))
       (filter (lambda (entry)
                 (not (assoc (car entry) %desktop-direct-home-links)))
               %home-sources)))

(define (desktop-file path)
  (local-file (repo-file path)))

(define %desktop-keyd-config
  (local-file (repo-file "desktop/keyd.conf")))
