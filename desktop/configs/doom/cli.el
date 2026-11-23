;;; cli.el -*- lexical-binding: t; -*-

;; Only the doom CLI reads this file; a running Emacs never loads it.
;;
;; `doom sync' and `doom env -r' rewrite the envvar file from the shell they run
;; in, and every Emacs loads that file over the environment it inherited. Doom
;; already leaves out WAYLAND_DISPLAY, XDG_SESSION_* and *SOCK. Leave out the rest
;; of what names one Hyprland login, one terminal pane or one agent session too,
;; or a saved copy points hyprctl, herdr, Rex and Ghostty at sessions that are
;; gone (T216), and a Worker's BBH_SUPER_TOKEN lands in a file every Emacs loads
;; (T218). desktop/lib/stale.clj flags a file that still holds any of these, so
;; keep its session-var in step.
(dolist (re '("^HYPRLAND_" "^HL_" "^HERDR_" "^GHOSTTY_" "^REX_" "^TERM_PROGRAM" "^TERMINFO$"
              "^COLORTERM$" "^VTE_VERSION$" "^WINDOWID$" "^TMUX" "^ZELLIJ" "^KITTY_" "^WEZTERM_"
              "^NVIM$" "^DIRENV_" "^VIRTUAL_ENV" "^BBH_" "^WF_RUN$" "^CLAUDE" "^EMACS$"
              "^DESKTOP_SESSION$" "^XDG_SEAT" "^fish_" "^EMACSLOADPATH$"))
  (add-to-list 'doom-env-deny re))

;; Terminals put their own store directory on PATH, as Ghostty does, so a saved
;; PATH names store items a later generation drops, and a super-agent Worker puts
;; its bbh shims first, such as an emacsclient that refuses. doom env saves the
;; environment Doom recorded at startup; drop those directories from that record
;; only, so the CLI itself keeps its PATH.
(defun eng/env-path-without-store (env)
  "ENV, a `process-environment' list, with store and bbh shim directories gone from PATH."
  (mapcar (lambda (kv)
            (if (string-prefix-p "PATH=" kv)
                (concat "PATH=" (mapconcat #'identity
                                           (seq-remove (lambda (d) (or (string-prefix-p "/gnu/store/" d)
                                                                       (string-suffix-p "/bbh/shims" d)))
                                                       (split-string (substring kv 5) path-separator t))
                                           path-separator))
              kv))
          env))

(put 'process-environment 'initial-value
     (eng/env-path-without-store (get 'process-environment 'initial-value)))
