(define-module (engstrand features desktop)
  #:use-module (engstrand features packages)
  #:use-module (engstrand packages bluetui)
  #:use-module (engstrand packages ghostty)
  #:use-module (engstrand packages hex)
  #:use-module (asahi guix systems desktop)
  #:use-module (gnu home services)
  #:use-module (gnu home services shepherd)
  #:use-module (gnu home services xdg)
  #:use-module (gnu packages)
  #:use-module (guix gexp)
  #:use-module (gnu services)
  #:use-module (gnu services desktop)
  #:use-module (gnu services shepherd)
  #:export (feature-familiar-desktop))

(define %desktop-home-packages
  (append (list bluetui
                ;; Dictation on Cmd+M; Hyprland starts it (startup.lua).
                hex
                ghostty)
          (map specification->package
               '("imv"
                 "xdg-utils"
                 "xdg-desktop-portal"
                 "xdg-desktop-portal-gtk"
                 "xdg-desktop-portal-hyprland"
                 "wl-clipboard"
                 ;; Clipboard history (desktop/bin/clipboard-history).
                 "cliphist"
                 "wtype"))))

(define %desktop-system-packages
  (append
   (map specification->package
        '("bluez"
          "hyprland"
          "polkit-gnome"
          "grim"
          "slurp"
          "libnotify"
          "font-dejavu"
          "font-jetbrains-mono"
          "font-google-noto-emoji"
          "adwaita-icon-theme"))))

