;; pi-org.nvim — a Neovim frontend for the `pi` AI agent.
;;
;; Drives `pi --mode rpc` over stdin/stdout JSON and renders the conversation
;; as org-mode in a transcript buffer, with input composed in a separate
;; org-mode buffer. Both input and output are org filetype buffers, so
;; nvim-orgmode provides folding, syntax, TODO states, source blocks, etc.

(local config-mod (require :pi-org.config))
(local session (require :pi-org.session))

(local M {})

(var config nil)

(fn setup [user-config]
  (set config (config-mod.parse user-config))
  ;; User commands.
  (vim.api.nvim_create_user_command :PiOrg (fn [] (session.open config))
                                    {:desc "pi-org: toggle the pi agent org-mode frontend"})
  (vim.api.nvim_create_user_command :PiOrgClose (fn [] (session.close))
                                    {:desc "pi-org: close the pi agent session"})
  (vim.api.nvim_create_user_command :PiOrgSubmit (fn [] (session.submit))
                                    {:desc "pi-org: submit the current input buffer"})
  ;; Global toggle keymap.
  (when config.keymaps.toggle
    (vim.keymap.set :n config.keymaps.toggle (fn [] (session.open config))
                    {:desc "pi-org: toggle"}))
  M)

(tset M :setup setup)
(tset M :config (fn [] config))
(tset M :open (fn [] (session.open config)))
(tset M :close session.close)
(tset M :submit session.submit)
M
