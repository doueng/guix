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
  #:export (make-familiar-os))

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
;; always adds a store indirection.  Install direct links during activation.
;; Also remove old store-backed tree links before creating child links; Guix's
;; symlink manager otherwise follows such a parent into the checkout.
(define %familiar-direct-home-links
  (simple-service 'familiar-direct-home-links home-activation-service-type
    #~(begin
        (use-modules (guix build utils) (ice-9 ftw) (srfi srfi-13))
        (define roots
          '(".config/fish" ".config/nvim" ".config/doom"
            ".config/hypr" ".config/waybar" ".pi/agent"
            ".local/share/catppuccin-mocha/wallpapers"
            ".local/share/herdr/tiny-fingers"))
        (define links '#$%desktop-direct-home-links)
        (define (symlink-target path)
          (catch 'system-error
            (lambda ()
              (and (eq? 'symlink (stat:type (lstat path)))
                   (readlink path)))
            (lambda _ #f)))
        (define (legacy-tree-link? path)
          (let ((target (symlink-target path)))
            (and target
                 (string-prefix? "/gnu/store/" target)
                 (string-suffix? "-familiar-live-file" target))))
        (define (path-exists? path)
          (catch 'system-error
            (lambda () (lstat path) #t)
            (lambda _ #f)))
        ;; Hyprland prefers hyprland.lua when both files exist.  This stale
        ;; generated file must not shadow the native config.
        (for-each
         (lambda (relative)
           (let ((path (string-append (getenv "HOME") "/" relative)))
             (when (path-exists? path)
               (format #t "Removing stale Home config ~a~%" path)
               (delete-file path))))
         '(".config/hypr/hyprland.lua"))
        (for-each
         (lambda (root)
           (let ((path (string-append (getenv "HOME") "/" root)))
             (when (legacy-tree-link? path)
               (format #t "Removing legacy Home tree link ~a~%" path)
               (delete-file path))))
         roots)
        (for-each
         (lambda (link)
           (let* ((relative (car link))
                  (source (cdr link))
                  (target (string-append (getenv "HOME") "/" relative))
                  (old (symlink-target target)))
             (mkdir-p (dirname target))
             (when (and old
                        (or (string=? old source)
                            (and (string-prefix? "/gnu/store/" old)
                                 (string-suffix? "-familiar-live-file" old))))
               (delete-file target))
             (unless (path-exists? target)
               (symlink source target))))
         links))))

(define %familiar-herdr-plugins
  (simple-service 'familiar-herdr-plugins home-activation-service-type
    #~(begin
        (use-modules (guix build utils) (ice-9 popen) (ice-9 rdelim)
                     (srfi srfi-13))
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
            (when (file-exists? target)
              (delete-file-recursively target))
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
                            "python-pytest" "python-pyyaml"
                            "emacs-no-x" "emacs-fish-mode"
                            "node" "make" "gcc-toolchain" "pkg-config"
                            "cmake" "dasel" "diff-so-fancy" "diffstat" "entr" "exercism"
                            "file" "fennel" "fnlfmt" "go" "gopls" "gore" "hyperfine"
                            "jless" "lua" "nixfmt" "pandoc" "qpdf" "shellcheck"
                            "shfmt" "sox" "typst" "uv" "xxd" "yq"
                            "unzip" "zip" "tree" "wl-clipboard"))))
    (services
      (cons*
        %familiar-direct-home-links
        %familiar-herdr-plugins
        (service home-bash-service-type)
        (simple-service 'familiar-environment home-environment-variables-service-type
          '(("EDITOR" . "nvim") ("VISUAL" . "nvim")
            ("DOOMDIR" . "/home/engstrand/.config/doom")
            ("COLORTERM" . "truecolor")
            ("TERM_PROGRAM" . "ghostty")
            ("XCURSOR_THEME" . "Adwaita")
            ("XCURSOR_SIZE" . "20")
            ("GTK_THEME" . "Adwaita:dark")
            ("RAYON_NUM_THREADS" . "4")))
        (simple-service 'familiar-files home-files-service-type
          %desktop-home-files)
        %asahi-desktop-home-services))))

(define* (make-familiar-os #:key root-uuid esp-uuid channels)
  (let ((base (make-ssd-os #:root-uuid root-uuid #:esp-uuid esp-uuid
                           #:channels channels)))
    (operating-system
      (inherit base)
      (packages
        (append (map specification->package
                            '("hyprland" "wofi" "hyprlock" "hypridle"
                              "polkit-gnome" "grim" "slurp" "keyd"
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
