(define-module (engstrand desktop)
  #:use-module (engstrand system asahi)
  #:use-module (engstrand packages definitions)
  #:use-module (engstrand services bluetooth)
  #:use-module (engstrand services herdr-plugins)
  #:use-module (engstrand services home-environment)
  #:use-module (engstrand services home-files)
  #:use-module (engstrand services home-links)
  #:use-module (engstrand services home-mime)
  #:use-module (engstrand services home-shell)
  #:use-module (engstrand services keyd)
  #:use-module (engstrand services keyd-config)
  #:use-module (engstrand services uinput)
  #:use-module (asahi guix systems desktop)
  #:use-module (gnu)
  #:use-module (gnu home)
  #:use-module (gnu packages)
  #:use-module (gnu packages curl)
  #:use-module (gnu services)
  #:use-module (gnu services guix)
  #:use-module (guix packages)
  #:use-module (srfi srfi-1)
  #:export (make-familiar-os %familiar-home))

(define %familiar-home
  (home-environment
    (packages (cons* pi-coding-agent herdr herdr-sesh herdr-tiny-fingers jjui bluetui
                     github-cli babashka noctalia ghostty curl
                     (map specification->package
                          '("fish" "jujutsu" "clojure" "clojure-tools" "emacs-clojure-mode" "emacs-cider"
                            "difftastic" "tmux" "fzf" "zoxide"
                            "direnv" "ripgrep" "fd" "jq" "bat" "btop"
                            "python" "python-black" "python-boto3" "python-pyopenssl"
                            "python-pyyaml"
                            "emacs-no-x" "emacs-fish-mode" "parinfer-rust-emacs"
                            "node" "make" "gcc-toolchain" "tree-sitter-cli" "pkg-config"
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
        %home-shell-service
        %home-environment-service
        %home-mime-service
        %home-files-service
        %asahi-desktop-home-services))))

(define* (make-familiar-os #:key root-uuid esp-uuid channels)
  (let ((base (make-base-os #:root-uuid root-uuid #:esp-uuid esp-uuid
                            #:channels channels)))
    (operating-system
      (inherit base)
      (packages
        (append (map specification->package
                            '("bluez" "hyprland" "wofi"
                              "polkit-gnome" "grim" "slurp" "keyd" "libnotify"
                              "font-jetbrains-mono" "font-google-noto-emoji" "adwaita-icon-theme"))
                (remove (lambda (package)
                          (member (package-name package) '("sway" "foot")))
                        (operating-system-packages base))))
      (services
        (cons*
          %bluetooth-service
          %keyd-service
          %uinput-service
          %keyd-config-service
          (modify-services (operating-system-user-services base)
            (guix-home-service-type
              homes => `(("engstrand" ,%familiar-home)))))))))
