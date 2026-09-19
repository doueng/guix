(local plugin (. (require :lib.lazy) :plugin))

(plugin :folke/which-key.nvim
        {:opts {:spec [(plugin :<leader>f {:group :file/find})
                       (plugin :<leader>g {:group :git})
                       (plugin :<leader>o {:group :octo})
                       (plugin :<leader>s {:group :search})
                       (plugin :<leader>c {:group :code})
                       (plugin :<leader>t {:group :test})]}})
