(local lazy-spec (require :lib.lazy))
(local plugin lazy-spec.plugin)
(local key lazy-spec.key)

(plugin :nvim-telescope/telescope.nvim
        {:cmd :Telescope
         :keys [(key :<leader>bb
                     "<cmd>Telescope buffers sort_mru=true ignore_current_buffer=true<cr>"
                     {:desc "Search open buffers"})]
         :dependencies [:nvim-lua/plenary.nvim]})
