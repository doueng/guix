(local plugin (. (require :lib.lazy) :plugin))

(plugin :folke/which-key.nvim
        {:opts {:delay 350
                :spec [(plugin :<leader>g {:group :git})]}})
