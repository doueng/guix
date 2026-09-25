(local plugin (. (require :lib.lazy) :plugin))

(plugin :akinsho/bufferline.nvim
        {:opts {:options {:diagnostics :nvim_lsp
                          :show_buffer_close_icons false
                          :show_close_icon false}}})
