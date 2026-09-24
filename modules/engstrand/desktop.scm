(define-module (engstrand desktop)
  #:use-module (engstrand asahi)
  #:use-module (engstrand desktop-files)
  #:use-module (engstrand packages)
  #:use-module (asahi guix systems desktop)
  #:use-module (gnu)
  #:use-module (gnu home)
  #:use-module (gnu home services)
  #:use-module (gnu home services shells)
  #:use-module (gnu packages)
  #:use-module (gnu packages curl)
  #:use-module (gnu packages linux)
  #:use-module (gnu packages lua)
  #:use-module (gnu packages package-management)
  #:use-module (gnu packages xdisorg)
  #:use-module (gnu services guix)
  #:use-module (gnu services linux)
  #:use-module (gnu services shepherd)
  #:use-module (gnu services xorg)
  #:use-module (guix gexp)
  #:use-module (guix packages)
  #:use-module (srfi srfi-1)
  #:export (make-familiar-os %familiar-home))

(define %keyd-service
  (simple-service 'familiar-keyd shepherd-root-service-type
    (list (shepherd-service
            (provision '(keyd))
            (requirement '(udev kernel-module-loader))
            (documentation "Personal keyboard layers.")
            (start #~(make-forkexec-constructor
                       (list #$(file-append keyd "/bin/keyd"))
                       #:log-file "/var/log/keyd.log"))
            (stop #~(make-kill-destructor))))))

;; Live files are deliberately not home-files-service entries: that service
;; always adds a store indirection. Install direct links during activation,
;; and track ownership so stale links can be removed without touching files
;; the user has replaced. Conflicting live-link destinations are moved to a
;; backup under ~/.local/state/guix-home before being replaced.
(define %familiar-direct-home-links
  (simple-service 'familiar-direct-home-links home-activation-service-type
    #~(begin
        (use-modules (guix build utils) (ice-9 ftw) (ice-9 rdelim)
                     (srfi srfi-1) (srfi srfi-13))
        (define roots
          '(".config/fish" ".config/nvim" ".config/doom"
            ".config/hypr" ".config/waybar" ".pi/agent"
            ".local/share/catppuccin-mocha/wallpapers"
            ".local/share/herdr/tiny-fingers"))
        (define links '#$%desktop-direct-home-links)
        (define home (getenv "HOME"))
        (define state (string-append home "/.local/state/guix-home"))
        (define manifest (string-append state "/live-links"))
        (define backup-directory
          (string-append state "/backups/" (number->string (current-time))
                         "-" (number->string (getpid))))
        (define (symlink-target path)
          (catch 'system-error
            (lambda ()
              (and (eq? 'symlink (stat:type (lstat path)))
                   (readlink path)))
            (lambda _ #f)))
        (define (path-exists? path)
          (catch 'system-error
            (lambda () (lstat path) #t)
            (lambda _ #f)))
        (define (backup-conflict relative)
          (let* ((target (string-append home "/" relative))
                 (backup (string-append backup-directory "/" relative)))
            (when (path-exists? target)
              (when (path-exists? backup)
                (error "Home backup destination already exists" backup))
              (mkdir-p (dirname backup))
              (format #t "Backing up conflicting Home path ~a to ~a~%"
                      target backup)
              (rename-file target backup))))
        (define (ensure-parent relative)
          (let loop ((parts (drop-right (string-split relative #\/) 1))
                     (prefix ""))
            (unless (null? parts)
              (let* ((next (if (string-null? prefix) (car parts)
                               (string-append prefix "/" (car parts))))
                     (target (string-append home "/" next)))
                (when (and (path-exists? target)
                           (not (eq? 'directory (stat:type (lstat target)))))
                  (backup-conflict next))
                (mkdir-p target)
                (loop (cdr parts) next)))))
        (define (legacy-link? target)
          (and target
               (string-prefix? "/gnu/store/" target)
               (string-suffix? "-familiar-live-file" target)))
        (define previous
          (if (path-exists? manifest)
              (call-with-input-file manifest
                (lambda (port)
                  (let loop ((line (read-line port)) (result '()))
                    (if (eof-object? line)
                        (reverse result)
                        (let ((fields (string-split line #\tab)))
                          (unless (= (length fields) 2)
                            (error "Invalid live-link ownership record" manifest))
                          (loop (read-line port) (cons fields result)))))))
              '()))
        (define current-paths (map car links))
        ;; Retire only known links left by older Home generations before
        ;; checking child paths; those children otherwise resolve through the
        ;; old store-backed tree symlink during preflight.
        (for-each
         (lambda (relative)
           (let* ((target (string-append home "/" relative))
                  (old (symlink-target target)))
             (when (legacy-link? old) (backup-conflict relative))))
         '(".config/hypr/hyprland.lua"))
        (for-each
         (lambda (root)
           (let* ((target (string-append home "/" root))
                  (old (symlink-target target)))
             (when (legacy-link? old) (backup-conflict root))))
         roots)
        (for-each
         (lambda (owned)
           (unless (member (car owned) current-paths)
             (let* ((target (string-append home "/" (car owned)))
                    (old (symlink-target target)))
               (when (equal? old (cadr owned))
                 (format #t "Removing stale managed Home link ~a~%" target)
                 (delete-file target)))))
         previous)
        (for-each
         (lambda (link)
           (let* ((relative (car link))
                  (source (cdr link))
                  (target (string-append home "/" relative))
                  (old (symlink-target target)))
             (ensure-parent relative)
             (when (and (path-exists? target)
                        (not (equal? old source)))
               (backup-conflict relative))
             (unless (path-exists? target) (symlink source target))))
         links)
        (mkdir-p state)
        (let ((temporary (string-append manifest ".new")))
          (call-with-output-file temporary
            (lambda (port)
              (for-each (lambda (link)
                          (format port "~a\t~a\n" (car link) (cdr link)))
                        links)))
          (rename-file temporary manifest)))))

(define %familiar-herdr-plugins
  (simple-service 'familiar-herdr-plugins home-activation-service-type
    #~(begin
        (use-modules (guix build utils) (ice-9 ftw) (ice-9 popen)
                     (ice-9 rdelim) (srfi srfi-13))
        (define (herdr-symlink-target path)
          (catch 'system-error
            (lambda ()
              (and (eq? 'symlink (stat:type (lstat path)))
                   (readlink path)))
            (lambda _ #f)))
        (define (herdr-path-exists? path)
          (catch 'system-error
            (lambda () (lstat path) #t)
            (lambda _ #f)))
        (let* ((herdr #$(file-append herdr "/bin/herdr"))
               (sesh #$(file-append herdr-sesh ""))
               (tiny #$(file-append herdr-tiny-fingers "")))
          (invoke herdr "plugin" "link" sesh "--enabled")
          (invoke herdr "plugin" "link" tiny "--enabled")
          (let* ((pipe (open-input-pipe
                        (string-append herdr " plugin config-dir fullerzz.sesh")))
                 (config-dir (string-trim-both (read-line pipe)))
                 (status (close-pipe pipe))
                 (target (string-append config-dir "/sesh.toml"))
                 (config (string-append (getenv "HOME") "/.config/herdr/sesh.toml")))
            (unless (zero? status)
              (error "Could not determine Herdr sesh plugin config directory"))
            (mkdir-p config-dir)
            (when (herdr-path-exists? target)
              (let ((old (herdr-symlink-target target)))
                (unless (and old
                             (or (string=? old config)
                                 (string-prefix? "/gnu/store/" old)))
                  (error "Refusing to replace unmanaged Herdr config" target))
                (delete-file target)))
            (symlink config target))))))

(define %familiar-home
  (home-environment
    (packages (cons* pi-coding-agent herdr herdr-sesh herdr-tiny-fingers jjui
                     github-cli babashka noctalia ghostty curl
                     (map specification->package
                          '("fish" "jujutsu" "clojure" "clojure-tools" "emacs-clojure-mode" "emacs-cider"
                            "difftastic" "tmux" "fzf" "zoxide"
                            "direnv" "ripgrep" "fd" "jq" "bat" "btop"
                            "python" "python-black" "python-boto3" "python-pyopenssl"
                            "python-pyyaml"
                            "emacs-no-x" "emacs-fish-mode" "parinfer-rust-emacs"
                            "node" "make" "gcc-toolchain" "pkg-config"
                            "cmake" "dasel" "diff-so-fancy" "diffstat" "entr" "exercism"
                            "file" "fennel" "fnlfmt" "go" "gopls" "gore" "hyperfine"
                            "jless" "libnotify" "lua" "nixfmt" "pandoc" "qpdf" "shellcheck"
                            "shfmt" "sox" "thunar" "typst" "uv" "xxd" "yq"
                            "xdg-desktop-portal" "xdg-desktop-portal-gtk"
                            "xdg-desktop-portal-hyprland"
                            "unzip" "zip" "tree" "wl-clipboard"))))
    (services
      (cons*
        ;; Activation gexps are folded in reverse service order. Install
        ;; checkout links before Herdr tries to use its linked configuration.
        %familiar-herdr-plugins
        %familiar-direct-home-links
        (service home-bash-service-type)
        (simple-service 'familiar-environment home-environment-variables-service-type
          '(("EDITOR" . "nvim") ("VISUAL" . "nvim")
            ("DOOMDIR" . "/home/engstrand/.config/doom")
            ("COLORTERM" . "truecolor")
            ("XCURSOR_THEME" . "Adwaita")
            ("XCURSOR_SIZE" . "20")
            ("GTK_THEME" . "Adwaita:dark")
            ;; Let launchers and xdg-open discover Flatpak's Chrome entry.
            ("XDG_DATA_DIRS" . "$XDG_DATA_DIRS:$HOME/.local/share/flatpak/exports/share:/var/lib/flatpak/exports/share")))
        (simple-service 'familiar-files home-files-service-type
          %desktop-home-files)
        %asahi-desktop-home-services))))

(define* (make-familiar-os #:key root-uuid esp-uuid channels)
  (let ((base (make-base-os #:root-uuid root-uuid #:esp-uuid esp-uuid
                            #:channels channels)))
    (operating-system
      (inherit base)
      (packages
        (append (map specification->package
                            '("hyprland" "wofi" "hyprlock" "hypridle"
                              "polkit-gnome" "grim" "slurp" "keyd" "libnotify"
                              "font-jetbrains-mono" "font-google-noto-emoji" "adwaita-icon-theme"))
                (remove (lambda (package)
                          (member (package-name package) '("sway" "foot")))
                        (operating-system-packages base))))
      (services
        (cons*
          %keyd-service
          (simple-service 'familiar-uinput kernel-module-loader-service-type '("uinput"))
          (simple-service 'familiar-keyd-config etc-service-type
            `(("keyd/default.conf" ,%desktop-keyd-config)))
          (service screen-locker-service-type
            (screen-locker-configuration
              (name "hyprlock")
              (program (file-append hyprlock "/bin/hyprlock"))))
          (modify-services (operating-system-user-services base)
            (guix-home-service-type
              homes => `(("engstrand" ,%familiar-home)))))))))
