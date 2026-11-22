;;; cli.el -*- lexical-binding: t; -*-

;; `doom sync' rewrites the envvar file from the shell it runs in, and every
;; Emacs loads that file over the environment it inherited. Doom already leaves
;; out WAYLAND_DISPLAY, XDG_SESSION_* and *SOCK. Leave out the rest of what names
;; one Hyprland login or one terminal pane too, or a saved copy points hyprctl,
;; herdr and Ghostty at sessions that are gone (T216).
;; Rex's pane variables and the store paths of EMACSLOADPATH and REX_BIN_DIR go
;; stale the same way (T218).
(dolist (re '("^HYPRLAND_" "^HL_" "^HERDR_" "^GHOSTTY_" "^TERM_PROGRAM" "^TERMINFO$"
              "^DESKTOP_SESSION$" "^XDG_SEAT" "^fish_" "^EMACSLOADPATH$"
              "^REX_\\(SESSION\\|BLOCK\\|PANE\\|BIN_DIR\\)$"))
  (add-to-list 'doom-env-deny re))
