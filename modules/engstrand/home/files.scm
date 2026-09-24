(define-module (engstrand home files)
  #:use-module (guix build utils)
  #:use-module (guix gexp)
  #:use-module (srfi srfi-1)
  #:use-module (srfi srfi-13)
  #:export (%desktop-home-files %desktop-direct-home-links
            %desktop-keyd-config))

(define %module-file
  (canonicalize-path (search-path %load-path "engstrand/home/files.scm")))
(define %repo
  (dirname (dirname (dirname (dirname %module-file)))))

(define (repo-file relative)
  (string-append %repo "/" relative))

(define (relative-to root path)
  (substring path (string-length root)))

(define (generated-source? path)
  (or (string-suffix? ".pyc" path)
      (string-suffix? ".pyo" path)
      (string-contains path "/__pycache__/")))

(define (source-files relative)
  (let* ((root (repo-file relative))
         (prefix (if (string-suffix? "/" root) root (string-append root "/"))))
    ;; Wallpapers are optional and not present in every checkout.
    (if (and (string=? relative "desktop/configs/theme/wallpapers")
             (not (file-exists? root)))
        '()
        (begin
          (unless (file-exists? root) (error "Missing desktop source directory" root))
          (map (lambda (path)
                 (cons (relative-to prefix path) path))
               (sort (filter (lambda (path) (not (generated-source? path)))
                             (find-files root #:directories? #f))
                     string<?))))))

(define (tree-entries target-root source-root)
  (map (lambda (entry)
         (cons (if (string-null? target-root)
                   (car entry)
                   (string-append target-root "/" (car entry)))
               (cdr entry)))
       (source-files source-root)))

(define (file-entry target source)
  (cons target (repo-file source)))

(define %desktop-direct-home-links
  (append
   (list
    (file-entry ".config/git/config" "desktop/configs/git/config")
    (file-entry ".config/jj/config.toml" "desktop/configs/jj/config.toml")
    (file-entry ".config/jjui/config.toml" "desktop/configs/jjui/config.toml")
    (file-entry ".config/herdr/config.toml" "desktop/configs/herdr/config.toml")
    (file-entry ".config/herdr/sesh.toml" "desktop/configs/herdr/sesh.toml")
    (file-entry ".config/noctalia/config.toml"
                 "desktop/configs/noctalia/config.toml")
    (file-entry ".local/share/icons/transparent.svg"
                 "desktop/configs/noctalia/transparent.svg")
    (file-entry ".config/ghostty/config" "desktop/configs/ghostty/config")
    (file-entry ".config/ghostty/config.asahi"
                 "desktop/configs/ghostty/config.asahi")
    (file-entry ".config/btop/themes/catppuccin-mocha.theme"
                 "desktop/configs/theme/btop.theme")
    (file-entry ".pi/README.md" "desktop/configs/pi/README.md"))
   (tree-entries ".config/fish" "desktop/configs/shell/fish")
   (tree-entries ".config/nvim" "desktop/configs/neovim")
   (tree-entries ".config/doom" "desktop/configs/doom")
   (tree-entries ".config/hypr" "desktop/configs/hypr")
   (tree-entries ".pi/agent" "desktop/configs/pi/assets/agent")
   (tree-entries ".local/share/catppuccin-mocha/wallpapers"
                  "desktop/configs/theme/wallpapers")
   (tree-entries ".local/share/herdr/tiny-fingers"
                  "desktop/configs/herdr/tiny-fingers")
   (tree-entries ".local/bin" "desktop/bin")
   (tree-entries ".local/bin" "desktop/configs/herdr/bin")))

(define %desktop-home-files
  (map (lambda (entry)
         (list (car entry) (local-file (cdr entry))))
       (tree-entries ".config/ghostty/themes"
                     "desktop/configs/ghostty/themes")))

(define %desktop-keyd-config
  (local-file (repo-file "desktop/keyd.conf")))
