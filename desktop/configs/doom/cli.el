;;; cli.el -*- lexical-binding: t; -*-

;; `doom env' saves the shell's variables for every Emacs to load, the bbh-super
;; daemon among them. Hyprland's signature names one compositor run, so a saved
;; copy points hyprctl, in every shell Emacs starts, at a dead instance after the
;; next login. Doom already leaves out WAYLAND_DISPLAY for the same reason.
(add-to-list 'doom-env-deny "^HYPRLAND_INSTANCE_SIGNATURE$")
