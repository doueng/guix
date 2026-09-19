;; lazy.nvim spec for nvim-orgmode, which pi-org renders into.
;;
;; pi-org itself is a local Fennel module already on the runtimepath under
;; fnl/pi-org/, so it has no lazy.nvim entry of its own — init.fnl calls
;; (require :pi-org).setup {} directly. We only need orgmode installed and
;; loaded before pi-org first opens a buffer.

(local lazy (require :lib.lazy))
(local plugin lazy.plugin)

[(plugin :nvim-orgmode/orgmode
         {:ft [:org]
          :cmd [:PiOrg]
          :config (fn []
                    (let [org (require :orgmode)]
                      (org.setup {:org_agenda_files []
                                  :org_default_notes_file nil})))})]
