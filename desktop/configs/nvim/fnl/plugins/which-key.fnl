(local plugin (. (require :lib.lazy) :plugin))

(plugin :folke/which-key.nvim
        {:opts {:delay 350
                ;; leader.edn names the shared groups (shared-keys.fnl).
                :spec [(plugin :<leader>g {:group :git})]}})
