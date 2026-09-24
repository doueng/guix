(local plugin (. (require :lib.lazy) :plugin))

(plugin :hrsh7th/nvim-cmp
        {:opts (fn [_ opts]
                 (let [cmp (require :cmp)]
                   (set opts.mapping
                        (vim.tbl_extend :force opts.mapping
                                        {:<C-n> (cmp.mapping.select_next_item {:behavior cmp.SelectBehavior.Insert})
                                         :<C-p> (cmp.mapping.select_prev_item {:behavior cmp.SelectBehavior.Insert})}))))})