;; oilfm (https://github.com/doueng/oilfm is not published; the source is ~/projects/oilfm)
;; is built like dusk: make oilfm, run through desktop/bin/oilfm. Hyprland's PATH lacks
;; ~/.local/bin, so the entry names the wrapper by its full path, spelled out because the
;; system reconfigure evaluates this as root (getenv "HOME" gave /root). Written by hand because
;; xdg-desktop-entry would spell MimeType as Mimetype.
(define %oilfm-desktop-file
  (plain-file "oilfm.desktop"
              (string-append "[Desktop Entry]
Type=Application
Name=oilfm
GenericName=File Manager
Comment=Edit a directory as text, then apply the changes
Exec=/home/engstrand/.local/bin/oilfm %U
Icon=folder
Terminal=false
Categories=System;FileTools;FileManager;
MimeType=inode/directory;
StartupWMClass=oilfm
")))

;; md-view renders markdown with pandoc and opens it in Chrome (desktop/bin/md-view).
;; Only xdg-open and portal callers use it; Emacs and Neovim open .md files themselves.
(define %md-view-desktop-file
  (plain-file "md-view.desktop"
              (string-append "[Desktop Entry]
Type=Application
Name=Markdown Viewer
Comment=Render markdown and open it in Chrome
Exec=/home/engstrand/.local/bin/md-view %U
Icon=text-x-generic
Terminal=false
NoDisplay=true
Categories=Utility;
MimeType=text/markdown;text/x-markdown;
")))

(define %home-desktop-entries
  (simple-service 'familiar-desktop-entries home-xdg-data-files-service-type
                  `(("applications/oilfm.desktop" ,%oilfm-desktop-file)
                    ("applications/md-view.desktop" ,%md-view-desktop-file))))

;; Entries flatpak exported before flatpak/stable-exports name a /gnu/store flatpak,
;; which a guix gc breaks.  Until each app is deployed again, activation copies its
;; entry into ~/.local/share/applications, which shadows XDG_DATA_DIRS, with Exec
;; naming the system profile's flatpak.  Copies carry a marker, and a copy whose
;; source is gone or fixed is removed.  Delete this service once
;; ~/.local/share/applications holds no X-Familiar-Flatpak-Copy entry.
(define %home-flatpak-desktop-entries
  (simple-service 'familiar-flatpak-desktop-entries home-activation-service-type
    #~(begin
        (use-modules (guix build utils) (ice-9 regex) (ice-9 textual-ports)
                     (srfi srfi-1) (srfi srfi-26))
        (let* ((home (getenv "HOME"))
               (target (string-append home "/.local/share/applications"))
               (marker "X-Familiar-Flatpak-Copy=")
               (store-flatpak (make-regexp "/gnu/store/[^/ \"]+/bin/flatpak"))
               (stable "/run/current-system/profile/bin/flatpak")
               (sources
                (append-map (lambda (dir)
                              (if (file-exists? dir)
                                  (find-files dir "\\.desktop$")
                                  '()))
                            (list (string-append home "/.local/share/flatpak/exports/share/applications")
                                  "/var/lib/flatpak/exports/share/applications")))
               (read-file (cut call-with-input-file <> get-string-all)))
          (mkdir-p target)
          ;; Drop stale copies first, so a fixed or removed app falls back to flatpak's entry.
          (for-each (lambda (copy)
                      (when (string-contains (read-file copy) marker)
                        (delete-file copy)))
                    (find-files target "\\.desktop$"))
          (for-each
           (lambda (source)
             (let* ((text (read-file source))
                    (fixed (regexp-substitute/global
                            #f store-flatpak text 'pre stable 'post))
                    (header (string-match "\\[Desktop Entry\\]\n" fixed))
                    (copy (string-append target "/" (basename source))))
               (when (and header
                          (not (string=? text fixed))
                          (not (file-exists? copy)))
                 (call-with-output-file copy
                   (lambda (port)
                     (put-string port
                       (regexp-substitute #f header
                                          'pre 0 marker source "\n" 'post)))))))
           sources)))))

(define %home-mime-service
  (service home-xdg-mime-applications-service-type
    (home-xdg-mime-applications-configuration
      (default '(("x-scheme-handler/http" . "com.google.Chrome.desktop")
                 ("x-scheme-handler/https" . "com.google.Chrome.desktop")
                 ("text/html" . "com.google.Chrome.desktop")
                 ("inode/directory" . "oilfm.desktop")
                 ("text/markdown" . "md-view.desktop")
                 ("text/x-markdown" . "md-view.desktop")
                 ("image/jpeg" . "imv.desktop")
                 ("image/png" . "imv.desktop")
                 ("image/webp" . "imv.desktop"))))))

;; Flatpak resolves Chrome's xdg-download grant from user-dirs.dirs; without
;; it the sandbox writes ~/Downloads to a tmpfs that vanishes on exit.
;; Directories pointed at $HOME are disabled, so activation skips them.
(define %home-user-dirs-service
  (service home-xdg-user-directories-service-type
    (home-xdg-user-directories-configuration
      (documents "$HOME")
      (music "$HOME")
      (projects "$HOME")
      (publicshare "$HOME")
      (templates "$HOME")
      (videos "$HOME"))))

;; Clipboard history: wl-paste --watch hands each new clipboard entry to
;; desktop/bin/clipboard-history, which stores it with cliphist unless a
;; password manager marked it sensitive, a password manager has focus, or it
;; is over 5 MiB; Cmd+Caps+V picks one.  Shepherd starts before Hyprland and
;; has no WAYLAND_DISPLAY, so the script waits for the session's socket and
;; follows the next one when Hyprland restarts.
(define %clipboard-history-service
  (simple-service 'familiar-clipboard-history home-shepherd-service-type
    (list (shepherd-service
           (provision '(clipboard-history))
           (documentation "Record the Wayland clipboard history with cliphist.")
           (respawn? #t)
           (respawn-limit #~'(5 . 60))
           (respawn-delay 5)
           (start #~(lambda ()
                      (let ((home (getenv "HOME")))
                        ((make-forkexec-constructor
                          (list (string-append home "/.local/bin/clipboard-history") "watch")
                          #:environment-variables
                          (cons* (string-append "PATH=" home "/.guix-home/profile/bin"
                                                ":/run/current-system/profile/bin")
                                 (filter (lambda (variable)
                                           (not (string-prefix? "PATH=" variable)))
                                         (default-environment-variables)))
                          #:log-file (string-append home "/.local/state/clipboard-history.log"))))))
           (stop #~(make-kill-destructor))))))

(define (feature-familiar-desktop)
  (feature-package-set
   'familiar-desktop
   #:home-packages %desktop-home-packages
   #:system-packages %desktop-system-packages
   #:system-services (list (service bluetooth-service-type
                                    (bluetooth-configuration (auto-enable? #t))))
   #:home-services (append %asahi-desktop-home-services
                           (list %home-mime-service
                                 %home-desktop-entries
                                 %home-flatpak-desktop-entries
                                 %home-user-dirs-service
                                 %clipboard-history-service))))
