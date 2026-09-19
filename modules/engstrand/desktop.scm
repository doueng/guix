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

(define %familiar-home
  (home-environment
    (packages (cons* pi-coding-agent herdr jjui github-cli babashka noctalia ghostty curl
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
        (service home-bash-service-type)
        (simple-service 'familiar-environment home-environment-variables-service-type
          '(("EDITOR" . "nvim") ("VISUAL" . "nvim")
            ("DOOMDIR" . "/home/engstrand/.config/doom")
            ("COLORTERM" . "truecolor")
            ("TERM_PROGRAM" . "kitty")
            ("XCURSOR_THEME" . "Adwaita")
            ("XCURSOR_SIZE" . "20")
            ("GTK_THEME" . "Adwaita:dark")
            ("RAYON_NUM_THREADS" . "4")))
        (simple-service 'familiar-files home-files-service-type
          %desktop-live-home-files)
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
